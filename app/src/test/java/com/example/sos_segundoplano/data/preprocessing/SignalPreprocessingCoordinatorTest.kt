package com.example.sos_segundoplano.data.preprocessing

import com.example.sos_segundoplano.domain.preprocessing.DataQuality
import com.example.sos_segundoplano.domain.preprocessing.PreprocessingConfig
import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalState
import com.example.sos_segundoplano.domain.preprocessing.ProcessingTimestamp
import com.example.sos_segundoplano.domain.preprocessing.RawSignalEvent
import com.example.sos_segundoplano.domain.preprocessing.RawSignalValue
import com.example.sos_segundoplano.domain.preprocessing.SignalKind
import com.example.sos_segundoplano.domain.preprocessing.SignalSourceId
import com.example.sos_segundoplano.domain.preprocessing.TimeDomain
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalPreprocessingCoordinatorTest {
    @Test fun completeScenarioPublishesProcessedWindowAndStopsEmissions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = TestRawEvents()
        val store = InMemoryProcessedSignalStore()
        val coordinator = SignalPreprocessingCoordinator(
            rawEvents = events,
            processedStore = store,
            config = PreprocessingConfig(windowDurationNanos = 100L, windowStepNanos = 100L, minimumSamplesPerWindow = 1),
            dispatcher = dispatcher,
            sessionIdGenerator = { 7L }
        )

        coordinator.start()
        advanceUntilIdle()
        events.emit(vector(SignalKind.Accelerometer, 0L, 1.0, 0.0, 0.0))
        events.emit(vector(SignalKind.Gyroscope, 10L, 0.0, 1.0, 0.0))
        events.emit(scalar(SignalKind.Speed, 20L, 4.0))
        events.emit(vector(SignalKind.Accelerometer, 30L, 1.0, 0.0, 0.0, SignalSourceId.Wear, original = 5_000L))
        events.emit(vector(SignalKind.Accelerometer, 150L, 2.0, 0.0, 0.0))
        advanceUntilIdle()

        val ready = store.states.value as ProcessedSignalState.WindowReady
        assertEquals(7L, ready.window.sessionId)
        assertTrue(ready.window.features.vector.containsKey(SignalKind.Accelerometer))
        assertTrue(ready.window.features.scalar.containsKey(SignalKind.Speed))
        assertTrue(ready.window.synchronizedFrames.any { it.withinTolerance })
        assertEquals(DataQuality.Sufficient, ready.window.context.quality)

        coordinator.stop()
        advanceUntilIdle()
        events.emit(vector(SignalKind.Accelerometer, 300L, 9.0, 9.0, 9.0))
        advanceUntilIdle()

        assertEquals(ProcessedSignalState.Stopped, store.states.value)
    }

    @Test fun absentWearKeepsMobileProcessingWithoutFictitiousZeros() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = TestRawEvents()
        val store = InMemoryProcessedSignalStore()
        val coordinator = SignalPreprocessingCoordinator(
            events,
            store,
            PreprocessingConfig(windowDurationNanos = 100L, windowStepNanos = 100L, minimumSamplesPerWindow = 1),
            dispatcher,
            sessionIdGenerator = { 8L }
        )

        coordinator.start()
        advanceUntilIdle()
        events.emit(vector(SignalKind.Accelerometer, 0L, 1.0, 2.0, 3.0))
        events.emit(vector(SignalKind.Gyroscope, 10L, 1.0, 1.0, 1.0))
        events.emit(vector(SignalKind.Accelerometer, 120L, 2.0, 2.0, 2.0))
        advanceUntilIdle()

        val ready = store.states.value as ProcessedSignalState.WindowReady
        assertEquals(0.0, ready.window.context.wearCoverageRatio, 0.0001)
        assertTrue(ready.window.synchronizedFrames.all { it.wearSample == null })
        assertFalse(ready.window.samples.any { (it.sample.filteredValue as? RawSignalValue.Vector)?.let { v -> v.x == 0.0 && v.y == 0.0 && v.z == 0.0 } == true })
    }

    @Test fun startAndStopAreIdempotentAndNewTripClearsPreviousData() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = TestRawEvents()
        val store = InMemoryProcessedSignalStore()
        var session = 0L
        val coordinator = SignalPreprocessingCoordinator(
            events,
            store,
            PreprocessingConfig(windowDurationNanos = 100L, windowStepNanos = 100L, minimumSamplesPerWindow = 1),
            dispatcher,
            sessionIdGenerator = { ++session }
        )

        coordinator.start()
        coordinator.start()
        advanceUntilIdle()
        events.emit(vector(SignalKind.Accelerometer, 0L, 1.0, 0.0, 0.0))
        events.emit(vector(SignalKind.Accelerometer, 150L, 1.0, 0.0, 0.0))
        advanceUntilIdle()
        val first = (store.states.value as ProcessedSignalState.WindowReady).window
        coordinator.stop()
        coordinator.stop()
        advanceUntilIdle()

        coordinator.start()
        advanceUntilIdle()
        events.emit(vector(SignalKind.Accelerometer, 1_000L, 2.0, 0.0, 0.0))
        events.emit(vector(SignalKind.Gyroscope, 1_010L, 2.0, 0.0, 0.0))
        events.emit(vector(SignalKind.Accelerometer, 1_150L, 2.0, 0.0, 0.0))
        advanceUntilIdle()
        val second = (store.states.value as ProcessedSignalState.WindowReady).window

        assertEquals(1L, first.sessionId)
        assertEquals(2L, second.sessionId)
        assertTrue(second.samples.none { it.sample.event.timestamp.phoneTimeNanos < 1_000L })
    }

    @Test fun processedStorePublishesLatestResultAndRawStreamTracksOverflow() = runTest {
        val stream = RawSignalEventStream(PreprocessingConfig(rawEventBufferCapacity = 1))
        stream.tryEmit(vector(SignalKind.Accelerometer, 0L, 1.0, 0.0, 0.0))
        stream.tryEmit(vector(SignalKind.Accelerometer, 1L, 1.0, 0.0, 0.0))
        stream.tryEmit(vector(SignalKind.Accelerometer, 2L, 1.0, 0.0, 0.0))

        assertTrue(stream.droppedEvents > 0L)
    }

    @Test fun preprocessingNamesDoNotExposeRiskOrAlerts() {
        val names = listOf(
            ProcessedSignalState.WindowReady::class.java.simpleName,
            DataQuality.Sufficient.name,
            SignalPreprocessingCoordinator::class.java.simpleName
        ).joinToString(" ")

        assertFalse(names.contains("RiskScore"))
        assertFalse(names.contains("CrashDetected"))
        assertFalse(names.contains("EmergencyEvent"))
        assertFalse(names.contains("Alert"))
    }

    private fun TestRawEvents.emit(event: RawSignalEvent) {
        tryEmit(event)
    }

    private fun vector(kind: SignalKind, time: Long, x: Double, y: Double, z: Double, source: SignalSourceId = SignalSourceId.Phone, original: Long = time) = RawSignalEvent(
        source,
        kind,
        SignalAvailability.Available,
        RawSignalValue.Vector(x, y, z),
        ProcessingTimestamp(
            phoneTimeNanos = time,
            originalTimestampNanos = original,
            originalTimeDomain = if (source == SignalSourceId.Wear) TimeDomain.WearElapsedRealtime else TimeDomain.PhoneElapsedRealtime,
            receivedAtPhoneNanos = if (source == SignalSourceId.Wear) time else null
        )
    )

    private fun scalar(kind: SignalKind, time: Long, value: Double) = RawSignalEvent(
        SignalSourceId.Phone,
        kind,
        SignalAvailability.Available,
        RawSignalValue.Scalar(value),
        ProcessingTimestamp(time, time, TimeDomain.PhoneWallClock)
    )
}

private class TestRawEvents : RawSignalEventSink {
    private val flow = MutableSharedFlow<RawSignalEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RawSignalEvent> = flow
    override var droppedEvents: Long = 0L

    override fun tryEmit(event: RawSignalEvent) {
        if (!flow.tryEmit(event)) droppedEvents++
    }
}
