## Resumen

Describe el cambio y el flujo de MotoSOS afectado.

## Validación obligatoria

- [ ] `:app:compileDebugKotlin`
- [ ] `:app:testDebugUnitTest`
- [ ] `:app:lintDebug`
- [ ] `:app:assembleDebug`
- [ ] Gitleaks sin secretos nuevos
- [ ] CodeQL revisado
- [ ] No se versionaron `google-services.json`, `local.properties`, APK/AAB, keystores ni tokens

## Flujos de seguridad revisados

- [ ] SOS manual conserva independencia del ML
- [ ] SOS automático conserva confirmación/countdown e idempotencia
- [ ] No se inventaron endpoints del backend
- [ ] Pérdida/recuperación de Wi‑Fi o datos móviles fue considerada
- [ ] Los logs nuevos no contienen JWT, refresh tokens, FCM tokens, contraseñas ni coordenadas innecesarias

## Evidencia

Incluye comandos ejecutados, resultados y, cuando aplique, prueba en dispositivo/emulador.
