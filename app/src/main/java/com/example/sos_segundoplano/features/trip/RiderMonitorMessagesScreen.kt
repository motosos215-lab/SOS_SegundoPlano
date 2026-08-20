package com.example.sos_segundoplano.features.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackMessage
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackType
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.format.DisplayFormatters
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun RiderMonitorMessagesScreen(
    messages: List<RiderMonitorFeedbackMessage>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("rider_monitor_messages_screen"),
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = stringResource(R.string.rider_messages_title),
                navigationIcon = MotoTopBarIcon.Back,
                showNavigationIcon = true,
                showNotificationsIcon = false,
                onNavigationClick = onBack
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.rider_messages_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MotoTextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.rider_messages_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MotoTextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.rider_messages_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MotoTextSecondary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                items(
                    items = messages,
                    key = { it.notificationDeliveryAttemptId }
                ) { message ->
                    RiderMonitorFeedbackCard(message)
                }
            }
        }
    }
}

@Composable
private fun RiderMonitorFeedbackCard(message: RiderMonitorFeedbackMessage) {
    val accent = when (message.type) {
        RiderMonitorFeedbackType.Viewed -> MotoPrimaryBlue
        RiderMonitorFeedbackType.Acknowledged -> MotoSuccess
        RiderMonitorFeedbackType.Declined -> MotoAlert
    }
    val title = when (message.type) {
        RiderMonitorFeedbackType.Viewed -> stringResource(R.string.rider_message_viewed_title)
        RiderMonitorFeedbackType.Acknowledged -> stringResource(R.string.rider_message_acknowledged_title)
        RiderMonitorFeedbackType.Declined -> stringResource(R.string.rider_message_declined_title)
    }
    val body = message.body?.takeIf { it.isNotBlank() } ?: when (message.type) {
        RiderMonitorFeedbackType.Viewed -> stringResource(R.string.rider_message_viewed_body)
        RiderMonitorFeedbackType.Acknowledged -> stringResource(R.string.rider_message_acknowledged_body)
        RiderMonitorFeedbackType.Declined -> stringResource(R.string.rider_message_declined_body)
    }
    val time = message.occurredAtUtc?.let { DisplayFormatters.dateTime(it) }
        ?: stringResource(R.string.rider_message_recent)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MotoSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (!message.isRead) {
                    Text(
                        text = stringResource(R.string.rider_message_new),
                        style = MaterialTheme.typography.labelSmall,
                        color = MotoAlert,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MotoTextPrimary)
            Text(time, style = MaterialTheme.typography.labelMedium, color = MotoTextSecondary)
        }
    }
}
