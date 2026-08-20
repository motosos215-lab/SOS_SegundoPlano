package com.example.sos_segundoplano.ui.maps

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSurface
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Public OpenFreeMap style. It is rendered by MapLibre Native inside MotoSOS and does not use a
 * Google Maps SDK key. Google Maps remains an external navigation target only.
 */
const val OPEN_FREE_MAP_LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"

data class MotoMapPoint(
    val latitude: Double,
    val longitude: Double
)

@Composable
fun MotoBaseMap(
    center: MotoMapPoint,
    modifier: Modifier = Modifier,
    zoom: Double = 11.0
) {
    val safeCenter = center.takeIf(MotoMapPoint::isValid) ?: return
    NativeMotoMap(
        modifier = modifier,
        renderKey = "base:${safeCenter.latitude}:${safeCenter.longitude}:$zoom"
    ) { map, _ ->
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(safeCenter.latitude, safeCenter.longitude))
            .zoom(zoom)
            .build()
    }
}

@Composable
fun MotoSinglePointMap(
    point: MotoMapPoint,
    modifier: Modifier = Modifier,
    markerColor: Color = MotoAlert,
    zoom: Double = 16.0
) {
    val safePoint = point.takeIf(MotoMapPoint::isValid) ?: return
    val markerArgb = markerColor.toArgb()
    val surfaceArgb = MotoSurface.toArgb()
    NativeMotoMap(
        modifier = modifier,
        renderKey = "point:${safePoint.latitude}:${safePoint.longitude}:$markerArgb:$zoom"
    ) { map, style ->
        style.upsertPoint(
            prefix = "motosos-single-point",
            point = safePoint,
            fillColor = markerArgb,
            strokeColor = surfaceArgb,
            radius = 8f
        )
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(safePoint.latitude, safePoint.longitude))
            .zoom(zoom)
            .build()
    }
}

@Composable
fun MotoRouteMap(
    points: List<MotoMapPoint>,
    modifier: Modifier = Modifier,
    routeColor: Color = MotoPrimaryBlue,
    recenterToken: Int = 0
) {
    val valid = remember(points) { points.filter(MotoMapPoint::isValid) }
    if (valid.isEmpty()) return

    val routeArgb = routeColor.toArgb()
    val surfaceArgb = MotoSurface.toArgb()
    val startArgb = MotoPrimaryDark.toArgb()
    val endArgb = MotoSuccess.toArgb()
    val paddingPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val renderKey = remember(valid, routeArgb, paddingPx, recenterToken) {
        buildString {
            append("route:")
            append(routeArgb)
            append(':')
            append(paddingPx)
            append(':')
            append(recenterToken)
            valid.forEach {
                append(':')
                append(it.latitude)
                append(',')
                append(it.longitude)
            }
        }
    }

    NativeMotoMap(
        modifier = modifier,
        renderKey = renderKey
    ) { map, style ->
        style.upsertRoute(
            points = valid,
            routeColor = routeArgb,
            startColor = startArgb,
            endColor = endArgb,
            strokeColor = surfaceArgb
        )
        if (valid.size == 1) {
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(valid.first().latitude, valid.first().longitude))
                .zoom(16.0)
                .build()
        } else {
            val bounds = LatLngBounds.Builder().apply {
                valid.forEach { include(LatLng(it.latitude, it.longitude)) }
            }.build()
            map.getCameraForLatLngBounds(
                bounds,
                intArrayOf(paddingPx, paddingPx, paddingPx, paddingPx)
            )?.let { map.cameraPosition = it }
        }
    }
}

