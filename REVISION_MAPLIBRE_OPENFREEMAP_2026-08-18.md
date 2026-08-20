# MotoSOS — migración de mapas internos a MapLibre + OpenFreeMap

Fecha: 18/08/2026

## Objetivo

Eliminar la dependencia de Google Maps SDK para los mapas embebidos de MotoSOS y evitar la necesidad de una `MAPS_API_KEY`, conservando Google Maps únicamente como navegación externa cuando el usuario lo solicita.

## Implementación

- Renderer interno: **MapLibre Native Android 13.5.0** (`org.maplibre.gl:android-sdk`).
- Mapa base: **OpenFreeMap Liberty** (`https://tiles.openfreemap.org/styles/liberty`).
- Integración con Jetpack Compose: `MapView` nativo alojado mediante `AndroidView`, con ciclo de vida sincronizado con el `LifecycleOwner`.
- Puntos GPS: `GeoJsonSource` + `CircleLayer`.
- Recorrido real: `GeoJsonSource` + `LineLayer`; no se generan puntos intermedios ficticios.
- Cámara del detalle de viaje: se ajusta a los límites de todos los puntos GPS válidos del recorrido.

## Pantallas cubiertas

1. **Mapa Rider**: muestra la ubicación actual/última ubicación válida.
2. **Detalle del viaje**: muestra la Polyline del recorrido real persistido por la API de Trips.
3. **Monitor / alerta**: muestra la ubicación del incidente cuando el snapshot remoto está disponible.

## Navegación externa

Los botones de navegación conservan Google Maps únicamente como aplicación externa mediante `Intent`/URL. MotoSOS no utiliza Google Directions ni calcula la ruta planeada.

## Configuración

Ya no se requiere `MAPS_API_KEY`, meta-data `com.google.android.geo.API_KEY`, `maps-compose` ni `play-services-maps`.

Sí se requiere permiso `INTERNET` para descargar el estilo, sprites, fuentes y tiles de OpenFreeMap.

## Validación realizada en este entorno

- Búsqueda estática de dependencias/imports Google Maps SDK.
- Validación XML de manifest y recursos.
- Revisión de que las tres pantallas utilicen el helper común MapLibre.
- No se modificaron los assets protegidos.

## Validación pendiente en Windows

Este entorno no dispone del Android SDK del proyecto. Ejecutar:

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Después instalar con `adb install -r` y validar Mapa, detalle del recorrido y ubicación en Monitor.
