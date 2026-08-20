# MotoSOS — SOS automático, recorrido real y Google Maps

Fecha: 2026-08-17

## Alcance

Esta revisión continúa sobre la versión móvil ya corregida. No cambia los PNG visuales existentes. Se concentra en:

1. robustez y visibilidad del SOS automático;
2. captura/sincronización durable del recorrido GPS real de cada viaje;
3. historial con recorrido real, no una línea A→B inventada;
4. nueva sección Mapa con Google Maps embebido y navegación externa a destino.

## 1. SOS automático

### Correcciones

- `CountdownActive` ahora tiene prioridad visual global mientras existe un viaje activo. Si el Rider está en SOS, Historial o Mapa y aparece un posible accidente, la pantalla de cuenta regresiva reemplaza esa vista inmediatamente.
- Los botones **Estoy bien** y **Necesito ayuda** permanecen conectados también cuando la pantalla de emergencia sustituyó otra pestaña.
- Un fallo transitorio de persistencia ya no se muestra como error terminal mientras hay un retry programado. Se usa `IncidentDeliveryRetrying`.
- Red, timeout, GPS momentáneamente no disponible y `remoteTripId` todavía no reconciliado se consideran recuperables con retry acotado.
- Errores HTTP 4xx no recuperables siguen siendo terminales (excepto 401/408/429, que conservan semántica de retry).
- Los retries de un incidente ya preparado conservan exactamente `clientIncidentId`, `clientAlertRequestId`, `tripSessionKey`, `remoteTripId` y las coordenadas originales. No vuelven a capturar otro punto GPS ni otro viaje si esos datos ya existen.
- El primer bundle durable del automático contiene `remoteTripId` y ubicación antes del primer enqueue.
- Se añadieron logs debug seguros para distinguir etapa de detección/validación, persistencia y excepción de storage sin imprimir tokens o mensajes sensibles.
- `DebugAccidentAlertReceiver` requiere viaje local activo y ya no fabrica una sesión de prueba si el pipeline no está listo.

### Regresiones cubiertas en tests fuente

- identidad y ubicación idénticas en retry de persistencia;
- segunda captura GPS deliberadamente diferente no debe ejecutarse durante retry;
- fallo HTTP 400 permanente no programa una segunda submission;
- network failure queda en estado visible de retry;
- missing remote trip / location queda en retry, no en falso terminal inmediato;
- cuenta regresiva automática reemplaza otra pestaña abierta durante viaje activo.

## 2. Recorrido GPS real por viaje

Backend indicado para esta integración:

- `POST /api/v1/trips/{tripId}/route-points/batch`
- `GET /api/v1/trips/{tripId}/route`
- `GET /api/v1/trips/{tripId}/route?mode=preview`

Cada punto enviado conserva:

### Requeridos

- `clientRoutePointId`
- `sequence`
- `recordedAtUtc`
- `latitude`
- `longitude`
- `accuracyMeters`

### Opcionales

- `speedMetersPerSecond`
- `bearingDegrees`
- `appVersion`
- `deviceId`

### Diseño Android implementado

- Base Room independiente `trip_route_points.db`.
- Cada punto se guarda localmente antes de pedir sincronización.
- UUID por punto se genera una sola vez y se conserva en todos los retries.
- `sequence` es durable por `ownerUserId + remoteTripId` mediante un checkpoint separado. Aunque los puntos ya enviados se borren de la cola y el proceso muera, el siguiente punto no vuelve a `sequence=1`.
- WorkManager sincroniza sólo con red y usa backoff exponencial.
- Máximo por request Android: 500 puntos, alineado con `Trips__RoutePoints__MaxBatchSize=500`.
- Se agenda sync cada 10 puntos, al detener el recorder y cada vez que aparece/restaura una sesión Rider autenticada.
- Esto evita la carrera de startup donde WorkManager podía ejecutarse antes de restaurar la sesión cifrada.
- El worker elimina filas locales únicamente cuando HTTP es exitoso y el envelope responde `success=true`.
- 401/408/429/5xx se reintentan.
- 403/404/409 y otros rechazos no se borran silenciosamente: se conservan para diagnóstico.
- El recorder exige correlación entre `TripSessionState.Active(A)` y el `remoteTripId` guardado para A.
- Se usa la ubicación realmente capturada por MotoSOS; no se interpolan puntos ni se usa Directions API para reconstruir el viaje.

### Contrato pendiente de confirmar con OpenAPI del backend

El mensaje recibido especifica los endpoints y campos de **cada item**, pero no especifica literalmente el nombre del contenedor JSON del batch ni el nombre exacto de la colección dentro de `data` para GET.

La implementación actual usa:

```json
{
  "points": [ ... ]
}
```

para POST y acepta `data.routePoints` o `data.points` para GET.

Esto está deliberadamente aislado en DTOs/API tests. Antes de producción se debe confirmar contra el OpenAPI/Swagger del backend si el batch efectivamente se llama `points`. Si el backend usa otro wrapper, sólo debe ajustarse ese DTO/API test; no la lógica de captura o idempotencia.

