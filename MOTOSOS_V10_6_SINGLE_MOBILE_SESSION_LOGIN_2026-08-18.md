# MotoSOS v10.6 — Login móvil con sesión única

Fecha: 2026-08-18
Base: v10.5 SOS Automático Evidencia Corregida

## Alcance

Se modificó únicamente autenticación/sesiones y el mínimo soporte necesario para recordar el `mobileDeviceId` backend usado por esta instalación. No se cambió la lógica de SOS manual, SOS automático, RuleEngine, falso positivo, mapas, rutas, contactos ni UI principal Rider/Monitor.

## ClientDevice Android

`POST /api/v1/auth/login` ahora envía `clientDevice` con:

- `clientDeviceId`: UUID generado una sola vez por instalación y persistido en SharedPreferences.
- `deviceName`: fabricante + modelo Android.
- `platform`: `Android`.
- `osVersion`: versión de Android.
- `appVersion`: `BuildConfig.VERSION_NAME`.

El mismo modelo quedó definido para `login-with-code` y se reutiliza sin regenerar UUID.

`clientDeviceId` nunca se usa como `mobileDeviceId`, FCM token, userId ni token de autenticación.

## Respuesta de login

Se admite tanto el nombre anterior `accessTokenExpiresAtUtc` como el nuevo `expiresAtUtc` para evitar una regresión de compatibilidad.

Si la respuesta no incluye `user`, la app intenta recuperar al usuario autenticado mediante `GET /api/v1/users/me` usando el access token recién emitido.

La información `session` se acepta en el DTO. No es necesario persistir manualmente `sid`: la claim ya viaja dentro del JWT y el backend es la autoridad de revocación.

## active_session_exists

HTTP 409 con `active_session_exists` se convierte en un `SessionTakeoverChallenge` y muestra un modal.

Sin viaje activo:

- Cancelar: no se llama takeover y la sesión anterior permanece intacta.
- Confirmar: `POST /api/v1/auth/sessions/takeover` con el mismo `clientDevice`, `transferActiveTrip=false`, `mobileDeviceId=null`.

Con viaje activo:

- El modal informa que el mismo viaje será transferido.
- `transferActiveTrip=true`.
- La app sólo envía un `mobileDeviceId` backend que esta instalación haya resuelto previamente desde `/api/v1/devices`.
- Nunca sustituye `mobileDeviceId` por `clientDeviceId`.

## Conservación de mobileDeviceId

Cuando el flujo existente de inicio de viaje selecciona el dispositivo MobileApp vinculado desde `/api/v1/devices`, su id backend se guarda localmente asociado al correo de la cuenta. Esto permite reutilizar el id correcto si esa instalación necesita transferir posteriormente un viaje activo.

Si una instalación totalmente nueva no conoce todavía un `mobileDeviceId` backend vinculado, la app bloquea la transferencia activa y lo informa en lugar de enviar un id inventado.

## Monitor y FCM

Después de un takeover exitoso de Monitor, `PushAwareAuthRepository` vuelve a programar el registro FCM existente. Android no envía `sessionId`; el backend toma `sid` del JWT.

No se añadió revocación manual del FCM de la sesión anterior durante takeover: según el contrato nuevo, el backend la desactiva junto con la UserSession anterior.

## Logout y refresh

Logout envía el Bearer de la sesión actual además del refresh token que ya utilizaba la app. Así backend puede resolver el `sid` de la UserSession actual sin afectar sesiones WebApp/AdminWeb.

Refresh conserva el flujo existente. `session_revoked` se trata como expiración/revocación y limpia la sesión local para impedir revivir la sesión mediante refresh.

## Códigos de takeover contemplados

- `active_session_exists`
- `active_trip_transfer_required`
- `takeover_token_invalid`
- `takeover_token_expired`
- `takeover_token_already_used`
- `session_revoked`
- `device_not_available`
- `active_trip_not_available`

## Compatibilidad

- Rider y Monitor continúan usando la misma pantalla de login.
- Sesión web no se toca desde Android.
- No se persisten takeover tokens en disco.
- Los `toString()` sensibles ocultan contraseña, access token, refresh token y takeover token.
- La sesión cifrada existente sigue usando el mismo formato porque `sid` está contenido en el access token.

## Validación realizada en este entorno

- DTO/modelos de autenticación compilados con `kotlinc` usando un stub mínimo de `JsonClass`.
- 25 XML parseados: 0 errores.
- Los archivos de SOS automático/manual, FalsePositiveValidationCoordinator y RuleEngine son idénticos byte a byte a v10.5.
- Los PNG protegidos presentes en v10.5 permanecen idénticos.
- No se pudo ejecutar Gradle completo porque el wrapper intenta descargar Gradle 9.4.1 y el entorno no resuelve `services.gradle.org`.

## Nota de integración para viaje activo

El contrato requiere el `mobileDeviceId` del NUEVO dispositivo vinculado para transferir un viaje. En una instalación completamente nueva que nunca haya resuelto/vinculado un MobileDevice backend no existe una forma segura, con los endpoints descritos, de obtener ese id antes del takeover porque `/devices` requiere autenticación. MotoSOS por tanto no inventa el id. Si el equipo backend necesita soportar transferencia de viaje hacia un teléfono recién instalado, conviene que el conflicto de login entregue un identificador elegible del nuevo dispositivo ya vinculado o habilite un paso seguro de vinculación previo al takeover.
