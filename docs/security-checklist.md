# Checklist De Seguridad

## Android

- Backups desactivados para evitar extraccion de datos sensibles.
- Reglas de backup y data extraction cerradas por defecto.
- Permisos deben solicitarse solo cuando el flujo los requiera.
- Los datos sensibles locales deben cifrarse antes de persistirse.
- Las acciones criticas deben registrarse en bitacora de auditoria.

## Codigo

- No registrar tokens, ubicaciones sensibles, payloads personales o secretos en logs.
- Validar payloads antes de procesarlos o enviarlos.
- Manejar errores sin exponer informacion interna.
- Evitar duplicados en eventos offline e incidentes.

## CI/CD

- Ejecutar pruebas unitarias en cada push y pull request.
- Ejecutar Android Lint en cada push y pull request.
- Escanear secretos antes de integrar cambios.
- Revisar dependencias antes de releases.
