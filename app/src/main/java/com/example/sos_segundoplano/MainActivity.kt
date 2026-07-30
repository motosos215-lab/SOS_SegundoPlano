package com.example.sos_segundoplano

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
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionChecker
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.usecase.StartTripUseCase
import com.example.sos_segundoplano.features.background.MonitoringScreen
import com.example.sos_segundoplano.features.permissions.BackgroundLocationPermissionDialog
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
                    onOpenAppSettings = ::openAppSettings
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
}

@Composable
fun MotoSosApp(
    modifier: Modifier = Modifier,
    startTripUseCase: (TripSessionState) -> TripSessionState = StartTripUseCase()::invoke,
    locationPermissionStatusProvider: BackgroundLocationPermissionStatusProvider =
        BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
    onOpenAppSettings: () -> Unit = {}
) {
    var isTripActive by rememberSaveable { mutableStateOf(false) }
    var isTripStartPending by remember { mutableStateOf(false) }
    var permissionDialogStatus by remember {
        mutableStateOf<BackgroundLocationPermissionStatus?>(null)
    }
    val currentState = if (isTripActive) {
        TripSessionState.Active
    } else {
        TripSessionState.Idle
    }

    fun completeTripStartIfAllowed(status: BackgroundLocationPermissionStatus) {
        when (status) {
            BackgroundLocationPermissionStatus.Granted -> {
                if (isTripStartPending && !isTripActive) {
                    val nextState = startTripUseCase(TripSessionState.Idle)
                    isTripActive = nextState == TripSessionState.Active
                }
                isTripStartPending = false
                permissionDialogStatus = null
            }

            BackgroundLocationPermissionStatus.ForegroundMissing,
            BackgroundLocationPermissionStatus.BackgroundMissing -> {
                permissionDialogStatus = status
            }
        }
    }

    when (currentState) {
        TripSessionState.Idle -> HomeScreen(
            onStartTrip = {
                isTripStartPending = true
                completeTripStartIfAllowed(locationPermissionStatusProvider.getStatus())
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
                completeTripStartIfAllowed(locationPermissionStatusProvider.getStatus())
            },
            onDismiss = {
                isTripStartPending = false
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
