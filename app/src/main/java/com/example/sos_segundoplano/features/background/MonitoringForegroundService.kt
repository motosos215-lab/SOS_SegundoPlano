package com.example.sos_segundoplano.features.background

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.sos_segundoplano.data.signals.TripSignalCaptureCoordinator
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationStoreProvider
import com.example.sos_segundoplano.data.validation.WearValidationStatusNotifier
import com.example.sos_segundoplano.domain.validation.UserResponseSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MonitoringForegroundService : Service() {
    private val notificationFactory: MonitoringNotificationFactory by lazy {
        MonitoringNotificationFactory(this)
    }
    private val captureCoordinator: TripSignalCaptureCoordinator by lazy {
        TripSignalCaptureCoordinator(applicationContext)
    }
    private var notificationScope: CoroutineScope? = null
    private var notificationCollector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONFIRM_SAFE -> {
                handleValidationAction(intent, isHelpRequest = false)
                return START_NOT_STICKY
            }
            ACTION_REQUEST_HELP -> {
                handleValidationAction(intent, isHelpRequest = true)
                return START_NOT_STICKY
            }
            ACTION_START_MONITORING -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        notificationFactory.createChannel()
        if (!notificationFactory.isChannelEnabled()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return try {
            FalsePositiveValidationCoordinatorProvider.setNotifier(WearValidationStatusNotifier(applicationContext))
            promoteToForeground(notificationFactory.buildNotification())
            startNotificationUpdates()
            captureCoordinator.start()
            START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        notificationCollector?.cancel()
        notificationScope?.cancel()
        notificationCollector = null
        notificationScope = null
        captureCoordinator.stop()
        super.onDestroy()
    }

    private fun startNotificationUpdates() {
        if (notificationCollector != null) return
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        notificationScope = nextScope
        notificationCollector = nextScope.launch {
            FalsePositiveValidationStoreProvider.store.states.collect { state ->
                promoteToForeground(notificationFactory.buildNotification(state))
            }
        }
    }

    private fun handleValidationAction(intent: Intent, isHelpRequest: Boolean) {
        if (!intent.hasExtra(EXTRA_SESSION_ID) || !intent.hasExtra(EXTRA_ASSESSMENT_ID)) return
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, Long.MIN_VALUE)
        val assessmentId = intent.getLongExtra(EXTRA_ASSESSMENT_ID, Long.MIN_VALUE)
        val responseId = intent.getStringExtra(EXTRA_RESPONSE_ID)?.takeIf { it.isNotBlank() } ?: return
        if (sessionId == Long.MIN_VALUE || assessmentId == Long.MIN_VALUE) return
        if (isHelpRequest) {
            FalsePositiveValidationCoordinatorProvider.coordinator.requestHelp(sessionId, assessmentId, UserResponseSource.ForegroundNotification, responseId)
        } else {
            FalsePositiveValidationCoordinatorProvider.coordinator.confirmSafe(sessionId, assessmentId, UserResponseSource.ForegroundNotification, responseId)
        }
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MonitoringNotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(
                MonitoringNotificationFactory.NOTIFICATION_ID,
                notification
            )
        }
    }

    companion object {
        const val ACTION_START_MONITORING = "com.example.sos_segundoplano.action.START_MONITORING"
        const val ACTION_CONFIRM_SAFE = "com.example.sos_segundoplano.action.CONFIRM_SAFE"
        const val ACTION_REQUEST_HELP = "com.example.sos_segundoplano.action.REQUEST_HELP"
        const val EXTRA_SESSION_ID = "com.example.sos_segundoplano.extra.SESSION_ID"
        const val EXTRA_ASSESSMENT_ID = "com.example.sos_segundoplano.extra.ASSESSMENT_ID"
        const val EXTRA_RESPONSE_ID = "com.example.sos_segundoplano.extra.RESPONSE_ID"

        fun createStartIntent(context: Context): Intent = Intent(context, MonitoringForegroundService::class.java)
            .setAction(ACTION_START_MONITORING)
    }
}
