package com.example.sos_segundoplano.features.trip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.monitoring.MonitoringReadiness
import com.example.sos_segundoplano.domain.monitoring.MonitoringReadinessStatus
import com.example.sos_segundoplano.domain.monitoring.MonitoringRequirementStatus
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun MonitoringReadinessCard(
    readiness: MonitoringReadiness,
    onLocationAction: () -> Unit,
    onNotificationAction: () -> Unit,
    onBluetoothAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("monitoring_readiness_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MotoSurface),
        border = BorderStroke(1.dp, MotoDivider)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.monitoring_readiness_title),
                style = MaterialTheme.typography.titleMedium,
                color = MotoTextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(readiness.status.labelRes),
                modifier = Modifier.testTag("monitoring_readiness_overall_status"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (readiness.status == MonitoringReadinessStatus.Ready) MotoSuccess else MotoTextSecondary,
                fontWeight = FontWeight.Medium
            )
            MonitoringRequirementRow(
                label = stringResource(R.string.monitoring_readiness_location),
                status = readiness.location,
                testTag = "monitoring_readiness_location",
                onAction = onLocationAction
            )
            MonitoringRequirementRow(
                label = stringResource(R.string.monitoring_readiness_notifications),
                status = readiness.notifications,
                testTag = "monitoring_readiness_notifications",
                onAction = onNotificationAction
            )
            MonitoringRequirementRow(
                label = stringResource(R.string.monitoring_readiness_bluetooth),
                status = readiness.bluetooth,
                testTag = "monitoring_readiness_bluetooth",
                onAction = onBluetoothAction
            )
        }
    }
}

@Composable
private fun MonitoringRequirementRow(
    label: String,
    status: MonitoringRequirementStatus,
    testTag: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MotoTextPrimary)
            Text(
                text = stringResource(status.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = if (status == MonitoringRequirementStatus.Available) MotoSuccess else MotoTextSecondary
            )
        }
        if (status != MonitoringRequirementStatus.Available) {
            TextButton(onClick = onAction, modifier = Modifier.testTag("${testTag}_action")) {
                Text(stringResource(R.string.monitoring_readiness_review), color = MotoPrimaryBlue)
            }
        }
    }
}

private val MonitoringReadinessStatus.labelRes: Int
    get() = when (this) {
        MonitoringReadinessStatus.Ready -> R.string.monitoring_readiness_ready
        MonitoringReadinessStatus.NeedsAttention -> R.string.monitoring_readiness_needs_attention
    }

private val MonitoringRequirementStatus.labelRes: Int
    get() = when (this) {
        MonitoringRequirementStatus.Available -> R.string.monitoring_readiness_available
        MonitoringRequirementStatus.RequiresAttention -> R.string.monitoring_readiness_requires_attention
        MonitoringRequirementStatus.NotAvailable -> R.string.monitoring_readiness_not_available
    }
