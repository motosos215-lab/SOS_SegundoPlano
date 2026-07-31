package com.example.sos_segundoplano.features.permissions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun MonitoringStopFailureDialog(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .testTag("monitoring_stop_failure_dialog")
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .background(MotoSurface, RoundedCornerShape(18.dp))
                .border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonitoringStopFailureIcon()
            Text(
                text = stringResource(R.string.monitoring_stop_failure_title),
                style = MaterialTheme.typography.titleMedium,
                color = MotoPrimaryDark,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.monitoring_stop_failure_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MotoTextSecondary
            )
            Text(
                text = stringResource(R.string.monitoring_stop_failure_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MotoTextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("retry_monitoring_stop_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MotoAlert)
            ) {
                Text(text = stringResource(R.string.retry), color = MotoSurface)
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("dismiss_monitoring_stop_failure_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MotoAlert),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MotoAlert)
            ) {
                Text(text = stringResource(R.string.continue_trip), color = MotoAlert)
            }
        }
    }
}

@Composable
private fun MonitoringStopFailureIcon() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MotoBackground),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_top_notifications),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            colorFilter = ColorFilter.tint(MotoAlert)
        )
    }
}
