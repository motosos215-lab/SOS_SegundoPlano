package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import com.example.sos_segundoplano.domain.history.RiderHistoryRepository
import com.example.sos_segundoplano.domain.history.RiderHistoryResult
import com.example.sos_segundoplano.domain.history.RiderIncidentHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryLocation
import com.example.sos_segundoplano.features.history.RiderHistoryScreen
import com.example.sos_segundoplano.features.history.RiderHistoryViewModel
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Rule
import org.junit.Test

class RiderHistoryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun showsCompactTripCardAndNoTechnicalIds() {
        setContent(
            trips = listOf(RiderTripHistoryItem("Finished", "2026-08-12T16:00:00Z", "2026-08-12T17:30:00Z")),
            incidents = emptyList()
        )
        composeRule.onNodeWithText("Historial de viajes").assertIsDisplayed()
        composeRule.onAllNodesWithText("Viajes").assertCountEquals(2)
        composeRule.onNodeWithContentDescription("Actualizar historial").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_nav_trips").assertIsSelected()
        composeRule.onNodeWithText("Finalizado").assertIsDisplayed()
        composeRule.onNodeWithText("01:30:00").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trip_history_card").assertCountEquals(1)
        composeRule.onAllNodesWithText("trip-123").assertCountEquals(0)
    }

    @Test fun switchesToIncidentsAndUsesFriendlyCause() {
        setContent(emptyList(), listOf(RiderIncidentHistoryItem("CriticalEvent", "High", "Open", "2026-08-12T16:00:00Z")))
        composeRule.onNodeWithText("Incidentes").performClick()
        composeRule.onNodeWithText("Evento crítico").assertIsDisplayed()
        composeRule.onAllNodesWithText("incident-123").assertCountEquals(0)
    }

    @Test fun showsEmptyHistory() {
        setContent(emptyList(), emptyList())
        composeRule.onNodeWithText("No hay registros todavía.").assertIsDisplayed()
    }

    @Test fun compactCardDoesNotInventRouteFromEndpoints() {
        setContent(
            trips = listOf(
                RiderTripHistoryItem("Finished", "2026-08-12T16:00:00Z", "2026-08-12T17:30:00Z",
                    RiderTripHistoryLocation(19.4326, -99.1332), RiderTripHistoryLocation(19.4350, -99.1360))
            ), incidents = emptyList()
        )
        composeRule.onNodeWithContentDescription("Recorrido real aún no disponible").assertIsDisplayed()
        composeRule.onAllNodesWithText("Abrir inicio en Google Maps").assertCountEquals(0)
        composeRule.onAllNodesWithText("Abrir final en Google Maps").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Ver detalle del viaje").performClick()
        composeRule.onNodeWithText("Detalle del viaje").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Volver al historial").performClick()
        composeRule.onNodeWithText("Historial de viajes").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_nav_trips").assertIsSelected()
    }

    @Test fun showsOnlyEndLocationWhenStartIsMissing() {
        setContent(listOf(RiderTripHistoryItem("Finished", "2026-08-12T16:00:00Z", "2026-08-12T17:30:00Z", null, RiderTripHistoryLocation(19.4350, -99.1360))), emptyList())
        composeRule.onNodeWithContentDescription("Inicio no disponible").assertIsDisplayed()
    }

    @Test fun showsOnlyStartLocationWhenEndIsMissing() {
        setContent(listOf(RiderTripHistoryItem("Active", "2026-08-12T16:00:00Z", null, RiderTripHistoryLocation(19.4326, -99.1332), null)), emptyList())
        composeRule.onNodeWithContentDescription("Final no disponible").assertIsDisplayed()
    }

    @Test fun showsNoMapActionsWhenBothLocationsAreMissing() {
        setContent(listOf(RiderTripHistoryItem("Finished", "2026-08-12T16:00:00Z", "2026-08-12T17:30:00Z")), emptyList())
        composeRule.onNodeWithContentDescription("Ubicación no disponible").assertIsDisplayed()
    }

    private fun setContent(trips: List<RiderTripHistoryItem>, incidents: List<RiderIncidentHistoryItem>) {
        val repository = object : RiderHistoryRepository {
            override suspend fun trips() = RiderHistoryResult.Success(trips)
            override suspend fun incidents() = RiderHistoryResult.Success(incidents)
        }
        val viewModel = RiderHistoryViewModel(repository)
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderHistoryScreen(
                    viewModel = viewModel,
                    onBack = {}
                )
            }
        }
    }
}
