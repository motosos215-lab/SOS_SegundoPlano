package com.example.sos_segundoplano.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun MotoMetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    @DrawableRes iconRes: Int? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .wrapContentHeight()
            .background(MotoSurface, RoundedCornerShape(16.dp))
            .border(1.dp, MotoDivider, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (iconRes == null) 0.dp else 28.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MotoTextSecondary,
                softWrap = true
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MotoTextPrimary,
                fontWeight = FontWeight.SemiBold,
                softWrap = true
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MotoTextSecondary,
                    textAlign = TextAlign.Start,
                    softWrap = true
                )
            }
        }
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp),
                tint = MotoSuccess
            )
        }
    }
}
