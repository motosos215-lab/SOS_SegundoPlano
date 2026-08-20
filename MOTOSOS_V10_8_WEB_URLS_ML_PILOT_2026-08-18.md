# MotoSOS v10.8 — URLs Web + Accident Model v1.1 piloto

Fecha: 2026-08-18
Base: MotoSOS v10.7 (sesión viva + mensajes Monitor→Rider)

## 1. URLs Web configuradas

Se configuraron las rutas entregadas por el equipo web:

- Registro: `https://deploy-preview-4--motosos.netlify.app/registro`
- Recuperación de contraseña: `https://deploy-preview-4--motosos.netlify.app/recuperar-contrasena`
- Editar monitor/contacto: `https://deploy-preview-4--motosos.netlify.app/configuracion/contactos`
- Vista de contactos en dashboard: `https://deploy-preview-4--motosos.netlify.app/dashboard/contactos`

Propiedades Gradle:

```properties
MOTOSOS_WEB_REGISTER_URL=https://deploy-preview-4--motosos.netlify.app/registro
MOTOSOS_WEB_PASSWORD_RECOVERY_URL=https://deploy-preview-4--motosos.netlify.app/recuperar-contrasena
MOTOSOS_WEB_CONTACTS_URL=https://deploy-preview-4--motosos.netlify.app/configuracion/contactos
MOTOSOS_WEB_CONTACTS_DASHBOARD_URL=https://deploy-preview-4--motosos.netlify.app/dashboard/contactos
```

Uso actual:

- Login → “Registrar cuenta” abre `MOTOSOS_WEB_REGISTER_URL`.
- Login → “¿Olvidaste tu contraseña?” abre `MOTOSOS_WEB_PASSWORD_RECOVERY_URL`.
- Rider → Contacto de emergencia → “Editar monitor en la web” abre `MOTOSOS_WEB_CONTACTS_URL`.
- La ruta dashboard queda configurada para futuras entradas web sin mezclarla con el editor.

## 2. Accident Model v1.1 integrado

Artefacto incluido exactamente como fue entregado:

`app/src/main/assets/motosos_accident_model_v1_1_pilot_ready.json`

Versión: `1.1.0-pilot-ready`
Threshold leído del JSON: `0.71`

El modelo corre completamente local. No se agregó TensorFlow Lite, Python ni una llamada de red para inferencia.

## 3. Adaptación a la arquitectura real de SegundoPlano

El manual de implementación entregado referencia otro repositorio con `service:monitoring` y `TripSampler`. SegundoPlano ya tiene un pipeline más completo:

`TripSignalCaptureCoordinator`
→ `SignalPreprocessingCoordinator`
→ `ProcessedSignalWindow`
→ `RuleEngineCoordinator`
→ `FalsePositiveValidationCoordinator`

Por eso no se creó un segundo sampler ni se registraron sensores duplicados.

La integración se hizo sobre el pipeline existente:

- `AccidentMlModel.kt`: carga/normaliza/infiere logistic regression.
- `AccidentMlFeatureExtractor.kt`: construye las 8 features desde las ventanas reales.
- `AccidentMlEvaluator.kt`: ejecuta el modelo y añade resultado ML al `RiskAssessment`.
- `RuleEngineCoordinator.kt`: evalúa ML después de reglas, en la misma ventana.
- `FalsePositiveValidationCoordinator.kt`: ML puede abrir el mismo countdown de confirmación.

## 4. Features implementadas

Se calculan las ocho features exigidas por el artefacto:

1. `speed_kmh`
2. `delta_speed_kmh`
3. `deceleration_kmh_s`
4. `gps_accuracy_m`
5. `accel_peak_g`
6. `gyro_peak_dps`
7. `stillness_seconds`
8. `jerk_peak_g_s`

### Velocidad

Para el feature ML se intenta primero velocidad por distancia/tiempo entre fixes GPS distintos con sanity check de intervalo y velocidad máxima razonable. Si no existe una pareja útil, se usa como respaldo la velocidad ya procesada por MotoSOS.

Esto no reemplaza `SpeedPolicy` productivo ni modifica el comportamiento de otras pantallas.

### Sensores

Los picos se obtienen de las mismas señales ya capturadas por teléfono/reloj. No se registran listeners adicionales.

