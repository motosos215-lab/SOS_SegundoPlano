package com.example.sos_segundoplano.features.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import com.example.sos_segundoplano.domain.history.RiderTripHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryLocation
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RiderTripDetailScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun showsFinishedTripEndpointsAndBackAction() {
        var backCalls = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderTripDetailScreen(finishedTrip(), onBack = { backCalls++ })
            }
        }

        composeRule.onNodeWithText("Detalle del viaje").assertIsDisplayed()
        composeRule.onNodeWithText("Finalizado").assertIsDisplayed()
        composeRule.onNodeWithText("01:30:00").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Trayecto aproximado entre inicio y fin").assertIsDisplayed()
        composeRule.onNodeWithTag("rider_trip_detail_list")
            .performScrollToNode(hasText("19.43260, -99.13320"))
        composeRule.onNodeWithText("19.43260, -99.13320").assertIsDisplayed()
        composeRule.onNodeWithTag("rider_trip_detail_list")
            .performScrollToNode(hasText("19.43500, -99.13600"))
        composeRule.onNodeWithText("19.43500, -99.13600").assertIsDisplayed()
        composeRule.onNodeWithTag("rider_trip_detail_list")
            .performScrollToNode(hasText("Abrir inicio en mapa"))
        composeRule.onNodeWithText("Abrir inicio en mapa").assertIsDisplayed()
        composeRule.onNodeWithTag("rider_trip_detail_list")
            .performScrollToNode(hasText("Abrir destino en mapa"))
        composeRule.onNodeWithText("Abrir destino en mapa").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Volver al historial").performClick()
        assertEquals(1, backCalls)
    }

    @Test fun hidesUnavailableLocationActionsAndSupportsActiveTrips() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderTripDetailScreen(
                    RiderTripHistoryItem("Active", "2026-08-12T16:00:00Z", null, RiderTripHistoryLocation(19.4326, -99.1332), null),
                    onBack = {}
                )
            }
        }

        composeRule.onAllNodesWithText("En curso").assertCountEquals(3)
        composeRule.onNodeWithTag("rider_trip_detail_list")
            .performScrollToNode(hasText("No disponible"))
        composeRule.onNodeWithText("No disponible").assertIsDisplayed()
        composeRule.onAllNodesWithText("Abrir destino en mapa").assertCountEquals(0)
    }

    @Test fun hidesUnavailableStartActionWhenOnlyDestinationExists() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderTripDetailScreen(
                    RiderTripHistoryItem("Finished", "2026-08-12T16:00:00Z", "2026-08-12T17:30:00Z", null, RiderTripHistoryLocation(19.4350, -99.1360)),
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Ubicación inicial").assertIsDisplayed()
        composeRule.onAllNodesWithText("Abrir inicio en mapa").assertCountEquals(0)
        composeRule.onNodeWithTag("rider_trip_detail_list")
            .performScrollToNode(hasText("Abrir destino en mapa"))
        composeRule.onNodeWithText("Abrir destino en mapa").assertIsDisplayed()
    }

    private fun finishedTrip() = RiderTripHistoryItem(
        status = "Finished",
        startedAtUtc = "2026-08-12T16:00:00Z",
        finishedAtUtc = "2026-08-12T17:30:00Z",
        startLocation = RiderTripHistoryLocation(19.4326, -99.1332),
        endLocation = RiderTripHistoryLocation(19.4350, -99.1360)
    )
}
