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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun BackgroundLocationPermissionDialog(
    status: BackgroundLocationPermissionStatus,
    onOpenSettings: () -> Unit,
    onRecheckPermissions: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .testTag("location_permission_dialog")
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .background(MotoSurface, RoundedCornerShape(18.dp))
                .border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LocationPermissionIcon()
            Text(
                text = stringResource(status.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MotoPrimaryDark,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(status.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MotoTextSecondary
            )
            Text(
                text = stringResource(status.helpRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MotoTextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("open_location_settings_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryBlue)
            ) {
                Text(text = stringResource(R.string.open_app_settings), color = MotoSurface)
            }
            OutlinedButton(
                onClick = onRecheckPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("recheck_location_permissions_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MotoPrimaryBlue),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MotoPrimaryBlue)
            ) {
                Text(text = stringResource(R.string.recheck_permissions), color = MotoPrimaryBlue)
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp)
                    .testTag("dismiss_location_permission_button")
            ) {
                Text(
                    text = stringResource(R.string.not_now),
                    color = MotoTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun LocationPermissionIcon() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MotoBackground),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_location_pin),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            colorFilter = ColorFilter.tint(MotoPrimaryBlue)
        )
    }
}

private val BackgroundLocationPermissionStatus.titleRes: Int
    get() = when (this) {
        BackgroundLocationPermissionStatus.ForegroundMissing -> R.string.location_permission_required_title
        BackgroundLocationPermissionStatus.BackgroundMissing -> R.string.background_location_required_title
        BackgroundLocationPermissionStatus.Granted -> R.string.background_location_authorized
    }

private val BackgroundLocationPermissionStatus.descriptionRes: Int
    get() = when (this) {
        BackgroundLocationPermissionStatus.ForegroundMissing -> R.string.location_permission_foreground_explanation
        BackgroundLocationPermissionStatus.BackgroundMissing -> R.string.background_location_explanation
        BackgroundLocationPermissionStatus.Granted -> R.string.background_location_authorized
    }

private val BackgroundLocationPermissionStatus.helpRes: Int
    get() = when (this) {
        BackgroundLocationPermissionStatus.ForegroundMissing -> R.string.location_permission_settings_help
        BackgroundLocationPermissionStatus.BackgroundMissing -> R.string.background_location_settings_help
        BackgroundLocationPermissionStatus.Granted -> R.string.background_location_authorized
    }
