# MotoSOS API - Mobile/Web Functional Context

Esta guia resume el estado funcional actual de MotoSOS API para equipos mobile, web y admin. Los endpoints listados fueron validados contra los archivos `MotoSOS.API/Modules/**/Endpoints/*.cs` actuales.

## Estado General De La API

MotoSOS API es la API central del producto. Actualmente expone flujos funcionales para autenticacion, onboarding de riders, perfil, vehiculos, contactos de emergencia, dispositivos, planes, viajes, incidentes, dispatch de alertas, intentos de notificacion simulados, acknowledgements de monitores, ubicacion de emergencia, estado agregado de emergencia, reporte de resolucion, procesamiento offline y dashboard operacional admin.

La API usa JSON con propiedades en `camelCase` y responde normalmente mediante el wrapper estandar `ApiResponse<T>`.

Las notificaciones actuales son simuladas. La API prepara y procesa registros de notificacion, pero no envia SMS, email, push ni WhatsApp reales.

## Roles

`Rider`: usuario motociclista/conductor. Puede completar onboarding, administrar perfil, vehiculos, contactos, dispositivos, plan, viajes, incidentes, alert dispatch, notificaciones simuladas, ubicacion de emergencia, status propio y reportes de resolucion.

`Monitor`: contacto/monitor de emergencia. Puede consultar alertas asignadas, ver estado/ubicacion/reporte de una emergencia asignada y responder con view, acknowledge o decline.

`Admin`: operador administrativo. Puede consultar dashboard operacional y ejecutar herramientas admin del notification outbox simulado.

Los endpoints declaran `RequireAuthorization()` cuando requieren token. El rol efectivo se valida en la capa de servicio para los flujos Rider, Monitor y Admin.

## Wrapper Estandar De Respuesta

Respuesta exitosa:

```json
{
  "success": true,
  "data": {
    "id": "example-id"
  },
  "error": null
}
```

