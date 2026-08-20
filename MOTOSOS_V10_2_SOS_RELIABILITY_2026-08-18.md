# MotoSOS v10.2 — restauración de confiabilidad SOS manual y automático

Fecha: 2026-08-18

## Objetivo

Revisar la regresión reportada donde el SOS manual y el SOS automático dejaron de reaccionar de forma confiable, comparando la v10.1 con versiones anteriores en las que el flujo ya funcionaba físicamente.

## Hallazgos de comparación

Se compararon v5, v8 y v10.1. Los archivos nucleares de creación de SOS (`RiderSosScreen`, `ManualSosIncidentCoordinator`, `ManualSosAlertCreator`, `AutomaticSosAlertCreator`, `IncidentRemoteProvider` y el contrato `/mobile/sos-alerts`) no habían sido sustituidos por los cambios recientes del Monitor.

La regresión práctica se concentraba en tres puntos:

1. El ajuste de falso positivo mantenía un gate agregado de score/confianza demasiado alto para algunos patrones físicos coherentes. Un evento real podía tener impacto + inmovilidad + orientación, pero quedarse debajo de 40 y nunca abrir countdown.
2. Manual y automático podían esperar una ubicación excesivamente fresca antes de crear la alerta. En una emergencia esto hacía que el botón o la finalización automática parecieran no responder aunque ya existiera una ubicación principal válida.
3. Después de crear correctamente `/api/v1/mobile/sos-alerts`, ambos flujos esperaban de manera síncrona el endpoint secundario de `location-sharing/snapshot`. Si ese servicio era lento, la UI podía permanecer en "Enviando" y el automático tardaba en finalizar/reconciliar aunque el SOS principal ya existiera.

También se reforzó la inicialización del SOS automático al inicio real del foreground service para evitar dependencias NoOp después de recreación del proceso/instalaciones de prueba.

## Cambios implementados

### SOS manual

- La mejor ubicación principal reciente se acepta con una ventana de hasta 30 segundos.
- La precisión NO invalida la ubicación de emergencia: una muestra real con precisión amplia sigue siendo utilizable.
- Antes de esperar un callback GPS nuevo, Android revisa la ubicación conocida más reciente de GPS/network.
- Si necesita un fix nuevo, la espera máxima de ubicación se redujo a 5 segundos.
- La operación manual completa tiene un límite de 30 segundos. Si algo externo queda colgado, pasa a estado reintentable en vez de quedar indefinidamente en preparación/envío.
- Se mantiene exactamente el mismo `clientIncidentId` y `clientAlertRequestId` en retry; no se generan duplicados.

### SOS automático

- Se añadió un gate específico para patrones físicos correlacionados: score >= 30 y confidence >= 0.50.
- Ese gate bajo NO acepta inmovilidad sola ni un bache aislado.
- Una caída coherente puede abrir countdown aunque el agregado no haya llegado a 40.
- Impacto + inmovilidad requiere severidades mínimas; un impacto moderado además necesita orientación o frenado como apoyo.
- Impacto muy fuerte + inmovilidad puede abrir countdown directamente.
- Riesgo estacionario permanece en 0; no se reintrodujo la vieja contribución de +20 por estar quieto.
- El POST automático tiene un límite de 30 segundos. Un timeout se convierte en `IncidentRemoteCreationStatus.Timeout`, de modo que el mecanismo de retry existente puede manejarlo sin perder identidad.
- Al arrancar `MonitoringForegroundService`, se vuelven a enlazar el creador SOS automático y el offline event sink reales.

### Ubicación secundaria para el Monitor

`POST /api/v1/mobile/location-sharing/snapshot` sigue ejecutándose después de crear el incidente, pero ahora es best-effort y tiene un límite de 2.5 segundos.

Un fallo o demora de ese endpoint secundario ya no convierte un SOS principal creado en un flujo que parece bloqueado.

## Garantías conservadas

- Endpoint canónico: `POST /api/v1/mobile/sos-alerts`.
- `tripSessionKey` y `bundleKey` no se regeneran.
- Retry conserva los UUID durables.
- El procesamiento automático continúa reclamando el bundle exacto; no se introdujo `claimNext` como fallback.
- La ubicación principal no depende de alcanzar una precisión GPS específica.
- El SOS automático continúa usando `CountdownTimeout`, `UserRequestedHelp` o `CriticalEvent`; no se usa `CrashDetected`.
- El SOS manual continúa usando `ManualSos`.
- No se modificaron los PNG protegidos.

## Pruebas focalizadas agregadas/actualizadas

- Ubicación principal de 29 s y precisión de 85 m se acepta inmediatamente para SOS.
- El snapshot secundario que se queda esperando no bloquea el SOS principal.
- Patrón correlacionado con score 34/confidence 0.55 abre countdown.
- El timeout del POST automático produce un fallo reintentable de tipo Timeout.
- Se conservan las pruebas anteriores de inmovilidad sola, bache, frenado y patrones de caída/impacto.

## Validación en este entorno

- XML app + wear: 24 archivos, 0 errores de parseo.
- 7/7 PNG protegidos: hash idéntico a v10.1.
- Gradle completo no pudo ejecutarse porque el wrapper intenta descargar Gradle 9.4.1 desde `services.gradle.org`, dominio no resoluble en este entorno.

## Prueba recomendada en Windows

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Después, con viaje activo:

1. SOS manual: debe pasar por preparación/ubicación/envío y terminar en confirmación; no debe quedarse indefinidamente en "Enviando".
2. SOS automático físico: patrón fuerte coherente debe abrir countdown; quieto debe conservar riesgo 0.
3. SOS automático debug:

```powershell
$rider = "adb-RFCW30M1F4E-yndv1R._adb-tls-connect._tcp"
adb -s $rider shell am broadcast `
  -a com.example.sos_segundoplano.DEBUG_TRIGGER_ACCIDENT `
  -n com.example.sos_segundoplano/.debug.DebugAccidentAlertReceiver
```

El debug trigger requiere un viaje activo y que el pipeline de señales ya esté iniciado.
