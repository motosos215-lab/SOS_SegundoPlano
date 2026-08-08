package com.example.sos_segundoplano.features.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.core.wear.WearProvider
import com.example.sos_segundoplano.domain.profile.RiderProfile
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.repository.ProfileRepository
import com.example.sos_segundoplano.domain.wear.WatchConnectionRepository
import com.example.sos_segundoplano.features.wear.WatchConnectionRoute
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSuccessSoft
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import java.util.Locale

@Composable
fun ProfileRoute(
    profileRepository: ProfileRepository,
    authRepository: AuthRepository,
    onHomeSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showWatchConnection by rememberSaveable { mutableStateOf(false) }
    if (showWatchConnection) {
        val context = LocalContext.current
        val watchRepository: WatchConnectionRepository = remember(context) {
            WearProvider.get(context)
        }
        WatchConnectionRoute(
            repository = watchRepository,
            authRepository = authRepository,
            onBack = { showWatchConnection = false },
            onHomeSelected = {
                showWatchConnection = false
                onHomeSelected()
            },
            onProfileSelected = { showWatchConnection = false },
            modifier = modifier
        )
        return
    }
    val factory = remember(profileRepository, authRepository) {
        ProfileViewModel.Factory(profileRepository, authRepository)
    }
    val viewModel: ProfileViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        onHomeSelected = onHomeSelected,
        onRetry = viewModel::retry,
        onLogoutSelected = viewModel::showLogoutDialog,
        onLogoutDismissed = viewModel::dismissLogoutDialog,
        onLogoutConfirmed = viewModel::confirmLogout,
        onWatchConnectionSelected = { showWatchConnection = true },
        modifier = modifier
    )
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onHomeSelected: () -> Unit,
    onRetry: () -> Unit,
    onLogoutSelected: () -> Unit,
    onLogoutDismissed: () -> Unit,
    onLogoutConfirmed: () -> Unit,
    onWatchConnectionSelected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onHomeSelected)
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("profile_screen"),
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = stringResource(R.string.profile_title),
                subtitle = stringResource(R.string.profile_subtitle),
                navigationIcon = MotoTopBarIcon.Back
            )
        },
        bottomBar = {
            MotoBottomBar(
                selectedItem = MotoBottomBarItem.Profile,
                onHomeSelected = onHomeSelected,
                onProfileSelected = {},
                enabledItems = setOf(MotoBottomBarItem.Home, MotoBottomBarItem.Profile)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                state.loading -> ProfileLoading()
                state.profile != null -> ProfileContent(
                    profile = state.profile,
                    isLoggingOut = state.isLoggingOut,
                    onLogoutSelected = onLogoutSelected,
                    onWatchConnectionSelected = onWatchConnectionSelected
                )
                state.error != null -> ProfileErrorContent(
                    error = state.error,
                    isRetrying = state.isRetrying,
                    onRetry = onRetry
                )
            }
        }
    }
    if (state.isLogoutDialogVisible) {
        LogoutDialog(
            isLoggingOut = state.isLoggingOut,
            onDismiss = onLogoutDismissed,
            onConfirm = onLogoutConfirmed
        )
    }
}

@Composable
private fun ProfileLoading() {
    val description = stringResource(R.string.profile_loading_description)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("profile_loading")
            .semantics { contentDescription = description }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MotoPrimaryBlue)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.profile_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MotoTextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProfileContent(
    profile: RiderProfile,
    isLoggingOut: Boolean,
    onLogoutSelected: () -> Unit,
    onWatchConnectionSelected: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        IdentityCard(profile)
        WatchEntryCard(onClick = onWatchConnectionSelected)
        InfoCard(title = stringResource(R.string.profile_personal_info)) {
            ReadOnlyField(stringResource(R.string.profile_full_name), profile.fullName, "profile_full_name")
            ReadOnlyField(stringResource(R.string.profile_email), profile.email, "profile_email")
            ReadOnlyField(stringResource(R.string.profile_phone), profile.phoneNumber ?: stringResource(R.string.profile_not_available))
            ReadOnlyField(stringResource(R.string.profile_account_type), profile.role)
            ReadOnlyField(stringResource(R.string.profile_account_status), stringResource(R.string.profile_active_account))
        }
        InfoCard(title = stringResource(R.string.profile_account_info)) {
            ReadOnlyField(stringResource(R.string.profile_created_at), formatProfileDate(profile.createdAtUtc) ?: stringResource(R.string.profile_not_available))
            ReadOnlyField(stringResource(R.string.profile_updated_at), formatProfileDate(profile.updatedAtUtc) ?: stringResource(R.string.profile_not_available))
            ReadOnlyField(stringResource(R.string.profile_last_login), formatProfileDate(profile.lastLoginAtUtc) ?: stringResource(R.string.profile_not_available))
        }
        OutlinedButton(
            onClick = onLogoutSelected,
            enabled = !isLoggingOut,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("profile_logout_button"),
            border = BorderStroke(1.5.dp, MotoAlert),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MotoSurface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(stringResource(R.string.profile_logout), color = MotoAlert)
        }
    }
}

