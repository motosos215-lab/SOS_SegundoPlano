# Manual tecnico - MotoSOS Accident Model v1.1

## 1. Entregable

Archivo para integracion movil:

```text
artifacts/motosos_accident_model_v1_1_pilot_ready.json
```

El artefacto es un JSON autocontenido. No requiere Python, TensorFlow Lite, scikit-learn ni conexion a internet en la app.

## 2. Estado del modelo

Version: `1.1.0-pilot-ready`

Uso recomendado: piloto tecnico y validacion controlada en app movil.

No debe presentarse como modelo productivo validado con accidentes reales. Fue entrenado con ventanas sinteticas/public-style y necesita validacion con telemetria real de MotoSOS antes de hacer claims de produccion.

## 3. Objetivo

Estimar la probabilidad de que una ventana de telemetria durante un viaje activo sea compatible con un posible accidente de motocicleta.

El modelo es una senal de apoyo. No reemplaza SOS manual, reglas duras ni confirmacion del usuario.

## 4. Entradas requeridas

La app debe calcular exactamente estas 8 features numericas por ventana de monitoreo:

| Feature | Unidad | Descripcion |
| --- | --- | --- |
| `speed_kmh` | km/h | Velocidad actual estimada |
| `delta_speed_kmh` | km/h | Cambio de velocidad contra la ventana anterior |
| `deceleration_kmh_s` | km/h/s | Desaceleracion positiva por segundo |
| `gps_accuracy_m` | m | Precision GPS reportada por Android |
| `accel_peak_g` | g | Pico de aceleracion de la ventana |
| `gyro_peak_dps` | grados/s | Pico de giroscopio de la ventana |
| `stillness_seconds` | s | Tiempo de inmovilidad posterior al evento |
| `jerk_peak_g_s` | g/s | Pico de cambio brusco de aceleracion |

## 5. Regla de velocidad

No confiar solamente en `Location.speed` de Android.

La app debe calcular velocidad por distancia y tiempo entre puntos GPS:

```text
computed_speed_kmh = distance_meters / elapsed_seconds * 3.6
```

Recomendacion:

```text
speed_kmh = computed_speed_kmh si es razonable; si no, usar Location.speed * 3.6 como respaldo
```

Guardar logs de debug durante piloto:

```text
raw_gps_speed_kmh
computed_speed_kmh
gps_accuracy_m
latitude
longitude
provider
```

## 6. Inferencia

El JSON contiene:

```text
bias
features
normalization.mean
normalization.std
weights
threshold
```

La app debe normalizar cada feature, sumar pesos y aplicar sigmoid.

Referencia Kotlin:

```kotlin
fun predict(sample: Map<String, Double>, hardRuleDetected: Boolean): Pair<Double, Boolean> {
    var logit = model.bias

    for (feature in model.features) {
        val normalized = (sample.getValue(feature) - model.mean.getValue(feature)) /
            model.std.getValue(feature)
        logit += model.weights.getValue(feature) * normalized
    }

    val probability = 1.0 / (1.0 + kotlin.math.exp(-logit))
    val possibleAccident = hardRuleDetected || probability >= model.threshold

    return probability to possibleAccident
}
```

## 7. Politica de decision

La politica obligatoria es:

```text
possibleAccident = hardRuleDetected || probability >= threshold
```

Para v1.1:

```text
threshold = 0.71
```

El umbral fue seleccionado en el split de entrenamiento para priorizar recall y mantener falsos positivos acotados.

## 8. Definicion de hardRuleDetected

`hardRuleDetected` es un booleano externo al modelo ML generado por reglas explicables en la app.

Reglas minimas esperadas:

- Desaceleracion fuerte entre velocidad anterior y actual.
- Impacto o rotacion anormal si hay sensores disponibles.
- Inmovilidad posterior al evento si hay sensores disponibles.

Referencia Android actual:

```text
domain/src/main/kotlin/com/motosos/driver/domain/trip/TripStatsCalculator.kt::AccidentDetector
```

## 9. Salida esperada

La inferencia devuelve:

```text
probability: Double entre 0.0 y 1.0
possibleAccident: Boolean
```

Si `possibleAccident == true`, la app debe:

- Mostrar pantalla de confirmacion: `Estas bien?`.
- Iniciar cuenta regresiva.
- Permitir cancelar si fue falso positivo.
- Si el usuario no responde, crear incidente `PossibleAccident`.
- Persistir o enviar el incidente usando el flujo existente.

## 10. Metricas v1.1

Evaluacion en split held-out sintetico/public-style:

| Metrica | Valor |
| --- | ---: |
| Accuracy | 0.983 |
| Precision | 0.909 |
| Recall | 0.960 |
| Specificity | 0.986 |
| F1 | 0.934 |
| Threshold | 0.71 |

Matriz de confusion test:

| Campo | Valor |
| --- | ---: |
| True negative | 863 |
| False positive | 12 |
| False negative | 5 |
| True positive | 120 |

En este caso de seguridad, `recall` es la metrica principal porque perder un accidente real es mas costoso que activar una confirmacion falsa. La app aun debe usar confirmacion del usuario para reducir impacto de falsos positivos.

## 11. Datos de entrenamiento

Fuente: ventanas sinteticas/public-style generadas para MotoSOS.

Tamanos:

```text
train = 4000 ventanas
test = 1000 ventanas
```

Balance:

```text
train negative = 3516
train positive = 484
test negative = 875
test positive = 125
```

Configuracion:

```text
algorithm = logistic_regression
epochs = 650
learning_rate = 0.08
seed = 215
regularization = none_explicit
dependencies = Python standard library only
```

## 12. Limitaciones

- No esta validado con un dataset grande de accidentes reales de MotoSOS.
- Fue entrenado con datos sinteticos/public-style.
- `gps_accuracy_m` tiene peso relativo bajo; se mantiene como senal de calidad GPS y debe compararse contra una ablacion en v2.
- La calidad de `speed_kmh` depende de que la app calcule bien distancia/tiempo.
- El modelo no debe disparar emergencia final sin confirmacion del usuario.

## 13. Recomendacion para piloto

Durante pruebas reales, registrar por ventana:

```text
speed_kmh
delta_speed_kmh
deceleration_kmh_s
gps_accuracy_m
accel_peak_g
gyro_peak_dps
stillness_seconds
jerk_peak_g_s
probability
hardRuleDetected
possibleAccident
user_cancelled_countdown
incident_sent
```

Usar esos datos para entrenar y validar una v2 con telemetria real.
