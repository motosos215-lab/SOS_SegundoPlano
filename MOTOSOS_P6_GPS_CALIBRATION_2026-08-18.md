# MotoSOS P6.1 — Calibración GPS de arranque

Fecha: 2026-08-18
Base: MotoSOS v5 MapLibre/OpenFreeMap

## Alcance de esta entrega

Se implementó únicamente el primer paso acordado: calibración GPS al iniciar el monitoreo del viaje. No se modificó todavía la corrección de gravedad/acelerómetro del reloj, los umbrales de falso positivo, los iconos ni la política de sesiones únicas.

## Comportamiento integrado

1. Al iniciar la captura del viaje, el GPS entra en `GpsCalibrationState.Calibrating`.
2. MotoSOS muestra la ubicación/precisión actual para que el Rider vea cómo mejora, pero no la considera todavía una posición de arranque confiable para la ruta.
3. El objetivo de arranque es `accuracyMeters <= 20 m` durante 3 muestras consecutivas.
4. Una muestra peor a 20 m reinicia la racha de muestras buenas; se conserva la mejor precisión observada sólo como diagnóstico.
5. Al completar 3 muestras consecutivas buenas, el estado queda enclavado en `GpsCalibrationState.Ready` para ese arranque.
6. Mientras está calibrando:
   - la UI muestra `Calibrando GPS` y la precisión actual;
   - la velocidad GPS se mantiene en espera para evitar velocidad falsa por deriva inicial;
   - la ubicación de arranque no se entrega al preprocesamiento/riesgo como muestra confiable;
   - `TripRouteRecorder` no persiste esos puntos ruidosos en el recorrido definitivo.
7. Una emergencia no queda bloqueada por esta calibración: la ubicación actual sigue disponible para el flujo SOS como mejor dato disponible. La calibración sólo protege la interpretación de viaje/ruta/velocidad al arranque.
8. Si el GPS está desactivado, no hay permisos o el proveedor falla, la UI conserva los estados existentes de error en lugar de quedarse mostrando `Calibrando GPS`.

## Nota sobre precisión

`Location.accuracy` es una estimación del radio de precisión horizontal; Android no puede conocer la distancia exacta al punto "verdadero". Por eso MotoSOS usa una compuerta de calidad repetida (3 muestras <= 20 m) en vez de prometer una precisión absoluta.

## Archivos principales modificados

- `domain/signals/SignalModels.kt`
- `data/signals/GpsStartupCalibrator.kt` (nuevo)
- `data/signals/AndroidLocationSignalSource.kt`
- `data/signals/TripSignalStore.kt`
- `data/route/TripRouteRecorder.kt`
- `features/background/MonitoringScreen.kt`
- `data/signals/GpsStartupCalibratorTest.kt` (nuevo)
- `data/signals/InMemoryTripSignalStoreTest.kt`
- `data/signals/RawSignalEventStoreTest.kt`

## Pruebas agregadas

- requiere 3 fixes consecutivos <= 20 m;
- una muestra mala reinicia la racha;
- el estado Ready queda enclavado;
- la velocidad derivada se bloquea durante calibración;
- la ubicación sigue visible en snapshot, pero no entra al preprocesamiento/riesgo durante calibración.

## Validación realizada en este entorno

- Lógica pura del calibrador compilada con `kotlinc` y ejecutada: `GPS_CALIBRATION_LOGIC_OK`.
- XML de `app/src`: 19 archivos, 0 errores.
- Corrección previa de MapLibre preservada: `intArrayOf(...)` en `MotoMapLibre.kt`.
- 7 PNG protegidos comparados contra v5: idénticos.
- Gradle no pudo ejecutarse en este contenedor porque el wrapper intenta descargar Gradle 9.4.1 y no hay acceso a `services.gradle.org`.

## Qué revisar físicamente

1. Inicia un viaje al aire libre.
2. En Viaje activo, observa `Precisión GPS`.
3. Debe mostrar algo parecido a `Calibrando · ± 35 m` y `0/3 · objetivo <=20 m`.
4. Cuando lleguen tres muestras buenas consecutivas, debe pasar a `GPS listo`.
5. Durante calibración, Velocidad debe mostrar `Calibrando GPS`, no una velocidad falsa por deriva.
6. Finaliza el viaje y revisa el detalle: el recorrido no debe arrancar con los puntos ruidosos previos a la calibración.
