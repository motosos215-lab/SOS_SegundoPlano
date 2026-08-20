# MotoSOS SegundoPlano — changelog de integración final

Fecha: 2026-08-20

## Base

Deriva de la versión validada funcionalmente por el usuario con SOS manual y automático offline.

## Cambios de esta integración

### Cierre de viaje offline

- nuevo `PendingTripFinishStore` durable y correlacionado por Rider/viaje;
- nuevo `UserTripFinishCoordinator` que persiste antes de depender de red;
- nuevo `TripFinishRecoveryWorker` + scheduler único;
- retry por Wi‑Fi o datos móviles;
- no se limpia el pendiente hasta confirmación/reconciliación;
- bloqueo de nuevo viaje mientras el cierre anterior siga pendiente/atención.

### Estado de sincronización

- eliminado del `MonitoringScreen`;
- nueva tarjeta en Inicio;
- cuenta SOS automático, SOS manual, cierre de viaje y puntos de ruta pendientes;
- estado vacío: `Todo sincronizado / Sin eventos por sincronizar`;
- `MinorEvent/Bump` no se cuenta como remoto pendiente.

### Cola secundaria

- retirado el mapeo experimental `AuthenticatedOfflineEventTransport` para tipos no confirmados;
- `PausedNotConfigured` deja de generar un loop de reintentos WorkManager;
- el endpoint backend offline batch puede existir, pero no se asume soporte para `MinorEvent` sin contrato de `type/payload`.

### DevSecOps

- Android CI ahora incluye compile, unit, lint y assemble;
- Gitleaks;
- CodeQL Java/Kotlin;
- Dependency Review;
- Dependabot Gradle/GitHub Actions;
- PR template, `SECURITY.md`, checklist y documentación de integración.

## Sin cambios intencionales

- política ML/SOS automático;
- threshold/modelo v1.1;
- SOS manual ya corregido;
- QR/código Monitor;
- Wear OS;
- endpoints canónicos de SOS/trips/ruta.
