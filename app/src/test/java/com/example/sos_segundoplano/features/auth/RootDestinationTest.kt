package com.example.sos_segundoplano.features.auth

import com.example.sos_segundoplano.domain.auth.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

class RootDestinationTest {
    @Test fun riderAuthenticatedAndRefreshingAlwaysSelectRiderRoot() {
        listOf(AuthEntryStatus.Authenticated, AuthEntryStatus.Refreshing).forEach { status ->
            assertEquals(
                RootDestination.Rider,
                resolveRootDestination(LoginUiState(startupState = status, authenticatedRole = UserRole.Rider))
            )
        }
    }

    @Test fun monitorAuthenticatedAndRefreshingAlwaysSelectMonitorRoot() {
        listOf(AuthEntryStatus.Authenticated, AuthEntryStatus.Refreshing).forEach { status ->
            assertEquals(
                RootDestination.Monitor,
                resolveRootDestination(LoginUiState(startupState = status, authenticatedRole = UserRole.Monitor))
            )
        }
    }

    @Test fun unsupportedOrMissingRoleNeverSelectsAnAuthenticatedRoot() {
        listOf(UserRole.Unknown, null).forEach { role ->
            assertEquals(
                RootDestination.Login,
                resolveRootDestination(
                    LoginUiState(
                        startupState = AuthEntryStatus.Authenticated,
                        authenticatedRole = role
                    )
                )
            )
        }
    }
}
