package com.example.sos_segundoplano.features.sos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.components.MotoAssetIcon
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSurface

private val SosDarkRed = lerp(MotoAlert, MotoPrimaryDark, 0.58f)
private val SosDeepRed = lerp(MotoAlert, Color.Black, 0.78f)
private val SosNavRed = lerp(MotoAlert, Color.Black, 0.72f)

@Composable
fun RiderSosScreen(
    canRequestLocalHelp: Boolean,
    onRequestLocalHelp: () -> Unit,
    onNavigateBack: () -> Unit,
    onHomeSelected: () -> Unit = onNavigateBack,
    onProfileSelected: () -> Unit = onNavigateBack,
    modifier: Modifier = Modifier
) {
    val sendDescription = stringResource(R.string.sos_manual_send_cd)
    val cancelDescription = stringResource(R.string.sos_manual_cancel_cd)
    var requestAccepted by rememberSaveable { mutableStateOf(false) }
    var showUnavailable by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onNavigateBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to SosDeepRed,
                    0.42f to SosDarkRed,
                    0.68f to MotoAlert.copy(alpha = 0.86f),
                    1f to SosDeepRed
                )
            )
            .testTag("rider_sos_screen")
    ) {
        SosTopBar(onNavigateBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SosRadar()
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.sos_manual_help_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MotoSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.sos_manual_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MotoSurface.copy(alpha = 0.92f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.9f)
            )
            if (showUnavailable) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.sos_manual_local_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MotoSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("sos_local_unavailable")
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    if (canRequestLocalHelp && !requestAccepted) {
                        requestAccepted = true
                        onRequestLocalHelp()
                    } else if (!canRequestLocalHelp) {
                        showUnavailable = true
                    }
                },
                enabled = !requestAccepted,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("send_sos_button")
                    .semantics {
                        contentDescription = sendDescription
                    },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotoAlert,
                    contentColor = MotoSurface,
                    disabledContainerColor = MotoAlert.copy(alpha = 0.48f),
                    disabledContentColor = MotoSurface.copy(alpha = 0.74f)
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
            ) {
                SendIcon()
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(R.string.sos_manual_send),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNavigateBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("cancel_sos_button")
                    .semantics { contentDescription = cancelDescription },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotoSurface,
                    contentColor = MotoPrimaryDark
                )
            ) {
                Text(
                    text = stringResource(R.string.sos_manual_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        MotoBottomBar(
            selectedItem = MotoBottomBarItem.Sos,
            enabledItems = setOf(MotoBottomBarItem.Home, MotoBottomBarItem.Sos, MotoBottomBarItem.Profile),
            onHomeSelected = onHomeSelected,
            onSosSelected = {},
            onProfileSelected = onProfileSelected,
            containerColor = SosNavRed,
            contentColor = MotoSurface.copy(alpha = 0.72f),
            selectedColor = MotoSurface,
            dividerColor = MotoSurface.copy(alpha = 0.16f)
        )
    }
}

@Composable
private fun SosTopBar(onNavigateBack: () -> Unit) {
    val backDescription = stringResource(R.string.cd_back)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.sos_manual_title),
                style = MaterialTheme.typography.titleLarge,
                color = MotoSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onNavigateBack)
                    .testTag("sos_back_button")
                    .semantics { contentDescription = backDescription },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(26.dp)) {
                    drawLine(MotoSurface, Offset(17.dp.toPx(), 5.dp.toPx()), Offset(8.dp.toPx(), 13.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
                    drawLine(MotoSurface, Offset(8.dp.toPx(), 13.dp.toPx()), Offset(17.dp.toPx(), 21.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun SosRadar() {
    val badgeDescription = stringResource(R.string.sos_manual_badge_cd)
    Box(
        modifier = Modifier
            .size(210.dp)
            .testTag("sos_central_badge")
            .semantics { contentDescription = badgeDescription },
        contentAlignment = Alignment.Center
    ) {
        listOf(210.dp, 184.dp, 160.dp).forEachIndexed { index, diameter ->
            Box(
                Modifier
                    .size(diameter)
                    .border(
                        width = if (index == 2) 2.dp else 1.dp,
                        color = MotoSurface.copy(alpha = 0.18f + index * 0.08f),
                        shape = CircleShape
                    )
            )
        }
        MotoAssetIcon(
            iconRes = R.drawable.ic_sos_badge,
            contentDescription = null,
            viewportSize = 148.dp,
            assetSize = 222.dp,
            modifier = Modifier.clip(CircleShape)
        )
    }
}

@Composable
private fun SendIcon() {
    Canvas(
        modifier = Modifier.size(22.dp)
    ) {
        val path = Path().apply {
            moveTo(2.dp.toPx(), 3.dp.toPx())
            lineTo(20.dp.toPx(), 11.dp.toPx())
            lineTo(2.dp.toPx(), 19.dp.toPx())
            lineTo(6.dp.toPx(), 12.5.dp.toPx())
            lineTo(13.dp.toPx(), 11.dp.toPx())
            lineTo(6.dp.toPx(), 9.5.dp.toPx())
            close()
        }
        drawPath(path, color = MotoSurface)
    }
}
