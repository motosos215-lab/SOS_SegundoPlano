# MotoSOS — cierre de viaje offline y estado de sincronización en Inicio

Fecha: 2026-08-20

## Objetivo

Permitir que el Rider finalice el viaje local aunque la red desaparezca y asegurar que el cierre remoto se ejecute después, sin duplicar ni perder el viaje.

## Flujo de cierre

1. El viaje ya debe tener `remoteTripId` y `tripSessionKey` correlacionados.
2. Al pulsar **Terminar viaje**, Android crea `FinishTripRequestDto` y persiste primero un `PendingTripFinish`.
3. Si no existe Internet validado, no se espera un timeout HTTP: el cierre queda `RetryPending` y se agenda `TripFinishRecoveryWorker` con `NetworkType.CONNECTED`.
4. El monitoreo, timing y sesión local pueden finalizar normalmente porque la intención remota ya quedó durable.
5. Al volver Wi‑Fi o datos móviles, el worker llama `POST /api/v1/trips/{id}/finish` con el mismo `remoteTripId` y `clientFinishedAtUtc`.
6. Sólo después de confirmación remota se limpia el pendiente correlacionado.
7. El endpoint de finish es idempotente; los reintentos mantienen la misma identidad del viaje.

## Persistencia

`SharedPreferencesPendingTripFinishStore` conserva únicamente la información necesaria para completar el cierre:

- owner Rider
- `remoteTripId`
- `tripSessionKey`
- `clientFinishedAtUtc`
- estado, intentos, siguiente reintento y código de error sanitizado

La ubicación final no se duplica en este store. Los puntos GPS reales del viaje tienen su almacenamiento durable independiente y su endpoint batch de ruta.

## Política de error

Retry automático:

- sin Internet validado
- timeout
- 401 (el finisher intenta refresh una vez)
- 408
- 409
- 429
- 5xx
- respuesta/contexto temporalmente no resoluble

Requiere atención:

- rechazo HTTP no retryable (por ejemplo 400/403/404), sin borrar el registro local
- fallo de persistencia local: el viaje NO se cierra localmente porque no existe garantía durable

## Scheduler

Workers únicos:

- `trip-finish-sync-immediate`
- `trip-finish-sync-retry`

El processor persiste `nextAttemptAt` y programa el retry exacto; el Worker devuelve success para ese ciclo y evita crear una segunda cadena de backoff independiente.

## Inicio: Estado de sincronización

La información de sincronización ya no se muestra en la pantalla de monitoreo.

La tarjeta de Inicio incluye únicamente datos con destino remoto conocido:

- bundles SOS automáticos
- SOS manual pendiente
- cierre de viaje pendiente
- puntos de ruta pendientes

Estado sin pendientes:

> Todo sincronizado  
> Sin eventos por sincronizar.

Los `MinorEvent/Bump` locales no se incluyen como pendientes remotos porque el contrato disponible no documenta su `type/payload` para el batch offline, aunque sí documenta el endpoint con un ejemplo `LocationSnapshot`.

## Inicio de un nuevo viaje

Si existe un cierre de viaje pendiente o que requiere atención para el Rider actual, se bloquea el inicio de otro viaje hasta reconciliar el anterior. Esto evita mezclar dos `remoteTripId` en un mismo dispositivo.