@Composable
fun MotoIncidentResponseMap(
    monitorPoint: MotoMapPoint,
    incidentPoint: MotoMapPoint,
    routePoints: List<MotoMapPoint>,
    modifier: Modifier = Modifier,
    routeColor: Color = MotoPrimaryBlue,
    monitorColor: Color = MotoPrimaryBlue,
    incidentColor: Color = MotoAlert,
    recenterToken: Int = 0
) {
    val safeMonitor = monitorPoint.takeIf(MotoMapPoint::isValid) ?: return
    val safeIncident = incidentPoint.takeIf(MotoMapPoint::isValid) ?: return
    val safeRoute = remember(routePoints) { routePoints.filter(MotoMapPoint::isValid) }
    val routeArgb = routeColor.toArgb()
    val monitorArgb = monitorColor.toArgb()
    val incidentArgb = incidentColor.toArgb()
    val surfaceArgb = MotoSurface.toArgb()
    val paddingPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    val renderKey = remember(safeMonitor, safeIncident, safeRoute, recenterToken) {
        buildString {
            append("incident-response:")
            append(recenterToken)
            append(':').append(safeMonitor.latitude).append(',').append(safeMonitor.longitude)
            append(':').append(safeIncident.latitude).append(',').append(safeIncident.longitude)
            safeRoute.forEach { append(':').append(it.latitude).append(',').append(it.longitude) }
        }
    }

    NativeMotoMap(modifier = modifier, renderKey = renderKey) { map, style ->
        style.upsertIncidentResponse(
            routePoints = safeRoute,
            monitorPoint = safeMonitor,
            incidentPoint = safeIncident,
            routeColor = routeArgb,
            monitorColor = monitorArgb,
            incidentColor = incidentArgb,
            strokeColor = surfaceArgb
        )
        val framingPoints = buildList {
            add(safeMonitor)
            add(safeIncident)
            addAll(safeRoute)
        }
        if (safeMonitor == safeIncident && safeRoute.isEmpty()) {
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(safeIncident.latitude, safeIncident.longitude))
                .zoom(17.0)
                .build()
        } else {
            val bounds = LatLngBounds.Builder().apply {
                framingPoints.forEach { include(LatLng(it.latitude, it.longitude)) }
            }.build()
            map.getCameraForLatLngBounds(
                bounds,
                intArrayOf(paddingPx, paddingPx, paddingPx, paddingPx)
            )?.let { fittedCamera ->
                map.cameraPosition = if (fittedCamera.zoom > INCIDENT_RESPONSE_MAX_AUTO_ZOOM) {
                    CameraPosition.Builder(fittedCamera)
                        .zoom(INCIDENT_RESPONSE_MAX_AUTO_ZOOM)
                        .build()
                } else {
                    fittedCamera
                }
            }
        }
    }
}

@Composable
private fun NativeMotoMap(
    modifier: Modifier,
    renderKey: Any,
    configure: (MapLibreMap, Style) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestConfigure by rememberUpdatedState(configure)
    var styleReady by remember { mutableStateOf(false) }
    var loadTimedOut by remember { mutableStateOf(false) }

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }

    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        var destroyed = false

        fun startIfNeeded() {
            if (!started && !destroyed) {
                mapView.onStart()
                started = true
            }
        }

        fun resumeIfNeeded() {
            if (!resumed && !destroyed) {
                startIfNeeded()
                mapView.onResume()
                resumed = true
            }
        }

        fun pauseIfNeeded() {
            if (resumed && !destroyed) {
                mapView.onPause()
                resumed = false
            }
        }

        fun stopIfNeeded() {
            pauseIfNeeded()
            if (started && !destroyed) {
                mapView.onStop()
                started = false
            }
        }

        fun destroyIfNeeded() {
            if (!destroyed) {
                stopIfNeeded()
                mapView.onDestroy()
                destroyed = true
            }
        }

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startIfNeeded()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumeIfNeeded()

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startIfNeeded()
                Lifecycle.Event.ON_RESUME -> resumeIfNeeded()
                Lifecycle.Event.ON_PAUSE -> pauseIfNeeded()
                Lifecycle.Event.ON_STOP -> stopIfNeeded()
                Lifecycle.Event.ON_DESTROY -> destroyIfNeeded()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)

        onDispose {
            lifecycle.removeObserver(observer)
            destroyIfNeeded()
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setStyle(OPEN_FREE_MAP_LIBERTY_STYLE) { style ->
                styleReady = true
                loadTimedOut = false
                latestConfigure(map, style)
            }
        }
        onDispose { }
    }

    LaunchedEffect(styleReady, renderKey) {
        if (!styleReady) return@LaunchedEffect
        mapView.getMapAsync { map ->
            map.style?.let { style -> latestConfigure(map, style) }
        }
    }

    LaunchedEffect(styleReady) {
        if (styleReady) {
            loadTimedOut = false
            return@LaunchedEffect
        }
        delay(MAP_LOAD_TIMEOUT_MS)
        if (!styleReady) loadTimedOut = true
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )
        if (!styleReady) {
            MapStatusOverlay(
                if (loadTimedOut) {
                    "No se pudo cargar OpenFreeMap. Revisa tu conexión a internet."
                } else {
                    "Cargando mapa…"
                }
            )
        }
    }
}

