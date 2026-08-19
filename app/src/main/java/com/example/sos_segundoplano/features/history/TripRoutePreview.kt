package com.example.sos_segundoplano.features.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.domain.history.RiderTripHistoryLocation
import com.example.sos_segundoplano.domain.history.RiderTripRoutePoint
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class NormalizedTripRoutePoint(val x: Float, val y: Float)

/** Normalizes only real persisted route points; it never fabricates intermediate geometry. */
internal fun normalizeRealTripRoute(
    points: List<RiderTripRoutePoint>,
    padding: Float = ROUTE_PADDING
): List<NormalizedTripRoutePoint> {
    require(padding >= 0f && padding < 0.5f)
    val valid = points.filter {
        it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    }
    if (valid.isEmpty()) return emptyList()
    if (valid.size == 1) return listOf(NormalizedTripRoutePoint(0.5f, 0.5f))

    val minLongitude = valid.minOf { it.longitude }
    val maxLongitude = valid.maxOf { it.longitude }
    val minLatitude = valid.minOf { it.latitude }
    val maxLatitude = valid.maxOf { it.latitude }
    fun normalize(value: Double, min: Double, max: Double): Float =
        if (max == min) 0.5f else padding + ((value - min) / (max - min)).toFloat() * (1f - 2f * padding)

    return valid.map {
        NormalizedTripRoutePoint(
            x = normalize(it.longitude, minLongitude, maxLongitude),
            y = 1f - normalize(it.latitude, minLatitude, maxLatitude)
        )
    }
}

internal fun routeDistanceMeters(points: List<RiderTripRoutePoint>): Double {
    if (points.size < 2) return 0.0
    return points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }
}

internal fun formatRouteDistance(meters: Double): String = when {
    !meters.isFinite() || meters < 0.0 -> "No disponible"
    meters < 1_000.0 -> "${meters.toInt()} m"
    else -> String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1_000.0)
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val deltaLat = Math.toRadians(lat2 - lat1)
    val deltaLon = Math.toRadians(lon2 - lon1)
    val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

@Composable
fun TripRoutePreview(
    startLocation: RiderTripHistoryLocation?,
    endLocation: RiderTripHistoryLocation?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    routePoints: List<RiderTripRoutePoint> = emptyList(),
    isLoading: Boolean = false
) {
    val ordered = routePoints.sortedBy { it.sequence }
    val normalized = normalizeRealTripRoute(ordered)
    val description = when {
        normalized.size >= 2 -> "Recorrido real del viaje con ${normalized.size} puntos"
        isLoading -> "Cargando recorrido real"
        else -> "Recorrido real aún no disponible"
    }
    Box(
        modifier = modifier
            .height(if (large) 220.dp else 128.dp)
            .semantics { contentDescription = description }
            .testTag("trip_route_preview"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(MotoBackground, cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f))
            val gridColor = MotoPrimaryBlue.copy(alpha = 0.08f)
            repeat(4) { index ->
                val fraction = (index + 1) / 5f
                drawLine(gridColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), strokeWidth = 1f)
            }

            fun marker(point: NormalizedTripRoutePoint, color: Color) {
                val center = Offset(size.width * point.x, size.height * point.y)
                drawCircle(color, radius = if (large) 15f else 12f, center = center)
                drawCircle(Color.White, radius = if (large) 5f else 4f, center = center)
            }

            if (normalized.size >= 2) {
                val path = Path()
                normalized.forEachIndexed { index, point ->
                    val x = size.width * point.x
                    val y = size.height * point.y
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, MotoPrimaryBlue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (large) 7f else 5f))
                marker(normalized.first(), MotoPrimaryDark)
                marker(normalized.last(), MotoSuccess)
            } else if (normalized.size == 1) {
                marker(normalized.single(), MotoPrimaryDark)
            }
        }
        Text(
            text = when {
                normalized.size >= 2 -> "Inicio  •  recorrido GPS real  •  Fin"
                normalized.size == 1 -> "Primer punto GPS sincronizado"
                isLoading -> "Cargando recorrido real…"
                startLocation != null || endLocation != null -> "Ruta GPS aún no sincronizada"
                else -> "Recorrido no disponible"
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
            color = MotoTextSecondary
        )
    }
}

private const val ROUTE_PADDING = 0.12f
private const val EARTH_RADIUS_METERS = 6_371_000.0
