# Control de configuración

## Versionado

Se versionan únicamente archivos reproducibles y necesarios para desarrollar/revisar MotoSOS:

- Gradle Wrapper y scripts Gradle
- catálogo de versiones
- código fuente y recursos Android
- tests
- documentación
- workflows/Dependabot

## No versionado

- `local.properties`
- `app/google-services.json`
- `.gradle/`
- `.idea/`
- `**/build/`
- APK/AAB
- `*.jks` / `*.keystore`
- `secrets.properties`
- logs locales

`.gitignore` debe cubrir estas rutas antes de cualquier commit.

## Firebase en CI

GitHub Actions reconstruye temporalmente `app/google-services.json` desde:

`GOOGLE_SERVICES_JSON_B64`

El valor debe cargarse como GitHub Actions Secret y no escribirse en logs, PRs, issues ni artifacts.

## Endpoints

Los endpoints/base URLs configurables se resuelven mediante Gradle/BuildConfig cuando corresponde. No añadir tokens o credenciales a `BuildConfig` versionado.

Contratos remotos críticos:

- SOS: `/api/v1/mobile/sos-alerts`
- inicio/fin de viaje: `/api/v1/trips/start`, `/api/v1/trips/{id}/finish`
- ruta: `/api/v1/trips/{id}/route-points/batch`

`/api/v1/mobile/offline-ingestion/batch` aparece en la documentación backend con ejemplo `LocationSnapshot`; no se debe asumir un payload para `MinorEvent` sin contrato explícito.

## Ambientes

Cuando existan credenciales diferentes por ambiente, separar `debug`, `staging` y `release` con configuración externa y principio de mínimo privilegio.
