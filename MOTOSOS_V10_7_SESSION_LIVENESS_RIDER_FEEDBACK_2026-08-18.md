# MotoSOS v10.7 — sesión móvil viva y retroalimentación Monitor → Rider

Fecha: 2026-08-18
Base: MotoSOS v10.6 (login con sesión única)

## Alcance

Esta revisión agrega únicamente:

1. Detección en el teléfono anterior cuando el backend revoca su `sid` por takeover/logout.
2. Recepción y persistencia local de los eventos push del Monitor hacia el Rider:
   - `monitor_alert_viewed`
   - `monitor_alert_acknowledged`
   - `monitor_alert_declined`
3. Bandeja de "Mensajes de emergencia" para Rider, acceso superior con badge y tarjeta en Inicio.
4. Registro FCM también para Rider y re-vinculación del token después de login/restore/takeover.

El núcleo de SOS manual/automático, RuleEngine y FalsePositiveValidationCoordinator no fue modificado respecto a v10.6.

## 1. Cierre de la sesión anterior

El backend continúa siendo la autoridad: al confirmar takeover revoca inmediatamente la UserSession anterior mediante `sid`.

Android ahora valida esa sesión usando `GET /api/v1/users/me`:

- Mientras `MainActivity` está STARTED: validación inmediata al entrar y luego cada 10 segundos.
- Mientras existe `MonitoringForegroundService`: validación cada 10 segundos aunque la UI no esté visible.

Si backend responde `session_revoked` o una autenticación inválida asociada a esa sesión:

- `DefaultAuthRepository` elimina access/refresh locales.
- `SessionState` pasa a Expired.
- La UI vuelve al login.
- Si el teléfono anterior tenía viaje activo, `MonitoringForegroundService` se detiene para evitar que dos teléfonos sigan capturando el mismo viaje transferido.
- Se muestra el aviso: "Tu sesión se cerró porque esta cuenta se inició o cerró en otro dispositivo."

La invalidación del backend es inmediata. El cambio visible en un teléfono anterior que ya está abierto es near-real-time, con una ventana máxima aproximada de 10 s; al volver a primer plano la comprobación se realiza de inmediato. Sin un evento push específico `session_revoked` desde backend no es posible prometer una transición visual instantánea cuando la app antigua está suspendida o terminada, aunque sus tokens ya sean inválidos.

Los fallos transitorios de red no eliminan la sesión.

## 2. FCM para Rider

La nueva funcionalidad backend omite la retroalimentación si Rider no tiene token FCM activo. Por ello se amplió el flujo existente de `PushTokenRepository`:

- Rider y Monitor sincronizan su token FCM después de login.
- Rider y Monitor sincronizan después de restaurar sesión.
- Rider y Monitor vuelven a registrar después de takeover.
- El token se vuelve a registrar cuando cambia la identidad lógica de sesión Android (`generation`), incluso si el token FCM físico no cambió, para que backend lo asocie al nuevo `sid`.
- Android no envía `sessionId`; backend sigue obteniendo `sid` del JWT.
- Logout intenta revocar el registro de push de la sesión actual antes de cerrar autenticación.

Nota de contrato: el backend debe permitir a Rider registrar el token mediante el endpoint de push existente, tal como implica la nueva funcionalidad Monitor → Rider. Si ese endpoint continuara limitado sólo a Monitor, el backend tendría que habilitar Rider para que las notificaciones de retorno puedan entregarse.

## 3. Eventos Monitor → Rider

Se implementó parser estricto para los tres `eventType` documentados:

- `monitor_alert_viewed`
- `monitor_alert_acknowledged`
- `monitor_alert_declined`

Se conservan localmente para la cuenta Rider:

- `notificationDeliveryAttemptId` (nuevo intento dirigido al Rider)
- `incidentId`
- `alertDispatchId`
- `monitorAlertAttemptId`
- `occurredAtUtc`
- tipo de evento
- body del push si backend/FCM lo proporciona
- estado leído/no leído

No se muestran IDs técnicos en la interfaz.

La deduplicación se realiza por `notificationDeliveryAttemptId`; por ello un reenvío idempotente no crea una segunda tarjeta.

Máximo local: 50 actualizaciones.

## 4. Mensajes visibles para Rider

Se agregó una nueva pantalla:

`Mensajes de emergencia`

Muestra cronológicamente:

- Alerta vista
- Alerta confirmada
- Monitor no disponible
- hora del evento
- indicador NUEVO mientras corresponda

Mensajes fallback conforme al contrato backend:

