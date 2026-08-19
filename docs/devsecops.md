# DevSecOps MotoSOS

## Controles implementados

- Android CI ejecuta `testDebugUnitTest`, `lintDebug` y `assembleDebug`.
- Gitleaks analiza el historial completo (`fetch-depth: 0`) en `push` y `pull_request`.
- CodeQL analiza Java/Kotlin en `push` y `pull_request`. Usa `build-mode: none` para no requerir la configuración Firebase durante el análisis estático.
- Dependency Review no se habilita aún: GitHub informó que Dependency Graph está desactivado para este repositorio. Debe activarse en la configuración de Security & analysis antes de reintroducir el workflow.
- Dependabot abre revisiones semanales para Gradle y GitHub Actions.
- Las Actions se fijan a SHA completos verificados y los workflows aplican permisos mínimos. CodeQL requiere además `security-events: write` para publicar resultados.

## Android y datos locales

- Producción exige HTTPS mediante `usesCleartextTraffic="false"`.
- Backups se desactivan y existen reglas explícitas de backup/data extraction.
- Sesiones y tokens push se almacenan con Android Keystore y AES-GCM.
- La cola offline usa cifrado AES-GCM; la base de rutas conserva coordenadas locales para sincronización, por lo que debe tratarse como dato sensible del dispositivo.
- Los logs de sincronización de rutas no incluyen `tripId` y solo se emiten en builds debug.
- No se detectaron bypasses TLS ni trust managers permisivos en el código auditado.

## ML y SOS

- El artefacto local es `motosos_accident_model_v1_1_pilot_ready.json`, versión `1.1.0-pilot-ready`, con umbral `0.71` y ocho features exactas.
- La política se conserva: `possibleAccident = hardRuleDetected || probability >= threshold`.
- El modelo es señal de apoyo local: no crea ni envía SOS. La confirmación del Rider y el countdown permanecen en `FalsePositiveValidationCoordinator`; el SOS manual es independiente.
- El modelo está marcado pilot-ready y requiere validación real antes de cualquier afirmación de seguridad o rendimiento en producción.

## Wear

- Los servicios Wear expuestos por requisito de Google Play Services conservan validación de protocolo, versiones, `requestId`/`commandId` y semántica idempotente.
- Las acciones Wear no deben aceptar payloads sin versión, campos requeridos o rutas válidas. Las pruebas de codec y RPC cubren esa frontera.

## Secretos y archivos locales

No se deben versionar `google-services.json`, `local.properties`, `.env*`, certificados, llaves, keystores, APK/AAB ni directorios de build/IDE. Los secretos de CI se recuperan exclusivamente desde GitHub Actions Secrets.

## Limitaciones conocidas

- La generación de SBOM CycloneDX no se añade todavía: requiere evaluar un plugin compatible con el Android Gradle Plugin actual y su cadena de publicación. No se debe declarar una SBOM como generada hasta que exista un artefacto CI verificable.
- Dependency Review permanece no disponible hasta habilitar Dependency Graph en GitHub Security & analysis.
- R8/minificación de release sigue desactivada. Activarla exige validar reglas de Retrofit, Room, Firebase, Wear y serialización en un build release separado.
- Los controles CI no sustituyen revisión humana, pruebas en dispositivos físicos ni rotación de credenciales comprometidas.
