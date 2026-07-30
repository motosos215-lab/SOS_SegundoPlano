package com.example.sos_segundoplano

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusChecker
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusProvider
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionChecker
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.usecase.StartTripUseCase
import com.example.sos_segundoplano.features.background.MonitoringScreen
import com.example.sos_segundoplano.features.permissions.BackgroundLocationPermissionDialog
import com.example.sos_segundoplano.features.permissions.NotificationPermissionDialog
import com.example.sos_segundoplano.features.trip.HomeScreen
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    locationPermissionStatusProvider = BackgroundLocationPermissionChecker(applicationContext),
                    notificationStatusProvider = AppNotificationStatusChecker(applicationContext),
                    onOpenAppSettings = ::openAppSettings,
                    onOpenNotificationSettings = ::openNotificationSettings
                )
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun openNotificationSettings() {
        val notificationSettingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

        try {
            startActivity(notificationSettingsIntent)
        } catch (_: ActivityNotFoundException) {
            val fallbackIntent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(fallbackIntent)
        }
    }
}

@Composable
fun MotoSosApp(
    modifier: Modifier = Modifier,
    startTripUseCase: (TripSessionState) -> TripSessionState = StartTripUseCase()::invoke,
    locationPermissionStatusProvider: BackgroundLocationPermissionStatusProvider =
        BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
    notificationStatusProvider: AppNotificationStatusProvider =
        AppNotificationStatusProvider { AppNotificationStatus.Enabled },
    onOpenAppSettings: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {}
) {
    var isTripActive by rememberSaveable { mutableStateOf(false) }
    var isTripStartPending by remember { mutableStateOf(false) }
    var permissionDialogStatus by remember {
        mutableStateOf<BackgroundLocationPermissionStatus?>(null)
    }
    var isNotificationDialogVisible by remember { mutableStateOf(false) }
    val currentState = if (isTripActive) {
        TripSessionState.Active
    } else {
        TripSessionState.Idle
    }

    fun validateTripStartRequirements() {
        when (val locationStatus = locationPermissionStatusProvider.getStatus()) {
            BackgroundLocationPermissionStatus.Granted -> {
                when (notificationStatusProvider.getStatus()) {
                    AppNotificationStatus.Enabled -> {
                        if (isTripStartPending && currentState == TripSessionState.Idle) {
                            val nextState = startTripUseCase(currentState)
                            isTripActive = nextState == TripSessionState.Active
                        }
                        isTripStartPending = false
                        permissionDialogStatus = null
                        isNotificationDialogVisible = false
                    }

                    AppNotificationStatus.Disabled -> {
                        permissionDialogStatus = null
                        isNotificationDialogVisible = true
                    }
                }
            }

            BackgroundLocationPermissionStatus.ForegroundMissing,
            BackgroundLocationPermissionStatus.BackgroundMissing -> {
                permissionDialogStatus = locationStatus
                isNotificationDialogVisible = false
            }
        }
    }

    when (currentState) {
        TripSessionState.Idle -> HomeScreen(
            onStartTrip = {
                isTripStartPending = true
                validateTripStartRequirements()
            },
            modifier = modifier
        )

        TripSessionState.Active -> MonitoringScreen(
            modifier = modifier
        )
    }

    permissionDialogStatus?.let { status ->
        BackgroundLocationPermissionDialog(
            status = status,
            onOpenSettings = onOpenAppSettings,
            onRecheckPermissions = {
                validateTripStartRequirements()
            },
            onDismiss = {
                isTripStartPending = false
                permissionDialogStatus = null
                isNotificationDialogVisible = false
            }
        )
    }
    if (isNotificationDialogVisible) {
        NotificationPermissionDialog(
            onOpenSettings = onOpenNotificationSettings,
            onRecheckPermissions = {
                validateTripStartRequirements()
            },
            onDismiss = {
                isTripStartPending = false
                isNotificationDialogVisible = false
                permissionDialogStatus = null
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MotoSosAppPreview() {
    SOS_SegundoPlanoTheme {
        MotoSosApp()
    }
}
