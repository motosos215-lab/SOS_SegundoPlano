# Checklist de seguridad MotoSOS

Marcar únicamente después de evidencia verificable en la rama o el PR.

## Antes de integrar

- [ ] No secrets committed
- [ ] Gitleaks passing
- [ ] SAST passing
- [ ] Dependency scan passing
- [ ] Tests passing
- [ ] ML tests passing
- [ ] Android lint passing
- [ ] Debug build passing
- [ ] `google-services.json` not tracked
- [ ] No sensitive production logs
- [ ] HTTPS enforced
- [ ] Tokens protected by Android Keystore/AES-GCM
- [ ] SOS manual independent from ML
- [ ] Hard Rules independent from ML
- [ ] FalsePositiveValidation and the 20-second countdown remain in the automatic SOS path

## Revisión de cambios

- [ ] El PR no contiene `.env`, `local.properties`, certificados, llaves, APK/AAB o directorios de build.
- [ ] Las Actions usan permisos mínimos y referencias fijadas a SHA verificable.
- [ ] Los cambios de dependencia recibieron revisión.
- [ ] Se evaluaron logs, ubicación y datos persistidos para evitar exposición innecesaria.
- [ ] Cualquier cambio release/R8 incluye validación de compatibilidad.
