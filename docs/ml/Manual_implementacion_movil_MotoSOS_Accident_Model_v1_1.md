# Manual de implementacion movil - MotoSOS Accident Model v1.1

## 1. Objetivo

Integrar el modelo local:

```text
ml/accident_detection/artifacts/motosos_accident_model_v1_1_pilot_ready.json
```

en la app Android Kotlin del repo:

```text
C:\Users\cesar\dev\MotoSOS\SOS_PrimerPlano
```

La integracion debe correr solo durante viaje activo y debe usar esta politica:

```text
possibleAccident = hardRuleDetected || probability >= threshold
```

El SOS manual nunca debe depender del modelo.

## 2. Estado actual del repo movil

Puntos reales encontrados en el repo:

| Responsabilidad | Archivo |
| --- | --- |
| Servicio de monitoreo activo | `service/monitoring/src/main/java/com/motosos/driver/service/monitoring/MonitoringForegroundService.kt` |
| Muestreo GPS, velocidad, distancia y regla dura | `service/monitoring/src/main/java/com/motosos/driver/service/monitoring/TripSampler.kt` |
| Regla de desaceleracion fuerte | `domain/src/main/kotlin/com/motosos/driver/domain/trip/TripStatsCalculator.kt::AccidentDetector` |
| Publicacion de estadisticas | `TripRepository.publishTripStats(...)` |
| Reporte de posible accidente | `TripRepository.reportIncident(IncidentType.PossibleAccident, location)` |
| Codigo de referencia de sensores | `ui/mlcollector/src/main/java/com/motosos/driver/ui/mlcollector/MlCollectorScreen.kt` |

El lugar correcto para integrar ML es `service:monitoring`, no la UI.

## 3. Entregables que recibe movil

Modelo:

```text
ml/accident_detection/artifacts/motosos_accident_model_v1_1_pilot_ready.json
```

Manual tecnico del modelo:

```text
ml/accident_detection/Manual_tecnico_MotoSOS_Accident_Model_v1_1.md
```

Manual de implementacion movil:

```text
ml/accident_detection/Manual_implementacion_movil_MotoSOS_Accident_Model_v1_1.md
```

## 4. Paso 1: copiar el modelo a assets del modulo de monitoreo

Crear carpeta:

```text
service/monitoring/src/main/assets/
```

Copiar ahi el JSON:

```text
service/monitoring/src/main/assets/motosos_accident_model_v1_1_pilot_ready.json
```

Razon: el modelo se usa dentro de `MonitoringForegroundService`, que vive en `service:monitoring`.

## 5. Paso 2: crear clases de inferencia

Crear paquete:

```text
service/monitoring/src/main/java/com/motosos/driver/service/monitoring/ml/
```

Crear `AccidentMlInput.kt`:

```kotlin
package com.motosos.driver.service.monitoring.ml

data class AccidentMlInput(
    val speedKmh: Double,
    val deltaSpeedKmh: Double,
    val decelerationKmhS: Double,
    val gpsAccuracyM: Double,
    val accelPeakG: Double,
    val gyroPeakDps: Double,
    val stillnessSeconds: Double,
    val jerkPeakGS: Double,
)
```

Crear `AccidentMlPrediction.kt`:

```kotlin
package com.motosos.driver.service.monitoring.ml

data class AccidentMlPrediction(
    val probability: Double,
    val mlDetected: Boolean,
    val possibleAccident: Boolean,
)
```

Crear `AccidentMlModel.kt` usando `org.json.JSONObject` para evitar agregar dependencias nuevas:

