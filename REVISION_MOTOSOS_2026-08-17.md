# MotoSOS — revisión de lógica y pantallas

Fecha: 17 de agosto de 2026

## Alcance

Se revisaron los proyectos Android móvil (`SegundoPlano`) y Wear OS (`MotoSOS-Wearable`) tomando como referencia el backlog entregado y preservando el trabajo F.5C3/P5A ya existente en el ZIP móvil.

La revisión se concentró en identidad de viaje, cierre remoto/local, recuperación tras proceso, SOS automático/manual, acciones desde reloj y claridad de las pantallas de emergencia.

## Cambios principales — móvil

### 1. Finalizar viaje ya no cierra primero el estado local

Antes, la pantalla podía detener el monitoreo y marcar el viaje como terminado localmente antes de confirmar el cierre en el servidor. Si el cierre remoto fallaba, el teléfono podía mostrar el viaje terminado mientras el backend aún lo mantenía activo.

Ahora el flujo es:

1. identifica la sesión lógica exacta del viaje;
2. solicita la finalización remota;
3. si el servidor falla, mantiene el viaje/monitoreo y muestra opción de reintento;
4. si el servidor confirma, detiene monitoreo;
5. limpia timing y sesión sólo si siguen correspondiendo al mismo `tripSessionKey`.

También se evita volver a finalizar remotamente el mismo viaje cuando sólo se está reintentando el apagado/local cleanup.

### 2. Destruir `MonitoringForegroundService` ya no significa “viaje terminado”

Se eliminó la limpieza global de `TripTimingStore` y `TripSessionStore` desde `Service.onDestroy()`.

Un servicio puede morir por el sistema, actualización, error o lifecycle; eso no debe borrar por sí mismo la identidad durable del viaje.

Los flujos que realmente terminan un viaje son ahora responsables de reconciliar su estado de forma explícita y correlacionada.

### 3. Cleanup correlacionado de viajes

Se añadió `TripLocalStateReconciler`.

El reconciliador usa el `remoteTripId` y el `tripSessionKey` durables y evita que una finalización/retry tardío del viaje A borre estado perteneciente a un viaje B nuevo.

Reconciliación protegida:

- `RemoteTripSessionStore`
- `TripSessionStore`
- `TripTimingStore`

### 4. `RemoteTripSessionStore` conserva la correlación de identidad

La sesión remota ahora expone/persiste también `tripSessionKey`.

Un `remoteTripId` nuevo no hereda accidentalmente la clave de otro viaje. Los clears exactos requieren que `remoteTripId` y `tripSessionKey` correspondan al mismo viaje.

### 5. Recovery no inventa una nueva identidad local

`TripProcessRecoveryCoordinator` sólo recupera timing cuando ya existe una `TripSessionState.Active` durable y utiliza esa misma clave.

Si la identidad local no existe, la consulta remota no fabrica una sesión lógica nueva de forma silenciosa.

### 6. La reconciliación del backend conserva la clave local existente

Cuando el backend reporta un viaje activo y ya existe una sesión local correlacionable, `TripRemoteSessionReconciler` persiste el `remoteTripId` junto con ese mismo `tripSessionKey`. Si el backend devuelve un viaje remoto distinto, no hereda el `startedAt` del viaje anterior.

### 7. Finalizar desde el reloj usa servidor → stop → cleanup

La ruta de `FinishTrip` recibida desde Wear tenía el mismo riesgo del móvil: detener monitoreo antes de saber si el backend había cerrado el viaje.

Ahora:

1. valida el `remoteTripId` exacto;
2. finaliza remotamente ese ID;
3. detiene monitoreo;
4. ejecuta cleanup correlacionado;
5. conserva un estado parcial reintentable si el servidor ya terminó pero el cleanup local todavía no.

Un retry del estado parcial no vuelve a llamar al servidor innecesariamente.

## Cambios principales — Wear OS

### 1. Eliminada identidad SOS ficticia `trip-demo`

El SOS y las respuestas de countdown ya no fabrican `trip-demo` cuando el reloj no tiene un viaje activo sincronizado.

Si falta la identidad real:

- no se envía un evento con identidad falsa;
- la UI indica que no hay un viaje sincronizado;
- se orienta al usuario a iniciar/recuperar el viaje o usar el teléfono.

### 2. Errores de envío visibles

