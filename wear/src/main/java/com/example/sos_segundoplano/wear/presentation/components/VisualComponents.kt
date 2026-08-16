package com.example.sos_segundoplano.wear.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sos_segundoplano.wear.presentation.theme.MotoBlueGray
import com.example.sos_segundoplano.wear.presentation.theme.MotoCardBlue
import com.example.sos_segundoplano.wear.presentation.theme.MotoCardBlueLight
import com.example.sos_segundoplano.wear.presentation.theme.MotoGreen
import com.example.sos_segundoplano.wear.presentation.theme.MotoNavy
import com.example.sos_segundoplano.wear.presentation.theme.MotoProgressGreen
import com.example.sos_segundoplano.wear.presentation.theme.MotoRed
import com.example.sos_segundoplano.wear.presentation.theme.MotoWhite

@Composable
internal fun EyebrowLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(), modifier = modifier, color = MotoProgressGreen,
        fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
}

@Composable
internal fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text, modifier = modifier.widthIn(max = 176.dp), color = MotoWhite,
        fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, maxLines = 2
    )
}

@Composable
internal fun ScreenSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text, modifier = modifier.widthIn(max = 168.dp), color = MotoBlueGray,
        fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center
    )
}

@Composable
internal fun HeroSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(118.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(MotoCardBlueLight, MotoCardBlue, MotoNavy)))
            .border(1.dp, MotoProgressGreen.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

@Composable
internal fun ConnectionBadge(label: String, modifier: Modifier = Modifier) {
    val color = when {
        "desconect" in label.lowercase() || "sin conexión" in label.lowercase() -> MotoRed
        else -> MotoGreen
    }
    Row(
        modifier = modifier
            .widthIn(max = 176.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = MotoWhite, fontSize = 9.sp, lineHeight = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun CompactMetricStrip(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String,
    thirdLabel: String,
    thirdValue: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().widthIn(max = 182.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CompactMetric(firstLabel, firstValue, Modifier.weight(1f))
        CompactMetric(secondLabel, secondValue, Modifier.weight(1f))
        CompactMetric(thirdLabel, thirdValue, Modifier.weight(1f))
    }
}

@Composable
private fun CompactMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(MotoCardBlue.copy(alpha = 0.94f))
            .border(1.dp, com.example.sos_segundoplano.wear.presentation.theme.MotoCardBorder.copy(alpha = 0.72f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 5.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(value, color = MotoWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        Text(label, color = com.example.sos_segundoplano.wear.presentation.theme.MotoMuted, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
internal fun MetricPill(label: String, value: String, accentColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 182.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(MotoCardBlue.copy(alpha = 0.94f))
            .border(1.dp, accentColor.copy(alpha = 0.52f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MotoBlueGray, fontSize = 10.sp)
        Text(value, color = MotoWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun InfoBanner(title: String, message: String, accentColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 182.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(MotoCardBlue.copy(alpha = 0.92f))
            .border(1.dp, accentColor.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accentColor))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MotoWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = MotoBlueGray, fontSize = 9.sp, lineHeight = 11.sp)
        }
    }
}
