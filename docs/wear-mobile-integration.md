# MotoSOS Phone ↔ Wear integration

`SegundoPlano` is the canonical Android project. The phone owns authentication, backend trips,
incidents, Manual SOS, validation and FCM. The Wear module never stores credentials and reaches
the phone only through Google Play services Wearable Data Layer.

## Discovery and RPC

The phone publishes `motosos_mobile_companion_v1`; the watch publishes
`motosos_wear_telemetry_v1`. The phone selects one reachable nearby watch deterministically and
uses request/response RPC for these shared `:wear-protocol` paths:

- `/motosos/v1/handshake`
- `/motosos/v1/status`
- `/motosos/v1/snapshot`

Every RPC carries the protocol/schema versions and a non-blank request id. The watch validates
them before responding. Handshake uses real build/manufacturer/model/API data. Status and snapshot
are served from `WearSignalStateStore`; unavailable sensors remain unavailable and are never
represented by zero values.

## Sensor pipeline

`WearSignalForegroundService` is the sole producer for motion, battery and heart-rate signals. It
updates `WearSignalStateStore` and continues publishing the existing
`/motosos/signals/latest` Data Layer format for `MobileWearableSignalSource` and `TripSignalStore`.
RPC and UI observe that store and must not register sensors.

## Safety boundary

Watch-to-phone trip start/finish and Manual SOS actions require a separate typed action protocol
and a reusable phone lifecycle coordinator. They are intentionally not emulated by the Wear UI:
the phone must validate Rider session, readiness, remote trip id and idempotency before an action
can be exposed. Watch Manual SOS must reuse the phone Manual SOS pipeline, including existing
EmergencyLocationSharing; it must never call HTTP itself.

## Phone action boundary (Block A)

`PhoneWearActionRpcService` serves `/motosos/v1/trip/state` through a testable handler. It accepts
only authenticated Rider sessions and reads the persisted local `remoteTripId`; it does not make a
backend lookup. The typed mutation routes currently return `unavailable/action_not_available`
until their real phone mutations are added in a later block.

`WearCommandResultStore` is bounded SharedPreferences storage keyed by action plus command id. It
stores only result metadata, optional sanitized code, remote trip id and timestamps.

## Manual validation

Validate a paired phone/watch with the same signing identity: discovery, handshake, real sensor
status/snapshot, existing trip signal publication, heart-rate permission behavior, foreground
service lifecycle and reconnection. Then validate the action protocol only after its phone
authority is implemented.
