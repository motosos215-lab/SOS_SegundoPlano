package com.example.sos_segundoplano.wear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class WearPermissionActivity : ComponentActivity() {
    private val permissionChecker by lazy { AndroidWearHealthPermissionChecker(this) }
    private val permissionRefresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PermissionContent(
                refreshSignal = permissionRefresh.intValue,
                currentStatus = { permissionChecker.status() },
                missingPermissions = { permissionChecker.missingPermissions() },
                onPermissionsResult = { permanentlyDeniedCandidates ->
                    permissionChecker.status(
                        permissionChecker.permanentlyDeniedPermissions(this, permanentlyDeniedCandidates)
                    )
                },
                onOpenSettings = ::openAppSettings
            )
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRefresh.intValue += 1
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }
}

@Composable
private fun PermissionContent(
    refreshSignal: Int,
    currentStatus: () -> WearPermissionStatus,
    missingPermissions: () -> List<String>,
    onPermissionsResult: (Set<String>) -> WearPermissionStatus,
    onOpenSettings: () -> Unit
) {
    var status by remember { mutableStateOf(currentStatus()) }
    var requestedPermissions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var previouslyRequestedBeforeLaunch by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { granted -> !granted }.keys
        val permanentlyDeniedCandidates = denied.intersect(previouslyRequestedBeforeLaunch.toSet())
        status = onPermissionsResult(permanentlyDeniedCandidates)
        if (status != WearPermissionStatus.PermanentlyDenied && denied.isNotEmpty()) {
            status = WearPermissionStatus.PermissionRequired
        }
        requestedPermissions = (requestedPermissions + results.keys).distinct()
    }

    LaunchedEffect(refreshSignal) {
        status = currentStatus()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Frecuencia cardiaca", style = MaterialTheme.typography.titleMedium)
        Text(text = statusMessage(status))
        Button(
            onClick = {
                val missing = missingPermissions()
                if (missing.isEmpty()) {
                    status = WearPermissionStatus.Granted
                } else {
                    previouslyRequestedBeforeLaunch = requestedPermissions
                    launcher.launch(missing.toTypedArray())
                }
            },
            enabled = status != WearPermissionStatus.Granted
        ) {
            Text(text = if (status == WearPermissionStatus.Granted) "Permiso concedido" else "Permitir frecuencia cardiaca")
        }
        if (status == WearPermissionStatus.PermanentlyDenied) {
            Button(onClick = onOpenSettings) {
                Text(text = "Abrir Ajustes")
            }
        } else {
            Button(onClick = { status = currentStatus() }) {
                Text(text = "Reintentar")
            }
        }
    }
}

private fun statusMessage(status: WearPermissionStatus): String = when (status) {
    WearPermissionStatus.Granted -> "Permiso concedido en este reloj. MotoSOS podrá leer frecuencia cardiaca durante un viaje activo."
    WearPermissionStatus.PermissionRequired -> "MotoSOS usa la frecuencia cardiaca como dato contextual durante un viaje activo. Concede el permiso en el reloj."
    WearPermissionStatus.PermanentlyDenied -> "El permiso fue denegado permanentemente. Abre Ajustes del reloj para concederlo y vuelve a intentar."
}
