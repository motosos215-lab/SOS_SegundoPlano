# MotoSOS v10 — Rider contacto + Monitor responsive/perfil/mapa

Fecha: 2026-08-18

## Alcance

Esta versión parte de v9.1 y aplica únicamente cambios de UI/lectura de contacto y encuadre del mapa. No modifica la lógica de detección de accidente, GPS, persistencia de SOS, route points ni protocolo Wear.

## Rider — contacto de emergencia real

- Se agregó `GET /api/v1/emergency-contacts` al cliente Retrofit.
- Se agregó `list()` a datasource y repositorio, conservando Bearer, validación de rol Rider y un único refresh ante 401.
- Inicio carga los contactos reales y selecciona el contacto preferente priorizando contacto activo, primario, vinculado y prioridad.
- La tarjeta muestra nombre, estado de vinculación, relación, teléfono y correo cuando existen.
- Al regresar a la app se vuelve a consultar el contacto para reflejar cambios hechos desde web.
- Se agregó prueba focal del path `/api/v1/emergency-contacts` y mapeo de `data.contacts`.

## Rider — Editar monitor en la web

- Se agregó botón `Editar monitor en la web`.
- La URL se configura mediante:

```properties
MOTOSOS_WEB_CONTACTS_URL=https://TU-FRONTEND/contactos
```

- El repositorio/contrato entregado no contiene la URL exacta del frontend. Por seguridad no se inventó un dominio.
- Si la propiedad está vacía, la app muestra un aviso en lugar de abrir una URL incorrecta.
- Al volver del navegador, Inicio refresca el contacto.

## Rider — reloj desde Inicio

- La tarjeta de dispositivo muestra `Reloj Wear OS`.
- Al tocarla continúa abriendo el flujo local existente de conexión/sensores del reloj.
- No se agregó QR ni otro mecanismo de pairing.

## Monitor — navegación y adaptación de pantalla

- Se sustituyó la barra inferior pequeña personalizada por `NavigationBar`/`NavigationBarItem` de Material 3.
- Los cinco destinos permanecen: Inicio, Incidentes, Mapa, Historial y Perfil.
- Iconos y texto ajustan tamaño en pantallas estrechas.
- Padding general responde al ancho de pantalla.
- Altura de mapas responde a ancho/alto del dispositivo.
- Acciones superiores se apilan en teléfonos estrechos para evitar recorte.
- Botones `Actualizar ruta` / `Centrar recorrido` también se apilan cuando falta ancho.
- Filas de datos cambian a formato vertical en pantallas estrechas; en pantallas normales usan columnas con proporciones para evitar que correo/teléfono se salgan de la tarjeta.

## Monitor — Perfil y cerrar sesión

- `Cerrar sesión` queda únicamente dentro de `Perfil`.
- Inicio, alertas, mapa e historial no muestran el botón de logout.
- Perfil muestra datos reales de la sesión autenticada del Monitor:
  - nombre,
  - correo,
  - teléfono,
  - rol,
  - estado de la cuenta.
- Se agregaron pruebas Compose para comprobar que logout no existe en Inicio y aparece en Perfil junto con los datos del Monitor.

## Monitor — ruta visible y cámara menos cercana

- El encuadre sigue usando Monitor + incidente + geometría completa de la ruta.
- Se añadió un máximo de zoom automático de `14.5` para evitar que una ruta muy corta quede excesivamente acercada.
- El usuario todavía puede acercar manualmente después.
- La ruta usa una línea azul más gruesa con halo claro para mejorar contraste sobre OpenFreeMap.
- `Centrar recorrido` vuelve a aplicar este encuadre.

## Validaciones realizadas en este entorno

- XML app + wear: 24 archivos, 0 errores de parseo.
- No existe import explícito `androidx.compose.foundation.layout.weight` en las fuentes modificadas.
- Revisión con `kotlinc` no encontró errores de sintaxis; las referencias Android/Compose no pueden resolverse sin Android/Gradle classpath.
- Los 7 PNG protegidos permanecen sin cambios respecto de v9.1.
- `:app:compileDebugKotlin` no pudo ejecutarse aquí: el wrapper intenta descargar Gradle 9.4.1 y el entorno no puede resolver `services.gradle.org`.

## Validación recomendada en Windows

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Antes de probar `Editar monitor en la web`, configurar en `gradle.properties` la URL real del frontend MotoSOS.