Respuesta con error:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "validation_error",
    "message": "The request is invalid."
  }
}
```

Los endpoints `NoContent` pueden responder `204` sin body.

## Flujo Recomendado Para Movil

1. Registrar o iniciar sesion con `POST /api/v1/auth/register` o `POST /api/v1/auth/login`.
2. Consultar usuario actual con `GET /api/v1/users/me`.
3. Completar perfil con `PUT /api/v1/profiles/me`.
4. Registrar vehiculo con `POST /api/v1/vehicles`.
5. Registrar contactos de emergencia con `POST /api/v1/emergency-contacts`.
6. Crear codigo de activacion movil con `POST /api/v1/devices/mobile/activation-code` si aplica.
7. Vincular movil con `POST /api/v1/devices/mobile/link`.
8. Seleccionar plan basico con `POST /api/v1/subscriptions/select-basic`.
9. Revisar onboarding con `GET /api/v1/onboarding/summary`.
10. Confirmar onboarding con `POST /api/v1/onboarding/confirm`.
11. Iniciar viaje con `POST /api/v1/trips/start`.
12. Crear incidente con `POST /api/v1/incidents` cuando se detecte emergencia.
13. Crear alert dispatch con `POST /api/v1/alert-dispatches`.
14. Preparar notification attempts con `POST /api/v1/notifications/delivery-attempts/prepare`.
15. Compartir ubicacion con `POST /api/v1/mobile/location-sharing/snapshot`.
16. Consultar estado con `GET /api/v1/rider/emergencies/{incidentId}/status`.
17. Cerrar incidente o cancelar falso positivo.
18. Crear reporte de resolucion con `POST /api/v1/rider/emergencies/{incidentId}/resolution-report`.

## Flujo Recomendado Para Web

1. Implementar login y sesion con Auth API.
2. Mostrar estado de onboarding con `GET /api/v1/onboarding/status` y `GET /api/v1/onboarding/summary`.
3. Permitir edicion de perfil, vehiculos y contactos de emergencia.
4. Permitir consulta de viajes, incidentes, dispatches, attempts y acknowledgements del rider.
5. Mostrar estado agregado de emergencia con Emergency Status API.
6. Mostrar ubicacion de emergencia si existe snapshot disponible.
7. Mostrar reportes de resolucion existentes.

## Flujo Recomendado Para Admin

1. Iniciar sesion con cuenta Admin.
2. Consultar resumen operacional con `GET /api/v1/admin/dashboard/summary`.
3. Revisar incidentes con `GET /api/v1/admin/dashboard/incidents`.
4. Revisar tiempos de respuesta con `GET /api/v1/admin/dashboard/response-times`.
5. Revisar outcomes de resolucion con `GET /api/v1/admin/dashboard/resolution-outcomes`.
6. Consultar outbox con `GET /api/v1/admin/notifications/outbox/status`.
7. Ejecutar procesamiento simulado con `POST /api/v1/admin/notifications/outbox/run`.
8. Reintentar fallidos con `POST /api/v1/admin/notifications/outbox/retry-failed`.

## Matriz De Endpoints Reales

| Metodo | Ruta | Rol | Proposito | Estado actual |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | Public | Registro de usuario Rider o Monitor | Funcional |
| POST | `/api/v1/auth/login` | Public | Login con email/password | Funcional |
| POST | `/api/v1/auth/forgot-password` | Public | Solicitud de reset password | Funcional como solicitud |
| POST | `/api/v1/auth/request-access-code` | Public | Solicitar codigo de acceso | Funcional como solicitud |
| POST | `/api/v1/auth/login-with-code` | Public | Login con codigo de acceso | Preparado, responde `501 feature_not_implemented` |
| POST | `/api/v1/auth/refresh` | Public | Renovar access token | Funcional |
| POST | `/api/v1/auth/logout` | Public | Cerrar sesion/refresh token | Funcional |
| GET | `/api/v1/users/me` | Auth | Obtener usuario actual | Funcional |
| GET | `/api/v1/profiles/me` | Rider | Obtener perfil propio | Funcional |
| PUT | `/api/v1/profiles/me` | Rider | Crear/actualizar perfil propio | Funcional |
| GET | `/api/v1/vehicles` | Rider | Listar vehiculos propios | Funcional |
| GET | `/api/v1/vehicles/{id}` | Rider | Obtener vehiculo propio | Funcional |
| POST | `/api/v1/vehicles` | Rider | Crear vehiculo | Funcional |
| PUT | `/api/v1/vehicles/{id}` | Rider | Actualizar vehiculo | Funcional |
| DELETE | `/api/v1/vehicles/{id}` | Rider | Eliminar vehiculo | Funcional |
| GET | `/api/v1/emergency-contacts` | Rider | Listar contactos | Funcional |
| GET | `/api/v1/emergency-contacts/{id}` | Rider | Obtener contacto | Funcional |
| POST | `/api/v1/emergency-contacts` | Rider | Crear contacto | Funcional |
| PUT | `/api/v1/emergency-contacts/{id}` | Rider | Actualizar contacto | Funcional |
| DELETE | `/api/v1/emergency-contacts/{id}` | Rider | Eliminar contacto | Funcional |
| POST | `/api/v1/emergency-contacts/{id}/invite` | Rider | Generar invitacion de contacto | Funcional |
| GET | `/api/v1/emergency-contacts/invitations/{code}` | Public | Consultar invitacion por codigo | Funcional |
| GET | `/api/v1/devices` | Rider | Listar dispositivos | Funcional |
| POST | `/api/v1/devices/mobile/activation-code` | Rider | Crear codigo de activacion movil | Funcional |
| GET | `/api/v1/devices/activation-codes/current` | Rider | Obtener codigo actual | Funcional |
| POST | `/api/v1/devices/mobile/link` | Rider | Vincular dispositivo movil | Funcional |
| POST | `/api/v1/devices/smartwatch/link` | Rider | Vincular smartwatch en backend | Funcional backend; no es pairing Wear OS local |
| PATCH | `/api/v1/devices/{id}/heartbeat` | Rider | Actualizar heartbeat de dispositivo | Funcional |
| POST | `/api/v1/devices/{id}/revoke` | Rider | Revocar dispositivo | Funcional |
| GET | `/api/v1/plans` | Rider | Consultar catalogo de planes | Funcional |
| GET | `/api/v1/subscriptions/me` | Rider | Consultar suscripcion propia | Funcional |
| POST | `/api/v1/subscriptions/select-basic` | Rider | Seleccionar plan basico | Funcional |
| GET | `/api/v1/onboarding/status` | Rider | Estado de onboarding | Funcional |
| GET | `/api/v1/onboarding/summary` | Rider | Resumen de onboarding | Funcional |
| POST | `/api/v1/onboarding/confirm` | Rider | Confirmar onboarding | Funcional |
| GET | `/api/v1/trips/active` | Rider | Consultar viaje activo | Funcional |
| POST | `/api/v1/trips/start` | Rider | Iniciar viaje | Funcional |
| POST | `/api/v1/trips/{id}/finish` | Rider | Finalizar viaje | Funcional |
| GET | `/api/v1/trips/{id}` | Rider | Obtener viaje | Funcional |
| GET | `/api/v1/trips` | Rider | Listar viajes | Funcional |
| POST | `/api/v1/mobile/offline-ingestion/batch` | Rider | Ingerir lote offline movil | Funcional |
| POST | `/api/v1/offline-processing/run` | Rider | Procesar eventos offline pendientes | Funcional manual |
| GET | `/api/v1/offline-processing/status` | Rider | Estado de procesamiento offline | Funcional |
| POST | `/api/v1/incidents` | Rider | Crear incidente | Funcional |
| GET | `/api/v1/incidents` | Rider | Listar incidentes | Funcional |
| GET | `/api/v1/incidents/{id}` | Rider | Obtener incidente | Funcional |
| POST | `/api/v1/incidents/{id}/cancel-false-positive` | Rider | Cancelar falso positivo | Funcional |
| POST | `/api/v1/incidents/{id}/close` | Rider | Cerrar incidente | Funcional |
| POST | `/api/v1/alert-dispatches` | Rider | Crear alert dispatch | Funcional |
| GET | `/api/v1/alert-dispatches` | Rider | Listar alert dispatches | Funcional |
| GET | `/api/v1/alert-dispatches/{id}` | Rider | Obtener alert dispatch | Funcional |
| POST | `/api/v1/alert-dispatches/{id}/cancel` | Rider | Cancelar alert dispatch | Funcional |
| POST | `/api/v1/notifications/delivery-attempts/prepare` | Rider | Preparar attempts de notificacion | Funcional, no envia real |
| GET | `/api/v1/notifications/delivery-attempts` | Rider | Listar attempts | Funcional |
| GET | `/api/v1/notifications/delivery-attempts/{id}` | Rider | Obtener attempt | Funcional |
| POST | `/api/v1/notifications/delivery-attempts/{id}/mark-simulated-sent` | Rider | Marcar enviado simulado | Funcional manual |
| POST | `/api/v1/notifications/delivery-attempts/{id}/mark-failed` | Rider | Marcar fallido | Funcional manual |
| POST | `/api/v1/notifications/delivery-attempts/{id}/cancel` | Rider | Cancelar attempt | Funcional |
| GET | `/api/v1/monitor/alerts` | Monitor | Listar alertas asignadas | Funcional |
| GET | `/api/v1/monitor/alerts/{id}` | Monitor | Ver alerta asignada | Funcional |
| POST | `/api/v1/monitor/alerts/{id}/view` | Monitor | Marcar alerta vista | Funcional |
| POST | `/api/v1/monitor/alerts/{id}/acknowledge` | Monitor | Confirmar atencion | Funcional |
| POST | `/api/v1/monitor/alerts/{id}/decline` | Monitor | Rechazar atencion | Funcional |
| GET | `/api/v1/rider/alerts/acknowledgements` | Rider | Ver respuestas de monitores | Funcional |
| POST | `/api/v1/mobile/location-sharing/snapshot` | Rider | Compartir snapshot de ubicacion | Funcional |
| GET | `/api/v1/monitor/alerts/{notificationDeliveryAttemptId}/location` | Monitor | Ver ubicacion de alerta asignada | Funcional |
| GET | `/api/v1/rider/incidents/{incidentId}/location` | Rider | Ver ubicacion de incidente propio | Funcional |
| GET | `/api/v1/rider/emergencies/active` | Rider | Listar emergencias activas | Funcional |
| GET | `/api/v1/rider/emergencies/{incidentId}/status` | Rider | Estado agregado de emergencia | Funcional |
| GET | `/api/v1/monitor/alerts/{notificationDeliveryAttemptId}/status` | Monitor | Estado agregado para monitor | Funcional |
| POST | `/api/v1/rider/emergencies/{incidentId}/resolution-report` | Rider | Crear reporte de resolucion | Funcional si incidente esta cerrado/cancelado |
| GET | `/api/v1/rider/emergencies/{incidentId}/resolution-report` | Rider | Obtener reporte propio | Funcional |
| GET | `/api/v1/rider/emergencies/resolution-reports` | Rider | Listar reportes propios | Funcional |
| GET | `/api/v1/monitor/alerts/{notificationDeliveryAttemptId}/resolution-report` | Monitor | Ver reporte de alerta asignada | Funcional |
| POST | `/api/v1/admin/notifications/outbox/run` | Admin | Procesar outbox simulado | Funcional, no envia real |
| GET | `/api/v1/admin/notifications/outbox/status` | Admin | Consultar conteos de outbox | Funcional |
| POST | `/api/v1/admin/notifications/outbox/retry-failed` | Admin | Reintentar fallidos | Funcional |
| GET | `/api/v1/admin/dashboard/summary` | Admin | Resumen operacional | Funcional |
| GET | `/api/v1/admin/dashboard/incidents` | Admin | Incidentes para dashboard | Funcional |
| GET | `/api/v1/admin/dashboard/response-times` | Admin | Tiempos de respuesta | Funcional |
| GET | `/api/v1/admin/dashboard/resolution-outcomes` | Admin | Outcomes de resolucion | Funcional |
| GET | `/api/v1/admin/dashboard/offline-processing` | Admin | Resumen offline | Funcional |

## JSONs De Ejemplo

### Registro

```http
POST /api/v1/auth/register
```

```json
{
  "email": "rider@example.com",
  "password": "StrongPass1!",
  "confirmPassword": "StrongPass1!",
  "fullName": "Rider Example",
  "phoneNumber": "+525512345678",
  "accountType": "Rider",
  "acceptTerms": true
}
```

### Login

```http
POST /api/v1/auth/login
```

```json
{
  "email": "rider@example.com",
  "password": "StrongPass1!",
  "rememberMe": true
}
```

### Solicitar Access Code

```http
POST /api/v1/auth/request-access-code
```

```json
{
  "email": "rider@example.com"
}
```

### Login With Code

```http
POST /api/v1/auth/login-with-code
```

```json
{
  "email": "rider@example.com",
  "code": "123456"
}
```

Estado actual: responde `501 feature_not_implemented` porque el proveedor externo esta pendiente.

### Perfil

```http
PUT /api/v1/profiles/me
```

```json
{
  "fullName": "Rider Example",
  "phoneNumber": "+525512345678",
  "dateOfBirth": "1990-01-15",
  "curpOrIdentifier": "OPTIONAL-ID",
  "addressOrZone": "Centro",
  "primaryCity": "Ciudad de Mexico",
  "bloodType": "O+",
  "allergies": "None",
  "medicalConditions": "None",
  "provisionalEmergencyContactName": "Contact Example",
  "provisionalEmergencyContactPhone": "+525587654321",
  "saveMode": "Complete"
}
```

### Vehiculo

```http
POST /api/v1/vehicles
```

```json
{
  "vehicleType": "Motorcycle",
  "brand": "Yamaha",
  "model": "FZ",
  "year": 2023,
  "alias": "Daily bike",
  "primaryUse": "Commuting",
  "color": "Blue",
  "plateNumber": "ABC123",
  "vin": "VIN123456789",
  "usageFrequency": "Daily",
  "saveMode": "Complete"
}
```

### Contacto De Emergencia

```http
POST /api/v1/emergency-contacts
```

```json
{
  "fullName": "Emergency Contact",
  "relationship": "Brother",
  "phoneNumber": "+525587654321",
  "email": "contact@example.com",
  "priority": 1,
  "permissions": {
    "canReceiveSms": true,
    "canReceiveEmail": true,
    "canViewLocation": true
  },
  "saveMode": "Complete"
}
```

### Vincular Movil

```http
POST /api/v1/devices/mobile/link
```

```json
{
  "code": "ABCD-1234",
  "deviceName": "Pixel 8",
  "platform": "Android",
  "manufacturer": "Google",
  "model": "Pixel 8",
  "operatingSystemVersion": "15",
  "appVersion": "1.0.0",
  "deviceIdentifier": "mobile-device-identifier"
}
```

### Heartbeat De Dispositivo

```http
PATCH /api/v1/devices/{id}/heartbeat
```

```json
{
  "batteryLevel": 82,
  "connectionStatus": "Online",
  "appVersion": "1.0.0"
}
```

### Iniciar Viaje

```http
POST /api/v1/trips/start
```

```json
{
  "vehicleId": "vehicle-id",
  "mobileDeviceId": "mobile-device-id",
  "smartwatchDeviceId": "smartwatch-device-id",
  "clientStartedAtUtc": "2026-08-08T14:00:00Z",
  "startLocation": {
    "latitude": 19.432608,
    "longitude": -99.133209,
    "accuracyMeters": 15
  },
  "batteryLevel": 80,
  "appVersion": "1.0.0"
}
```

### Crear Incidente

```http
POST /api/v1/incidents
```

```json
{
  "tripId": "trip-id",
  "clientIncidentId": "client-incident-001",
  "source": "MobileDetection",
  "cause": "CountdownTimeout",
  "riskLevel": "High",
  "score": 95,
  "confidence": 0.94,
  "gpsQuality": "Good",
  "ruleSetVersion": "rules-1.0",
  "validationPolicyVersion": "policy-1.0",
  "occurredAtUtc": "2026-08-08T14:20:00Z",
  "location": {
    "latitude": 19.432608,
    "longitude": -99.133209,
    "accuracyMeters": 15
  },
  "evidenceSummary": {
    "accelerometerPeakG": 4.5,
    "gyroscopePeak": 2.1,
    "speedBeforeKmh": 42,
    "speedAfterKmh": 0
  }
}
```

### Crear Alert Dispatch

```http
POST /api/v1/alert-dispatches
```

```json
{
  "incidentId": "incident-id",
  "clientAlertRequestId": "client-alert-001",
  "priority": "High",
  "reason": "IncidentCreated",
  "requestedAtUtc": "2026-08-08T14:20:10Z",
  "notes": "Emergency detected by mobile app."
}
```

### Preparar Notification Attempts

```http
POST /api/v1/notifications/delivery-attempts/prepare
```

```json
{
  "alertDispatchId": "alert-dispatch-id",
  "notes": "Prepare emergency contact notifications."
}
```

Respuesta representativa:

```json
{
  "success": true,
  "data": {
    "attempts": [
      {
        "id": "attempt-id",
        "alertDispatchId": "alert-dispatch-id",
        "incidentId": "incident-id",
        "tripId": "trip-id",
        "emergencyContactId": "contact-id",
        "contactFullName": "Emergency Contact",
        "channel": "Sms",
        "status": "Prepared",
        "provider": "None",
        "attemptNumber": 1,
        "failureReason": null
      }
    ]
  },
  "error": null
}
```

### Notification Outbox Run

```http
POST /api/v1/admin/notifications/outbox/run
```

```json
{
  "maxItems": 20,
  "simulateFailures": false
}
```

Respuesta representativa:

```json
{
  "success": true,
  "data": {
    "processed": 2,
    "simulatedSent": 2,
    "failed": 0,
    "skipped": 0,
    "items": [
      {
        "notificationDeliveryAttemptId": "attempt-id",
        "status": "SimulatedSent",
        "channel": "Sms",
        "reason": null
      }
    ]
  },
  "error": null
}
```

### Notification Outbox Status

```http
GET /api/v1/admin/notifications/outbox/status
```

```json
{
  "success": true,
  "data": {
    "prepared": 5,
    "simulatedSent": 12,
    "failed": 1,
    "cancelled": 0
  },
  "error": null
}
```

### Retry Failed Outbox Items

```http
POST /api/v1/admin/notifications/outbox/retry-failed
```

```json
{
  "maxItems": 20
}
```

### Monitor Acknowledge

```http
POST /api/v1/monitor/alerts/{id}/acknowledge
```

```json
{
  "responseType": "CanAssist",
  "message": "I am calling the rider now."
}
```

### Monitor Decline

```http
POST /api/v1/monitor/alerts/{id}/decline
```

```json
{
  "responseType": "CannotAssist",
  "message": "I am unavailable."
}
```

### Compartir Ubicacion

```http
POST /api/v1/mobile/location-sharing/snapshot
```

```json
{
  "incidentId": "incident-id",
  "clientLocationUpdateId": "location-update-001",
  "latitude": 19.432608,
  "longitude": -99.133209,
  "accuracyMeters": 15,
  "altitudeMeters": 2240,
  "speedMetersPerSecond": 3.2,
  "headingDegrees": 180,
  "batteryPercentage": 80,
  "source": "MobileApp",
  "recordedAtUtc": "2026-08-08T14:20:00Z"
}
```

### Emergency Status

```http
GET /api/v1/rider/emergencies/{incidentId}/status
```

Respuesta representativa:

```json
{
  "success": true,
  "data": {
    "incident": {
      "id": "incident-id",
      "status": "Open",
      "source": "MobileDetection",
      "cause": "CountdownTimeout",
      "riskLevel": "High"
    },
    "trip": {
      "id": "trip-id",
      "status": "Active"
    },
    "alertDispatch": {
      "id": "alert-dispatch-id",
      "status": "PendingDispatch",
      "priority": "High"
    },
    "notifications": {
      "total": 3,
      "prepared": 1,
      "simulatedSent": 2,
      "failed": 0,
      "cancelled": 0
    },
    "acknowledgements": {
      "total": 1,
      "pending": 0,
      "viewed": 0,
      "acknowledged": 1,
      "declined": 0
    },
    "location": {
      "available": true,
      "incidentId": "incident-id",
      "latitude": 19.432608,
      "longitude": -99.133209,
      "accuracyMeters": 15,
      "source": "MobileApp",
      "isActive": true,
      "isStale": false
    },
    "overallStatus": "Acknowledged",
    "requiresAttention": false
  },
  "error": null
}
```

### Crear Reporte De Resolucion

```http
POST /api/v1/rider/emergencies/{incidentId}/resolution-report
```

```json
{
  "outcome": "RealEmergency",
  "summary": "The rider received assistance and is safe.",
  "notes": "Resolved by emergency contact."
}
```

### Offline Ingestion Batch

```http
POST /api/v1/mobile/offline-ingestion/batch
```

```json
{
  "batchId": "offline-batch-001",
  "mobileDeviceId": "mobile-device-id",
  "tripId": "trip-id",
  "schemaVersion": 1,
  "sentAtUtc": "2026-08-08T14:30:00Z",
  "appVersion": "1.0.0",
  "items": [
    {
      "clientEventId": "event-001",
      "type": "LocationSnapshot",
      "occurredAtUtc": "2026-08-08T14:25:00Z",
      "payloadVersion": 1,
      "payload": {
        "latitude": 19.432608,
        "longitude": -99.133209
      }
    }
  ]
}
```

## Notificaciones Simuladas

El backend actual no envia mensajes reales. El flujo disponible es operacional y auditable, pero simulado.

`POST /api/v1/notifications/delivery-attempts/prepare` crea registros `NotificationDeliveryAttempts` en estado `Prepared` para los contactos aplicables.

`POST /api/v1/admin/notifications/outbox/run` procesa attempts `Prepared` y los marca como `SimulatedSent` cuando `simulateFailures` es `false`.

Cuando `simulateFailures` es `true`, los attempts seleccionados pasan a `Failed` con razon `simulated_failure_requested`.

`POST /api/v1/admin/notifications/outbox/retry-failed` mueve attempts `Failed` de vuelta a `Prepared` para poder procesarlos otra vez.

Estados relevantes:

```text
Prepared
SimulatedSent
Failed
Cancelled
```

El outbox no crea acknowledgements, no crea resolution reports, no cierra incidentes, no completa alert dispatches y no actualiza ubicaciones.

## Que Funciona Hoy

- Registro y login con email/password.
- Refresh token y logout.
- Solicitud de access code.
- Onboarding Rider completo con perfil, vehiculo, contacto, dispositivo, plan y confirmacion.
- Device activation code, current activation code, mobile link, smartwatch link backend, heartbeat y revoke.
- Trips start, active, finish, get y list.
- Offline ingestion batch movil.
- Offline processing manual.
- Incidents create, list, get, cancel false positive y close.
- Alert dispatch create, list, get y cancel.
- Notification attempts prepare, list, get, mark simulated sent, mark failed y cancel.
- Notification outbox admin run, status y retry failed.
- Monitor alerts list, get, view, acknowledge y decline.
- Rider acknowledgements list.
- Emergency location sharing snapshot y consultas Rider/Monitor.
- Emergency status Rider/Monitor.
- Emergency resolution report create, get y list.
- Operational dashboard admin.

## Que No Esta Implementado Todavia

- Login real con codigo externo: `POST /api/v1/auth/login-with-code` esta preparado pero responde `501 feature_not_implemented`.
- Envio real de SMS.
- Envio real de email.
- Envio real de push notifications.
- Envio real de WhatsApp.
- Worker/background processor real para outbox.
- Completar automaticamente alert dispatch al terminar todos los attempts.
- Tracking en vivo por streaming.
- Dashboard frontend.
- Pagos reales.
- Pairing API Wear OS local.

## Fuera De Alcance Explicito

Los siguientes puntos estan fuera de alcance del backend actual y no deben asumirse como disponibles:

- SMS real.
- Email real.
- Push real.
- WhatsApp real.
- Twilio.
- SendGrid.
- FCM.
- Worker real.
- WebSockets.
- SignalR.
- Tracking en vivo.
- Pagos.
- Pairing API smartwatch.

## QA Flow Recomendado

1. Registrar Rider con `POST /api/v1/auth/register`.
2. Iniciar sesion Rider con `POST /api/v1/auth/login`.
3. Completar perfil con `PUT /api/v1/profiles/me`.
4. Crear vehiculo con `POST /api/v1/vehicles`.
5. Crear contacto con `POST /api/v1/emergency-contacts`.
6. Crear activation code con `POST /api/v1/devices/mobile/activation-code`.
7. Vincular movil con `POST /api/v1/devices/mobile/link`.
8. Seleccionar plan basico con `POST /api/v1/subscriptions/select-basic`.
9. Confirmar onboarding con `POST /api/v1/onboarding/confirm`.
10. Iniciar viaje con `POST /api/v1/trips/start`.
11. Crear incidente con `POST /api/v1/incidents`.
12. Crear dispatch con `POST /api/v1/alert-dispatches`.
13. Preparar attempts con `POST /api/v1/notifications/delivery-attempts/prepare`.
14. Ejecutar outbox admin con `POST /api/v1/admin/notifications/outbox/run` usando `simulateFailures: false`.
15. Consultar status admin con `GET /api/v1/admin/notifications/outbox/status`.
16. Consultar emergency status Rider con `GET /api/v1/rider/emergencies/{incidentId}/status`.
17. Iniciar sesion Monitor asignado.
18. Consultar alertas Monitor con `GET /api/v1/monitor/alerts`.
19. Marcar vista con `POST /api/v1/monitor/alerts/{id}/view`.
20. Confirmar o rechazar con `POST /api/v1/monitor/alerts/{id}/acknowledge` o `POST /api/v1/monitor/alerts/{id}/decline`.
21. Enviar snapshot con `POST /api/v1/mobile/location-sharing/snapshot`.
22. Consultar ubicacion Monitor con `GET /api/v1/monitor/alerts/{notificationDeliveryAttemptId}/location`.
23. Cerrar incidente con `POST /api/v1/incidents/{id}/close` o cancelar falso positivo con `POST /api/v1/incidents/{id}/cancel-false-positive`.
24. Crear resolution report con `POST /api/v1/rider/emergencies/{incidentId}/resolution-report`.
25. Revisar dashboard admin con `GET /api/v1/admin/dashboard/summary`.

Para validar fallas simuladas, repetir el paso de outbox con `simulateFailures: true`, confirmar attempts en `Failed`, ejecutar `POST /api/v1/admin/notifications/outbox/retry-failed` y correr outbox nuevamente con `simulateFailures: false`.