```kotlin
package com.motosos.driver.service.monitoring.ml

import kotlin.math.exp
import org.json.JSONObject

class AccidentMlModel private constructor(
    private val features: List<String>,
    private val means: Map<String, Double>,
    private val stds: Map<String, Double>,
    private val weights: Map<String, Double>,
    private val bias: Double,
    private val threshold: Double,
) {
    fun predict(input: AccidentMlInput, hardRuleDetected: Boolean): AccidentMlPrediction {
        val sample = mapOf(
            "speed_kmh" to input.speedKmh,
            "delta_speed_kmh" to input.deltaSpeedKmh,
            "deceleration_kmh_s" to input.decelerationKmhS,
            "gps_accuracy_m" to input.gpsAccuracyM,
            "accel_peak_g" to input.accelPeakG,
            "gyro_peak_dps" to input.gyroPeakDps,
            "stillness_seconds" to input.stillnessSeconds,
            "jerk_peak_g_s" to input.jerkPeakGS,
        )

        var logit = bias
        for (feature in features) {
            val std = stds.getValue(feature).takeIf { it != 0.0 } ?: 1.0
            val normalized = (sample.getValue(feature) - means.getValue(feature)) / std
            logit += weights.getValue(feature) * normalized
        }

        val probability = sigmoid(logit)
        val mlDetected = probability >= threshold
        return AccidentMlPrediction(
            probability = probability,
            mlDetected = mlDetected,
            possibleAccident = hardRuleDetected || mlDetected,
        )
    }

    private fun sigmoid(value: Double): Double = if (value >= 0.0) {
        val z = exp(-value)
        1.0 / (1.0 + z)
    } else {
        val z = exp(value)
        z / (1.0 + z)
    }

    companion object {
        fun fromJson(rawJson: String): AccidentMlModel {
            val json = JSONObject(rawJson)
            val normalization = json.getJSONObject("normalization")
            return AccidentMlModel(
                features = json.getJSONArray("features").toStringList(),
                means = normalization.getJSONObject("mean").toDoubleMap(),
                stds = normalization.getJSONObject("std").toDoubleMap(),
                weights = json.getJSONObject("weights").toDoubleMap(),
                bias = json.getDouble("bias"),
                threshold = json.getDouble("threshold"),
            )
        }
    }
}

private fun org.json.JSONArray.toStringList(): List<String> =
    List(length()) { index -> getString(index) }

private fun JSONObject.toDoubleMap(): Map<String, Double> =
    keys().asSequence().associateWith { key -> getDouble(key) }
```

## 6. Paso 3: crear acumulador de sensores

Crear `SensorWindow.kt`:

```kotlin
package com.motosos.driver.service.monitoring.ml

import kotlin.math.abs
import kotlin.math.sqrt

data class SensorFeatures(
    val accelPeakG: Double = 0.0,
    val gyroPeakDps: Double = 0.0,
    val jerkPeakGS: Double = 0.0,
    val stillnessSeconds: Double = 0.0,
)

class SensorWindow(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var accelPeakG = 0.0
    private var gyroPeakDps = 0.0
    private var jerkPeakGS = 0.0
    private var lastAccelG = 0.0
    private var lastAccelAt = 0L
    private var stillnessSeconds = 0.0
    private var lastStillnessAt = 0L

    fun onAccelerometer(x: Float, y: Float, z: Float) {
        val now = clock()
        val magnitudeG = sqrt((x * x + y * y + z * z).toDouble()) / G_TO_MS2
        accelPeakG = maxOf(accelPeakG, magnitudeG)

        if (lastAccelAt > 0L) {
            val dt = ((now - lastAccelAt).coerceAtLeast(1L)) / 1000.0
            jerkPeakGS = maxOf(jerkPeakGS, abs(magnitudeG - lastAccelG) / dt)
        }

        if (magnitudeG in STILLNESS_MIN_G..STILLNESS_MAX_G) {
            if (lastStillnessAt > 0L) {
                stillnessSeconds += ((now - lastStillnessAt).coerceAtLeast(1L)) / 1000.0
            }
            lastStillnessAt = now
        } else {
            stillnessSeconds = 0.0
            lastStillnessAt = 0L
        }

        lastAccelG = magnitudeG
        lastAccelAt = now
    }

    fun onGyroscope(x: Float, y: Float, z: Float) {
        val magnitudeDps = sqrt((x * x + y * y + z * z).toDouble()) * RAD_TO_DEG
        gyroPeakDps = maxOf(gyroPeakDps, magnitudeDps)
    }

    fun snapshotAndReset(): SensorFeatures {
        val snapshot = SensorFeatures(
            accelPeakG = accelPeakG,
            gyroPeakDps = gyroPeakDps,
            jerkPeakGS = jerkPeakGS,
            stillnessSeconds = stillnessSeconds,
        )
        accelPeakG = 0.0
        gyroPeakDps = 0.0
        jerkPeakGS = 0.0
        return snapshot
    }

    fun reset() {
        accelPeakG = 0.0
        gyroPeakDps = 0.0
        jerkPeakGS = 0.0
        lastAccelG = 0.0
        lastAccelAt = 0L
        stillnessSeconds = 0.0
        lastStillnessAt = 0L
    }

    companion object {
        private const val G_TO_MS2 = 9.80665
        private const val RAD_TO_DEG = 57.29577951308232
        private const val STILLNESS_MIN_G = 0.85
        private const val STILLNESS_MAX_G = 1.15
    }
}
```