Las acciones de “Estoy bien” y “Necesito ayuda” ahora capturan fallos y los muestran en pantalla en lugar de asumir éxito.

### 3. SOS del reloj más claro

Se añadió un banner que explica que la alerta se enviará al teléfono vinculado y se asociará al viaje activo. También se muestra un banner explícito cuando el SOS no puede enviarse.

## Mejoras de pantalla — móvil

### SOS manual

- explicación más clara del propósito;
- tarjeta visual con la información usada por la alerta (ubicación disponible, hora y aviso asociado al flujo de contactos);
- se mantiene el diseño rojo de emergencia y los assets existentes.

### Posible accidente

- contador central más grande y visible;
- círculo de alerta con mejor jerarquía visual;
- se conservan las acciones “Estoy bien” y “Necesito ayuda”.

### Viaje activo

- el botón de finalizar muestra progreso y queda deshabilitado mientras se procesa;
- si el servidor no confirma el cierre, se informa que el viaje continúa activo y se ofrece reintentar.

### Mensajes automáticos

Se eliminaron textos obsoletos que afirmaban que el envío remoto “aún no está disponible”. El SOS automático P5A ya dispone de pipeline remoto y retry durable.

## Pruebas/cobertura actualizadas

Se añadieron o reforzaron pruebas para:

- cleanup A→A sin borrar B;
- persistencia/restauración de `tripSessionKey` en sesión remota;
- recovery con la misma identidad;
- backend active trip correlacionado con sesión local existente;
- fallo de finalización remota antes de detener captura;
- retry de stop sin finalizar remotamente dos veces;
- finish desde Wear con partial side effects;
- SOS Wear sin identidad sintética;
- countdown Wear sin identidad sintética.

Además se actualizaron pruebas instrumentadas antiguas que todavía trataban `TripSessionState.Active` como singleton y fakes que usaban la firma vieja de `beginConfirmedTrip()`.

## Validaciones realizadas en este entorno

- XML móvil: válido.
- XML Wear: válido.
- nombres de recursos de `strings.xml`: sin duplicados.
- `trip-demo` en `app/src/main` del Wearable: eliminado.
- firma vieja `beginConfirmedTrip()` en implementación/fakes actualizados: revisada.
- `WearPhoneActionCoordinator.kt`: compilación Kotlin focalizada exitosa contra los artefactos precompilados disponibles.
- assets gráficos protegidos del móvil: SHA-256 idéntico al ZIP recibido.

## Limitación del entorno de revisión

No fue posible ejecutar una compilación Android completa de esta revisión dentro del sandbox porque el entorno Linux no tiene Android SDK y el Gradle wrapper necesita una distribución/dependencias que no puede descargar por red.

Esto significa que **la revisión actual debe compilarse y ejecutarse una vez en tu Windows/Android SDK antes de instalarla en teléfonos reales**.

La base anterior F.5C3/P5A sí venía con unitarios/assemble y SOS automático físico exitosos; las modificaciones descritas en este informe son posteriores a esa validación.

## Comandos recomendados en Windows — móvil

Desde la carpeta `SegundoPlano`:

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Después, si ambos pasan:

```powershell
adb -s "RFCW30M1F4E" install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

No uses `pm clear` ni desinstales si quieres conservar la sesión/datos de prueba.

## Comandos recomendados en Windows — Wearable

Desde la raíz de `MotoSOS-Wearable`:

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

## Reprueba física recomendada

1. cerrar cualquier viaje viejo;
2. instalar el APK móvil nuevo con `-r`;
3. iniciar un viaje nuevo por UI;
4. comprobar que el monitoring inicia una sola vez;
5. probar finalización manual con red disponible;
6. probar un fallo de red durante finalización y comprobar que el viaje no desaparece localmente;
7. probar SOS manual;
8. probar una sola vez `DEBUG_TRIGGER_ACCIDENT` y confirmar el pipeline automático;
9. comprobar que tras finalización automática no se borra el estado de un viaje nuevo;
10. probar iniciar/finalizar/SOS desde el reloj con una sesión real.

## Pendiente no bloqueante conservado

En pruebas físicas anteriores se observó ocasionalmente `offline_queue_storage_unavailable` en el primer enqueue del SOS automático; el retry inmediato completó el pipeline end-to-end. La excepción original está ocultada por el mapeo genérico actual. No se modificó esa ruta sin evidencia concreta para evitar romper el SOS automático que ya estaba funcionando.
