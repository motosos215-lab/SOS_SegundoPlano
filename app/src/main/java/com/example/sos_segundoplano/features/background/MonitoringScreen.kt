package com.example.sos_segundoplano.features.background

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.components.MotoMetricCard
import com.example.sos_segundoplano.ui.components.MotoMonitoringIndicator
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDisabled
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoSuccess

@Composable
fun MonitoringScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("monitoring_screen"),
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = stringResource(R.string.monitoring_title),
                subtitle = stringResource(R.string.active_trip),
                navigationIcon = MotoTopBarIcon.Back
            )
        },
        bottomBar = {
            MotoBottomBar(selectedItem = MotoBottomBarItem.Home)
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val useFlexibleBottomSpace = maxHeight >= 560.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (useFlexibleBottomSpace) {
                            Modifier
                        } else {
                            Modifier.verticalScroll(rememberScrollState())
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                MotoMonitoringIndicator()
                Text(
                    text = stringResource(R.string.background_location_authorized),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MotoSuccess,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(8.dp))
                MetricsGrid()
                if (useFlexibleBottomSpace) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("finish_trip_disabled_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MotoSurface,
                        disabledContainerColor = MotoSurface,
                        disabledContentColor = MotoDisabled
                    ),
                    border = BorderStroke(1.5.dp, MotoAlert)
                ) {
                    Text(text = stringResource(R.string.finish_trip), color = MotoAlert)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun MetricsGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MotoMetricCard(
                title = stringResource(R.string.speed),
                value = stringResource(R.string.not_available_value),
                unit = stringResource(R.string.speed_unit),
                modifier = Modifier.weight(1f)
            )
            MotoMetricCard(
                title = stringResource(R.string.gps_accuracy),
                value = stringResource(R.string.pending),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MotoMetricCard(
                title = stringResource(R.string.mobile_battery),
                value = stringResource(R.string.not_available_value),
                iconRes = R.drawable.ic_metric_battery,
                modifier = Modifier.weight(1f)
            )
            MotoMetricCard(
                title = stringResource(R.string.signal),
                value = stringResource(R.string.pending),
                iconRes = R.drawable.ic_metric_signal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
