package com.example.sos_segundoplano.domain.trip

data class TripLocalSummary(
    val durationText: String?
)

class TripLocalSummaryFactory(
    private val clock: ElapsedRealtimeClock
) {
    fun capture(timingState: TripTimingState): TripLocalSummary = TripLocalSummary(
        durationText = TripDurationFormatter.format(timingState, clock.nowMillis())
    )
}
