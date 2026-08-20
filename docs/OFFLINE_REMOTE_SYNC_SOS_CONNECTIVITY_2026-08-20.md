# MotoSOS — sincronización remota y conectividad

Fecha: 2026-08-20

## Estado final

La app no usa un mapeo genérico inventado para la cola offline. Cada dato con contrato remoto conocido tiene un flujo explícito:

- SOS manual → `POST /api/v1/mobile/sos-alerts`
- SOS automático → `POST /api/v1/mobile/sos-alerts`
- cierre de viaje → `POST /api/v1/trips/{id}/finish`
- recorrido GPS → `POST /api/v1/trips/{id}/route-points/batch`

Todos los workers de recuperación que dependen de red aceptan `NetworkType.CONNECTED`; por tanto pueden ejecutarse con Wi‑Fi o datos móviles.

## Eventos secundarios

`MinorEvent`/`Bump` conserva persistencia local, cifrado y metadatos internos, pero permanece `PausedNotConfigured` para transporte remoto. La documentación disponible enumera `/api/v1/mobile/offline-ingestion/batch`, pero sólo ejemplifica `LocationSnapshot`; no documenta el `type/payload` aceptado para `MinorEvent`.

Estos eventos secundarios:

- no se muestran como error de sincronización al Rider;
- no bloquean SOS;
- no se cuentan en la tarjeta Home **Estado de sincronización**;
- no se envían a endpoints no documentados.

## Por qué se eliminó el transporte experimental

Una iteración anterior incluyó un transporte `OfflineIngestion` que mapeaba `MinorEvent`, `LocalIncident` y `AlertDispatchRequest` sin evidencia suficiente de que el backend aceptara esos tipos. Se retiró ese mapeo del código de producción y tests porque produjo 4xx y el contrato disponible sólo demuestra `LocationSnapshot`. El endpoint backend puede existir sin que esos tipos concretos sean válidos.
