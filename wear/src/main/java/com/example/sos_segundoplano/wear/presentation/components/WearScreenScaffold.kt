package com.example.sos_segundoplano.wear.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.wear.presentation.theme.MotoBlueGlow
import com.example.sos_segundoplano.wear.presentation.theme.MotoNavy

/** Material3-compatible adaptation of the donor's circular Wear scaffold. */
@Composable
internal fun WearScreenScaffold(
    backgroundColor: Color = MotoNavy,
    accentGlowColor: Color = MotoBlueGlow,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(backgroundColor.copy(alpha = 0.96f), backgroundColor)
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = accentGlowColor.copy(alpha = 0.12f),
                radius = size.minDimension * 0.72f,
                center = Offset(size.width / 2f, -size.height * 0.04f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.22f),
                radius = size.minDimension * 0.76f,
                center = Offset(size.width / 2f, size.height * 1.12f)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            content = content
        )
    }
}