@Composable
private fun IdentityCard(profile: RiderProfile) {
    val initials = profileInitials(profile.fullName)
    val cd = stringResource(R.string.profile_initials_cd, initials)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MotoSurface, RoundedCornerShape(18.dp))
            .border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MotoPrimaryBlue)
                .semantics { contentDescription = cd },
            contentAlignment = Alignment.Center
        ) {
            Text(initials, style = MaterialTheme.typography.titleLarge, color = MotoSurface)
        }
        Column(modifier = Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = profile.fullName,
                style = MaterialTheme.typography.titleMedium,
                color = MotoTextPrimary,
                modifier = Modifier.semantics { heading() }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfilePill(text = profile.role, color = MotoPrimaryDark, background = MotoPrimaryBlue.copy(alpha = 0.10f))
                ProfilePill(text = stringResource(R.string.profile_active_account), color = MotoSuccess, background = MotoSuccessSoft)
            }
        }
    }
}

@Composable
private fun ProfilePill(text: String, color: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun WatchEntryCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MotoSurface, RoundedCornerShape(18.dp))
            .border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp)
            .testTag("profile_watch_connection_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.watch_entry_title),
            style = MaterialTheme.typography.titleMedium,
            color = MotoTextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.watch_entry_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MotoTextSecondary
        )
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MotoSurface, RoundedCornerShape(18.dp))
            .border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MotoTextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String, testTag: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MotoTextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MotoTextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProfileErrorContent(
    error: ProfileUiError,
    isRetrying: Boolean,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.profile_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MotoTextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(error.messageRes()),
            style = MaterialTheme.typography.bodyLarge,
            color = MotoTextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onRetry,
            enabled = !isRetrying,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("profile_retry_button"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryBlue)
        ) {
            Text(stringResource(R.string.retry), color = MotoSurface)
        }
    }
}

@Composable
private fun LogoutDialog(
    isLoggingOut: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isLoggingOut) onDismiss() },
        title = { Text(stringResource(R.string.profile_logout_dialog_title)) },
        text = { Text(stringResource(R.string.profile_logout_dialog_description)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isLoggingOut,
                modifier = Modifier.testTag("profile_logout_confirm")
            ) { Text(stringResource(R.string.profile_logout), color = MotoAlert) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoggingOut,
                modifier = Modifier.testTag("profile_logout_cancel")
            ) { Text(stringResource(R.string.cancel)) }
        },
        modifier = Modifier.testTag("profile_logout_dialog")
    )
}

fun profileInitials(fullName: String, locale: Locale = Locale.getDefault()): String {
    val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().firstOrNull()?.toString().orEmpty()
    val last = parts.takeIf { it.size > 1 }?.last()?.firstOrNull()?.toString().orEmpty()
    return (first + last).take(2).uppercase(locale).ifBlank { "?" }
}

private fun ProfileUiError.messageRes(): Int = when (this) {
    ProfileUiError.NetworkUnavailable -> R.string.profile_error_network
    ProfileUiError.Timeout -> R.string.profile_error_timeout
    ProfileUiError.ServerFailure -> R.string.profile_error_server
    ProfileUiError.RateLimited -> R.string.profile_error_rate_limited
    ProfileUiError.InvalidResponse -> R.string.profile_error_invalid_response
    ProfileUiError.SessionUnavailable -> R.string.profile_error_session
}
