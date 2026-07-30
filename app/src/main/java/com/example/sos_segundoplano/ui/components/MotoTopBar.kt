package com.example.sos_segundoplano.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess

@Composable
fun MotoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: MotoTopBarIcon = MotoTopBarIcon.Menu
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MotoBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarIcon(
                icon = navigationIcon,
                contentDescription = when (navigationIcon) {
                    MotoTopBarIcon.Menu -> stringResource(R.string.cd_open_menu)
                    MotoTopBarIcon.Back -> stringResource(R.string.cd_back)
                    MotoTopBarIcon.Bell -> stringResource(R.string.cd_notifications)
                }
            )

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MotoPrimaryDark,
                    textAlign = TextAlign.Center
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 13.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                        color = MotoSuccess,
                        textAlign = TextAlign.Center
                    )
                }
            }

            TopBarIcon(
                icon = MotoTopBarIcon.Bell,
                contentDescription = stringResource(R.string.cd_notifications)
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MotoDivider)
        )
    }
}

enum class MotoTopBarIcon {
    Menu,
    Back,
    Bell
}

@Composable
private fun TopBarIcon(
    icon: MotoTopBarIcon,
    contentDescription: String
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .semantics {
                this.contentDescription = contentDescription
                disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon == MotoTopBarIcon.Bell) {
            Image(
                painter = painterResource(R.drawable.ic_top_notifications),
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MotoPrimaryDark)
            )
            return@Box
        }

        Canvas(modifier = Modifier.size(26.dp)) {
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            when (icon) {
                MotoTopBarIcon.Menu -> {
                    drawLine(MotoPrimaryDark, Offset(4.dp.toPx(), 8.dp.toPx()), Offset(24.dp.toPx(), 8.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(MotoPrimaryDark, Offset(4.dp.toPx(), 14.dp.toPx()), Offset(24.dp.toPx(), 14.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(MotoPrimaryDark, Offset(4.dp.toPx(), 20.dp.toPx()), Offset(24.dp.toPx(), 20.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }

                MotoTopBarIcon.Back -> {
                    drawLine(MotoPrimaryDark, Offset(17.dp.toPx(), 6.dp.toPx()), Offset(9.dp.toPx(), 14.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(MotoPrimaryDark, Offset(9.dp.toPx(), 14.dp.toPx()), Offset(17.dp.toPx(), 22.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }

                MotoTopBarIcon.Bell -> Unit
            }
        }
    }
}
