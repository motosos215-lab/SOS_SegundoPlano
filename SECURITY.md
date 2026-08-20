# Security Policy

## Reporte de vulnerabilidades

No publiques credenciales, tokens, llaves, coordenadas privadas ni evidencia sensible en issues públicos.
Cuando el repositorio tenga habilitados **GitHub Private Vulnerability Reporting / Security Advisories**, usa ese canal para reportes de seguridad.

## Secretos y configuración local

Nunca deben entrar al repositorio:

- `app/google-services.json`
- `local.properties`
- `secrets.properties`
- `*.jks` / `*.keystore`
- access tokens, refresh tokens, FCM tokens, contraseñas o certificados privados

CI reconstruye `app/google-services.json` desde el secret `GOOGLE_SERVICES_JSON_B64`.

## Datos sensibles

Los logs no deben contener JWT, secretos, payloads personales completos ni ubicación precisa salvo evidencia de depuración explícitamente controlada y no versionada.
