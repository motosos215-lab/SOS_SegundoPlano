# DevSecOps — MotoSOS Android Segundo Plano

## Objetivo

Evitar que un cambio de aplicación, configuración o dependencia llegue a `main` sin evidencia de compilación, pruebas y controles de seguridad.

## Workflows

### Android CI — `.github/workflows/android-ci.yml`

En push a ramas protegidas y Pull Requests:

1. checkout
2. restaura `app/google-services.json` desde `GOOGLE_SERVICES_JSON_B64`
3. JDK 17
4. Gradle setup + wrapper validation
5. `:app:compileDebugKotlin`
6. `:app:testDebugUnitTest`
7. `:app:lintDebug`
8. `:app:assembleDebug`
9. conserva reportes como artifacts por 14 días

### Gitleaks — `.github/workflows/security-scan.yml`

Escanea el historial disponible del repositorio para detectar secretos versionados.

### CodeQL — `.github/workflows/codeql.yml`

Analiza Java/Kotlin en PR, push y semanalmente. Usa build manual de `:app:compileDebugKotlin` para que el análisis observe Kotlin/K2 compilado.

### Dependency Review — `.github/workflows/dependency-review.yml`

En Pull Requests rechaza cambios de dependencias con vulnerabilidades de severidad `high` o superior según la información disponible en GitHub Dependency Graph.

### Dependabot — `.github/dependabot.yml`

Revisión semanal de:

- Gradle
- GitHub Actions

## Secretos

No versionar:

- `app/google-services.json`
- `local.properties`
- `secrets.properties`
- `*.jks` / `*.keystore`
- access/refresh tokens
- FCM tokens
- contraseñas

Secret requerido por CI:

`GOOGLE_SERVICES_JSON_B64`

Debe contener el `google-services.json` del proyecto codificado en Base64. Los workflows nunca imprimen su contenido.

## Seguridad de flujos críticos

Antes de merge se debe comprobar:

- SOS manual independiente de ML
- SOS automático conserva countdown/confirmación
- IDs idempotentes se reutilizan en retry
- cierre de viaje offline queda durable antes de cerrar localmente
- WorkManager acepta Wi‑Fi o cellular mediante `NetworkType.CONNECTED`
- no existe un mapeo remoto no certificado de `MinorEvent` hacia el batch offline
- JWT/tokens/coordenadas sensibles no aparecen en logs nuevos
- `PendingIntent` sensible permanece explícito e immutable

## CodeQL + Kotlin/K2

En una revisión anterior, CodeQL reportó `java/android/implicit-pendingintents` sobre un `PendingIntent` que el código construye con `ComponentName(appContext, MainActivity::class.java)` y `FLAG_IMMUTABLE`. La revisión manual lo clasificó como probable falso positivo asociado con el análisis Kotlin 2/K2.

Si vuelve a aparecer:

1. no debilitar el código para “hacer pasar” el scanner;
2. verificar en el código actual que el Intent sea explícito y el PendingIntent immutable;
3. revisar la traza exacta del alert;
4. documentar la evidencia en el PR;
5. no suprimir/dismiss sin evidencia reproducible.

Referencias históricas de seguimiento: `github/codeql#20153` y `github/codeql#21915`.

## Criterio mínimo de PR

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
.\gradlew.bat :app:lintDebug --console=plain --no-daemon --no-configuration-cache --max-workers=2
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon
```

Además:

- Gitleaks PASS
- Dependency Review PASS o hallazgo justificado/corregido
- CodeQL revisado
- diff sin secretos/artefactos generados
- evidencia manual para cambios en SOS, red, permisos o background processing

## Política de merge

Los workflows preparan evidencia; no autorizan por sí solos el merge. El merge se realiza sólo después de revisión humana y autorización del responsable del proyecto.
