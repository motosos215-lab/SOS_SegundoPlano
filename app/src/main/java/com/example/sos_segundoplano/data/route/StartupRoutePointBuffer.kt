package com.example.sos_segundoplano.data.route

import com.example.sos_segundoplano.domain.signals.LocationSample

/**
 * Small in-memory buffer used only during the startup GPS calibration window.
 * It prevents the first seconds of a real trip from disappearing while the quality gate settles.
 */
internal class StartupRoutePointBuffer(
    private val maxPoints: Int = DEFAULT_MAX_POINTS
) {
    init { require(maxPoints > 0) }

    private val points = LinkedHashMap<String, LocationSample>()

    fun add(sample: LocationSample) {
        val key = sample.routeBufferKey()
        points[key] = sample
        while (points.size > maxPoints) {
            val firstKey = points.keys.firstOrNull() ?: break
            points.remove(firstKey)
        }
    }

    fun drain(): List<LocationSample> {
        val result = points.values.sortedBy { it.timestampMillis }
        points.clear()
        return result
    }

    fun clear() = points.clear()

    fun contains(sample: LocationSample): Boolean = sample.routeBufferKey() in points

    private fun LocationSample.routeBufferKey(): String =
        "$timestampMillis|$latitude|$longitude"

    companion object {
        // 20 s normally means ~20 points; 64 leaves room for faster providers without unbounded RAM.
        const val DEFAULT_MAX_POINTS = 64
    }
}
