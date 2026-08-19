# MotoSOS — P6: falso positivo, reloj y gestos de mapa

Fecha: 2026-08-18
Base: v6 GPS calibrado

## Alcance

Esta iteración no modifica iconos ni sesiones únicas. Se concentra en:

1. riesgo físico / falso positivo;
2. continuidad del SOS automático;
3. lectura visible de aceleración del reloj;
4. interacción del mapa del detalle de viaje.

## 1. Riesgo y falso positivo

### Inmovilidad

La inmovilidad ya no crea riesgo por sí sola. La regla puede seguir marcándose como evidencia contextual, pero su contribución efectiva es 0 si no existe un evento físico primario (caída, impacto o frenado brusco).

Resultado esperado con moto/teléfono/reloj quietos:

- velocidad: 0;
- inmovilidad: puede quedar detectada internamente;
- riesgo visible: 0 / Bajo.

### Correlación temporal

Se conservan durante 6 segundos las evidencias físicas recientes necesarias para correlacionar un impacto con lo que ocurre inmediatamente después. Esto permite detectar el patrón impacto -> inmovilidad aunque el pico de impacto haya ocurrido en una ventana de señal anterior.

### Separación teléfono / reloj

Impacto y cambio de orientación se calculan por fuente. Ya no se mezclan un vector inicial del teléfono con uno final del reloj como si pertenecieran al mismo sensor. Se selecciona después la evidencia válida más fuerte.

### Caída

Una caída requiere una secuencia temporal coherente:

- tramo continuo de baja aceleración (free fall);
- impacto posterior dentro de la ventana permitida;
- misma fuente física cuando la fuente del impacto es conocida;
- orientación compatible cuando existe como apoyo.

### Movimiento / inmovilidad

La dispersión del acelerómetro y giroscopio se calcula por dispositivo y luego se toma la señal de movimiento más fuerte. Esto evita crear movimiento artificial sólo por combinar dos dispositivos con orientaciones/base distintas.

### Countdown de validación

Reglas nuevas para iniciar countdown:

- score >= 40;
- confianza >= 0.60;
- una caída válida puede iniciar countdown;
- impacto + inmovilidad requieren impacto >= 0.55 e inmovilidad >= 0.50;
- impacto muy fuerte (>= 0.85 de severidad normalizada) + inmovilidad puede iniciar countdown directamente;
- impacto moderado (0.55 a <0.85) + inmovilidad necesita además orientación o frenado brusco con severidad >= 0.30.

Esto evita que estar detenido, una rotación aislada o un bache aislado sean suficientes para escalar.

### Confirmación "Estoy bien"

Después de confirmar que está bien, se suprime durante 10 segundos de tiempo de señal la re-escalada del mismo evento físico reciente. Evita que el mismo impacto residual vuelva a abrir inmediatamente otro countdown.

## 2. SOS automático

No se cambió la ruta productiva de creación/envío del SOS automático. El flujo sigue siendo:

riesgo físico coherente -> countdown -> usuario puede confirmar que está bien / pedir ayuda -> timeout genera el incidente automático -> persistencia durable -> POST canónico de SOS.

Las identidades durables del incidente/alerta y los reintentos exactos existentes se mantienen.

Se añadieron pruebas focalizadas para:

- inmovilidad estacionaria con score 0;
- orientación teléfono/reloj no mezclada;
- impacto reciente + inmovilidad correlacionados;
- impacto moderado + inmovilidad sin apoyo NO inicia countdown;
- impacto moderado + inmovilidad + orientación SÍ inicia countdown;
- impacto fuerte + inmovilidad SÍ inicia countdown;
- confirmar seguro impide reabrir inmediatamente el mismo evento;
- timeout sigue cubierto por las pruebas existentes de creación de incidente/alerta.

## 3. Reloj

El sensor bruto `TYPE_ACCELEROMETER` se conserva para detección de caída/impacto, ya que su magnitud y vector contienen información necesaria para free fall y orientación.

Para la lectura visible de movimiento se añadió `TYPE_LINEAR_ACCELERATION` cuando el reloj lo soporta. Esa señal excluye gravedad. Si el dispositivo no ofrece ese sensor, la UI usa un fallback de magnitud compensada para que un reloj quieto no se muestre como ~9.8–10 m/s² de movimiento.

La pantalla del reloj ahora etiqueta la métrica como `Aceleración dinámica`.

La pantalla de diagnóstico del teléfono distingue entre `Aceleración dinámica` y el `Sensor bruto (incluye gravedad)`.

## 4. Detalle de viaje / MapLibre

El `MapView` pide a su contenedor no interceptar gestos mientras el dedo está dentro del mapa. De esta forma arrastrar/pellizcar actúa sobre MapLibre y no sobre el `LazyColumn` del detalle.

Se agregó el botón `Centrar recorrido` para volver al encuadre completo de todos los puntos GPS después de mover o ampliar el mapa.

También se corrigió la clave de render para que el botón de recentrado realmente vuelva a ejecutar el ajuste de cámara.

## Validación realizada en este entorno

- XML: 25 revisados, 0 errores de parseo.
- 7 PNG protegidos: hashes idénticos a v6.
- Kotlin focal de `RuleEngine`: compilado con compilador local usando sólo una adaptación temporal de `ArrayDeque.addLast` por diferencia de stdlib del entorno; el código fuente del proyecto no fue alterado para esto.
- Harness lógico de producción:
  - `STATIONARY_RISK_OK score=0`
  - `CORRELATED_IMPACT_RISK_OK score=62 level=Medium`
  - `FALL_PATTERN_OK score=71 ... risk=High`
- Revisión sintáctica focal de archivos Kotlin modificados: sin errores de sintaxis detectados.

No fue posible ejecutar el Gradle completo del proyecto en este contenedor porque el wrapper requiere descargar Gradle 9.4.1 desde `services.gradle.org` y el entorno no tiene resolución de red para ese host. La compilación Android definitiva debe ejecutarse en el Windows del proyecto.

## Validación recomendada en Windows

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :wear:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :wear:assembleDebug --console=plain
```

## Prueba física recomendada

1. Iniciar viaje y esperar `GPS listo`.
2. Dejar teléfono/reloj quietos al menos 10 segundos: riesgo debe permanecer en 0.
3. Mover reloj/teléfono normalmente: no debe abrir countdown.
4. Ver en el reloj `Aceleración dinámica`; quieto debe estar cerca de 0, no cerca de 9.8–10 por gravedad.
5. Usar el trigger debug de accidente para confirmar que el countdown y el SOS automático siguen funcionando.
6. Abrir Historial -> Detalle del viaje y arrastrar/pellizcar dentro del mapa; la pantalla no debe robar el gesto.
7. Pulsar `Centrar recorrido` y comprobar que vuelve a mostrar toda la ruta.