private fun Style.upsertPoint(
    prefix: String,
    point: MotoMapPoint,
    fillColor: Int,
    strokeColor: Int,
    radius: Float
) {
    val sourceId = "$prefix-source"
    val layerId = "$prefix-layer"
    val geoJson = pointGeoJson(point)
    val source = getSource(sourceId) as? GeoJsonSource
    if (source == null) {
        addSource(GeoJsonSource(sourceId, geoJson))
    } else {
        source.setGeoJson(geoJson)
    }
    val layer = getLayer(layerId) as? CircleLayer
    if (layer == null) {
        addLayer(
            CircleLayer(layerId, sourceId).withProperties(
                circleColor(fillColor),
                circleRadius(radius),
                circleStrokeColor(strokeColor),
                circleStrokeWidth(3f)
            )
        )
    } else {
        layer.setProperties(
            circleColor(fillColor),
            circleRadius(radius),
            circleStrokeColor(strokeColor),
            circleStrokeWidth(3f)
        )
    }
}

private fun Style.upsertRoute(
    points: List<MotoMapPoint>,
    routeColor: Int,
    startColor: Int,
    endColor: Int,
    strokeColor: Int
) {
    // A two-point degenerate LineString keeps the source valid when only one GPS point exists.
    val safeLinePoints = if (points.size == 1) listOf(points.first(), points.first()) else points
    val routeGeoJson = routeGeoJson(safeLinePoints)
    val routeSource = getSource(ROUTE_SOURCE_ID) as? GeoJsonSource
    if (routeSource == null) {
        addSource(GeoJsonSource(ROUTE_SOURCE_ID, routeGeoJson))
    } else {
        routeSource.setGeoJson(routeGeoJson)
    }

    val routeLayer = getLayer(ROUTE_LAYER_ID) as? LineLayer
    if (routeLayer == null) {
        addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor(routeColor),
                lineWidth(5f),
                lineOpacity(if (points.size >= 2) 1f else 0f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND)
            )
        )
    } else {
        routeLayer.setProperties(
            lineColor(routeColor),
            lineWidth(5f),
            lineOpacity(if (points.size >= 2) 1f else 0f),
            lineCap(LINE_CAP_ROUND),
            lineJoin(LINE_JOIN_ROUND)
        )
    }

    upsertPoint(
        prefix = "motosos-route-start",
        point = points.first(),
        fillColor = startColor,
        strokeColor = strokeColor,
        radius = 7f
    )
    upsertPoint(
        prefix = "motosos-route-end",
        point = points.last(),
        fillColor = endColor,
        strokeColor = strokeColor,
        radius = 7f
    )
}

