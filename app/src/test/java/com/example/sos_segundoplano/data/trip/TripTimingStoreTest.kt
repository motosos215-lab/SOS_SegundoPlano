package com.example.sos_segundoplano.data.trip

import com.example.sos_segundoplano.domain.trip.ElapsedRealtimeClock
import com.example.sos_segundoplano.domain.trip.TripDurationFormatter
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingClearResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTimingStoreTest {
    @Test fun timingIdentitySurvivesRecreationAndConditionalClearProtectsAnotherTrip() {
        val persistence = FakeTripTimingPersistence()
        val first = store(persistence, FakeElapsedRealtimeClock(1_000L), bootSessionId = 7L)
        first.beginConfirmedTrip("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val restored = store(persistence, FakeElapsedRealtimeClock(2_000L), bootSessionId = 7L)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", (restored.states.value as TripTimingState.Active).tripSessionKey)
        assertEquals(TripTimingClearResult.DifferentTrip, restored.clearIfMatches("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", (restored.states.value as TripTimingState.Active).tripSessionKey)
    }

    @Test fun legacyTimingIsNotClearedByIdentityTarget() {
        val store = store(FakeTripTimingPersistence(PersistedTripTiming(1_000L, 7L)), FakeElapsedRealtimeClock(2_000L), 7L)
        assertEquals(TripTimingClearResult.LegacyUncorrelated, store.clearIfMatches("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        assertTrue(store.states.value is TripTimingState.Active)
    }

    @Test fun matchingTimingIdentityClearsPersistedTiming() {
        val store = store(FakeTripTimingPersistence(), FakeElapsedRealtimeClock(1_000L), 7L)
        store.beginConfirmedTrip("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        assertEquals(TripTimingClearResult.Cleared, store.clearIfMatches("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        assertEquals(TripTimingState.Unknown, store.states.value)
    }
    @Test fun confirmedNewTripStartsAtZeroAndAdvancesFromMonotonicReference() {
        val clock = FakeElapsedRealtimeClock(10_000L)
        val store = store(clock = clock)

        store.beginConfirmedTrip()

        assertEquals("00:00:00", TripDurationFormatter.format(store.states.value, clock.nowMillis()))
        clock.advanceBy(12_037L)
        assertEquals("00:00:12", TripDurationFormatter.format(store.states.value, clock.nowMillis()))
    }

    @Test fun durationFormatsHoursMinutesAndSeconds() {
        val state = TripTimingState.Active(1_000L)

        assertEquals("02:15:33", TripDurationFormatter.format(state, 8_134_000L))
    }

    @Test fun recreationWithSameStoreKeepsOriginalStart() {
        val clock = FakeElapsedRealtimeClock(5_000L)
        val store = store(clock = clock)
        store.beginConfirmedTrip()
        val original = store.states.value
        clock.advanceBy(47_000L)

        assertEquals(original, store.states.value)
        assertEquals("00:00:47", TripDurationFormatter.format(store.states.value, clock.nowMillis()))
    }

    @Test fun newStoreDuringSameBootRestoresPersistedDuration() {
        val persistence = FakeTripTimingPersistence()
        val clock = FakeElapsedRealtimeClock(10_000L)
        store(persistence, clock, bootSessionId = 7L).beginConfirmedTrip()
        clock.advanceBy(65_000L)

        val restored = store(persistence, clock, bootSessionId = 7L)

        assertEquals("00:01:05", TripDurationFormatter.format(restored.states.value, clock.nowMillis()))
    }

    @Test fun finishClearsTimingAndNextTripGetsNewStart() {
        val persistence = FakeTripTimingPersistence()
        val clock = FakeElapsedRealtimeClock(2_000L)
        val store = store(persistence, clock)
        store.beginConfirmedTrip()
        val firstStart = (store.states.value as TripTimingState.Active).startedAtElapsedRealtimeMillis
        clock.advanceBy(9_000L)

        store.clear()

        assertEquals(TripTimingState.Unknown, store.states.value)
        assertNull(persistence.value)
        clock.advanceBy(1_000L)
        store.beginConfirmedTrip()
        val secondStart = (store.states.value as TripTimingState.Active).startedAtElapsedRealtimeMillis
        assertTrue(secondStart > firstStart)
        assertEquals("00:00:00", TripDurationFormatter.format(store.states.value, clock.nowMillis()))
    }

    @Test fun wallClockChangesCannotAffectMonotonicDuration() {
        val clock = FakeElapsedRealtimeClock(20_000L)
        val store = store(clock = clock)
        store.beginConfirmedTrip()
        var fakeWallClock = 1_000_000L
        fakeWallClock -= 500_000L
        clock.advanceBy(3_000L)

        assertEquals(500_000L, fakeWallClock)
        assertEquals("00:00:03", TripDurationFormatter.format(store.states.value, clock.nowMillis()))
    }

    @Test fun previousBootReferenceIsDiscardedInsteadOfProducingFalseDuration() {
        val persistence = FakeTripTimingPersistence(PersistedTripTiming(4_000L, bootSessionId = 3L))

        val restored = store(persistence, FakeElapsedRealtimeClock(8_000L), bootSessionId = 4L)

        assertEquals(TripTimingState.Unknown, restored.states.value)
        assertNull(persistence.value)
    }

    @Test fun futureOrNegativeMonotonicReferenceIsInvalid() {
        listOf(-1L, 9_000L).forEach { invalidStart ->
            val persistence = FakeTripTimingPersistence(PersistedTripTiming(invalidStart, bootSessionId = 5L))
            val restored = store(persistence, FakeElapsedRealtimeClock(8_000L), bootSessionId = 5L)

            assertEquals(TripTimingState.Unknown, restored.states.value)
            assertNull(persistence.value)
        }
    }

    @Test fun unavailableBootIdentityCannotRestorePersistedTiming() {
        val persistence = FakeTripTimingPersistence(PersistedTripTiming(2_000L, bootSessionId = 5L))

        val restored = DefaultTripTimingStore(
            persistence,
            FakeElapsedRealtimeClock(8_000L),
            BootSessionProvider { null }
        )

        assertEquals(TripTimingState.Unknown, restored.states.value)
        assertNull(persistence.value)
    }

    @Test fun remoteTripWithoutReliableLocalStartFormatsAsUnknown() {
        assertNull(TripDurationFormatter.format(TripTimingState.Unknown, 50_000L))
    }

    private fun store(
        persistence: FakeTripTimingPersistence = FakeTripTimingPersistence(),
        clock: FakeElapsedRealtimeClock,
        bootSessionId: Long = 1L
    ) = DefaultTripTimingStore(persistence, clock, BootSessionProvider { bootSessionId })
}

private class FakeElapsedRealtimeClock(initialMillis: Long) : ElapsedRealtimeClock {
    private var value = initialMillis
    override fun nowMillis(): Long = value
    fun advanceBy(millis: Long) {
        value += millis
    }
}

private class FakeTripTimingPersistence(
    var value: PersistedTripTiming? = null
) : TripTimingPersistence {
    override fun read(): PersistedTripTiming? = value
    override fun save(value: PersistedTripTiming): Boolean {
        this.value = value
        return true
    }
    override fun clear() {
        value = null
    }
}
