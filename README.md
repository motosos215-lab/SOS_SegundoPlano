# SOS Segundo Plano

Aplicacion Android para MotoSOS enfocada en monitoreo en segundo plano, vinculacion movil-smartwatch, deteccion de incidentes y flujo SOS.

## Estado del proyecto

Base inicial para el primer commit. Incluye estructura Android, pruebas base, hardening minimo y workflows DevSecOps iniciales.

## Requisitos

- Android Studio compatible con AGP 9.2.1
- JDK 17
- Gradle Wrapper incluido en el repositorio

## Comandos

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

En Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

## Estructura

```text
app/src/main/java/com/example/sos_segundoplano/
  core/        Configuracion, permisos, seguridad y utilidades compartidas
  data/        Fuentes locales/remotas y repositorios
  domain/      Modelos, contratos y casos de uso
  features/    Modulos funcionales de MotoSOS
  ui/          Tema, componentes y navegacion
```

## DevSecOps

El repositorio incluye workflows iniciales para:

- compilacion Android
- pruebas unitarias
- Android Lint
- generacion de APK debug
- escaneo de secretos con Gitleaks

Ver `docs/devsecops.md`.

## Testing

La estrategia de pruebas queda documentada en `docs/testing-strategy.md`.

## Recorrido real y mapas

Durante un viaje Rider, MotoSOS persiste puntos GPS locales y los sincroniza por lotes con la API de Trips. El historial consulta la ruta persistida y dibuja la Polyline real; no genera una línea artificial entre inicio y fin.

Los mapas integrados usan **MapLibre Native + OpenFreeMap** y no requieren una API key de Google. La pestaña **Mapa**, el detalle de viaje y la ubicación de una alerta en el Monitor usan el estilo Liberty de OpenFreeMap.

Google Maps se conserva únicamente como destino externo para las acciones Rider que todavía delegan navegación. En Monitor, **Ver ubicación del incidente** abre el mapa interno de MotoSOS y calcula una ruta de asistencia desde la ubicación del Monitor hasta el incidente.

La ruta de asistencia del Monitor se mantiene desacoplada del backend MotoSOS mediante `MONITOR_ROUTING_BASE_URL`. La configuración por defecto usa el servicio HTTP de OSRM para obtener geometría GeoJSON y MapLibre la dibuja dentro de la app. Para un despliegue productivo con requisitos estrictos de privacidad/disponibilidad se recomienda sustituir esa URL por un servicio de routing administrado por MotoSOS o autoalojado.

Los detalles de configuración están en `docs/maps-setup.md`. Los endpoints de recorrido, SOS automático y pruebas están documentados en `MOTOSOS_CAMBIOS_SOS_RUTAS_MAPAS_2026-08-17.md`.

## Configuracion

No se deben versionar secretos ni archivos locales. Ver `docs/configuration-control.md`.


## SOS manual y respuesta Monitor

El SOS manual se puede enviar sin clasificación adicional. Por defecto usa riesgo/severity `Unknown` y prioridad `High`; el Rider puede cambiar esos valores de forma opcional antes de enviar. Los SOS automáticos conservan severity calculada por riesgo y prioridad `High`/`Critical` según la decisión de emergencia.

El Monitor puede confirmar recepción con `responseType = CanAssist` y respuestas rápidas. El Rider maneja los eventos push `monitor_alert_viewed`, `monitor_alert_acknowledged` y `monitor_alert_declined` en la bandeja local **Mensajes de emergencia**.

## Enlaces MotoSOS Web

Las acciones de autenticación y administración de contactos que corresponden al frontend web abren el despliegue de Netlify configurado en Gradle:

```properties
MOTOSOS_WEB_REGISTER_URL=https://deploy-preview-4--motosos.netlify.app/registro
MOTOSOS_WEB_PASSWORD_RECOVERY_URL=https://deploy-preview-4--motosos.netlify.app/recuperar-contrasena
MOTOSOS_WEB_CONTACTS_URL=https://deploy-preview-4--motosos.netlify.app/configuracion/contactos
MOTOSOS_WEB_CONTACTS_DASHBOARD_URL=https://deploy-preview-4--motosos.netlify.app/dashboard/contactos
```

`MOTOSOS_WEB_CONTACTS_URL` se usa para **Editar monitor en la web**. La ruta de dashboard queda disponible como configuración del proyecto para futuras entradas web sin mezclarla con el editor.

## Accident Model v1.1 (piloto)

El artefacto `app/src/main/assets/motosos_accident_model_v1_1_pilot_ready.json` se evalúa localmente durante un viaje activo, sin Python, TensorFlow Lite ni conexión de red. Se calculan las ocho features del modelo a partir del pipeline de señales/preprocesamiento existente y la decisión efectiva conserva la política `reglas explicables OR ML`.

El ML es únicamente una señal de apoyo: si supera el threshold del artefacto abre el mismo countdown de confirmación del Rider; nunca reemplaza el SOS manual ni crea por sí solo una alerta crítica inmediata. Si el modelo no puede cargarse o falta una feature obligatoria, las reglas existentes continúan funcionando. Los manuales del modelo están en `docs/ml/`.
