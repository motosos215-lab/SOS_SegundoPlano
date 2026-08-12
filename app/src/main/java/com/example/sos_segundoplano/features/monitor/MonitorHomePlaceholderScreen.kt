package com.example.sos_segundoplano.features.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sos_segundoplano.domain.auth.authenticatedIdentityOrNull
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun MonitorRoot(authRepository: AuthRepository) {
    val scope = rememberCoroutineScope()
    val sessionState by authRepository.observeSession().collectAsStateWithLifecycle()
    val sessionIdentity = sessionState.authenticatedIdentityOrNull()
    MonitorHomePlaceholderScreen(
        onLogout = {
            sessionIdentity?.let { expected ->
                scope.launch {
                    authRepository.logoutIfCurrent(expected)
                }
            }
        }
    )
}

@Composable
fun MonitorHomePlaceholderScreen(
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Modo Monitor", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Sesión Monitor activa",
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onLogout) {
            Text("Cerrar sesión")
        }
    }
}
