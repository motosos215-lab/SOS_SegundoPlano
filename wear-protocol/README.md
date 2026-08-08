# :wear-protocol

Contrato compartido móvil ↔ Wear para MotoSOS (protocolo V1).

Módulo de solo biblioteca (`com.android.library`) sin dependencias del código
de las apps. Aquí viven las constantes, modelos, valores wire y el codec que
**ambas** apps (móvil y futura Wear) deben usar para que sus mensajes coincidan.

Este módulo es nuevo para el contrato V1. El módulo `:wear` del repositorio ya
existía por funcionalidades previas y no fue creado ni migrado por esta entrega.

## Contenido

| Archivo | Responsabilidad |
|---|---|
| `WearProtocol.kt` | Rutas, versiones, capabilities, límite de payload y generador de `requestId`. |
| `WearProtocolValues.kt` | enums de wire: `WearRpcResult`, `SensorPermissionState`. |
| `WearProtocolModels.kt` | Solicitudes y respuestas de las 3 rutas + lecturas vectoriales/FC. |
| `WearProtocolError.kt` | Errores de validación del payload. |
| `WearProtocolCodec.kt` | Codec `DataMap` ↔ bytes (encode de solicitud, decode de respuesta) y errores. |

## Uso

Toda app que hable Wearable Data Layer depende de este módulo e implementa:

- Público: `WearProtocol.PATH_HANDSHAKE`, `PATH_STATUS`, `PATH_SNAPSHOT`,
  `CAPABILITY_MOBILE_COMPANION`, `CAPABILITY_WEAR_TELEMETRY`,
  `PROTOCOL_VERSION`, `SCHEMA_VERSION`, `MAX_PAYLOAD_BYTES`.
- El móvil codifica solicitudes con `WearProtocolCodec.encode*Request(...)`
  y decodifica respuestas con `decode*Response(...)`.
- La futura app Wear hará lo inverso: decodificar solicitudes con el mismo
  codec y responder rellenando los modelos de respuesta.
- El transporte esperado es Wearable Data Layer con `MessageClient.sendRequest()`
  y payload `DataMap.toByteArray()` / `DataMap.fromByteArray()`.
- No se envían tokens, usuarios, backend, BLE/GATT, `DataClient` ni
  `ChannelClient` en este contrato.

## Configuración

- Android Library: `namespace = com.example.sos_segundoplano.wearprotocol`.
- SDKs: `minSdk = 26`, `compileSdk = 36.1`.
- Herramientas del repo: AGP `9.2.1`, Kotlin `2.2.10`.
- Dependencia wearable: `com.google.android.gms:play-services-wearable:19.0.0`.
- App móvil actual: `applicationId = com.example.sos_segundoplano`.

## Capabilities

- Móvil: `motosos_mobile_companion_v1`, declarado en app con
  `android_wear_capabilities`.
- Wear: `motosos_wear_telemetry_v1`, requerido para discovery y handshake.

## Pruebas

- `src/test/*`: unit tests JVM que ejercitan las variantes `*Map` (sin
  serialización a bytes, porque `DataMap.toByteArray()` no está mockeado en
  JVM).
- `src/androidTest/*`: round-trip real de bytes (instrumentado) para las tres
  rutas + errores de payload.

## Contrato completo

Ver [`docs/wear-mobile-protocol-v1.md`](../docs/wear-mobile-protocol-v1.md).
