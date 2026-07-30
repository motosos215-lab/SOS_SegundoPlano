package com.example.sos_segundoplano.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSurface

@Composable
fun MotoHeroTripCard(
    onStartTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startTripContentDescription = stringResource(R.string.start_trip)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(MotoPrimaryBlue, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HomeRiderIllustration()
        Text(
            text = stringResource(R.string.trip_ready_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MotoSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.trip_ready_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MotoSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = onStartTrip,
            modifier = Modifier
                .height(48.dp)
                .fillMaxWidth()
                .widthIn(max = 310.dp)
                .testTag("start_trip_button")
                .semantics { contentDescription = startTripContentDescription },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MotoSuccess)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_action_play),
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MotoSurface
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.start_trip),
                style = MaterialTheme.typography.labelLarge,
                color = MotoSurface
            )
        }
    }
}

@Composable
private fun HomeRiderIllustration() {
    val contentDescription = stringResource(R.string.cd_motorcycle_illustration)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.img_home_rider),
            contentDescription = contentDescription,
            modifier = Modifier.height(112.dp),
            contentScale = ContentScale.Fit
        )
    }
}
