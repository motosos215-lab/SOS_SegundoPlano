# MotoSOS v10.3 — Corrección SOS automático

Fecha: 2026-08-18

## Objetivo

Recuperar el comportamiento del SOS automático sin modificar el SOS manual que ya fue validado físicamente.

## Hallazgos

1. La validación posterior al ajuste de falsos positivos había quedado demasiado exigente: un impacto real seguido de inmovilidad podía no abrir countdown si no existía una tercera señal de apoyo.
2. Impacto + cambio fuerte de orientación tampoco podía abrir countdown antes de completar la ventana de inmovilidad.
3. El broadcast de depuración publicaba únicamente al SharedFlow de evaluaciones. Si se ejecutaba justo en el arranque del monitoreo existía una ventana pequeña donde el collector todavía podía no estar suscrito.
4. El SOS automático consideraba inválida una respuesta HTTP exitosa si no encontraba un NotificationAttempt todavía en estado `Prepared`. Ese estado pertenece al outbox asíncrono; una emergencia cuyo Incident y AlertDispatch ya fueron creados no debe convertirse en fallo sólo por el estado secundario del attempt.

## Cambios

### Detección / falso positivo

Se conserva:
- inmovilidad sola no inicia countdown;
- motocicleta quieta mantiene riesgo 0;
- bache aislado con movimiento continuo se suprime;
- frenado fuerte con movimiento continuo se suprime;
- el SOS automático sigue pasando por countdown salvo eventos críticos ya contemplados.

Ahora pueden abrir countdown estos patrones correlacionados:
- caída coherente;
- impacto + inmovilidad sostenida;
- impacto + cambio relevante de orientación;
- impacto + frenado relevante cuando el movimiento ya no continúa claramente;
- impacto muy fuerte con movimiento detenido/intermitente aunque todavía no complete toda la ventana de inmovilidad.

El gate correlacionado se mantiene en score >= 30 y confidence >= 0.50. Estas reglas sólo abren la validación/countdown; no envían un SOS de forma inmediata por sí solas.

### DEBUG_TRIGGER_ACCIDENT

El receiver ahora entrega la evaluación sintética directamente al coordinador en ejecución y además la publica al store de riesgo. El coordinador deduplica por identidad de assessment, por lo que no se generan dos incidentes.

Esto elimina la carrera de suscripción del SharedFlow en el arranque y hace determinista la prueba ADB.

### Éxito remoto del SOS automático

El boundary de éxito ahora es el mismo concepto que usa el SOS manual: si el backend devuelve correctamente `incident.id` y `alertDispatch.id`, la creación de la emergencia es exitosa.

No se rechaza una emergencia creada sólo porque `notificationAttempts` esté vacío o porque el worker ya haya movido el estado fuera de `Prepared`.

La ubicación secundaria para Monitor continúa siendo best-effort y no cambia la identidad del incidente.

## Identidad e idempotencia preservadas

No se modificó:
- tripSessionKey;
- bundleKey;
- clientIncidentId;
- clientAlertRequestId;
- claim exacto por bundle;
- reintentos durables;
- finalización exacta del viaje automático.

## Pruebas actualizadas/agregadas

- impacto moderado + inmovilidad abre countdown;
- impacto + orientación puede abrir countdown antes de completar inmovilidad;
- respuesta backend con Incident + AlertDispatch sigue siendo éxito aunque no haya attempt Prepared;
- se mantienen pruebas existentes de caída, bache, frenado, persistencia, retries e identidad.

## Validación realizada en este entorno

- XML app + wear: 21 archivos, 0 errores.
- Sintaxis focal de los Kotlin modificados: no se detectaron errores `expecting/unclosed` con kotlinc.
- 7 PNG protegidos: idénticos a v10.2.
- No se incluyeron directorios build, .gradle o .git en el paquete.

No se pudo ejecutar Gradle completo porque el wrapper intenta descargar Gradle 9.4.1 desde services.gradle.org y este entorno no tiene resolución de red para ese host.
