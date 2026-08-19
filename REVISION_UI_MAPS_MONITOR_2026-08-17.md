# Revisión UI / Maps / Monitor — 2026-08-17

Cambios principales:

- Bottom navigation unificada: Inicio, Viajes, SOS, Mapa y Perfil quedan accesibles desde las pantallas Rider principales, incluso durante un viaje activo.
- Detalle de viaje conserva bottom navigation y deja de convertirse en una pantalla aislada.
- Los iconos de top bar que no tenían acción real se ocultan; los botones Back visibles sí ejecutan navegación.
- Los mapas integrados migraron a **MapLibre Native + OpenFreeMap** y ya no requieren `MAPS_API_KEY`.
- El estilo integrado es `https://tiles.openfreemap.org/styles/liberty`; Google Maps queda sólo para navegación externa.
- El detalle de viaje encuadra el recorrido GPS real y permite abrirlo en Google Maps.
- El SOS automático publica el snapshot de ubicación después de crear la alerta, igual que el flujo manual.
- El Monitor consulta también `GET /api/v1/monitor/alerts/{notificationDeliveryAttemptId}/location` como fuente dedicada si `/status` todavía reporta ubicación no disponible.
- Cuando hay coordenadas, el Monitor muestra lat/lon, precisión, mini mapa MapLibre/OpenFreeMap y botón para abrir Google Maps.

Pruebas focalizadas agregadas/actualizadas:

- publicación de ubicación tras SOS automático;
- parseo del endpoint dedicado de ubicación Monitor;
- fallback del ViewModel Monitor a la ubicación dedicada.

Pendiente de validación en Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```
- El Monitor reintenta de forma corta y acotada la lectura de ubicación (4 lecturas, 1.25 s entre ellas) para cubrir la carrera entre recepción del push y persistencia del snapshot; no reenvía ni duplica el SOS.
- Las pruebas UI de Perfil/SOS se actualizaron al contrato de navegación global y al modal de confirmación del SOS manual.
