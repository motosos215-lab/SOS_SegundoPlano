# MotoSOS — SOS Segundo Plano

Aplicación Android Kotlin para el Rider y el Monitor de MotoSOS. El proyecto integra monitoreo de viaje en segundo plano, Wear OS, reglas explicables + modelo ML local de apoyo, SOS manual/automático, recuperación offline, recorrido GPS real, mapas MapLibre/OpenFreeMap y vinculación Monitor ↔ Rider mediante código/QR.

## Estado de esta versión

Versión de integración preparada para validación y Pull Request. Los flujos críticos de emergencia conservan identidad/idempotencia durable y se separan de la telemetría secundaria.

### Garantías offline con destino remoto conocido

- **SOS manual:** se persiste antes del envío y se reintenta al recuperar Wi‑Fi o datos móviles.
- **SOS automático:** `LocalIncident + AlertDispatchRequest` se conserva de forma durable y se recupera por su worker especializado contra `POST /api/v1/mobile/sos-alerts`.
- **Cierre de viaje:** el Rider puede terminar localmente el viaje aunque no haya Internet. El cierre queda persistido y `TripFinishRecoveryWorker` ejecuta después `POST /api/v1/trips/{id}/finish`.
- **Recorrido:** los puntos GPS reales pendientes continúan sincronizándose por lotes mediante `/api/v1/trips/{id}/route-points/batch`.
- **Inicio:** la tarjeta **Estado de sincronización** resume SOS, cierre de viaje y datos de ruta pendientes. Con cero pendientes muestra **Todo sincronizado / Sin eventos por sincronizar**.

Los `MinorEvent` (por ejemplo, `Bump`) continúan locales porque, aunque la documentación del backend enumera `/api/v1/mobile/offline-ingestion/batch`, sólo muestra un ejemplo `LocationSnapshot` y no certifica el `type/payload` de `MinorEvent`. No se inventa ese mapeo.

## SOS y modelo de accidente

El modelo local se encuentra en:

`app/src/main/assets/motosos_accident_model_v1_1_pilot_ready.json`

Contrato de integración:

- versión: `1.1.0-pilot-ready`
- algoritmo: regresión logística
- exactamente 8 features
- threshold leído desde el JSON (`0.71` en el artefacto actual)
- política: `possibleAccident = hardRuleDetected || probability >= threshold`
- el ML es señal de apoyo; **no envía un SOS directamente**
- el SOS automático pasa por `FalsePositiveValidationCoordinator`, confirmación **¿Estás bien?** y countdown de 20 s
- el SOS manual es independiente del ML

El modelo fue entrenado con ventanas sintéticas/public-style y requiere validación con telemetría real antes de considerarlo validado para producción real.

## Vinculación Monitor ↔ Rider

El Monitor inicia sesión con su propia cuenta y después puede abrir **Perfil → Vinculación con Rider** para:

1. escribir el `linkingCode`, o
2. escanear un QR compatible, por ejemplo `motosos://monitor-link?code=...`;
3. consultar la invitación;
4. aceptar la vinculación;
5. revisar disponibilidad de FCM.

El QR/código sirve para **vinculación**, no sustituye la autenticación.

## Mapas y recorrido

- MapLibre Native + OpenFreeMap para mapas internos.
- Ruta real basada en puntos GPS persistidos; no se sintetiza una línea inicio-fin.
- Routing del Monitor desacoplado mediante `MONITOR_ROUTING_BASE_URL`.

Ver `docs/maps-setup.md`.

## Requisitos

- JDK 17
- Android Studio compatible con AGP `9.2.1`
- Gradle Wrapper `9.4.1`
- Android SDK definido en `local.properties`
- `app/google-services.json` local para Firebase/FCM

## Configuración que NO se versiona

- `local.properties`
- `app/google-services.json`
- keystores/certificados
- APK/AAB
- caches `.gradle/`, `.idea/`, `build/`
- tokens y secretos

En GitHub Actions, `app/google-services.json` se restaura desde el secret `GOOGLE_SERVICES_JSON_B64`.

## Validación local

Windows PowerShell:

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
.\gradlew.bat :app:lintDebug --console=plain --no-daemon --no-configuration-cache --max-workers=2
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon
```

Prueba focal de cierre offline:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.example.sos_segundoplano.data.remote.trip.UserTripFinishCoordinatorTest" `
  --console=plain --no-daemon
```

## DevSecOps

El repositorio incluye:

- Android CI: compile + unit tests + lint + assemble
- Gradle Wrapper validation
- Gitleaks
- CodeQL Java/Kotlin
- Dependency Review en Pull Requests
- Dependabot para Gradle y GitHub Actions
- configuración mínima de permisos en workflows
- checklist de seguridad y PR template

Ver:

- `docs/devsecops.md`
- `docs/security-checklist.md`
- `docs/configuration-control.md`
- `docs/GIT_INTEGRATION_DEVSECOPS_2026-08-20.md`

## Documentación de recuperación offline

- `docs/SOS_OFFLINE_MANUAL_AUTOMATIC_RECOVERY_2026-08-20.md`
- `docs/OFFLINE_TRIP_FINISH_AND_HOME_SYNC_2026-08-20.md`

## Estructura principal

```text
app/src/main/java/com/example/sos_segundoplano/
  core/        autenticación, permisos, push y utilidades
  data/        persistencia, red, rutas, cola y recuperaciones
  domain/      modelos, contratos y casos de uso
  features/    Rider, Monitor, SOS, viajes, mapas y perfil
  ui/          componentes y tema Compose
wear/          aplicación Wear OS
wear-protocol/ contrato compartido móvil-reloj
```

## Créditos académicos

**Proyecto:** MotoSOS

**Estudiantes:**

- Jose Gamaliel Potenciano Mendez
- Cesar Augusto Cabrera Marcos
- Valeria Galindo Marin
- Ulises Gonzales Coronado
- Kevin Alexander Vega Zarza

**Universidad:** Universidad Tecnológica de Tula-Tepeji
**Programa educativo:** Ingeniería en Desarrollo y Gestión de Software
**Año:** 2026