Nota: `stillnessSeconds` es una aproximacion inicial con acelerometro del telefono. En v2 debe calibrarse con datos reales.

## 7. Paso 4: ampliar TripSampler

Actualizar `TripSampleResult` para exponer debug ML:

```kotlin
data class TripSampleResult(
    val stats: TripStats,
    val detectedAccident: Boolean,
    val hardRuleDetected: Boolean = false,
    val mlProbability: Double? = null,
    val mlDetected: Boolean = false,
)
```

Modificar constructor de `TripSampler`:

```kotlin
class TripSampler(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val riskCalculator: RiskScoreCalculator = RiskScoreCalculator(),
    private val accidentDetector: AccidentDetector = AccidentDetector(),
    private val accidentMlModel: AccidentMlModel? = null,
)
```

Modificar firma de `onSample`:

```kotlin
fun onSample(
    location: Location,
    batteryPercent: Int,
    smartwatchConnected: Boolean,
    sensorFeatures: SensorFeatures = SensorFeatures(),
): TripSampleResult
```

Dentro de `onSample`, calcular estas features:

```kotlin
val deltaSpeedKmh = speedKmh - lastSpeedKmh
val decelerationKmhS = if (deltaSeconds > 0.0) {
    ((lastSpeedKmh - speedKmh) / deltaSeconds).coerceAtLeast(0.0)
} else {
    0.0
}
```

Despues de calcular `hardRuleDetected`, llamar al modelo:

```kotlin
val mlPrediction = accidentMlModel?.predict(
    input = AccidentMlInput(
        speedKmh = speedKmh,
        deltaSpeedKmh = deltaSpeedKmh,
        decelerationKmhS = decelerationKmhS,
        gpsAccuracyM = location.accuracyMeters?.toDouble() ?: 999.0,
        accelPeakG = sensorFeatures.accelPeakG,
        gyroPeakDps = sensorFeatures.gyroPeakDps,
        stillnessSeconds = sensorFeatures.stillnessSeconds,
        jerkPeakGS = sensorFeatures.jerkPeakGS,
    ),
    hardRuleDetected = hardRuleDetected,
)

val detectedAccident = mlPrediction?.possibleAccident ?: hardRuleDetected
```

El orden es importante: calcular features antes de actualizar `lastSpeedKmh`.

## 8. Paso 5: cargar modelo en MonitoringForegroundService

Agregar propiedad:

```kotlin
private val sensorWindow = SensorWindow()
private var accidentMlModel: AccidentMlModel? = null
```

En `startMonitoring()`, cargar asset antes de `sampler.start()`:

```kotlin
accidentMlModel = runCatching {
    assets.open("motosos_accident_model_v1_1_pilot_ready.json")
        .bufferedReader()
        .use { AccidentMlModel.fromJson(it.readText()) }
}.getOrNull()
```

Mejor opcion: cambiar `sampler` de `val` a `var` para inyectar modelo cargado:

```kotlin
private var sampler = TripSampler()
```

y luego:

```kotlin
sampler = TripSampler(accidentMlModel = accidentMlModel)
sampler.start()
```

## 9. Paso 6: registrar sensores en el foreground service

Agregar imports:

```kotlin
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
```

Agregar propiedades:

