package com.example.sos_segundoplano.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = MotoPrimaryBlue,
    secondary = MotoSuccess,
    tertiary = MotoWarning,
    error = MotoAlert,
    background = MotoBackground,
    surface = MotoSurface,
    onPrimary = MotoSurface,
    onSecondary = MotoSurface,
    onTertiary = MotoPrimaryDark,
    onError = MotoSurface,
    onBackground = MotoTextPrimary,
    onSurface = MotoTextPrimary
)

@Composable
fun SOS_SegundoPlanoTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
