# Mapas en MotoSOS: MapLibre + OpenFreeMap

MotoSOS usa **MapLibre Native** para renderizar los mapas integrados y el estilo público **Liberty de OpenFreeMap** como mapa base.

No se requiere `MAPS_API_KEY` para los mapas integrados.

## Mapa base

El estilo configurado es:

```text
https://tiles.openfreemap.org/styles/liberty
```

Los mapas integrados se usan en:

- pestaña **Mapa** del Rider;
- detalle del viaje para dibujar el recorrido GPS real;
- detalle de alerta del Monitor para mostrar la ubicación de emergencia.

## Google Maps externo

MotoSOS conserva Google Maps sólo como destino externo para:

- iniciar navegación a una dirección;
- abrir una ubicación puntual;
- abrir un recorrido con origen, destino y algunos puntos intermedios.

Esta navegación se abre mediante intents/URLs y no forma parte del mapa integrado de MotoSOS.

## Requisitos

- permiso de Internet para descargar el estilo y tiles de OpenFreeMap;
- ubicación concedida cuando la pantalla necesite centrar al Rider;
- conexión disponible para cargar el mapa base.

El recorrido del historial sigue dependiendo de los puntos GPS persistidos en la API de Trips; MapLibre sólo los visualiza, no calcula ni inventa la ruta.
