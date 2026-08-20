# Integración Git + DevSecOps — MotoSOS SegundoPlano

Fecha: 2026-08-20

## Base funcional

Esta entrega deriva de `SegundoPlano_SOS_Offline_Manual_Automatico_COMPLETO_Windows` y agrega:

- cierre de viaje durable sin Internet;
- retry al recuperar Wi‑Fi/datos móviles;
- tarjeta de sincronización en Inicio;
- eliminación del indicador genérico de sincronización de la pantalla de monitoreo;
- conteo de SOS, cierre y puntos de ruta con destino remoto real;
- tests focalizados de cierre offline/estado Home;
- workflows DevSecOps y documentación actualizados.

## No mezclar con el PR histórico

El PR #38 fue cerrado intencionalmente sin merge. Esta entrega debe integrarse en **rama y PR nuevos**. No reabrir PR #38 ni reutilizar su rama como rama final.

## Overlay sobre repo Git existente

El ZIP `GitReady` no contiene `.git`, secretos ni artifacts. Debe copiarse **encima del working tree del repositorio existente**, conservando la carpeta `.git` del clone original.

No copiar/versionar:

- `local.properties`
- `app/google-services.json`
- `.gradle/`, `.idea/`, `**/build/`
- APK/AAB
- keystores

## Contratos que no deben alterarse

### SOS

`POST /api/v1/mobile/sos-alerts`

- client IDs UUID estables en retries
- incidentType permitido
- manual independiente de ML
- automático pasa por confirmación/countdown

### Cierre de viaje

`POST /api/v1/trips/{id}/finish`

- conservar `remoteTripId`
- `clientFinishedAtUtc` estable entre retries
- no limpiar el pendiente hasta confirmación/reconciliación

### Recorrido

`POST /api/v1/trips/{id}/route-points/batch`

### Eventos secundarios

`MinorEvent/Bump` queda local mientras backend no publique/aclare su `type/payload` aceptado. `/api/v1/mobile/offline-ingestion/batch` sí aparece en la documentación con un ejemplo `LocationSnapshot`; no asumir que acepta `MinorEvent`, `LocalIncident` o `AlertDispatchRequest` sin confirmación.

## ML

No modificar el artefacto ni su política:

- `1.1.0-pilot-ready`
- 8 features exactas
- threshold leído del JSON (`0.71` actual)
- `hardRuleDetected || probability >= threshold`

## Secret de GitHub requerido

`GOOGLE_SERVICES_JSON_B64`

CI lo decodifica temporalmente como `app/google-services.json`.

## Checks antes de PR

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
.\gradlew.bat :app:lintDebug --console=plain --no-daemon --no-configuration-cache --max-workers=2
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon
```

Después revisar Gitleaks, Dependency Review y CodeQL en GitHub.

## Merge

No hacer merge automáticamente. Reportar SHA, URL del PR y checks y esperar autorización humana.
