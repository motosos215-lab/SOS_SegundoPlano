# MotoSOS ↔ Wear OS: Protocolo V1 (contrato móvil–reloj)

Estatus: **estable / básico de emisión** · Canal: Wearable Data Layer (mensajes RPC)

Este documento es la única referencia del contrato entre la app móvil MotoSOS
(`com.example.sos_segundoplano`) y la futura app Wear MotoSOS. El código de
referencia vive en el módulo compartido [`:wear-protocol`](../wear-protocol/),
navegable por la app móvil como `com.example.sos_segundoplano.wearprotocol`.

El módulo `:wear` ya existía antes de esta foundation y contiene código de
funcionalidades previas. Esta entrega no lo creó ni lo modifica. La futura
integración Wear V1 deberá respetar ese módulo existente y conectar
explícitamente con `:wear-protocol` cuando corresponda.

## 1. Objetivo

Permitir que el reloj Wear OS publique sensores (acelerómetro, giroscopio,
frecuencia cardiaca) y que el móvil los lea bajo demanda, usando solo el
Wearable Data Layer. No hay sockets, BLE/GATT directo, servidor backend ni
código de emparejamiento propio.

## 2. Canal de transporte

- **Librería**: `com.google.android.gms.play-services-wearable` (Wearable `19.0.0`).
- **App móvil**: `applicationId = com.example.sos_segundoplano`, variante debug/release
  estándar, `namespace = com.example.sos_segundoplano`, `minSdk = 26`,
  `targetSdk = 36`, `compileSdk = 36.1`, AGP `9.2.1`, Kotlin `2.2.10`.
- **Contrato compartido**: `:wear-protocol`, Android Library,
  `namespace = com.example.sos_segundoplano.wearprotocol`, `minSdk = 26`.
- **Clientes usados por el móvil**:
  - `NodeClient.connectedNodes` — listar relojes vinculados.
  - `CapabilityClient` (filtro `FILTER_REACHABLE`) con la capability
    `motosos_wear_telemetry_v1` — descubrir qué relojes tienen la app de Wear.
  - `CapabilityClient.addListener/removeListener` — repetir discovery ante cambios
    de capability alcanzable mientras la pantalla está activa.
  - `MessageClient.sendRequest` (RPC de `Request a mensaje`) — las tres rutas.
- **Prohibido en esta entrega**: `DataClient` nuevo, `ChannelClient`, BLE/GATT,
  `BluetoothAdapter`, `CompanionDeviceManager`, direcciones MAC, RSSI, sockets,
  protobuf propio, códigos de 6 dígitos, emparejamiento manual.

La app móvil declara su capability con el recurso oficial:

```xml
<string-array name="android_wear_capabilities" translatable="false">
    <item>motosos_mobile_companion_v1</item>
</string-array>
```

## 3. Versionado

- `protocolVersion = 1`, `schemaVersion = 1` (enteros).
- Los cambios incompatibles se reflejan con una versión nueva (p. ej. v2 en
  capacidad `motosos_wear_telemetry_v2`); el móvil solo conversa con reloj
  que anuncie la v1 o la más cercana que reconozca.

## 4. Valores de referencia

| Componente      | Unidad  |
|-----------------|---------|
| Acelerómetro    | m/s²    |
| Giroscopio      | rad/s   |
| Heart rate      | bpm     |

## 5. Rutas (RPC)

Las tres solicitudes y sus respuestas llevan siempre `protocolVersion`,
`schemaVersion`, `requestId` y `timestampEpochMs`:

| Ruta                      | Solicitud       | Respuesta        | Uso mobile |
|---------------------------|-----------------|------------------|------------|
| `/motosos/v1/handshake`   | HandshakeRequest  | HandshakeResponse  | descubrir compatibilidad de versión |
| `/motosos/v1/status`      | WatchStatusRequest | WatchStatusResponse | estado de sensores / batería |
| `/motosos/v1/snapshot`    | WatchSnapshotRequest| WatchSnapshotResponse| lectura única de sensores |

### 5.1 handshake

Solicitud (mobile → wear):

```json
{
  "route": "/motosos/v1/handshake",
  "protocolVersion": 1,
  "schemaVersion": 1,
  "requestId": "uuid-mobile",
  "timestamp": 1700000000000
}
```

Respuesta (wear → mobile):

```json
{
  "route": "/motosos/v1/handshake",
  "protocolVersion": 1,
  "schemaVersion": 1,
  "requestId": "uuid-mobile",
  "result": "ok",
  "appVersionName": "1.0",
  "appVersionCode": 1,
  "manufacturer": "Samsung",
  "model": "SM-R900",
  "wearOsApiLevel": 34,
  "capability": "motosos_wear_telemetry_v1",
  "respondedAtEpochMs": 1700000000001
}
```

El móvil considera un reloj "compatible" si la respuesta es `ok`, el
`requestId` repite el de la solicitud, las versiones de protocolo/esquema
coinciden con la del contrato y `capability == motosos_wear_telemetry_v1`.

### 5.2 `status`

Pide un estado puntual del reloj:

