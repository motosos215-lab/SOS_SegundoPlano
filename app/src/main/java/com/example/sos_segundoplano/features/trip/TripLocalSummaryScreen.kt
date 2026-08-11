package com.example.sos_segundoplano.features.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.trip.TripLocalSummary
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSuccessSoft
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun TripLocalSummaryScreen(
    summary: TripLocalSummary,
    onReturnHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onReturnHome)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MotoBackground)
            .padding(24.dp)
            .testTag("trip_local_summary_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.trip_summary_success_mark),
            modifier = Modifier
                .background(MotoSuccessSoft, RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MotoSuccess,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.trip_summary_title),
            modifier = Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.headlineMedium,
            color = MotoTextPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.trip_summary_duration_label),
            modifier = Modifier.padding(top = 28.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MotoTextSecondary
        )
        Text(
            text = summary.durationText ?: stringResource(R.string.trip_summary_duration_unknown),
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag("trip_summary_duration"),
            style = MaterialTheme.typography.displaySmall,
            color = MotoTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Button(
            onClick = onReturnHome,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp)
                .heightIn(min = 52.dp)
                .testTag("trip_summary_return_home"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryBlue)
        ) {
            Text(stringResource(R.string.trip_summary_return_home), color = MotoSurface)
        }
    }
}
