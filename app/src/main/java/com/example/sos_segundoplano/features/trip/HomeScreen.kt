package com.example.sos_segundoplano.features.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.components.MotoHeroTripCard
import com.example.sos_segundoplano.ui.components.MotoInformationCard
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.components.deviceContentDescription
import com.example.sos_segundoplano.ui.components.emergencyContactContentDescription
import com.example.sos_segundoplano.ui.theme.MotoBackground

@Composable
fun HomeScreen(
    onStartTrip: () -> Unit,
    onProfileSelected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = stringResource(R.string.home_title),
                navigationIcon = MotoTopBarIcon.Menu
            )
        },
        bottomBar = {
            MotoBottomBar(
                selectedItem = MotoBottomBarItem.Home,
                enabledItems = setOf(MotoBottomBarItem.Home, MotoBottomBarItem.Profile),
                onProfileSelected = onProfileSelected
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MotoHeroTripCard(onStartTrip = onStartTrip)
            MotoInformationCard(
                title = stringResource(R.string.emergency_contact),
                state = stringResource(R.string.no_contact_configured),
                description = stringResource(R.string.emergency_contact_description),
                contentDescription = emergencyContactContentDescription(),
                iconRes = R.drawable.ic_nav_profile,
                iconViewportSize = 40.dp,
                iconAssetSize = 64.dp
            )
            MotoInformationCard(
                title = stringResource(R.string.device),
                state = stringResource(R.string.no_device_linked),
                description = stringResource(R.string.device_linking_future_message),
                contentDescription = deviceContentDescription(),
                iconRes = R.drawable.ic_device_watch,
                iconViewportSize = 42.dp,
                iconAssetSize = 76.dp
            )
        }
    }
}
