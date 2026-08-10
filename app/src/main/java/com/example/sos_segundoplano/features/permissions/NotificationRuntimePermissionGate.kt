package com.example.sos_segundoplano.features.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.sos_segundoplano.core.permissions.NotificationRuntimePermissionPolicy
import com.example.sos_segundoplano.core.permissions.NotificationRuntimePermissionState

@Composable
fun NotificationRuntimePermissionGate(
    lifecycleOwner: LifecycleOwner,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val policy = NotificationRuntimePermissionPolicy()
    fun currentState(): NotificationRuntimePermissionState = policy.evaluate(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    )

    var permissionState by rememberSaveable { mutableStateOf(currentState()) }
    var dialogDismissed by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionState = policy.evaluate(Build.VERSION.SDK_INT, granted)
        dialogDismissed = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState = currentState()
                if (permissionState != NotificationRuntimePermissionState.Denied) {
                    dialogDismissed = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    content()

    if (permissionState == NotificationRuntimePermissionState.Denied && !dialogDismissed) {
        NotificationPermissionDialog(
            onRequestPermission = {
                dialogDismissed = false
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onOpenSettings = onOpenSettings,
            onRecheckPermissions = { permissionState = currentState() },
            onDismiss = { dialogDismissed = true }
        )
    }
}
