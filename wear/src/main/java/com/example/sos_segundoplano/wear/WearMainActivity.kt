package com.example.sos_segundoplano.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tripDependencies = WearTripStateProvider.get(applicationContext)
        val controller = WearTripUiController(
            actions = WearTripUiReconcilerActions(
                tripDependencies.reconciler
            ),
            tripStateStore = tripDependencies.store
        )
        val validationController = WearValidationUiController(
            WearValidationActionGateway(applicationContext)
        )
        lifecycleScope.launch { controller.refreshInitial() }
        setContent {
            WearMotoSosApp(
                tripStateStore = tripDependencies.store,
                controller = controller,
                validationController = validationController,
                onOpenHeartRatePermission = {
                    startActivity(Intent(this@WearMainActivity, WearPermissionActivity::class.java))
                }
            )
        }
    }
}
