package com.example.sos_segundoplano.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class WearSignalForegroundService : Service() {
    private val dataClient by lazy { Wearable.getDataClient(applicationContext) }
    private val messageClient by lazy { Wearable.getMessageClient(applicationContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(applicationContext) }
    private val limiter = WearTransmissionLimiter()
    private var started = false
    private var snapshot = WearSignalSnapshot()
    private var validationStatus = WearDataLayerProtocol.ValidationStatus("idle")
    private lateinit var accelerometer: WearMotionSensorSource
    private lateinit var gyroscope: WearMotionSensorSource
    private lateinit var battery: WearBatterySource
    private lateinit var heartRate: WearHeartRateSource

    override fun onCreate() {
        super.onCreate()
        accelerometer = WearMotionSensorSource(this, Sensor.TYPE_ACCELEROMETER) { status, sample ->
            snapshot = snapshot.copy(accelerometerStatus = status, accelerometer = sample)
            publishThrottled()
        }
        gyroscope = WearMotionSensorSource(this, Sensor.TYPE_GYROSCOPE) { status, sample ->
            snapshot = snapshot.copy(gyroscopeStatus = status, gyroscope = sample)
            publishThrottled()
        }
        battery = WearBatterySource(this) { percentage ->
            snapshot = snapshot.copy(watchBatteryPercentage = percentage)
            publishThrottled()
        }
        heartRate = WearHeartRateSource(this) { status, bpm ->
            snapshot = snapshot.copy(
                heartRateStatus = status,
                heartRateBpm = bpm,
                status = status.toCaptureStatus(snapshot.status)
            )
            publishThrottled()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopSelf(startId)
            ACTION_VALIDATION_STATUS -> {
                validationStatus = WearDataLayerProtocol.ValidationStatus(
                    state = intent.getStringExtra(EXTRA_VALIDATION_STATE) ?: "idle",
                    sessionId = intent.getLongExtra(EXTRA_VALIDATION_SESSION_ID, -1L).takeIf { it >= 0L },
                    assessmentId = intent.getLongExtra(EXTRA_VALIDATION_ASSESSMENT_ID, -1L).takeIf { it >= 0L },
                    windowId = intent.getLongExtra(EXTRA_VALIDATION_WINDOW_ID, -1L).takeIf { it >= 0L },
                    remainingMillis = intent.getLongExtra(EXTRA_VALIDATION_REMAINING_MILLIS, -1L).takeIf { it >= 0L },
                    reason = intent.getStringExtra(EXTRA_VALIDATION_REASON)
                )
                if (started) promoteToForeground(buildNotification())
            }
            ACTION_CONFIRM_SAFE -> sendValidationAction(WearDataLayerProtocol.PATH_VALIDATION_CONFIRM_SAFE, "confirm_safe", 1)
            ACTION_REQUEST_HELP -> sendValidationAction(WearDataLayerProtocol.PATH_VALIDATION_REQUEST_HELP, "request_help", 2)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture() {
        if (started) return
        createChannel()
        promoteToForeground(buildNotification())
        started = true
        snapshot = WearSignalSnapshot(
            captureActive = true,
            status = WearCaptureStatus.Capturing,
            lastUpdatedMillis = System.currentTimeMillis()
        )
        accelerometer.start()
        gyroscope.start()
        battery.start()
        heartRate.start()
        publishNow()
    }

    private fun stopCapture() {
        if (!started) return
        started = false
        accelerometer.stop()
        gyroscope.stop()
        battery.stop()
        heartRate.release()
        limiter.reset()
        snapshot = snapshot.copy(
            captureActive = false,
            status = WearCaptureStatus.Stopped,
            heartRateBpm = null,
            heartRateStatus = WearSignalAvailability.Stopped,
            lastUpdatedMillis = System.currentTimeMillis()
        )
        publishNow()
    }

    private fun publishThrottled() {
        if (!started) return
        val now = System.currentTimeMillis()
        if (limiter.shouldSend(now)) publish(now)
    }

    private fun publishNow() = publish(System.currentTimeMillis())

    private fun publish(now: Long) {
        snapshot = snapshot.copy(lastUpdatedMillis = now)
        val request = PutDataMapRequest.create(WearDataLayerProtocol.PATH_SIGNALS_LATEST).apply {
            dataMap.putAll(WearDataLayerProtocol.encode(snapshot))
            dataMap.putLong("sequenceMillis", now)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.wear_monitoring_title), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_wear_notification)
        .setContentTitle(notificationTitle())
        .setContentText(notificationContent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (validationStatus.isCountdownActive) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (validationStatus.isCountdownActive) {
            builder.addAction(0, getString(R.string.wear_validation_confirm_safe), createServiceAction(ACTION_CONFIRM_SAFE, 1))
            builder.addAction(0, getString(R.string.wear_validation_request_help), createServiceAction(ACTION_REQUEST_HELP, 2))
        }
        return builder.build()
    }

    private fun createServiceAction(action: String, requestCode: Int) = android.app.PendingIntent.getService(
        this,
        requestCode,
        Intent(this, WearSignalForegroundService::class.java).setAction(action),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    private fun notificationTitle(): String = if (validationStatus.isCountdownActive) {
        getString(R.string.wear_validation_title)
    } else {
        getString(R.string.wear_monitoring_title)
    }

    private fun notificationContent(): String = if (validationStatus.isCountdownActive) {
        val remainingSeconds = (((validationStatus.remainingMillis ?: 0L) + 999L) / 1_000L).coerceAtLeast(0L)
        getString(R.string.wear_validation_countdown, remainingSeconds)
    } else {
        getString(R.string.wear_monitoring_content)
    }

    private fun sendValidationAction(path: String, action: String, actionId: Int) {
        val sessionId = validationStatus.sessionId ?: return
        val assessmentId = validationStatus.assessmentId ?: return
        val responseId = "wear-$sessionId-$assessmentId-$actionId"
        val payload = WearDataLayerProtocol.encodeValidationResponse(action, sessionId, assessmentId, responseId)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node -> messageClient.sendMessage(node.id, path, payload) }
        }
    }

    private fun WearSignalAvailability.toCaptureStatus(current: WearCaptureStatus): WearCaptureStatus = when (this) {
        WearSignalAvailability.Available,
        WearSignalAvailability.Waiting -> WearCaptureStatus.Capturing
        WearSignalAvailability.PermissionRequired -> WearCaptureStatus.PermissionRequired
        WearSignalAvailability.PermanentlyDenied -> WearCaptureStatus.PermanentlyDenied
        WearSignalAvailability.Unsupported -> WearCaptureStatus.SensorUnavailable
        WearSignalAvailability.HealthServicesUnavailable -> WearCaptureStatus.HealthServicesUnavailable
        WearSignalAvailability.StartFailed -> WearCaptureStatus.StartFailed
        WearSignalAvailability.Error -> WearCaptureStatus.Error
        WearSignalAvailability.Stopped -> WearCaptureStatus.Stopped
    }.takeUnless { current == WearCaptureStatus.Stopped } ?: WearCaptureStatus.Stopped

    companion object {
        private const val ACTION_START = "com.example.sos_segundoplano.wear.action.START_CAPTURE"
        private const val ACTION_STOP = "com.example.sos_segundoplano.wear.action.STOP_CAPTURE"
        private const val ACTION_VALIDATION_STATUS = "com.example.sos_segundoplano.wear.action.VALIDATION_STATUS"
        private const val ACTION_CONFIRM_SAFE = "com.example.sos_segundoplano.wear.action.CONFIRM_SAFE"
        private const val ACTION_REQUEST_HELP = "com.example.sos_segundoplano.wear.action.REQUEST_HELP"
        private const val EXTRA_VALIDATION_STATE = "validation_state"
        private const val EXTRA_VALIDATION_SESSION_ID = "validation_session_id"
        private const val EXTRA_VALIDATION_ASSESSMENT_ID = "validation_assessment_id"
        private const val EXTRA_VALIDATION_WINDOW_ID = "validation_window_id"
        private const val EXTRA_VALIDATION_REMAINING_MILLIS = "validation_remaining_millis"
        private const val EXTRA_VALIDATION_REASON = "validation_reason"
        private const val CHANNEL_ID = "wear_trip_signal_capture"
        private const val NOTIFICATION_ID = 13200

        fun createStartIntent(context: Context): Intent = Intent(context, WearSignalForegroundService::class.java)
            .setAction(ACTION_START)

        fun createStopIntent(context: Context): Intent = Intent(context, WearSignalForegroundService::class.java)
            .setAction(ACTION_STOP)

        fun createValidationStatusIntent(context: Context, status: WearDataLayerProtocol.ValidationStatus): Intent =
            Intent(context, WearSignalForegroundService::class.java)
                .setAction(ACTION_VALIDATION_STATUS)
                .putExtra(EXTRA_VALIDATION_STATE, status.state)
                .putExtra(EXTRA_VALIDATION_SESSION_ID, status.sessionId ?: -1L)
                .putExtra(EXTRA_VALIDATION_ASSESSMENT_ID, status.assessmentId ?: -1L)
                .putExtra(EXTRA_VALIDATION_WINDOW_ID, status.windowId ?: -1L)
                .putExtra(EXTRA_VALIDATION_REMAINING_MILLIS, status.remainingMillis ?: -1L)
                .putExtra(EXTRA_VALIDATION_REASON, status.reason)

        fun publishStartFailure(context: Context) {
            val request = PutDataMapRequest.create(WearDataLayerProtocol.PATH_WATCH_STATUS).apply {
                dataMap.putAll(
                    WearDataLayerProtocol.encode(
                        WearSignalSnapshot(
                            captureActive = false,
                            status = WearCaptureStatus.UserActionRequired,
                            lastUpdatedMillis = System.currentTimeMillis()
                        )
                    )
                )
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context.applicationContext).putDataItem(request)
        }
    }
}
