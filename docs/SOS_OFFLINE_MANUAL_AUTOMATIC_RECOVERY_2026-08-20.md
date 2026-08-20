# MotoSOS — recuperación offline de SOS manual y automático

Fecha: 2026-08-20

## Objetivo

Un SOS no debe perderse sólo porque el teléfono no tenga Internet en el momento de la emergencia.
La recuperación acepta tanto Wi‑Fi como datos móviles mediante `NetworkType.CONNECTED`.

## SOS manual

1. Se generan `clientIncidentId` y `clientAlertRequestId` una sola vez.
2. La identidad del SOS se persiste antes del intento remoto en `RemoteIncidentLinkStore`, asociada al Rider autenticado.
3. El intento foreground usa `POST /api/v1/mobile/sos-alerts` como antes.
4. Si falla por red, timeout, contexto temporal, 401/408/409/429 o 5xx, la UI pasa a `SavedOffline`.
5. `ManualSosSyncWorker` reintenta únicamente el SOS pendiente ya existente; nunca crea uno nuevo.
6. Al volver Wi‑Fi o datos móviles, el scheduler despierta el worker manual inmediatamente.
7. El mismo par de IDs se reutiliza en todos los reintentos para conservar idempotencia.

## SOS automático

El bundle durable `LocalIncident + AlertDispatchRequest` conserva su worker especializado y su endpoint canónico `/api/v1/mobile/sos-alerts`.
Cuando el envío entra en retry, la UI informa que la alerta quedó guardada y muestra un modal una sola vez por evaluación.

## Compatibilidad de filas antiguas

Una reparación de una sola ejecución por instalación/Rider vuelve a `RetryPending` bundles automáticos antiguos que quedaron `FailedPermanent` con el marcador legado `remote_submission_failed`.
No repara payloads corruptos ni fallos de identidad local.

## Aislamiento por Rider

Los contadores de SOS automático en UI se consultan sólo para el Rider autenticado. Un SOS de otra cuenta guardado en el mismo dispositivo no debe aparecer ni ser reintentado bajo un JWT distinto.

## Fuera de alcance

Los `MinorEvent`/eventos secundarios continúan locales porque el contrato backend disponible no define un endpoint certificado para esos tipos. No se inventa un API genérico.
