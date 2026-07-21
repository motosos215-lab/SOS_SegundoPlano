# Estrategia De Testing

## Niveles

- Unitarias: reglas de negocio, validadores, calculo de riesgo y casos de uso.
- Integracion local: repositorios, almacenamiento local, cola offline y serializacion.
- Instrumentadas: permisos Android, UI Compose, servicios en segundo plano y flujos dependientes del sistema.
- Manuales controladas: Bluetooth, Wear OS, sensores reales, notificaciones y escenarios de perdida de red.

## Cobertura por epica

- EPIC-06: vinculacion movil, QR, codigo manual y estado de dispositivos.
- EPIC-10: pantallas smartwatch, SOS manual, falso positivo y viaje activo.
- EPIC-13: segundo plano, sensores, preprocesamiento, motor de reglas y cola offline.
- EPIC-18: seguridad, privacidad, cifrado, auditoria y control de acceso.
- EPIC-19: pruebas web, moviles, smartwatch, backend e incidentes.
- EPIC-21: permisos, roles, revocacion y validaciones de acceso.

## Evidencia esperada

Cada prueba funcional debe registrar:

- entrada o precondicion
- resultado esperado
- resultado obtenido
- estado: aprobado, fallido o pendiente
- evidencia: reporte, captura, log o artifact de CI

## Convencion de pruebas

- `app/src/test`: pruebas unitarias rapidas sin Android runtime.
- `app/src/androidTest`: pruebas con dispositivo o emulador.
- Los nombres deben describir comportamiento, no implementacion interna.