```json
{
  "route": "/motosos/v1/status",
  "protocolVersion": 1,
  "schemaVersion": 1,
  "requestId": "uuid-mobile",
  "timestamp": 1700000000000
}
```

Respuesta (todas las claves de sensores presentes; batería opcional si no
se sabe):

| Campo                      | Tipo    | Notas |
|----------------------------|---------|----------------------------|
| `batteryAvailable`         | booleano | |
| `batteryPercent`           | entero 0..100 | obligatorio si `batteryAvailable == true`; ausente si `batteryAvailable == false` |
| `chargingKnown`            | booleano | |
| `charging`                 | booleano | solo si `chargingKnown == true` |
| `accelerometerAvailable`   | booleano | |
| `gyroscopeAvailable`       | booleano | |
| `heartRateAvailable`       | booleano | |
| `heartRatePermission`      | enum wire | `granted/denied/not_requested/not_required/unavailable` |
| `sequence`                 | entero >= 0 | numerado por el reloj |
| `respondedAtEpochMs`       | epoch ms | > 0 |

### 5.3 `snapshot`

Lectura puntual de sensores:

```jsonc
{
  "route": "/motosos/v1/snapshot",
  "protocolVersion": 1,
  "schemaVersion": 1,
  "requestId": "uuid-mobile",
  "timestamp": 1700000000000
}
```

Respuesta:

| Campo | Tipo | Notas |
|---|---|---|
| `capturedAtEpochMs` | epoch ms | |
| `accelerometerAvailable` | booleano | |
| `accelerometerX/Y/Z` | float | si el sensor está disponible (m/s²); si `accelerometerAvailable == false`, el móvil lo interpreta como `null` |
| `gyroscopeAvailable` | booleano | |
| `gyroscopeX/Y/Z` | float | rad/s; si `gyroscopeAvailable == false`, el móvil lo interpreta como `null` |
| `heartRateAvailable` | booleano | |
| `heartRatePermission` | enum wire | |
| `heartRateBpmPresent` | booleano | |
| `heartRateBpm` | float >= 0 | solo si `heartRateBpmPresent == true`; si `heartRateBpmPresent == false`, el móvil lo interpreta como `null` |
| `sequence` | entero 0+ | |

## 6. Valores de estado (enum wire)

`WearRpcResult` se transmite como string en el campo `result`:

| wire | significado |
|------|-------------|
| `ok` | solicitud atendida |
| `not_ready` | reloj no listo |
| `sensor_unavailable` | sensor no disponible |
| `permission_denied` | permiso denegado |
| `protocol_mismatch` | versiones incompatibles |
| `invalid_request` | solicitud mal formada |
| `internal_error` | error interno |

`SensorPermissionState` (`heartRatePermission`):

| wire |
|------|
| `granted` |
| `denied` |
| `not_requested` |
| `not_required` |
| `unavailable` |

## 7. Correlación y límites

- Timeout del móvil a una respuesta: `WEAR_RPC_TIMEOUT_MILLIS = 10_000L`.
- Límite de payload: `MAX_PAYLOAD_BYTES = 102400` (100 kB). Tamaños mayores se
  tratan como inválidos.
- `requestId` es único por solicitud (UUID del móvil). Se descarta toda
  respuesta cuyo `requestId` no coincida con la solicitud emitida.
- Status y snapshot se piden únicamente después de handshake `ok` contra un nodo
  `isNearby == true`. Un nodo indirecto no recibe telemetría.

## 8. Código fuente de referencia

| Pieza | Ubicación |
|-------|-----------|
| Contrato (rutas, versiones) | `:wear-protocol` → `WearProtocol.kt` |
| Modelos de solicitud/respuesta | `WearProtocolModels.kt` |
| Valores de wire (`WearRpcResult`, `SensorPermissionState`) | `WearProtocolValues.kt` |
| Codec (`DataMap` ↔ bytes) | `WearProtocolCodec.kt` |
| Errores de decoding | `WearProtocolError.kt` |
| Selección de nodo + RPC del móvil | app `data/wear/DefaultWatchConnectionRepository.kt` |
| Gateway sobre Play Services | app `data/wear/GooglePlayWearGateway.kt` |

## 9. Que NO debe hacerse en una ruta

- No enviar `nodeId`, mac, tokens ni datos personales en payloads de RPC.
- No exponer ni persistir `nodeId` fuera de la capa data; la UI solo muestra
  `displayName` del reloj y detalles del handshake.
- No abrir canales BFS a bajo nivel.
- No guardar datos del reloj en una cola offline (reloj no tiene persistencia).

## 10. Validaciones y pruebas futuras

- La firma móvil/Wear debe coincidir: mismo package esperado, misma capability
  publicada y mismo contrato `DataMap`.
- El reloj debe declarar sus permisos de sensores/Health Services según su app;
  esta foundation móvil no añade permisos Bluetooth nuevos para Data Layer.
- José validará manualmente Gradle, lint, dispositivo/emulador, adb, APIs y
  signingReport. No hay secretos ni resultado de signingReport en este documento.
- No se documentan endpoints backend porque este protocolo no usa backend.
