# Checklist de seguridad

## Android y almacenamiento

- [ ] Backups/data extraction restringidos según política del proyecto.
- [ ] Permisos solicitados sólo cuando el flujo los necesita.
- [ ] No se añade `CAMERA` únicamente para Google Code Scanner si el flujo delegado no la requiere.
- [ ] Payloads SOS offline sensibles permanecen en almacenamiento durable protegido por el diseño existente.
- [ ] El cierre offline no duplica coordenadas en su store de reintento; la ruta GPS tiene persistencia separada.
- [ ] Un retry tardío no limpia estado perteneciente a otro viaje.

## SOS

- [ ] SOS manual no depende del ML.
- [ ] SOS automático pasa por confirmación/countdown.
- [ ] `clientIncidentId` / `clientAlertRequestId` permanecen estables en reintentos.
- [ ] El worker nunca crea una emergencia nueva si no existe un pendiente durable.
- [ ] Wi‑Fi y datos móviles pueden recuperar un SOS.

## Red/API

- [ ] Bearer token sólo en header Authorization.
- [ ] No enviar `userId` cuando backend lo deriva del JWT.
- [ ] No inventar `incidentType` ni endpoints.
- [ ] No mapear `MinorEvent` al batch offline hasta conocer `type/payload` oficial.
- [ ] Cierre de viaje usa el mismo `remoteTripId` y timestamp durable en retries.

## Logs y secretos

- [ ] No loggear JWT/access token/refresh token/FCM token/contraseña.
- [ ] No loggear payload personal completo ni coordenadas innecesarias.
- [ ] `google-services.json`, `local.properties`, keystores y artifacts no están staged.
- [ ] Gitleaks PASS.

## Android IPC / PendingIntent

- [ ] Intents sensibles explícitos.
- [ ] `PendingIntent` immutable cuando no necesita mutabilidad.
- [ ] Cualquier alerta CodeQL se revisa por traza actual, sin suprimirla por antecedente.

## CI/CD

- [ ] compile Kotlin PASS.
- [ ] unit tests PASS.
- [ ] Android Lint PASS.
- [ ] assembleDebug PASS.
- [ ] Dependency Review sin vulnerabilidad nueva High/Critical no aceptada.
- [ ] CodeQL revisado.
- [ ] no merge automático sin autorización humana.
