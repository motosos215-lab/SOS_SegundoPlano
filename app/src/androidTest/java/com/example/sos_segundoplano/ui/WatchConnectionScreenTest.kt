package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import com.example.sos_segundoplano.domain.wear.HeartRatePermissionState
import com.example.sos_segundoplano.domain.wear.VectorReading
import com.example.sos_segundoplano.domain.wear.WatchConnectionState
import com.example.sos_segundoplano.domain.wear.WatchDeclineReason
import com.example.sos_segundoplano.domain.wear.WatchDeviceSummary
import com.example.sos_segundoplano.domain.wear.WatchHandshakeInfo
import com.example.sos_segundoplano.domain.wear.WatchSensorSnapshot
import com.example.sos_segundoplano.domain.wear.WatchStatus
import com.example.sos_segundoplano.features.wear.WatchConnectionScreen
import com.example.sos_segundoplano.features.wear.WatchConnectionUiState
import com.example.sos_segundoplano.features.wear.WatchNotice
import com.example.sos_segundoplano.features.wear.WatchOperation
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WatchConnectionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val device = WatchDeviceSummary("Watch", true)
    private val handshake = WatchHandshakeInfo(
        appVersionName = "motosos-wear",
        appVersionCode = 12L,
        manufacturer = "Maker",
        model = "Model-X",
        wearOsApiLevel = 34
    )
    private val status = WatchStatus(
        batteryAvailable = true,
        batteryPercent = 72,
        chargingKnown = true,
        charging = false,
        accelerometerAvailable = true,
        gyroscopeAvailable = false,
        heartRateAvailable = true,
        heartRatePermission = HeartRatePermissionState.Granted,
        respondedAtEpochMs = 1_700_000_000_000L
    )
    private val snapshot = WatchSensorSnapshot(
        capturedAtEpochMs = 1_700_000_000_000L,
        accelerometerAvailable = true,
        accelerometer = VectorReading(1.0f, 2.0f, 3.0f),
        gyroscopeAvailable = false,
        gyroscope = null,
        heartRateAvailable = true,
        heartRatePermission = HeartRatePermissionState.Granted,
        heartRateBpm = 85f
    )

    @Test fun loadingStateDisablesAllButtons() {
        setScreen(WatchConnectionUiState(connection = WatchConnectionState.Loading))
        composeRule.onNodeWithTag("watch_connection_state").assertIsDisplayed()
        composeRule.onNodeWithTag("watch_status_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("watch_snapshot_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("watch_refresh_button").assertIsNotEnabled()
    }

    @Test fun indirectStateShowsHintAndKeepsDataRequestsDisabled() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.CompatibleNodeIndirect(device.copy(isNearby = false))
            )
        )
        composeRule.onNodeWithTag("watch_status_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("watch_snapshot_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("watch_refresh_button").assertIsEnabled()
    }

    @Test fun connectedStateEnablesRequestButtonsAndShowsDeviceInfo() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Connected(device, handshake),
                handshake = handshake
            )
        )
        composeRule.onNodeWithTag("watch_status_button").assertIsEnabled()
        composeRule.onNodeWithTag("watch_snapshot_button").assertIsEnabled()
        composeRule.onNodeWithTag("watch_device_info").performScrollTo().assertIsDisplayed()
    }

    @Test fun busyStateDisablesRefreshDuringHandshake() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Handshaking(device)
            )
        )
        composeRule.onNodeWithTag("watch_refresh_button").assertIsNotEnabled()
    }

    @Test fun refreshInvokesCallbackOnClick() {
        var ref = 0
        setScreen(
            WatchConnectionUiState(connection = WatchConnectionState.NoWearNodes),
            onRefresh = { ref++ }
        )
        composeRule.onNodeWithTag("watch_refresh_button").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, ref) }
    }

    @Test fun statusAndSnapshotButtonsInvokeCallbacksOnce() {
        var stat = 0
        var snap = 0
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Connected(device, handshake),
                handshake = handshake
            ),
            onRequestStatus = { stat++ },
            onRequestSnapshot = { snap++ }
        )
        composeRule.onNodeWithTag("watch_status_button").performClick()
        composeRule.onNodeWithTag("watch_snapshot_button").performClick()
        composeRule.runOnIdle { assertEquals(1, stat) }
        composeRule.runOnIdle { assertEquals(1, snap) }
    }

    @Test fun statusAndSensorsCardsRenderWhenReadingsAvailable() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Connected(device, handshake),
                handshake = handshake,
                status = status
            )
        )
        composeRule.onNodeWithTag("watch_status_info").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("watch_sensors_info").performScrollTo().assertIsDisplayed()
    }

    @Test fun lastReadingCardRendersWhenSnapshotAvailable() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Connected(device, handshake),
                handshake = handshake,
                status = status,
                snapshot = snapshot
            )
        )
        composeRule.onNodeWithTag("watch_last_reading").performScrollTo().assertIsDisplayed()
    }

    @Test fun noticeRendersAndDismissInvokesCallback() {
        var dismissed = 0
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Connected(device, handshake),
                handshake = handshake,
                notice = WatchNotice.Timeout(WatchOperation.Status)
            ),
            onDismissNotice = { dismissed++ }
        )
        composeRule.onNodeWithTag("watch_notice").assertIsDisplayed()
        composeRule.onNodeWithTag("watch_notice_dismiss").performClick()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun declinedNoticeShowsPermissionMessage() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Connected(device, handshake),
                handshake = handshake,
                notice = WatchNotice.Declined(WatchDeclineReason.PermissionDenied)
            )
        )
        composeRule.onNodeWithTag("watch_notice").assertIsDisplayed()
    }

    @Test fun nonNearbyStateShowsIndirectAndNoHandshakeData() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.CompatibleNodeIndirect(device.copy(isNearby = false))
            )
        )
        composeRule.onAllNodesWithTag("watch_device_info").assertCountEquals(0)
        composeRule.onAllNodesWithTag("watch_status_info").assertCountEquals(0)
    }

    @Test fun timeoutStateKeepsHandshakeVisibleAndDisablesDataRequests() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Timeout(device),
                handshake = handshake
            )
        )
        composeRule.onNodeWithTag("watch_status_button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("watch_refresh_button").performScrollTo().assertIsEnabled()
    }

    @Test fun protocolIncompatibleStateDisablesRequests() {
        setScreen(
            WatchConnectionUiState(connection = WatchConnectionState.ProtocolIncompatible(device))
        )
        composeRule.onNodeWithTag("watch_status_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("watch_snapshot_button").assertIsNotEnabled()
    }

    @Test fun errorStateDisablesDataRequestsButAllowsRetry() {
        setScreen(
            WatchConnectionUiState(
                connection = WatchConnectionState.Error(
                    com.example.sos_segundoplano.domain.wear.WatchConnectionErrorCode.SendFailed,
                    device
                )
            )
        )
        composeRule.onNodeWithTag("watch_status_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("watch_refresh_button").assertIsEnabled()
    }

    private fun setScreen(
        state: WatchConnectionUiState,
        onRequestStatus: () -> Unit = {},
        onRequestSnapshot: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onDismissNotice: () -> Unit = {}
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                WatchConnectionScreen(
                    state = state,
                    onBack = {},
                    onHomeSelected = {},
                    onProfileSelected = {},
                    onRefresh = onRefresh,
                    onRequestStatus = onRequestStatus,
                    onRequestSnapshot = onRequestSnapshot,
                    onDismissNotice = onDismissNotice
                )
            }
        }
    }
}
