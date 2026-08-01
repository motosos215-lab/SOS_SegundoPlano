package com.example.sos_segundoplano.wear

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class WearPermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PermissionContent(onOpenSettings = ::openAppSettings) }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }
}

@Composable
private fun PermissionContent(onOpenSettings: () -> Unit) {
    val permission = if (Build.VERSION.SDK_INT >= 36) {
        "android.permission.health.READ_HEART_RATE"
    } else {
        Manifest.permission.BODY_SENSORS
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Frecuencia cardiaca", style = MaterialTheme.typography.titleMedium)
        Text(text = "MotoSOS usa la frecuencia cardiaca como dato contextual durante un viaje activo.")
        Button(onClick = { launcher.launch(permission) }) {
            Text(text = "Permitir frecuencia cardiaca")
        }
        Button(onClick = onOpenSettings) {
            Text(text = "Abrir Ajustes")
        }
        Text(text = "Para capturas prolongadas, revisa el permiso de fondo en Ajustes.")
    }
}
