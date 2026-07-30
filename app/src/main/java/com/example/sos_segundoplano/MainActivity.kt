package com.example.sos_segundoplano

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.usecase.StartTripUseCase
import com.example.sos_segundoplano.features.background.MonitoringScreen
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
                MotoSosApp()
            }
        }
    }
}

@Composable
fun MotoSosApp(
    modifier: Modifier = Modifier,
    startTripUseCase: StartTripUseCase = StartTripUseCase()
) {
    var isTripActive by rememberSaveable { mutableStateOf(false) }
    val currentState = if (isTripActive) {
        TripSessionState.Active
    } else {
        TripSessionState.Idle
    }

    when (currentState) {
        TripSessionState.Idle -> HomeScreen(
            onStartTrip = {
                val nextState = startTripUseCase(currentState)
                isTripActive = nextState == TripSessionState.Active
            },
            modifier = modifier
        )

        TripSessionState.Active -> MonitoringScreen(
            modifier = modifier
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
