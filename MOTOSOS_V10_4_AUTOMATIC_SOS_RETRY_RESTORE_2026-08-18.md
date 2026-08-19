# MotoSOS v10.4 — restauración del retry del SOS automático

Fecha: 2026-08-18

## Objetivo

Restaurar la confiabilidad observada en la versión P5A/v2 donde el SOS automático podía superar una falla transitoria y completar el envío sin cambiar su identidad durable.

El SOS manual no fue modificado porque ya fue confirmado físicamente como funcional.

## Comparación realizada

Se compararon especialmente:

- `MotoSOS-SegundoPlano-Revisado-2026-08-17.zip`
- `MotoSOS-SegundoPlano-SOS-Rutas-Mapas-2026-08-17-v2.zip`
- `MotoSOS-SegundoPlano-SOS-Automatico-Corregido-2026-08-18-v10.3.zip`

La ruta durable histórica ya conservaba correctamente:

- `clientIncidentId`
- `clientAlertRequestId`
- `tripSessionKey`
- `remoteTripId`
- latitud/longitud originales

También conservaba el retry corto de persistencia local cuando el primer `enqueueIncidentBundle()` fallaba.

## Cambio principal v10.4

`AuthenticatedAutomaticSosAlertCreator` ahora hace:

1. Construye una sola vez el request automático usando los IDs, viaje y coordenadas durables.
2. Ejecuta el POST a `/api/v1/mobile/sos-alerts`.
3. Si el resultado es transitorio, espera 1 segundo.
4. Reintenta exactamente el mismo request una vez.
5. Si el segundo intento funciona, continúa con el receipt durable y finalización normal.
6. Si vuelve a fallar, la cola durable/WorkManager conserva el bundle para recuperación posterior.

Fallos que reciben el retry corto:

- red no disponible;
- timeout;
- HTTP 408;
- HTTP 429;
- HTTP 5xx;
- respuesta inválida/transitoria.

Errores permanentes HTTP 4xx como 400 no se reintentan inmediatamente.

## Idempotencia

El retry no genera un segundo incidente lógico. Ambos POST utilizan exactamente:

- mismo `clientIncidentId`;
- mismo `clientAlertRequestId`;
- mismo `tripId` remoto;
- mismo `detectedAtUtc`;
- misma latitud y longitud;
- misma causa, severidad y prioridad.

Esto aprovecha la idempotencia que ya ofrece el backend MotoSOS.

## Debug accident

Se reforzó `DEBUG_TRIGGER_ACCIDENT`:

- primero toma el `sessionId` que está usando el coordinador de validación;
- evita discrepancias con una ventana vieja del preprocessing/risk store;
- el trigger debug ignora únicamente la supresión temporal posterior a `Estoy bien`, para que una prueba explícita ADB no desaparezca durante esos 10 segundos;
- las señales físicas productivas siguen respetando esa supresión.

## CriticalPhysicalEvent

`ImmediateAlertRequested` ahora también entrega su `bundleKey` a la finalización exacta del viaje después de la persistencia/creación remota exitosa. Antes el finalizador sólo ejecutaba `processBundle()` para `IncidentGenerated`, aunque el evento crítico inmediato ya estaba persistido.

## Cobertura agregada

Se agregó cobertura focal para verificar:

- primer POST `NetworkUnavailable` + segundo POST `Success`;
- ambos requests son estructuralmente idénticos;
- se conservan UUIDs, tripId y coordenadas;
- HTTP 400 no hace retry automático.

Se conserva la cobertura previa donde el primer enqueue local falla, aparece `IncidentDeliveryRetrying`, se reintenta y se reutilizan exactamente los mismos IDs/contexto.

## Validación de artefactos

- XML app + Wear parseados sin errores.
- 7 PNG protegidos comparados contra v10.3: idénticos.
- No se modificó el SOS manual.
- No se modificaron pantallas Rider/Monitor, MapLibre, contacto, GPS ni reglas de riesgo en esta revisión.

## Limitación del entorno

No se pudo ejecutar Gradle en el contenedor porque el wrapper intenta descargar Gradle 9.4.1 desde `services.gradle.org` y el entorno no tiene resolución de red. La compilación final debe ejecutarse en el PowerShell del proyecto.