private fun Style.upsertIncidentResponse(
    routePoints: List<MotoMapPoint>,
    monitorPoint: MotoMapPoint,
    incidentPoint: MotoMapPoint,
    routeColor: Int,
    monitorColor: Int,
    incidentColor: Int,
    strokeColor: Int
) {
    val linePoints = if (routePoints.size >= 2) routePoints else listOf(monitorPoint, monitorPoint)
    val routeGeoJson = routeGeoJson(linePoints)
    val routeSource = getSource(INCIDENT_RESPONSE_ROUTE_SOURCE_ID) as? GeoJsonSource
    if (routeSource == null) {
        addSource(GeoJsonSource(INCIDENT_RESPONSE_ROUTE_SOURCE_ID, routeGeoJson))
    } else {
        routeSource.setGeoJson(routeGeoJson)
    }

    val routeHaloLayer = getLayer(INCIDENT_RESPONSE_ROUTE_HALO_LAYER_ID) as? LineLayer
    if (routeHaloLayer == null) {
        addLayer(
            LineLayer(INCIDENT_RESPONSE_ROUTE_HALO_LAYER_ID, INCIDENT_RESPONSE_ROUTE_SOURCE_ID).withProperties(
                lineColor(strokeColor),
                lineWidth(11f),
                lineOpacity(if (routePoints.size >= 2) 0.86f else 0f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND)
            )
        )
    } else {
        routeHaloLayer.setProperties(
            lineColor(strokeColor),
            lineWidth(11f),
            lineOpacity(if (routePoints.size >= 2) 0.86f else 0f),
            lineCap(LINE_CAP_ROUND),
            lineJoin(LINE_JOIN_ROUND)
        )
    }

    val routeLayer = getLayer(INCIDENT_RESPONSE_ROUTE_LAYER_ID) as? LineLayer
    if (routeLayer == null) {
        addLayer(
            LineLayer(INCIDENT_RESPONSE_ROUTE_LAYER_ID, INCIDENT_RESPONSE_ROUTE_SOURCE_ID).withProperties(
                lineColor(routeColor),
                lineWidth(7f),
                lineOpacity(if (routePoints.size >= 2) 0.94f else 0f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND)
            )
        )
    } else {
        routeLayer.setProperties(
            lineColor(routeColor),
            lineWidth(7f),
            lineOpacity(if (routePoints.size >= 2) 0.94f else 0f),
            lineCap(LINE_CAP_ROUND),
            lineJoin(LINE_JOIN_ROUND)
        )
    }

    upsertPoint(
        prefix = "motosos-monitor-current",
        point = monitorPoint,
        fillColor = monitorColor,
        strokeColor = strokeColor,
        radius = 8f
    )
    upsertPoint(
        prefix = "motosos-incident-target",
        point = incidentPoint,
        fillColor = incidentColor,
        strokeColor = strokeColor,
        radius = 9f
    )
}

@Composable
private fun MapStatusOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MotoSurface.copy(alpha = 0.94f))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = MotoPrimaryDark)
    }
}

private fun MotoMapPoint.isValid(): Boolean =
    latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0)

private fun pointGeoJson(point: MotoMapPoint): String =
    """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]},"properties":{}}]}"""

private fun routeGeoJson(points: List<MotoMapPoint>): String {
    val coordinates = points.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordinates]},"properties":{}}]}"""
}

private const val ROUTE_SOURCE_ID = "motosos-route-source"
private const val ROUTE_LAYER_ID = "motosos-route-layer"
private const val INCIDENT_RESPONSE_ROUTE_SOURCE_ID = "motosos-incident-response-route-source"
private const val INCIDENT_RESPONSE_ROUTE_HALO_LAYER_ID = "motosos-incident-response-route-halo-layer"
private const val INCIDENT_RESPONSE_ROUTE_LAYER_ID = "motosos-incident-response-route-layer"
private const val INCIDENT_RESPONSE_MAX_AUTO_ZOOM = 14.5
private const val MAP_LOAD_TIMEOUT_MS = 12_000L
