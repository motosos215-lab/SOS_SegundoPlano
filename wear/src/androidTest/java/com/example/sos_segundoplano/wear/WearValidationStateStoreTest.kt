package com.example.sos_segundoplano.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.wearable.DataMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearValidationStateStoreTest {
    @Before
    fun setUp() {
        WearValidationStateStore.resetForTest()
    }

    @After
    fun tearDown() {
        WearValidationStateStore.resetForTest()
    }

    @Test
    fun initialStateIsUnconfirmed() {
        assertNull(WearValidationStateStore.state.value)
    }

    @Test
    fun confirmedCountdownStoresCompleteStatus() {
        val status = countdownStatus()

        WearValidationStateStore.confirm(status)

        assertEquals(status, WearValidationStateStore.state.value)
    }

    @Test
    fun confirmedNonCountdownStateIsPreserved() {
        val status = WearDataLayerProtocol.ValidationStatus(
            state = "safe_confirmed",
            sessionId = 101L,
            assessmentId = 202L,
            windowId = 303L,
            messageId = "phone-validation-safe-001",
            updatedAtElapsedRealtimeNanos = 404L
        )

        WearValidationStateStore.confirm(status)

        assertEquals(status, WearValidationStateStore.state.value)
    }

    @Test
    fun newConfirmedPhoneStatusReplacesPreviousStatus() {
        WearValidationStateStore.confirm(countdownStatus())
        val confirmedSafe = WearDataLayerProtocol.ValidationStatus(
            state = "safe_confirmed",
            sessionId = 101L,
            assessmentId = 202L
        )

        WearValidationStateStore.confirm(confirmedSafe)

        assertEquals(confirmedSafe, WearValidationStateStore.state.value)
    }

    @Test
    fun validCountdownPayloadDecodesStrictly() {
        val decoded = WearDataLayerProtocol.decodeValidationStatusOrNull(validCountdownPayload())

        assertEquals(countdownStatus(), decoded)
    }

    @Test
    fun malformedPayloadReturnsNull() {
        assertNull(WearDataLayerProtocol.decodeValidationStatusOrNull(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun wrongProtocolVersionReturnsNull() {
        assertNull(WearDataLayerProtocol.decodeValidationStatusOrNull(validCountdownPayload(protocolVersion = 2)))
    }

    @Test
    fun countdownWithoutSessionIdIsRejected() {
        assertNull(WearDataLayerProtocol.decodeValidationStatusOrNull(validCountdownPayload(includeSessionId = false)))
    }

    @Test
    fun countdownWithoutAssessmentIdIsRejected() {
        assertNull(WearDataLayerProtocol.decodeValidationStatusOrNull(validCountdownPayload(includeAssessmentId = false)))
    }

    @Test
    fun countdownWithNegativeRemainingMillisIsRejected() {
        assertNull(WearDataLayerProtocol.decodeValidationStatusOrNull(validCountdownPayload(remainingMillis = -1L)))
    }

    @Test
    fun countdownWithZeroRemainingMillisIsAccepted() {
        val decoded = WearDataLayerProtocol.decodeValidationStatusOrNull(validCountdownPayload(remainingMillis = 0L))

        assertEquals(0L, decoded?.remainingMillis)
    }

    @Test
    fun invalidPayloadDoesNotOverwriteLastConfirmedCountdown() {
        val previous = countdownStatus()
        WearValidationStateStore.confirm(previous)

        val accepted = WearValidationStatusReceiver.handle(validCountdownPayload(protocolVersion = 2))

        assertFalse(accepted)
        assertEquals(previous, WearValidationStateStore.state.value)
    }

    private fun countdownStatus(remainingMillis: Long = 15_000L) = WearDataLayerProtocol.ValidationStatus(
        state = "countdown_active",
        sessionId = 101L,
        assessmentId = 202L,
        windowId = 303L,
        remainingMillis = remainingMillis,
        reason = "impact_detected",
        messageId = "phone-validation-countdown-001",
        updatedAtElapsedRealtimeNanos = 404L
    )

    private fun validCountdownPayload(
        protocolVersion: Int = WearDataLayerProtocol.PROTOCOL_VERSION,
        includeSessionId: Boolean = true,
        includeAssessmentId: Boolean = true,
        remainingMillis: Long = 15_000L
    ): ByteArray = DataMap().apply {
        putInt("protocolVersion", protocolVersion)
        putString("state", "countdown_active")
        if (includeSessionId) putLong("sessionId", 101L)
        if (includeAssessmentId) putLong("assessmentId", 202L)
        putLong("windowId", 303L)
        putLong("remainingMillis", remainingMillis)
        putString("reason", "impact_detected")
        putString("messageId", "phone-validation-countdown-001")
        putLong("updatedAtElapsedRealtimeNanos", 404L)
    }.toByteArray()
}
