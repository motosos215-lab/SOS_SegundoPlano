# Estrategia de testing

## Niveles

- **Unitarias:** reglas, ML, serialización, idempotencia y coordinadores offline.
- **Integración local:** Room/SharedPreferences, cifrado, stores, recuperación y WorkManager scheduling.
- **Instrumentadas:** permisos Android, Compose, foreground service, notificaciones y recuperación de proceso.
- **Manual en dispositivo:** sensores, GPS, Wear OS, Firebase/FCM, pérdida/recuperación de Wi‑Fi y datos móviles.

## Flujos críticos obligatorios

### SOS manual offline

1. Rider con viaje activo.
2. desactivar Wi‑Fi y datos.
3. enviar SOS manual.
4. confirmar modal de alerta guardada.
5. activar sólo datos móviles.
6. comprobar envío con los mismos client IDs y recepción backend/Monitor.

### SOS automático offline

1. viaje activo + monitoreo.
2. perder Internet.
3. disparar evaluación automática y dejar vencer countdown.
4. comprobar persistencia durable/modal.
5. recuperar Wi‑Fi o datos móviles.
6. comprobar `POST /api/v1/mobile/sos-alerts` y estado `Sent`.

### Cierre de viaje offline

1. iniciar viaje con remoto confirmado.
2. perder Internet.
3. pulsar **Terminar viaje**.
4. comprobar que la sesión local finaliza y aparece aviso de cierre guardado.
5. Inicio debe mostrar `1 cierre de viaje` pendiente.
6. recuperar datos móviles o Wi‑Fi.
7. comprobar `POST /api/v1/trips/{id}/finish` y que Inicio vuelva a **Todo sincronizado** cuando tampoco existan SOS/ruta pendientes.

### Ruta por lotes

- crear puntos GPS pendientes sin red;
- recuperar conexión;
- verificar batch con UUID/sequence estables;
- eliminar filas sólo tras envelope exitoso.

## Tests focalizados relevantes

- `UserTripFinishCoordinatorTest`
- `RiderSyncUiStateTest`
- tests de recuperación SOS manual/automático
- tests del parser/modelo ML v1.1
- tests de vinculación Monitor QR/código

## Comandos

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
.\gradlew.bat :app:lintDebug --console=plain --no-daemon --no-configuration-cache --max-workers=2
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon
```

Focal:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.example.sos_segundoplano.data.remote.trip.UserTripFinishCoordinatorTest" `
  --tests "com.example.sos_segundoplano.features.trip.RiderSyncUiStateTest" `
  --console=plain --no-daemon
```

Instrumentadas, con dispositivo/emulador:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain --no-daemon
```

## Evidencia

Cada prueba funcional debe registrar precondición, resultado esperado, resultado obtenido, estado y evidencia (reporte/captura/log sanitizado/artifact CI).
