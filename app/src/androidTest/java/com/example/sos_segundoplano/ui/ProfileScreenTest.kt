package com.example.sos_segundoplano.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBackUnconditionally
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusProvider
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatusProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.profile.RiderProfile
import com.example.sos_segundoplano.features.profile.ProfileScreen
import com.example.sos_segundoplano.features.profile.ProfileUiState
import com.example.sos_segundoplano.features.profile.ProfileUiError
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ProfileScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun homeBottomBarOpensProfileAndBackReturnsHomeWithoutPermissionsOrService() {
        var starterCalls = 0
        setAppContent(
            profileContent = { onHome ->
                ProfileScreen(
                    state = ProfileUiState(profile = profile()),
                    onHomeSelected = onHome,
                    onRetry = {},
                    onLogoutSelected = {},
                    onLogoutDismissed = {},
                    onLogoutConfirmed = {}
                )
            },
            monitoringServiceStarter = MonitoringServiceStarter {
                starterCalls++
                MonitoringServiceStartResult.Started
            }
        )

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Perfil").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("profile_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("profile_full_name").assertIsDisplayed()
        composeRule.onNodeWithText("Inicio").performClick()
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()

        composeRule.onNodeWithText("Perfil").performClick()
        pressBackUnconditionally()
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("location_permission_dialog").assertCountEquals(0)
        assertEquals(0, starterCalls)
    }

    @Test fun profileLoadingIsDisplayed() {
        setProfile(ProfileUiState(loading = true))
        composeRule.onNodeWithTag("profile_loading").assertIsDisplayed()
    }

    @Test fun profileSuccessShowsReadOnlyInformation() {
        setProfile(ProfileUiState(profile = profile(phoneNumber = null)))
        composeRule.onAllNodesWithText("Moto Rider").assertCountEquals(2)
        composeRule.onNodeWithTag("profile_email").assertIsDisplayed()
        composeRule.onNodeWithText("No disponible").assertIsDisplayed()
        composeRule.onAllNodesWithText("Editar").assertCountEquals(0)
    }

    @Test fun profileErrorRetryInvokesCallbackOnce() {
        var retryCalls = 0
        setProfile(ProfileUiState(error = ProfileUiError.NetworkUnavailable), onRetry = { retryCalls++ })
        composeRule.onNodeWithText("Reintentar").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, retryCalls)
    }

    @Test fun profileLogoutButtonInvokesSelectionCallbackOnce() {
        var logoutSelectionCalls = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                ProfileScreen(
                    state = ProfileUiState(profile = profile()),
                    onHomeSelected = {},
                    onRetry = {},
                    onLogoutSelected = {
                        logoutSelectionCalls++
                    },
                    onLogoutDismissed = {},
                    onLogoutConfirmed = {}
                )
            }
        }
        composeRule.onNodeWithTag("profile_logout_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, logoutSelectionCalls) }
    }

    @Test fun profileLogoutDialogCanBeCancelled() {
        var dialogVisible by mutableStateOf(true)
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                ProfileScreen(
                    state = ProfileUiState(profile = profile(), isLogoutDialogVisible = dialogVisible),
                    onHomeSelected = {},
                    onRetry = {},
                    onLogoutSelected = {},
                    onLogoutDismissed = { dialogVisible = false },
                    onLogoutConfirmed = {}
                )
            }
        }
        composeRule.onNodeWithTag("profile_logout_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("profile_logout_cancel").performClick()
        composeRule.runOnIdle { }
        composeRule.onAllNodesWithTag("profile_logout_dialog").assertCountEquals(0)
    }

    @Test fun profileLogoutConfirmationInvokesCallbackOnce() {
        var logoutCalls = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                ProfileScreen(
                    state = ProfileUiState(profile = profile(), isLogoutDialogVisible = true),
                    onHomeSelected = {},
                    onRetry = {},
                    onLogoutSelected = {},
                    onLogoutDismissed = {},
                    onLogoutConfirmed = { logoutCalls++ }
                )
            }
        }
        composeRule.onNodeWithTag("profile_logout_confirm").performClick()
        composeRule.runOnIdle { }
        assertEquals(1, logoutCalls)
    }

    @Test fun disabledBottomItemsAndActiveTripFlowRemainUnchanged() {
        var startTripCalls = 0
        setAppContent(
            startTrip = { state ->
                startTripCalls++
                when (state) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("bottom_nav_home").assertIsEnabled()
        composeRule.onNodeWithTag("bottom_nav_trips").assertIsNotEnabled()
        composeRule.onNodeWithTag("bottom_nav_map").assertIsNotEnabled()
        composeRule.onNodeWithTag("bottom_nav_sos").assertIsNotEnabled()
        composeRule.onNodeWithTag("bottom_nav_profile").assertIsEnabled()
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(1, startTripCalls)
    }

    private fun setProfile(
        state: ProfileUiState,
        onRetry: () -> Unit = {},
        onLogoutSelected: () -> Unit = {},
        onLogoutConfirmed: () -> Unit = {}
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                ProfileScreen(
                    state = state,
                    onHomeSelected = {},
                    onRetry = onRetry,
                    onLogoutSelected = onLogoutSelected,
                    onLogoutDismissed = {},
                    onLogoutConfirmed = onLogoutConfirmed
                )
            }
        }
    }

    private fun setAppContent(
        profileContent: (@androidx.compose.runtime.Composable (() -> Unit) -> Unit)? = null,
        monitoringServiceStarter: MonitoringServiceStarter = MonitoringServiceStarter { MonitoringServiceStartResult.Started },
        startTrip: (TripSessionState) -> TripSessionState = { state ->
            when (state) {
                TripSessionState.Idle -> TripSessionState.Active
                TripSessionState.Active -> TripSessionState.Active
            }
        }
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    profileContent = profileContent,
                    locationPermissionStatusProvider = BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
                    notificationStatusProvider = AppNotificationStatusProvider { AppNotificationStatus.Enabled },
                    bluetoothRequirementStatusProvider = BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
                    monitoringServiceStarter = monitoringServiceStarter,
                    startTripUseCase = startTrip
                )
            }
        }
    }
}

private fun profile(phoneNumber: String? = "+52 555 555 5555") = RiderProfile(
    id = "rider-id",
    email = "rider@example.com",
    fullName = "Moto Rider",
    phoneNumber = phoneNumber,
    role = "Rider",
    isActive = true,
    createdAtUtc = Instant.parse("2026-08-04T12:00:00Z"),
    updatedAtUtc = Instant.parse("2026-08-04T12:05:00Z"),
    lastLoginAtUtc = Instant.parse("2026-08-04T12:05:00Z")
)
