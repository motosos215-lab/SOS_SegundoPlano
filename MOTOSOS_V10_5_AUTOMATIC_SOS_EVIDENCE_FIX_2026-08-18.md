# MotoSOS v10.5 — Automatic SOS evidence retention fix

Fecha: 2026-08-18

## Diagnóstico

Se comparó el proyecto anterior suministrado (`MotoSOS-SegundoPlano-SOS-API-MapLibre-2026-08-18-v5`) contra el `SegundoPlano` actual.

El envío remoto automático, la cola durable y el endpoint móvil SOS siguen presentes en el proyecto actual. La regresión reproducible encontrada está antes del envío, en el motor de reglas.

El motor actual introdujo `physicalEvidenceRetentionNanos = 6 s`. En un patrón realista de caída:

1. free-fall,
2. impacto,
3. cambio de orientación,
4. inmovilidad posterior (~3 s),

la evaluación final puede ocurrir un poco después de los 6 s. Al llegar esa evaluación, la ventana de free-fall/impacto inicial ya había quedado fuera del historial correlacionado. El patrón deja de clasificarse como `Fall`, el riesgo final baja y el coordinador de falsos positivos no llega al countdown/SOS automático.

Esto coincide con el fallo reportado por Gradle en:

- `RuleEngineCoordinatorTest > possibleFallWithImmobilityIsHighWithoutIncidentsOrAlerts`

También se encontró una segunda regresión de configuración:

- `RuleEngineTest > lateWindowsIncrementTraceabilityAndHistoryIsBounded`

El constructor rechazaba configuraciones con un historial menor al `physicalEvidenceRetentionNanos` por una validación rígida, aunque el motor puede limitar de forma segura la ventana efectiva al historial disponible.

## Corrección aplicada

### 1. Evidencia física vuelve al horizonte estable

`RuleEngineConfig.physicalEvidenceRetentionNanos` pasa de 6 s a 15 s, igual al historial normal del motor.

Esto conserva suficiente contexto para relacionar el impacto/free-fall con la inmovilidad posterior.

### 2. Ventana efectiva segura

El motor usa:

`minOf(physicalEvidenceRetentionNanos, historyDurationNanos)`

Por lo tanto configuraciones de prueba o personalizadas con historial de 2 s siguen siendo válidas y sólo usan esos 2 s.

### 3. Se elimina la restricción inválida del constructor

Ya no se exige `physicalEvidenceRetentionNanos <= historyDurationNanos` durante la construcción. La limitación se realiza al evaluar.

## Lo que NO se revirtió

Se conservaron las mejoras actuales:

- inmovilidad sola no suma riesgo (`quieto = 0`),
- separación teléfono/reloj para evitar mezclar señales,
- aceleración dinámica del Wear,
- calibración GPS,
- SOS manual actual,
- retry inmediato y durable del SOS automático,
- IDs durables (`clientIncidentId`, `clientAlertRequestId`, `tripSessionKey`, `bundleKey`),
- MapLibre/OpenFreeMap,
- pantallas Rider/Monitor actuales.

## Resultado esperado

Un patrón coherente de caída conserva ahora toda su evidencia hasta completar la inmovilidad y puede alcanzar nuevamente:

`Fall + post-impact immobility -> High risk -> countdown -> automatic SOS`

Mientras que:

`stationary only -> risk 0`

sigue intacto.

## Validación recomendada en Windows

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"

.\gradlew.bat :app:testDebugUnitTest --tests "com.example.sos_segundoplano.data.rules.RuleEngineCoordinatorTest.possibleFallWithImmobilityIsHighWithoutIncidentsOrAlerts" --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.sos_segundoplano.domain.rules.RuleEngineTest.lateWindowsIncrementTraceabilityAndHistoryIsBounded" --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Después hacer prueba física de viaje + trigger automático y dejar terminar el countdown sin pulsar “Estoy bien”.
