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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.domain.history.RiderTripHistoryLocation
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import kotlin.math.abs

internal data class NormalizedTripRoutePoints(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val locationsPracticallyCoincide: Boolean
)

/** Normalizes only the persisted endpoints; it never generates route points. */
internal fun normalizeTripRoutePoints(
    start: RiderTripHistoryLocation,
    end: RiderTripHistoryLocation,
    padding: Float = ROUTE_PADDING
): NormalizedTripRoutePoints {
    require(padding >= 0f && padding < 0.5f)
    val longitudeSpan = abs(end.longitude - start.longitude)
    val latitudeSpan = abs(end.latitude - start.latitude)
    val practicallyCoincident = longitudeSpan < CLOSE_COORDINATE_DELTA && latitudeSpan < CLOSE_COORDINATE_DELTA
    if (practicallyCoincident) {
        return NormalizedTripRoutePoints(0.44f, 0.5f, 0.56f, 0.5f, true)
    }

    val minLongitude = minOf(start.longitude, end.longitude)
    val maxLongitude = maxOf(start.longitude, end.longitude)
    val minLatitude = minOf(start.latitude, end.latitude)
    val maxLatitude = maxOf(start.latitude, end.latitude)
    fun normalize(value: Double, min: Double, max: Double): Float =
        if (max == min) 0.5f else (padding + ((value - min) / (max - min)).toFloat() * (1f - 2f * padding))

    return NormalizedTripRoutePoints(
        startX = normalize(start.longitude, minLongitude, maxLongitude),
        startY = 1f - normalize(start.latitude, minLatitude, maxLatitude),
        endX = normalize(end.longitude, minLongitude, maxLongitude),
        endY = 1f - normalize(end.latitude, minLatitude, maxLatitude),
        locationsPracticallyCoincide = false
    )
}

@Composable
fun TripRoutePreview(
    startLocation: RiderTripHistoryLocation?,
    endLocation: RiderTripHistoryLocation?,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    val description = when {
        startLocation != null && endLocation != null -> "Trayecto aproximado entre inicio y fin"
        startLocation == null && endLocation != null -> "Inicio no disponible"
        startLocation != null -> "Final no disponible"
        else -> "Ubicación no disponible"
    }
    val normalized = if (startLocation != null && endLocation != null) {
        normalizeTripRoutePoints(startLocation, endLocation)
    } else {
        null
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
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(size.width * fraction, 0f), androidx.compose.ui.geometry.Offset(size.width * fraction, size.height), strokeWidth = 1f)
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, size.height * fraction), androidx.compose.ui.geometry.Offset(size.width, size.height * fraction), strokeWidth = 1f)
            }
            fun marker(x: Float, y: Float, color: Color) {
                val center = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y)
                drawCircle(color, radius = if (large) 15f else 12f, center = center)
                drawCircle(Color.White, radius = if (large) 5f else 4f, center = center)
            }
            if (normalized != null) {
                val start = androidx.compose.ui.geometry.Offset(size.width * normalized.startX, size.height * normalized.startY)
                val end = androidx.compose.ui.geometry.Offset(size.width * normalized.endX, size.height * normalized.endY)
                drawLine(MotoPrimaryBlue, start, end, strokeWidth = if (large) 7f else 5f)
                marker(normalized.startX, normalized.startY, MotoPrimaryDark)
                marker(normalized.endX, normalized.endY, MotoSuccess)
            } else {
                startLocation?.let { marker(0.5f, 0.5f, MotoPrimaryDark) }
                endLocation?.let { marker(0.5f, 0.5f, MotoSuccess) }
            }
        }
        Text(
            text = when {
                normalized?.locationsPracticallyCoincide == true -> "Inicio y fin prácticamente coinciden"
                normalized != null -> "A  Inicio     ─────     Fin  B\nTrayecto aproximado"
                startLocation == null && endLocation != null -> "B  Fin registrado\nInicio no disponible"
                startLocation != null -> "A  Inicio registrado\nFinal no disponible"
                else -> "Ubicación no disponible"
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
            color = MotoTextSecondary
        )
    }
}

private const val ROUTE_PADDING = 0.16f
private const val CLOSE_COORDINATE_DELTA = 0.00001
