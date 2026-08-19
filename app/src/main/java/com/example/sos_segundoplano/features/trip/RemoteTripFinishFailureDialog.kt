package com.example.sos_segundoplano.features.trip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun RemoteTripFinishFailureDialog(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .testTag("remote_trip_finish_failure_dialog")
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .background(MotoSurface, RoundedCornerShape(18.dp))
                .border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text(
                text = stringResource(R.string.trip_finish_failure_title),
                style = MaterialTheme.typography.titleLarge,
                color = MotoPrimaryDark
            )
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Text(
                text = stringResource(R.string.trip_finish_failure_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MotoPrimaryDark
            )
            Text(
                text = stringResource(R.string.trip_finish_failure_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MotoTextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .heightIn(min = 48.dp)
                    .testTag("retry_remote_trip_finish_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MotoAlert)
            ) {
                Text(stringResource(R.string.retry))
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
                    .testTag("dismiss_remote_trip_finish_failure_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MotoAlert),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MotoSurface, contentColor = MotoAlert)
            ) {
                Text(stringResource(R.string.continue_trip))
            }
        }
    }
}
