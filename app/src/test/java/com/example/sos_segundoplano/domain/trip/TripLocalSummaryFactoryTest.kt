package com.example.sos_segundoplano.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripLocalSummaryFactoryTest {
    @Test fun activeTripFreezesFinalDurationBeforeTimingIsCleared() {
        var timingState: TripTimingState = TripTimingState.Active(10_000L)
        val summary = TripLocalSummaryFactory(ElapsedRealtimeClock { 57_000L }).capture(timingState)

        timingState = TripTimingState.Unknown

        assertEquals("00:00:47", summary.durationText)
        assertEquals(TripTimingState.Unknown, timingState)
        assertEquals("00:00:47", summary.durationText)
    }

    @Test fun unknownTimingDoesNotInventZeroDuration() {
        val summary = TripLocalSummaryFactory(ElapsedRealtimeClock { 57_000L })
            .capture(TripTimingState.Unknown)

        assertNull(summary.durationText)
    }
}
