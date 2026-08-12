package com.example.sos_segundoplano.features.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.data.trip.TripProcessRecoveryCoordinator
import com.example.sos_segundoplano.data.trip.TripProcessRecoveryState
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.authenticatedIdentityOrNull
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun RiderTripRecoveryGate(
    authRepository: AuthRepository,
    coordinator: TripProcessRecoveryCoordinator,
    content: @Composable (TripProcessRecoveryState) -> Unit
) {
    val sessionState by authRepository.observeSession().collectAsStateWithLifecycle()
    val recoveryState by coordinator.states.collectAsStateWithLifecycle()
    val identity = sessionState.authenticatedIdentityOrNull()
    val role = when (val state = sessionState) {
        is SessionState.Authenticated -> state.user.role
        is SessionState.Refreshing -> state.user.role
        else -> null
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(identity, role) {
        if (identity != null && role != null) coordinator.recover(identity, role)
    }

    when {
        identity == null -> TripRecoveryProgress()
        recoveryState is TripProcessRecoveryState.Idle && recoveryState.identityOrNull() == identity -> content(recoveryState)
        recoveryState is TripProcessRecoveryState.Active && recoveryState.identityOrNull() == identity -> content(recoveryState)
        recoveryState is TripProcessRecoveryState.RetryableFailure && recoveryState.identityOrNull() == identity ->
            TripRecoveryFailure(
                onRetry = {
                    role?.let { currentRole ->
                        scope.launch { coordinator.recover(identity, currentRole, retry = true) }
                    }
                }
            )
        else -> TripRecoveryProgress()
    }
}

private fun TripProcessRecoveryState.identityOrNull() = when (this) {
    is TripProcessRecoveryState.Restoring -> identity
    is TripProcessRecoveryState.Idle -> identity
    is TripProcessRecoveryState.Active -> identity
    is TripProcessRecoveryState.RetryableFailure -> identity
    TripProcessRecoveryState.NotStarted,
    TripProcessRecoveryState.NotApplicable -> null
}

@Composable
private fun TripRecoveryProgress() {
    Column(
        modifier = Modifier.fillMaxSize().testTag("trip_recovery_progress"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.trip_recovery_checking),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun TripRecoveryFailure(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("trip_recovery_failure"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.trip_recovery_failed),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 16.dp).testTag("trip_recovery_retry")
        ) {
            Text(androidx.compose.ui.res.stringResource(R.string.trip_recovery_retry))
        }
    }
}