- "Tu contacto de emergencia vio tu alerta SOS."
- "Tu contacto de emergencia confirmó que recibió tu alerta SOS."
- "Tu contacto de emergencia indicó que no puede atender la alerta en este momento."

Si el push contiene un body no vacío, se muestra ese body.

IMPORTANTE: el payload suministrado por backend no contiene el texto personalizado escrito por el Monitor en `acknowledge.message`. Por lo tanto esta versión NO inventa un chat ni afirma mostrar ese texto. Sólo muestra la retroalimentación documentada por backend.

## 5. Accesos a Mensajes

Rider tiene tres entradas coherentes sin agregar otra pestaña al bottom bar:

1. Icono de mensaje en la esquina superior derecha de Inicio, con badge de no leídos.
2. Tarjeta "Mensajes de emergencia" dentro de Inicio, mostrando cantidad no leída o la última actualización.
3. Durante un viaje activo, icono de mensaje en la barra superior de la pantalla de monitoreo, también con badge.

La notificación Android de retroalimentación abre directamente `Mensajes de emergencia`.

Al abrir la pantalla, los mensajes de esa cuenta se marcan como leídos.

## 6. Notificación Android

Nuevo canal de importancia alta:

`motosos_rider_monitor_feedback`

Título:

`Actualización de tu alerta SOS`

La notificación es privada en lock screen (`VISIBILITY_PRIVATE`) y abre MainActivity en la sección de mensajes.

## 7. Separación entre usuarios

Cada mensaje almacenado tiene `ownerUserId` y la UI filtra por el Rider autenticado. Si otra cuenta inicia sesión en el mismo teléfono, no ve las confirmaciones del Rider anterior.

El `monitorUserId` recibido en el payload no se muestra ni se persiste en la bandeja.

## 8. Archivos relevantes agregados

- `domain/push/RiderMonitorFeedback.kt`
- `data/local/push/SharedPreferencesRiderMonitorFeedbackStore.kt`
- `core/push/RiderMonitorFeedbackProvider.kt`
- `push/RiderMonitorFeedbackNotificationFactory.kt`
- `features/trip/RiderMonitorMessagesScreen.kt`
- `res/drawable/ic_message_bubble.xml`
- `RiderMonitorFeedbackTest.kt`

## 9. Archivos relevantes modificados

- `MainActivity.kt`
- `MotoSosApplication.kt`
- `AuthRepository.kt`
- `DefaultAuthRepository.kt`
- `PushAwareAuthRepository.kt`
- `DefaultPushTokenRepository.kt`
- `MotoSosFirebaseMessagingService.kt`
- `MonitoringForegroundService.kt`
- `MonitoringScreen.kt`
- `HomeScreen.kt`
- `MotoTopBar.kt`
- `strings.xml`

## 10. Validaciones realizadas en este entorno

- Parser XML app + wear: 0 errores.
- Prueba Kotlin pura del parser/coordinador de retroalimentación: `RIDER_MONITOR_FEEDBACK_OK`.
- Se verificó que no reapareció el import problemático `androidx.compose.foundation.layout.weight`.
- Los 6 recursos PNG protegidos que existen en la base v10.6 permanecen byte-for-byte iguales. `ic_sos_badge.png` no existe en la base v10.6 ni en este árbol, por lo que no fue creado/modificado.
- Archivos núcleo SOS comparados contra v10.6 y byte-for-byte iguales:
  - `AutomaticSosAlertCreator.kt`
  - `ManualSosAlertCreator.kt`
  - `FalsePositiveValidationCoordinator.kt`
  - `RuleEngine.kt`

No se pudo ejecutar Gradle completo en este contenedor porque el wrapper intenta obtener Gradle 9.4.1 desde `services.gradle.org` y el entorno no tiene resolución de red. La validación final de Android debe hacerse en el PowerShell normal del proyecto.

## 11. Prueba física recomendada

1. Compilar tests/APK.
2. Iniciar la misma cuenta en teléfono A.
3. Intentar login en teléfono B.
4. Confirmar takeover.
5. Verificar que A vuelve al login en <= aproximadamente 10 s si ya estaba abierto.
6. Si A tenía el viaje que se transfirió, verificar que su servicio de monitoreo se detiene.
7. Crear SOS con Rider.
8. Desde Monitor ejecutar view, acknowledge y/o decline.
9. Verificar que Rider recibe push.
10. Verificar badge en icono superior.
11. Verificar tarjeta de mensajes en Inicio.
12. Abrir `Mensajes de emergencia` y confirmar que la actualización queda almacenada una sola vez.
