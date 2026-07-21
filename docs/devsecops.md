# DevSecOps

## Objetivo

Mantener una base automatizada para construir, probar y revisar seguridad antes de integrar cambios.

## Pipeline inicial

El workflow `.github/workflows/android-ci.yml` ejecuta:

- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- publicacion del reporte de Android Lint como artifact

El workflow `.github/workflows/security-scan.yml` ejecuta:

- escaneo de secretos con Gitleaks

## Testing estatico

Controles iniciales:

- Android Lint para errores Android, permisos, manifest y recursos
- revision de secretos en CI
- validacion del Gradle Wrapper

Controles recomendados para siguientes iteraciones:

- Detekt para analisis estatico Kotlin
- Ktlint o Spotless para formato
- Dependency Check, Snyk o Dependabot para dependencias
- Semgrep o CodeQL para reglas de seguridad adicionales

## Testing dinamico

Controles iniciales:

- pruebas unitarias JVM
- pruebas instrumentadas Android existentes como base

Controles recomendados para siguientes iteraciones:

- pruebas de permisos moviles
- pruebas de servicio en segundo plano
- pruebas de cola offline
- pruebas de sensores y simulacion de incidentes
- pruebas de vinculacion con smartwatch

## Criterio minimo para merge

Todo cambio debe compilar y pasar:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```
