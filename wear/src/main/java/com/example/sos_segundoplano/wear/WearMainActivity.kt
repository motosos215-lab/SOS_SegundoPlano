package com.example.sos_segundoplano.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WearMainActivity : ComponentActivity() {
    private lateinit var tripDependencies: WearTripStateDependencies
    private lateinit var controller: WearTripUiController
    private lateinit var signalCaptureLauncher: WearSignalCaptureLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tripDependencies = WearTripStateProvider.get(applicationContext)
        signalCaptureLauncher = WearSignalCaptureLauncher.create(applicationContext)
        controller = WearTripUiController(
            actions = WearTripUiReconcilerActions(
                tripDependencies.reconciler
            ),
            tripStateStore = tripDependencies.store
        )
        val validationController = WearValidationUiController(
            WearValidationActionGateway(applicationContext)
        )
        val manualSosController = WearManualSosUiController(
            actions = MobileCompanionGateway(applicationContext),
            tripStateStore = tripDependencies.store
        )
        setContent {
            WearMotoSosApp(
                tripStateStore = tripDependencies.store,
                controller = controller,
                validationController = validationController,
                manualSosController = manualSosController,
                onOpenHeartRatePermission = {
                    startActivity(Intent(this@WearMainActivity, WearPermissionActivity::class.java))
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            controller.refresh()
            signalCaptureLauncher.reconcileActiveTrip(
                tripDependencies.store.state.value.active == true
            )
        }
    }
}
