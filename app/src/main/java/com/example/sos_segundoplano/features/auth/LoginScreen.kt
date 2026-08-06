package com.example.sos_segundoplano.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark

@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onRememberMeChanged: (Boolean) -> Unit,
    onPasswordVisibilityChanged: () -> Unit,
    onSubmit: () -> Unit,
    onDismissMessage: () -> Unit,
    onRegisterWebSelected: () -> Unit,
    onPasswordRecoverySelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val emailErrorText = state.emailError?.let {
        stringResource(if (it == EmailValidationError.Required) R.string.login_email_required else R.string.login_email_invalid)
    }
    val passwordErrorText = state.passwordError?.let { stringResource(R.string.login_password_required) }
    val passwordVisibilityDescription = stringResource(
        if (state.isPasswordVisible) R.string.login_hide_password else R.string.login_show_password
    )
    val passwordStateDescription = stringResource(
        if (state.isPasswordVisible) R.string.login_password_visible else R.string.login_password_hidden
    )
    val submittingDescription = stringResource(R.string.login_submitting_description)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("login_screen"),
        containerColor = MotoBackground,
        snackbarHost = {
            if (state.authMessage == AuthUiMessage.WebComingSoon) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = onDismissMessage) {
                            Text(stringResource(R.string.close))
                        }
                    }
                ) {
                    Text(stringResource(R.string.login_web_coming_soon))
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .safeDrawingPadding()
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MotoPrimaryBlue,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.login_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MotoPrimaryDark
                    )
                    Text(
                        text = stringResource(R.string.login_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.authMessage
                    ?.takeUnless { it == AuthUiMessage.WebComingSoon }
                    ?.let { message ->
                        val messageText = stringResource(message.stringResourceId())
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    error(messageText)
                                },
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = messageText,
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmailChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_email_field")
                        .then(
                            if (emailErrorText == null) Modifier else Modifier.semantics { error(emailErrorText) }
                        ),
                    enabled = !state.isSubmitting,
                    singleLine = true,
                    label = { Text(stringResource(R.string.login_email_label)) },
                    isError = emailErrorText != null,
                    supportingText = emailErrorText?.let { error -> { Text(error) } },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )

                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_password_field")
                        .semantics {
                            stateDescription = passwordStateDescription
                            if (passwordErrorText != null) error(passwordErrorText)
                        },
                    enabled = !state.isSubmitting,
                    singleLine = true,
                    label = { Text(stringResource(R.string.login_password_label)) },
                    visualTransformation = if (state.isPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    isError = passwordErrorText != null,
                    supportingText = passwordErrorText?.let { error -> { Text(error) } },
                    trailingIcon = {
                        TextButton(
                            onClick = onPasswordVisibilityChanged,
                            enabled = !state.isSubmitting,
                            modifier = Modifier
                                .semantics { contentDescription = passwordVisibilityDescription }
                                .testTag("login_password_visibility")
                        ) {
                            Text(
                                text = stringResource(
                                    if (state.isPasswordVisible) R.string.login_hide_password else R.string.login_show_password
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onSubmit()
                        }
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.rememberMe,
                            enabled = !state.isSubmitting,
                            role = Role.Checkbox,
                            onValueChange = onRememberMeChanged
                        )
                        .semantics(mergeDescendants = true) {}
                        .testTag("login_remember_me"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.rememberMe,
                        onCheckedChange = null,
                        enabled = !state.isSubmitting
                    )
                    Text(
                        text = stringResource(R.string.login_remember_me),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onSubmit()
                    },
                    enabled = state.email.isNotEmpty() && state.password.isNotEmpty() && !state.isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("login_submit_button")
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics {
                                    contentDescription = submittingDescription
                                }
                                .testTag("login_submit_progress"),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(stringResource(R.string.login_submit))
                }

                HorizontalDivider(color = MotoDivider)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = onRegisterWebSelected,
                        enabled = !state.isSubmitting,
                        modifier = Modifier.testTag("login_register_web")
                    ) {
                        Text(stringResource(R.string.login_register_web))
                    }
                    TextButton(
                        onClick = onPasswordRecoverySelected,
                        enabled = !state.isSubmitting,
                        modifier = Modifier.testTag("login_password_recovery_web")
                    ) {
                        Text(stringResource(R.string.login_forgot_password))
                    }
                }
            }
        }
    }
}

@Composable
fun AuthRestoringScreen(modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(R.string.login_loading_description)
    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .testTag("auth_restoring_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MotoPrimaryBlue,
                fontWeight = FontWeight.Bold
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .semantics { contentDescription = loadingDescription }
                    .testTag("auth_restoring_progress")
            )
            Text(
                text = stringResource(R.string.login_restoring_session),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun AuthUiMessage.stringResourceId(): Int = when (this) {
    AuthUiMessage.InvalidCredentials -> R.string.login_invalid_credentials
    AuthUiMessage.Unauthorized -> R.string.login_unauthorized
    AuthUiMessage.AccessDenied -> R.string.login_access_denied
    AuthUiMessage.InactiveAccount -> R.string.login_inactive_account
    AuthUiMessage.RateLimited -> R.string.login_rate_limited
    AuthUiMessage.NetworkUnavailable -> R.string.login_network_unavailable
    AuthUiMessage.Timeout -> R.string.login_timeout
    AuthUiMessage.ServerFailure -> R.string.login_server_failure
    AuthUiMessage.InvalidResponse -> R.string.login_invalid_response
    AuthUiMessage.StorageFailure -> R.string.login_storage_failure
    AuthUiMessage.SessionExpired -> R.string.login_session_expired
    AuthUiMessage.StorageUnavailable -> R.string.login_storage_unavailable
    AuthUiMessage.WebComingSoon -> R.string.login_web_coming_soon
}
