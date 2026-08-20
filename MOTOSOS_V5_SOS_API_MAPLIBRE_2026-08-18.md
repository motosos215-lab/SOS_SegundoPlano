# MotoSOS Android v5 — SOS, contrato Monitor y corrección MapLibre

Fecha: 2026-08-18

## 1. Corrección de compilación MapLibre

Se corrigió `MotoMapLibre.kt` en `MapLibreMap.getCameraForLatLngBounds(...)`.
La SDK 13.5.0 espera `IntArray`; la v4 enviaba `Array<Int>`.

Antes:

```kotlin
arrayOf(paddingPx, paddingPx, paddingPx, paddingPx)
```

Ahora:

```kotlin
intArrayOf(paddingPx, paddingPx, paddingPx, paddingPx)
```

## 2. Riesgo y prioridad del SOS

### Automático

Se conserva el mapeo canónico que ya existía:

- `RiskLevel.Low` -> severity `Low`
- `RiskLevel.Medium` -> severity `Medium`
- `RiskLevel.High` -> severity `High`
- `RiskLevel.Unknown` -> severity `Unknown`
- alerta automática normal -> priority `High`
- evento físico crítico/inmediato -> priority `Critical`

Se añadió cobertura focalizada para `CriticalEvent` con severity `High` y priority `Critical`.

### Manual

El SOS manual sigue siendo de un toque: no es obligatorio elegir nada antes de enviar.

Valores por defecto:

- incidentType: `ManualSos`
- severity/riesgo: `Unknown`
- priority: `High`
- reason: `ManualSos`

La pantalla SOS incluye un botón compacto `Clasificación opcional`. Si el Rider lo abre, puede elegir:

- Riesgo: Sin definir / Bajo / Medio / Alto
- Prioridad: Baja / Media / Alta / Crítica

La selección se persiste junto con `clientIncidentId` y `clientAlertRequestId`, por lo que un retry reutiliza la misma clasificación y no cambia el significado del SOS.

## 3. Monitor: acknowledge y decline según el contrato entregado

`acknowledge` ya utiliza:

```json
{
  "responseType": "CanAssist",
  "message": "..."
}
```

Se añadieron respuestas rápidas en la UI del Monitor:

- Ya recibí tu alerta
- Voy en camino
- Estoy llamando a emergencias
- Estoy revisando tu ubicación
- ¿Estás bien?

También se corrigió `decline`. La v4 enviaba un contrato antiguo (`responseType` + `message`). El contrato nuevo exige:

```json
{
  "reason": "No puedo apoyar en este momento"
}
```

La v5 envía exactamente `reason`.

## 4. ¿El mensaje del Monitor regresa al Rider?

No se implementó una bandeja de mensajes en Inicio del Rider porque el contrato compartido no define ninguna de estas piezas necesarias:

- endpoint Rider para consultar respuestas/mensajes;
- endpoint de conversación;
- FCM de Monitor -> Rider;
- payload FCM para una respuesta del Monitor;
- identificador/cursor para sincronizar mensajes del Rider.

El contrato sí permite que el Monitor guarde un `message` al hacer `acknowledge`, pero no documenta que ese mensaje sea enviado al Rider. Inventar una bandeja local haría parecer que existe mensajería bidireccional cuando el backend todavía no la garantiza.

Cambio backend mínimo recomendado para habilitarla después:

```text
GET /api/v1/mobile/alerts/{incidentId}/messages
```

y/o un FCM dirigido al Rider con, como mínimo:

```text
incidentId
messageId
message
responseType
sentAtUtc
```

Cuando exista ese contrato, el Home Rider puede mostrar `Mensajes` sin datos falsos.

## 5. Validación disponible en este entorno

No se pudo ejecutar Gradle porque el wrapper intenta descargar Gradle 9.4.1 y este entorno no tiene salida de red hacia `services.gradle.org`.

Sí se realizaron comprobaciones estáticas y se actualizó cobertura focalizada para:

- clasificación manual por defecto;
- selección High/Critical;
- persistencia de clasificación;
- mapeo automático CriticalEvent;
- body exacto de decline;
- corrección `IntArray` de MapLibre.

Validar en Windows con:

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Comprobaciones adicionales de empaquetado:

- 25 archivos XML parseados: 0 errores.
- 7 recursos PNG protegidos comparados contra v4: idénticos.
- smoke test sintáctico de los Kotlin focales: sin errores de sintaxis detectados (sin classpath Android no sustituye Gradle).
- ZIP limpio: sin `build/`, `.gradle/`, `.git/`, `node_modules/`, logs ni temporales.
