package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.sos_segundoplano.domain.emergency.EmergencyContact
import com.example.sos_segundoplano.domain.emergency.EmergencyContactPermissions
import com.example.sos_segundoplano.features.trip.RiderEmergencyContactDetailScreen
import com.example.sos_segundoplano.features.trip.RiderEmergencyContactUiState
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RiderEmergencyContactDetailScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun linkedContactShowsMonitorDetailsAndWebEditAction() {
        var edited = false
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderEmergencyContactDetailScreen(
                    state = RiderEmergencyContactUiState.Content(contact()),
                    onBack = {},
                    onEditMonitorWeb = { edited = true }
                )
            }
        }

        composeRule.onNodeWithText("Monitor MotoSOS").assertIsDisplayed()
        composeRule.onNodeWithText("Vinculado").assertIsDisplayed()
        composeRule.onNodeWithText("monitor@motosos.com").assertIsDisplayed()
        composeRule.onNodeWithText("Recibir alertas críticas").assertIsDisplayed()
        composeRule.onNodeWithText("Ver ubicación en tiempo real").assertIsDisplayed()
        composeRule.onNodeWithTag("rider_edit_monitor_web_button").performClick()
        assertTrue(edited)
    }

    private fun contact() = EmergencyContact(
        id = "contact-1",
        userId = "rider-1",
        fullName = "Monitor MotoSOS",
        relationship = "Contacto de emergencia",
        phoneNumber = "+525555555555",
        email = "monitor@motosos.com",
        priority = 1,
        invitationStatus = "Linked",
        linkingCode = null,
        linkingCodeExpiresAtUtc = null,
        linkedUserId = "monitor-1",
        permissions = EmergencyContactPermissions(
            canViewRealTimeLocation = true,
            canReceiveCriticalAlerts = true,
            canViewIncidentHistory = false,
            canViewVitalSigns = false
        ),
        isPrimary = true,
        isActive = true,
        createdAtUtc = "2026-08-11T00:00:00Z",
        updatedAtUtc = "2026-08-11T01:00:00Z",
        invitedAtUtc = "2026-08-11T00:30:00Z",
        linkedAtUtc = "2026-08-11T01:00:00Z",
        revokedAtUtc = null
    )
}
