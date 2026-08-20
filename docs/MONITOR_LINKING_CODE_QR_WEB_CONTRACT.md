# MotoSOS — Vinculación Rider → Monitor por código y QR

## Alcance

Este flujo reutiliza el contrato existente de `EmergencyContact`. No introduce endpoints nuevos y no cambia la responsabilidad del Web: el Rider crea/configura su contacto desde MotoSOS Web; Android Monitor sólo consulta y acepta la invitación.

## Flujo Web

1. El Web, autenticado como Rider, crea o usa un `EmergencyContact` existente.
2. El Web solicita la invitación:

```http
POST /api/v1/emergency-contacts/{contactId}/invite
Authorization: Bearer <TOKEN_RIDER>
Content-Type: application/json

{}
```

3. El backend devuelve `data.contact.linkingCode` y `data.contact.linkingCodeExpiresAtUtc`.
4. El Web muestra ambas alternativas al Rider:
   - Código manual, por ejemplo `8X7Q-3M2K-9L6R`.
   - QR que transporte únicamente ese código.

## Payload QR recomendado

Formato preferido:

```text
motosos://monitor-link?code=8X7Q-3M2K-9L6R
```

Android también acepta un QR HTTPS que incluya el parámetro `code`, o el código puro como contenido del QR.

El QR **no debe** incluir `accessToken`, `refreshToken`, contraseña, `userId`, `tokenHash`, token FCM, coordenadas ni otra información sensible.

## Flujo Android Monitor

1. Monitor inicia sesión.
2. Abre `Perfil` → `Vincular con Rider`.
3. Escribe el código o escanea el QR.
4. Android consulta:

```http
GET /api/v1/emergency-contacts/invitations/{code}
Authorization: Bearer <TOKEN_MONITOR>
```

5. Android muestra `driverFullName`, `contactFullName`, permisos, estado y expiración.
6. El Monitor confirma conscientemente la vinculación.
7. Android ejecuta:

```http
POST /api/v1/emergency-contacts/invitations/{code}/accept
Authorization: Bearer <TOKEN_MONITOR>
Content-Type: application/json

{}
```

8. Después de `Linked`, Android verifica la recepción Push mediante:

```http
GET /api/v1/push-notification-tokens/status
Authorization: Bearer <TOKEN_MONITOR>
```

`hasActiveAndroidFcm=true` indica que el backend ve al menos un registro Android FCM activo para la cuenta Monitor.

## Errores que la UI móvil contempla

- `invitation_expired`: pedir un código nuevo.
- `invitation_link_not_allowed`: la invitación no corresponde a esa cuenta Monitor.
- `invitation_already_linked`: ya fue enlazada a otro Monitor.
- `404`: no existe una invitación activa con ese código.
- `401`: sesión expirada.
- `403`: rol no permitido.

## Seguridad

- El backend sigue siendo la fuente de verdad del código, su expiración y la identidad del Monitor.
- Android no genera códigos de invitación.
- El Web no genera códigos por su cuenta: representa el `linkingCode` recibido del backend.
- El QR es sólo una representación del código; no autentica por sí mismo al usuario.
- La aceptación requiere Bearer de una cuenta Monitor válida.
