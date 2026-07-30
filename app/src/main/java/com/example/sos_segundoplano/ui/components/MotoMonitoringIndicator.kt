package com.example.sos_segundoplano.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSuccessSoft
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun MotoMonitoringIndicator(modifier: Modifier = Modifier) {
    val contentDescription = stringResource(R.string.cd_monitoring_indicator)
    Box(
        modifier = modifier
            .size(198.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(198.dp)) {
            drawCircle(MotoSuccessSoft, radius = size.minDimension / 2f)
            drawArc(
                color = MotoSuccess,
                startAngle = -90f,
                sweepAngle = 285f,
                useCenter = false,
                style = Stroke(width = 11.dp.toPx()),
                topLeft = androidx.compose.ui.geometry.Offset(6.dp.toPx(), 6.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx())
            )
        }
        Column(
            modifier = Modifier
                .size(152.dp)
                .background(MotoSurface, CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            MotoAssetIcon(
                iconRes = R.drawable.ic_motorcycle,
                contentDescription = stringResource(R.string.cd_motorcycle_illustration),
                viewportSize = 52.dp,
                assetSize = 90.dp,
                tint = MotoPrimaryBlue
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.session_started),
                style = MaterialTheme.typography.bodyLarge,
                color = MotoTextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 145.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.time_placeholder),
                style = MaterialTheme.typography.headlineMedium,
                color = MotoTextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.trip_time),
                style = MaterialTheme.typography.labelSmall,
                color = MotoTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
