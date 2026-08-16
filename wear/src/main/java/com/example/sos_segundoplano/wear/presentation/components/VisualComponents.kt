package com.example.sos_segundoplano.wear.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Text

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
