package com.example.sos_segundoplano.features.background

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun AccidentCountdownScreen(
    state: FalsePositiveValidationState,
    modifier: Modifier = Modifier,
    onConfirmSafe: (Long, Long, String) -> Unit = { _, _, _ -> },
    onRequestHelp: (Long, Long, String) -> Unit = { _, _, _ -> },
    onContinueTrip: () -> Unit = {}
) {
    val countdown = state as? FalsePositiveValidationState.CountdownActive
    val title = accidentTitle(state)
    val message = accidentMessage(state)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MotoBackground)
            .padding(20.dp)
            .testTag("accident_countdown_screen"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MotoSurface,
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MotoAlert.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.displaySmall,
                        color = MotoAlert,
                        fontWeight = FontWeight.Black
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MotoTextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                if (countdown != null) {
                    val remainingSeconds = ((countdown.remainingNanos + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND).coerceAtLeast(0L)
                    Box(
                        modifier = Modifier
                            .size(124.dp)
                            .clip(CircleShape)
                            .background(MotoAlert.copy(alpha = 0.06f))
                            .border(6.dp, MotoAlert.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = remainingSeconds.toString(),
                            modifier = Modifier.testTag("accident_countdown_seconds"),
                            style = MaterialTheme.typography.displayLarge,
                            color = MotoAlert,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = stringResource(R.string.validation_countdown_seconds, remainingSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        color = MotoAlert,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MotoTextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (countdown != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onConfirmSafe(
                                    countdown.metadata.sessionId,
                                    countdown.metadata.assessmentId,
                                    "mobile-confirm-${countdown.metadata.sessionId}-${countdown.metadata.assessmentId}"
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("confirm_safe_button")
                        ) {
                            Text(stringResource(R.string.validation_confirm_safe), textAlign = TextAlign.Center)
                        }
                        Button(
                            onClick = {
                                onRequestHelp(
                                    countdown.metadata.sessionId,
                                    countdown.metadata.assessmentId,
                                    "mobile-help-${countdown.metadata.sessionId}-${countdown.metadata.assessmentId}"
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("request_help_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MotoAlert)
                        ) {
                            Text(stringResource(R.string.validation_request_help), textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    Button(
                        onClick = onContinueTrip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("continue_trip_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryDark)
                    ) {
                        Text(stringResource(R.string.continue_trip))
                    }
                }
            }
        }
    }
}

@Composable
private fun accidentTitle(state: FalsePositiveValidationState): String = when (state) {
    is FalsePositiveValidationState.CountdownActive -> stringResource(R.string.validation_countdown_title)
    is FalsePositiveValidationState.HelpRequested -> stringResource(R.string.validation_request_help)
    is FalsePositiveValidationState.IncidentDeliveryRetrying -> stringResource(R.string.validation_delivery_retry_title)
    is FalsePositiveValidationState.IncidentGenerated -> stringResource(R.string.validation_incident_generated)
    is FalsePositiveValidationState.ImmediateAlertRequested -> stringResource(R.string.validation_immediate_alert_requested_title)
    is FalsePositiveValidationState.Error -> when (state.message) {
        "RemoteIncidentCreationFailed" -> stringResource(R.string.validation_remote_delivery_failed_title)
        else -> stringResource(R.string.validation_local_registration_failed_title)
    }
    else -> stringResource(R.string.validation_countdown_title)
}

@Composable
private fun accidentMessage(state: FalsePositiveValidationState): String = when (state) {
    is FalsePositiveValidationState.CountdownActive -> stringResource(R.string.validation_countdown_waiting_driver)
    is FalsePositiveValidationState.HelpRequested -> stringResource(R.string.validation_help_requested)
    is FalsePositiveValidationState.IncidentDeliveryRetrying -> stringResource(R.string.validation_delivery_retry)
    is FalsePositiveValidationState.IncidentGenerated -> when (state.incident.cause) {
        IncidentCause.UserRequestedHelp -> stringResource(R.string.validation_help_requested)
        IncidentCause.Timeout -> stringResource(R.string.validation_timeout_escalated)
        IncidentCause.CriticalPhysicalEvent -> stringResource(R.string.validation_immediate_alert_requested)
        IncidentCause.ManualSos -> stringResource(R.string.validation_help_requested)
    }
    is FalsePositiveValidationState.ImmediateAlertRequested -> stringResource(R.string.validation_immediate_alert_requested)
    is FalsePositiveValidationState.Error -> when (state.message) {
        "RemoteIncidentCreationFailed" -> stringResource(R.string.validation_remote_delivery_failed)
        else -> stringResource(R.string.validation_local_registration_failed)
    }
    else -> stringResource(R.string.validation_countdown_waiting_driver)
}

private const val NANOS_PER_SECOND = 1_000_000_000L
