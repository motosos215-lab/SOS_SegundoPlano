package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertDetail
import com.example.sos_segundoplano.domain.monitor.NotificationDeliveryAttemptId
import com.example.sos_segundoplano.features.monitor.MonitorAlertHistoryUiState
import com.example.sos_segundoplano.features.monitor.MonitorAlertsUiState
import com.example.sos_segundoplano.features.monitor.MonitorHomeScreen
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Rule
import org.junit.Test

class MonitorAlertsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun readyMonitorShowsCalmActiveState() {
        composeRule.setContent { SOS_SegundoPlanoTheme { MonitorHomeScreen(
            state = MonitorAlertsUiState.Ready,
            onRetry = {},
            onAcknowledge = {},
            onDecline = {},
            onLogout = {}
        ) } }
        composeRule.onNodeWithTag("monitor_home_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_ready_icon").assertIsDisplayed()
    }

    @Test fun pendingAlertShowsLoadingWithoutFakeMap() {
        composeRule.setContent { SOS_SegundoPlanoTheme { MonitorHomeScreen(
            state = MonitorAlertsUiState.Loading(NotificationDeliveryAttemptId("attempt-1")),
            onRetry = {},
            onAcknowledge = {},
            onDecline = {},
            onLogout = {}
        ) } }
        composeRule.onNodeWithTag("monitor_alert_loading").assertIsDisplayed()
    }

    @Test fun finalAlertUsesFriendlyLabelsAndDisablesConflictingActions() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Alert(NotificationDeliveryAttemptId("attempt-1"), MonitorAlertDetail(alert("Acknowledged", "CanAssist"))),
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    onLogout = {}
                )
            }
        }
        composeRule.onAllNodesWithText("Atendida").assertAny(hasText("Atendida"))
        composeRule.onNodeWithText("Puedo ayudar").assertIsDisplayed()
        composeRule.onNodeWithText("12/08/2026 10:51").assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_acknowledge_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("monitor_decline_button").assertIsNotEnabled()
    }

    @Test fun historyShowsLocalizedCardsAndReturnsCanonicalAttemptId() {
        var opened: String? = null
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Ready,
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    historyState = MonitorAlertHistoryUiState.Content(listOf(alert("Pending", "None"), alert("Declined", null))),
                    onRefresh = {},
                    onRefreshHistory = {},
                    onOpenHistoryAlert = { opened = it },
                    onLogout = {}
                )
            }
        }
        composeRule.onNodeWithText("Actualizar").assertIsDisplayed()
        composeRule.onNodeWithText("Historial").performClick()
        composeRule.onNodeWithText("Pendiente").assertIsDisplayed()
        composeRule.onNodeWithText("Sin respuesta").assertIsDisplayed()
        composeRule.onNodeWithText("Rechazada").assertIsDisplayed()
        composeRule.onNodeWithText("Pendiente").performClick()
        org.junit.Assert.assertEquals("attempt-1", opened)
    }

    private fun alert(status: String, response: String?) = MonitorAlertAcknowledgement(
        "id", "dispatch", "attempt-1", "incident", "trip", "contact", status, response, null,
        null, null, null, "2026-08-12T16:51:09Z", null
    )
}
