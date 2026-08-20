package com.example.sos_segundoplano.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun MotoBottomBar(
    selectedItem: MotoBottomBarItem,
    modifier: Modifier = Modifier,
    enabledItems: Set<MotoBottomBarItem> = setOf(MotoBottomBarItem.Home),
    onHomeSelected: () -> Unit = {},
    onTripsSelected: () -> Unit = {},
    onSosSelected: () -> Unit = {},
    onMapSelected: () -> Unit = {},
    onProfileSelected: () -> Unit = {},
    containerColor: Color = MotoSurface,
    contentColor: Color = MotoTextSecondary,
    selectedColor: Color = MotoPrimaryDark,
    dividerColor: Color = MotoDivider
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomItem(
                label = stringResource(R.string.home_title),
                iconRes = R.drawable.ic_nav_home,
                viewportSize = 28.dp,
                assetSize = 44.dp,
                selected = selectedItem == MotoBottomBarItem.Home,
                enabled = MotoBottomBarItem.Home in enabledItems,
                onClick = onHomeSelected,
                testTag = "bottom_nav_home",
                contentColor = contentColor,
                selectedColor = selectedColor
            )
            BottomItem(
                label = stringResource(R.string.trips),
                iconRes = R.drawable.ic_motorcycle,
                viewportSize = 30.dp,
                assetSize = 52.dp,
                selected = selectedItem == MotoBottomBarItem.Trips,
                enabled = MotoBottomBarItem.Trips in enabledItems,
                onClick = onTripsSelected,
                testTag = "bottom_nav_trips",
                contentColor = contentColor,
                selectedColor = selectedColor
            )
            SosItem(
                selected = selectedItem == MotoBottomBarItem.Sos,
                enabled = MotoBottomBarItem.Sos in enabledItems,
                onClick = onSosSelected,
                labelColor = if (selectedItem == MotoBottomBarItem.Sos) selectedColor else contentColor
            )
            BottomItem(
                label = stringResource(R.string.map),
                iconRes = R.drawable.ic_location_pin,
                viewportSize = 28.dp,
                assetSize = 44.dp,
                selected = selectedItem == MotoBottomBarItem.Map,
                enabled = MotoBottomBarItem.Map in enabledItems,
                onClick = onMapSelected,
                testTag = "bottom_nav_map",
                contentColor = contentColor,
                selectedColor = selectedColor
            )
            BottomItem(
                label = stringResource(R.string.profile),
                iconRes = R.drawable.ic_nav_profile,
                viewportSize = 28.dp,
                assetSize = 48.dp,
                selected = selectedItem == MotoBottomBarItem.Profile,
                enabled = MotoBottomBarItem.Profile in enabledItems,
                onClick = onProfileSelected,
                testTag = "bottom_nav_profile",
                contentColor = contentColor,
                selectedColor = selectedColor
            )
        }
    }
}

enum class MotoBottomBarItem {
    Home,
    Trips,
    Sos,
    Map,
    Profile
}

@Composable
private fun BottomItem(
    label: String,
    @DrawableRes iconRes: Int,
    viewportSize: Dp,
    assetSize: Dp,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    contentColor: Color,
    selectedColor: Color
) {
    val color = if (selected) selectedColor else contentColor
    Column(
        modifier = Modifier
            .size(width = 56.dp, height = 58.dp)
            .testTag(testTag)
            .then(
                if (enabled) {
                    Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics {
                contentDescription = label
                role = androidx.compose.ui.semantics.Role.Button
                this.selected = selected
                if (!enabled) disabled()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MotoAssetIcon(
            iconRes = iconRes,
            contentDescription = null,
            viewportSize = viewportSize,
            assetSize = assetSize,
            tint = color
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun SosItem(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    labelColor: Color
) {
    val contentDescription = stringResource(R.string.sos)
    Column(
        modifier = Modifier
            .size(width = 68.dp, height = 70.dp)
            .offset(y = (-6).dp)
            .testTag("bottom_nav_sos")
            .then(
                if (enabled) {
                    Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics {
                this.contentDescription = contentDescription
                role = androidx.compose.ui.semantics.Role.Button
                this.selected = selected
                if (!enabled) disabled()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MotoAssetIcon(
            iconRes = R.drawable.ic_sos_badge,
            contentDescription = null,
            viewportSize = 52.dp,
            assetSize = 76.dp
        )
        Text(
            text = contentDescription,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun MotoAssetIcon(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    viewportSize: Dp,
    assetSize: Dp,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    Box(
        modifier = modifier
            .size(viewportSize)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.requiredSize(assetSize),
            contentScale = ContentScale.Fit,
            colorFilter = tint?.let(ColorFilter::tint)
        )
    }
}
