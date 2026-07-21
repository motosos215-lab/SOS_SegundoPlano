# Control De Configuracion

## Archivos versionados

Se versionan archivos necesarios para reproducir el proyecto:

- Gradle Wrapper
- scripts Gradle
- catalogo de versiones
- codigo fuente
- recursos Android
- pruebas
- documentacion
- workflows CI

## Archivos no versionados

No se versionan:

- `local.properties`
- `.gradle/`
- `build/`
- `app/build/`
- `.idea/`
- `*.jks`
- `*.keystore`
- `secrets.properties`
- `google-services.json`

## Secretos

Los secretos deben administrarse mediante variables del entorno local o secretos del proveedor CI/CD.

Nunca se deben guardar tokens, llaves privadas, contrasenas, certificados o credenciales dentro del repositorio.

## Ambientes futuros

Cuando existan servicios reales, separar configuracion por ambiente:

- `debug`: desarrollo local
- `staging`: pruebas integradas
- `release`: produccion

Cada ambiente debe tener endpoints, llaves y permisos controlados por configuracion externa.