- acelerómetro → magnitud en g
- giroscopio → magnitud en grados/s
- jerk → cambio de magnitud del acelerómetro por segundo, separado por fuente Phone/Wear
- inmovilidad → duración continua ya calculada por las reglas explicables

## 5. Política de decisión

La política efectiva se conserva como:

`reglas explicables OR ML`

Si ML cruza el threshold:

- NO envía un SOS de inmediato;
- NO salta la confirmación;
- abre el mismo countdown “¿Estás bien?”;
- el Rider puede cancelar el falso positivo;
- si el countdown expira, usa el flujo automático existente de MotoSOS.

Las reglas críticas existentes siguen pudiendo actuar según la política actual de MotoSOS.

SOS manual continúa 100 % independiente del ML.

## 6. Fail-safe

Si el asset no carga, el JSON es inválido o ML no puede evaluarse:

- `mlDetected = false`;
- las reglas actuales siguen funcionando;
- no se bloquea captura, countdown ni SOS manual/automático por reglas.

No se usa `999 m` como precisión GPS faltante para inferencia. Ese valor queda muy fuera de la distribución de entrenamiento del modelo y, con un coeficiente positivo de `gps_accuracy_m`, puede sesgar artificialmente la probabilidad. Mientras no exista precisión GPS numérica válida, la ventana no usa ML y las reglas explicables siguen activas.

## 7. Logs de piloto

Sólo en `BuildConfig.DEBUG` se registra una línea sanitizada por ventana con:

- speed
- delta speed
- deceleration
- GPS accuracy
- accel peak
- gyro peak
- stillness
- jerk
- probability
- threshold
- hard-rule support
- ML detected

No se imprimen latitud, longitud, access tokens, FCM tokens ni información personal.

Tag Logcat:

`MotoSOS-AccidentML`

## 8. Tests añadidos

`AccidentMlModelTest`:

- parsea el modelo v1.1 incluido;
- confirma threshold 0.71;
- muestra normal queda debajo del umbral;
- muestra tipo accidente queda por encima;
- hard rule sigue forzando `possibleAccident` aunque ML sea bajo.

`FalsePositiveValidationCoordinatorTest`:

- ML positivo abre countdown;
- no crea incidente ni alerta de inmediato.

## 9. Protección de regresiones

Comparación contra v10.7:

- `AutomaticSosAlertCreator.kt`: sin cambios.
- `ManualSosAlertCreator.kt`: sin cambios.
- `RuleEngine.kt`: sin cambios.
- Assets PNG protegidos presentes en v10.7: sin cambios.

Se modificó `FalsePositiveValidationCoordinator` únicamente para insertar la nueva señal ML antes de los supresores de bump/frenado, de forma que la política OR del modelo realmente pueda llegar al countdown.

## 10. Validaciones realizadas en este entorno

- 25 XML analizados: 0 errores.
- JSON ML parseable: sí.
- Sample normal del manual: probabilidad ≈ `0.00407` (< 0.71).
- Sample tipo accidente del manual: probabilidad ≈ `0.99391` (> 0.71).
- Versión del artefacto: `1.1.0-pilot-ready`.
- Features del artefacto: 8.
- Hash del asset copiado igual al archivo entregado.
- Sintaxis focal de `AccidentMlModel.kt`: compilada con `kotlinc` y stubs.
- Sintaxis focal de `AccidentMlFeatureExtractor.kt`: compilada con `kotlinc` y stubs.
- Sintaxis focal de `AccidentMlEvaluator.kt`: compilada con `kotlinc` y stubs.
- Gradle Android completo no pudo ejecutarse aquí porque el wrapper intenta descargar Gradle 9.4.1 y el entorno no resuelve `services.gradle.org`.

## 11. Validación recomendada en Windows

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"

.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Pruebas focales:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.example.sos_segundoplano.data.ml.AccidentMlModelTest" `
  --console=plain

.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorTest.mlDetectionStartsCountdownWithoutBypassingUserConfirmation" `
  --console=plain
```

Para observar inferencia en piloto:

```powershell
adb logcat -s MotoSOS-AccidentML
```

## 12. Advertencia del modelo

El modelo sigue siendo `pilot-ready`, entrenado con datos sintéticos/public-style. No debe presentarse como un modelo productivo validado con accidentes reales. La integración conserva la confirmación del usuario y las reglas actuales precisamente para no convertir el modelo en una única fuente de decisión de seguridad.