## 3. Historial de viajes

- `RiderTripHistoryItem` conserva el `tripId` remoto.
- La lista solicita `route?mode=preview` para cada viaje disponible.
- Las miniaturas usan exclusivamente puntos reales devueltos por backend.
- Se eliminó la representación engañosa de una ruta recta entre punto inicial y final.
- Si no hay puntos: **Ruta GPS aún no sincronizada / Recorrido real aún no disponible**.
- El detalle consulta `GET /route` completo.
- MapLibre/OpenFreeMap embebido dibuja la Polyline siguiendo todos los puntos ordenados por `sequence`.
- Marca Inicio y Fin.
- La distancia mostrada suma todos los segmentos GPS con Haversine; no usa sólo distancia A→B.

## 4. Sección Mapa

Nueva pestaña **Mapa** dentro de la navegación Rider:

- mapa embebido con **MapLibre Native + OpenFreeMap**;
- marcador de ubicación actual;
- cámara centrada en una ubicación utilizable;
- campo **¿A dónde vas?**;
- botón **Navegar con Google Maps**;
- con Google Maps instalado se abre `google.navigation:q=...&mode=d`;
- si la app Google Maps no está disponible, se abre Google Maps web en modo Directions con destino y conducción;
- MotoSOS no calcula ni persiste esa ruta planificada: Google genera navegación/recálculo fuera de la app;
- si hay un viaje activo, MotoSOS continúa capturando su recorrido GPS real en segundo plano.

## 5. Configuración de mapas integrada

Dependencia añadida:

- MapLibre Native 0.14.0

El mapa base usa el estilo Liberty público de OpenFreeMap:

`https://tiles.openfreemap.org/styles/liberty`

No se requiere `MAPS_API_KEY` ni `Maps SDK for Android` para renderizar los mapas integrados. Google Maps se conserva solamente como navegación externa mediante intents/URLs.

## 6. Diseño visual

Se revisaron los mockups/imágenes entregados. Se mantuvo la lógica visual:

- navy/azul oscuro para identidad y estructura;
- tarjetas blancas y fondo claro;
- verde para acciones de navegación/estado correcto;
- rojo reservado para emergencia;
- mapa como contenido principal en ubicaciones/rutas;
- textos cortos que explican qué está ocurriendo.

Los PNG protegidos no fueron modificados.

## 7. Validación realizada en este entorno

- XML de `app/src`: parse correcto.
- Archivos Kotlin críticos modificados: parse sintáctico focalizado correcto con `kotlinc`.
- Los PNG protegidos fueron comparados por SHA-256 contra la fuente original y permanecen idénticos.
- No fue posible obtener `BUILD SUCCESSFUL` aquí porque este contenedor no tiene el Android SDK de Windows ni acceso de red para descargar la distribución/dependencias Gradle faltantes.

## 8. Validación obligatoria en Windows

Desde PowerShell normal:

```powershell
cd "C:\Users\gmen2\OneDrive\Escritorio\MotoSOS\SegundoPlano"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"

.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Después, para pruebas instrumentadas relevantes:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.example.sos_segundoplano.ui.TripSessionRestorationTest" `
  --console=plain
```

## 9. Prueba física SOS automático

Instalar conservando datos:

```powershell
adb -s "RFCW30M1F4E" install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

Iniciar viaje desde UI normal y disparar **una sola vez**:

```powershell
adb -s "RFCW30M1F4E" shell am broadcast `
  -a com.example.sos_segundoplano.DEBUG_TRIGGER_ACCIDENT `
  -n com.example.sos_segundoplano/.debug.DebugAccidentAlertReceiver
```

Logs focalizados:

```powershell
adb -s "RFCW30M1F4E" logcat -d | Select-String `
  "MotoSOS.DebugAccident|MotoSOS.AutoIncident|MotoSOS.OfflineQueue|MotoSOS.TripRoute"
```

Esperado:

- `DEBUG_TRIGGER_ACCIDENT` aceptado con viaje activo;
- countdown visible aunque se estuviera en otra pestaña;
- un bundle completo con identidad estable;
- transient storage/network => estado de retry, no falso error terminal;
- `IncidentGenerated` / envío remoto al recuperarse;
- no doble captura/UUID durante retries.

## 10. Prueba física de recorrido

1. iniciar un viaje real;
2. moverse varios minutos con GPS;
3. permitir red para al menos un batch;
4. finalizar viaje;
5. abrir Historial → viaje;
6. verificar que la Polyline sigue calles/puntos realmente recorridos y no una recta A→B;
7. reiniciar la app durante un viaje de prueba y confirmar que `sequence` continúa creciendo;
8. probar sin red, finalizar, recuperar red dentro de las 24 h y confirmar sincronización posterior;
9. probar `mode=preview` en lista y ruta completa en detalle.

## 11. No realizado

- No se modificó backend.
- No se usa Google Directions API dentro de MotoSOS.
- No se inventan puntos para completar una ruta.
- No se incluyó ninguna API key.
- No se modificaron los PNG originales.
