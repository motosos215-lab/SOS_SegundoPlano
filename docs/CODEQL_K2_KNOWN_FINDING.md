# CodeQL / Kotlin K2 — finding histórico de PendingIntent

En el endurecimiento DevSecOps previo se observó un finding `java/android/implicit-pendingintents` sobre código Kotlin/K2 donde el Intent ya estaba fijado explícitamente mediante `ComponentName(appContext, MainActivity::class.java)` y el `PendingIntent` usaba `FLAG_IMMUTABLE`.

Este documento no suprime findings futuros. Su objetivo es evitar dos errores:

1. modificar código seguro sólo para satisfacer un falso positivo;
2. ignorar un finding nuevo asumiendo que es el mismo.

Ante una nueva alerta, inspeccionar el sink/source actual, comprobar explicitud + immutability y documentar evidencia en el PR.

Seguimiento conocido: `github/codeql#20153` y `github/codeql#21915`.
