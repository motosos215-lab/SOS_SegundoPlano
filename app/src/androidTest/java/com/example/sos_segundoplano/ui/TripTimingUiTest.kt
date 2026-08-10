package com.example.sos_segundoplano.ui

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.sos_segundoplano.domain.trip.ElapsedRealtimeClock
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.features.background.MonitoringScreen
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class TripTimingUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun knownTimingShowsFormattedDurationAndRecompositionDoesNotResetIt() {
        val timing = MutableStateFlow<TripTimingState>(TripTimingState.Active(10_000L))
        val clock = FixedElapsedRealtimeClock(57_000L)
        val compositionKey = mutableStateOf(0)
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                key(compositionKey.value) {
                    MonitoringScreen(tripTimingStates = timing, elapsedRealtimeClock = clock)
                }
            }
        }

        composeRule.onNodeWithText("00:00:47").assertIsDisplayed()
        composeRule.runOnIdle { compositionKey.value++ }
        composeRule.onNodeWithText("00:00:47").assertIsDisplayed()
    }

    @Test fun remoteOrRestoredTripWithoutReliableLocalTimingShowsNeutralState() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitoringScreen(
                    tripTimingStates = MutableStateFlow(TripTimingState.Unknown),
                    elapsedRealtimeClock = FixedElapsedRealtimeClock(57_000L)
                )
            }
        }

        composeRule.onNodeWithText("En curso").assertIsDisplayed()
    }
}

private class FixedElapsedRealtimeClock(private val value: Long) : ElapsedRealtimeClock {
    override fun nowMillis(): Long = value
}
