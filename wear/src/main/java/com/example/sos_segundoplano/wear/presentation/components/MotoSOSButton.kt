package com.example.sos_segundoplano.wear.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.wear.presentation.theme.MotoGreen
import com.example.sos_segundoplano.wear.presentation.theme.MotoMuted
import com.example.sos_segundoplano.wear.presentation.theme.MotoWhite

@Composable
internal fun RoundPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = MotoGreen,
    contentColor: Color = MotoWhite,
    tag: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor,
            disabledContainerColor = MotoMuted,
            disabledContentColor = MotoWhite.copy(alpha = 0.72f)
        ),
        shape = CircleShape,
        modifier = modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 184.dp)
            .heightIn(min = 48.dp)
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
