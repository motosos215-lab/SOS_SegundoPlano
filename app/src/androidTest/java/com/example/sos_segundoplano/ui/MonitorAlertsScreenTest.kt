package com.example.sos_segundoplano.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertDetail
import com.example.sos_segundoplano.domain.monitor.MonitorAlertStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertIncidentStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertTripStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertDispatchStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertStatusLocation
import com.example.sos_segundoplano.domain.monitor.NotificationDeliveryAttemptId
import com.example.sos_segundoplano.features.monitor.MonitorAlertHistoryUiState
import com.example.sos_segundoplano.features.monitor.MonitorAlertsUiState
import com.example.sos_segundoplano.features.monitor.MonitorHomeScreen
import com.example.sos_segundoplano.features.monitor.MonitorProfileUiData
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
        composeRule.onNodeWithText("Resumen de alertas").assertIsDisplayed()
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

    @Test fun finalAlertShowsMessageAndAvailableTimesWithoutConflictingActions() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Alert(NotificationDeliveryAttemptId("attempt-1"), MonitorAlertDetail(alert(
                        status = "Acknowledged",
                        response = "CanAssist",
                        message = "Voy en camino",
                        viewedAtUtc = "2026-08-12T16:52:09Z",
                        acknowledgedAtUtc = "2026-08-12T16:53:09Z"
                    ))),
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    onLogout = {}
                )
            }
        }
        composeRule.onAllNodesWithText("Atendida").assertAny(hasText("Atendida"))
        composeRule.onNodeWithText("Puedo ayudar").assertIsDisplayed()
        composeRule.onNodeWithText("Voy en camino").assertIsDisplayed()
        composeRule.onNodeWithText("Registro").assertIsDisplayed()
        composeRule.onNodeWithText("Vista").assertIsDisplayed()
        composeRule.onAllNodesWithText("Atendida").assertAny(hasText("Atendida"))
        composeRule.onNodeWithText("12/08/2026 10:51").assertIsDisplayed()
        composeRule.onNodeWithText("Ubicación no disponible para esta alerta.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ver ubicación").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitor_acknowledge_button").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitor_decline_button").assertCountEquals(0)
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
                    historyState = MonitorAlertHistoryUiState.Content(listOf(alert("Pending", null), alert("Declined", "CanAssist"))),
                    onRefresh = {},
                    onRefreshHistory = {},
                    onOpenHistoryAlert = { opened = it },
                    onLogout = {}
                )
            }
        }
        composeRule.onNodeWithText("Actualizar").assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_bottom_incidents").performClick()
        composeRule.onNodeWithText("Pendiente").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sin respuesta").assertCountEquals(0)
        composeRule.onNodeWithText("Rechazada").assertIsDisplayed()
        composeRule.onNodeWithText("Puedo ayudar").assertIsDisplayed()
        composeRule.onNodeWithText("Pendiente").performClick()
        org.junit.Assert.assertEquals("attempt-1", opened)
    }

    @Test fun historyDetailBackReturnsToTheRetainedHistory() {
        var opened: String? = null
        composeRule.setContent {
            var currentState by remember { mutableStateOf<MonitorAlertsUiState>(MonitorAlertsUiState.Ready) }
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = currentState,
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    historyState = MonitorAlertHistoryUiState.Content(
                        listOf(
                            alert("Pending", "None", "attempt-one"),
                            alert("Declined", null, "attempt-two")
                        )
                    ),
                    onRefresh = {},
                    onRefreshHistory = {},
                    onOpenHistoryAlert = { id ->
                        opened = id
                        currentState = MonitorAlertsUiState.Alert(
                            NotificationDeliveryAttemptId(id),
                            MonitorAlertDetail(alert("Pending", "None", id))
                        )
                    },
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithTag("monitor_bottom_incidents").performClick()
        composeRule.onNodeWithTag("monitor_history_alert_attempt-one").performClick()
        org.junit.Assert.assertEquals("attempt-one", opened)
        composeRule.onNodeWithTag("monitor_alert_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_alert_back_to_incidents").performClick()
        composeRule.onNodeWithText("Pendiente").assertIsDisplayed()
        composeRule.onNodeWithText("Rechazada").assertIsDisplayed()
    }

    @Test fun emptyHistoryRemainsAvailableAndRefreshes() {
        var refreshes = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Ready,
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    historyState = MonitorAlertHistoryUiState.Empty,
                    onRefreshHistory = { refreshes += 1 },
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithTag("monitor_bottom_incidents").performClick()
        composeRule.onNodeWithText("No hay alertas registradas.").assertIsDisplayed()
        composeRule.onNodeWithText("Actualizar").performClick()
        org.junit.Assert.assertEquals(1, refreshes)
    }

    @Test fun historyErrorOffersRetryWithoutLeavingHistory() {
        var refreshes = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Ready,
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    historyState = MonitorAlertHistoryUiState.Error("response_json_invalid"),
                    onRefreshHistory = { refreshes += 1 },
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithTag("monitor_bottom_incidents").performClick()
        composeRule.onNodeWithText("No pudimos cargar los incidentes.").assertIsDisplayed()
        composeRule.onNodeWithText("Intenta nuevamente.").assertIsDisplayed()
        composeRule.onAllNodesWithText("response_json_invalid").assertCountEquals(0)
        composeRule.onNodeWithTag("monitor_alert_retry").performClick()
        org.junit.Assert.assertEquals(1, refreshes)
    }

    @Test fun partialHistoryCardUsesSafeStatusAndDateFallbacks() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Ready,
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    historyState = MonitorAlertHistoryUiState.Content(
                        listOf(
                            MonitorAlertAcknowledgement(
                                id = null,
                                alertDispatchId = null,
                                notificationDeliveryAttemptId = "attempt-partial",
                                incidentId = null,
                                tripId = null,
                                emergencyContactId = null,
                                status = null,
                                responseType = null,
                                message = null,
                                viewedAtUtc = null,
                                acknowledgedAtUtc = null,
                                declinedAtUtc = null,
                                createdAtUtc = null,
                                updatedAtUtc = null
                            )
                        )
                    ),
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithTag("monitor_bottom_incidents").performClick()
        composeRule.onNodeWithText("Estado no disponible").assertIsDisplayed()
        composeRule.onNodeWithText("Fecha no disponible").assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_history_alert_attempt-partial").assertIsDisplayed()
    }

    @Test fun historyKeepsNoneResponseVisibleWithoutAddingAResponseLine() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Ready,
                    onRetry = {}, onAcknowledge = {}, onDecline = {},
                    historyState = MonitorAlertHistoryUiState.Content(listOf(alert("Pending", "None"))),
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithTag("monitor_bottom_incidents").performClick()
        composeRule.onNodeWithTag("monitor_history_alert_attempt-1").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sin respuesta").assertCountEquals(0)
    }

    @Test fun detailHidesBlankOrMissingResponseFieldsAndKeepsPrimaryDateFallback() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Alert(
                        NotificationDeliveryAttemptId("attempt-partial"),
                        MonitorAlertDetail(alert(status = "Pending", response = null, message = " ", createdAtUtc = "not-a-date"))
                    ),
                    onRetry = {}, onAcknowledge = {}, onDecline = {}, onLogout = {}
                )
            }
        }

        composeRule.onNodeWithText("Pendiente").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sin respuesta").assertCountEquals(0)
        composeRule.onAllNodesWithText("Mensaje").assertCountEquals(0)
        composeRule.onNodeWithText("Fecha no disponible").assertIsDisplayed()
    }

    @Test fun detailShowsDeclinedTimeWithoutInventingAResponseOrMessage() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Alert(
                        NotificationDeliveryAttemptId("attempt-declined"),
                        MonitorAlertDetail(alert(
                            status = "Declined",
                            response = null,
                            declinedAtUtc = "2026-08-12T16:54:09Z"
                        ))
                    ),
                    onRetry = {}, onAcknowledge = {}, onDecline = {}, onLogout = {}
                )
            }
        }

        composeRule.onAllNodesWithText("Rechazada").assertAny(hasText("Rechazada"))
        composeRule.onAllNodesWithText("Sin respuesta").assertCountEquals(0)
        composeRule.onAllNodesWithText("Mensaje").assertCountEquals(0)
        composeRule.onNodeWithText("12/08/2026 10:54").assertIsDisplayed()
    }

    @Test fun enrichedStatusShowsLocalizedIncidentAndKeepsUnavailableLocationWithoutMapButton() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Alert(
                        NotificationDeliveryAttemptId("attempt-status"),
                        MonitorAlertDetail(alert("Acknowledged", "CanAssist")),
                        status = monitorStatus(location = MonitorAlertStatusLocation(false, null, null, null, null, null, null, null, null))
                    ),
                    onRetry = {}, onAcknowledge = {}, onDecline = {}, onLogout = {}
                )
            }
        }

        composeRule.onNodeWithText("SOS manual").assertIsDisplayed()
        composeRule.onNodeWithText("Riesgo: Alto").assertIsDisplayed()
        composeRule.onNodeWithText("Alta").assertIsDisplayed()
        composeRule.onNodeWithText("Finalizado").assertIsDisplayed()
        composeRule.onNodeWithText("Ocurrió").assertIsDisplayed()
        composeRule.onNodeWithText("Ubicación no disponible para esta alerta.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ver ubicación").assertCountEquals(0)
    }

    @Test fun enrichedStatusWithValidCoordinatesOpensInternalIncidentMap() {
        var requestedPoint: com.example.sos_segundoplano.ui.maps.MotoMapPoint? = null
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Alert(
                        NotificationDeliveryAttemptId("attempt-location"),
                        MonitorAlertDetail(alert("Pending", null)),
                        status = monitorStatus(location = MonitorAlertStatusLocation(true, 19.4326, -99.1332, 8.0, "gps", null, null, true, false))
                    ),
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    onLoadMapRoute = { requestedPoint = it },
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithText("Ver ubicación del incidente").assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_open_location_button").performClick()
        composeRule.onNodeWithText("Ruta de asistencia").assertIsDisplayed()
        org.junit.Assert.assertEquals(19.4326, requestedPoint?.latitude ?: Double.NaN, 0.0)
        org.junit.Assert.assertEquals(-99.1332, requestedPoint?.longitude ?: Double.NaN, 0.0)
    }

    @Test fun criticalEventIsLocalizedInCurrentAlert() = assertAutomaticCause(
        cause = "CriticalEvent",
        expectedTitle = "Evento crítico"
    )

    @Test fun countdownTimeoutIsLocalizedInCurrentAlert() = assertAutomaticCause(
        cause = "CountdownTimeout",
        expectedTitle = "Tiempo de confirmación agotado"
    )

    @Test fun userRequestedHelpIsLocalizedInCurrentAlert() = assertAutomaticCause(
        cause = "UserRequestedHelp",
        expectedTitle = "Solicitud de ayuda"
    )

    @Test fun homeShowsOnlyTheNewestPendingAlert() {
        var opened: String? = null
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Ready,
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    historyState = MonitorAlertHistoryUiState.Content(
                        listOf(
                            alert("Pending", null, "pending-old", createdAtUtc = "2026-08-12T16:40:00Z"),
                            alert("Pending", null, "pending-new", createdAtUtc = "2026-08-12T16:50:00Z"),
                            alert("Acknowledged", "CanAssist", "confirmed-newer", createdAtUtc = "2026-08-12T16:55:00Z", acknowledgedAtUtc = "2026-08-12T16:56:00Z")
                        )
                    ),
                    onOpenHistoryAlert = { opened = it },
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithText("Alerta pendiente más reciente").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitor_dashboard_open_alert").assertCountEquals(1)
        composeRule.onNodeWithTag("monitor_dashboard_open_alert").performClick()
        org.junit.Assert.assertEquals("pending-new", opened)
        composeRule.onAllNodesWithTag("monitor_history_section").assertCountEquals(0)
    }

    @Test fun acknowledgedAlertDisappearsFromHome() {
        val acknowledged = alert(
            "Acknowledged",
            "CanAssist",
            "confirmed",
            acknowledgedAtUtc = "2026-08-12T16:56:00Z"
        )
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Alert(
                        NotificationDeliveryAttemptId("confirmed"),
                        MonitorAlertDetail(acknowledged)
                    ),
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    historyState = MonitorAlertHistoryUiState.Content(listOf(acknowledged)),
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithTag("monitor_bottom_home").performClick()
        composeRule.onNodeWithTag("monitor_no_pending_alerts").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitor_dashboard_open_alert").assertCountEquals(0)
    }

    @Test fun logoutLivesOnlyInProfileAndProfileShowsMonitorSessionData() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Ready,
                    onRetry = {},
                    onAcknowledge = {},
                    onDecline = {},
                    profileData = MonitorProfileUiData(
                        fullName = "Ana Monitor",
                        email = "ana.monitor@motosos.com",
                        phoneNumber = "+525512345678",
                        isActive = true
                    ),
                    onLogout = {}
                )
            }
        }

        composeRule.onAllNodesWithText("Cerrar sesión").assertCountEquals(0)
        composeRule.onNodeWithTag("monitor_bottom_profile").performClick()
        composeRule.onNodeWithText("Ana Monitor").assertIsDisplayed()
        composeRule.onNodeWithText("ana.monitor@motosos.com").assertIsDisplayed()
        composeRule.onNodeWithText("+525512345678").assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_profile_logout").assertIsDisplayed()
    }

    private fun assertAutomaticCause(cause: String, expectedTitle: String) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitorHomeScreen(
                    state = MonitorAlertsUiState.Alert(
                        NotificationDeliveryAttemptId("attempt-$cause"),
                        MonitorAlertDetail(alert("Pending", null)),
                        status = monitorStatus(
                            location = MonitorAlertStatusLocation(false, null, null, null, null, null, null, null, null),
                            cause = cause
                        )
                    ),
                    onRetry = {}, onAcknowledge = {}, onDecline = {}, onLogout = {}
                )
            }
        }
        composeRule.onNodeWithText(expectedTitle).assertIsDisplayed()
    }

    private fun alert(
        status: String,
        response: String?,
        attemptId: String = "attempt-1",
        message: String? = null,
        createdAtUtc: String? = "2026-08-12T16:51:09Z",
        viewedAtUtc: String? = null,
        acknowledgedAtUtc: String? = null,
        declinedAtUtc: String? = null
    ) = MonitorAlertAcknowledgement(
        "id", "dispatch", attemptId, "incident", "trip", "contact", status, response, message,
        viewedAtUtc, acknowledgedAtUtc, declinedAtUtc, createdAtUtc, null
    )

    private fun monitorStatus(location: MonitorAlertStatusLocation, cause: String = "ManualSos") = MonitorAlertStatus(
        incident = MonitorAlertIncidentStatus("Open", "MobileDetection", cause, "High", "2026-08-12T16:50:00Z", null),
        trip = MonitorAlertTripStatus("Finished", null, null),
        alertDispatch = MonitorAlertDispatchStatus("PendingDispatch", "High", "ManualSos", null),
        notifications = null,
        acknowledgements = null,
        location = location,
        overallStatus = "Acknowledged",
        requiresAttention = false,
        lastUpdatedAtUtc = null
    )
}