```kotlin
private var sensorManager: SensorManager? = null
private val sensorListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> sensorWindow.onAccelerometer(
                event.values[0],
                event.values[1],
                event.values[2],
            )
            Sensor.TYPE_GYROSCOPE -> sensorWindow.onGyroscope(
                event.values[0],
                event.values[1],
                event.values[2],
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
```

En `startMonitoring()` despues de iniciar foreground:

```kotlin
val sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
sensorManager = sensors
sensors.registerListener(
    sensorListener,
    sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
    SensorManager.SENSOR_DELAY_GAME,
)
sensors.registerListener(
    sensorListener,
    sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
    SensorManager.SENSOR_DELAY_GAME,
)
```

En `onDestroy()`:

```kotlin
sensorManager?.unregisterListener(sensorListener)
sensorWindow.reset()
```

## 10. Paso 7: pasar features al sampler

En `onNewLocation(...)`, obtener una ventana de sensores:

```kotlin
val sensorFeatures = sensorWindow.snapshotAndReset()
```

Pasarla al sampler:

```kotlin
val result = sampler.onSample(
    location = location.toDomain(),
    batteryPercent = device.batteryPercent,
    smartwatchConnected = device.smartwatchConnected,
    sensorFeatures = sensorFeatures,
)
```

Mantener el flujo existente:

```kotlin
tripRepository.publishTripStats(result.stats)
if (result.detectedAccident) {
    tripRepository.reportIncident(IncidentType.PossibleAccident, location.toDomain())
}
```

## 11. Paso 8: logs de piloto

Durante piloto, loggear por cada ventana:

```text
speed_kmh
delta_speed_kmh
deceleration_kmh_s
gps_accuracy_m
accel_peak_g
gyro_peak_dps
stillness_seconds
jerk_peak_g_s
ml_probability
hardRuleDetected
mlDetected
detectedAccident
```

No loggear datos personales innecesarios. Si se guardan coordenadas, tratarlas como datos sensibles.

## 12. Paso 9: pruebas unitarias minimas

Agregar tests en:

```text
service/monitoring/src/test/java/com/motosos/driver/service/monitoring/
```

Casos minimos:

- `AccidentMlModel` parsea el JSON v1.1.
- Muestra normal queda bajo threshold.
- Muestra tipo accidente queda arriba del threshold.
- `hardRuleDetected = true` fuerza `possibleAccident = true` aunque ML sea bajo.
- `TripSampler` mantiene deteccion por regla dura existente.

Sample normal:

```kotlin
AccidentMlInput(
    speedKmh = 28.0,
    deltaSpeedKmh = 1.0,
    decelerationKmhS = 0.0,
    gpsAccuracyM = 8.0,
    accelPeakG = 0.45,
    gyroPeakDps = 35.0,
    stillnessSeconds = 0.0,
    jerkPeakGS = 0.7,
)
```

Sample tipo accidente:

```kotlin
AccidentMlInput(
    speedKmh = 62.0,
    deltaSpeedKmh = -50.0,
    decelerationKmhS = 25.0,
    gpsAccuracyM = 7.0,
    accelPeakG = 3.4,
    gyroPeakDps = 360.0,
    stillnessSeconds = 18.0,
    jerkPeakGS = 8.0,
)
```

## 13. Comandos de validacion

Desde la raiz del repo:

```powershell
.\gradlew :service:monitoring:testDebugUnitTest
.\gradlew :app:assembleDebug
```

Si se cambia codigo de dominio:

```powershell
.\gradlew :domain:test
```

## 14. Criterio de aceptacion

La implementacion movil queda aceptada cuando:

- El modelo esta en assets del modulo que lo usa.
- La inferencia corre sin internet.
- El monitoreo registra sensores solo en viaje activo.
- `hardRuleDetected` sigue funcionando aunque el modelo no cargue.
- `possibleAccident` usa regla dura OR ML.
- SOS manual sigue independiente del modelo.
- Los tests unitarios pasan.
- `:app:assembleDebug` pasa.

## 15. Advertencia importante

Este modelo es `pilot-ready`, no `production-real-world-validated`.

Antes de afirmar produccion real, recolectar telemetria real etiquetada y validar:

```text
precision
recall
f1
matriz de confusion
falsos positivos cancelados por usuario
falsos negativos conocidos
```
