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
    private val limiter = WearTransmissionLimiter()
    private var started = false
    private var snapshot = WearSignalSnapshot()
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
            snapshot = snapshot.copy(heartRateStatus = status, heartRateBpm = bpm)
            publishThrottled()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopSelf(startId)
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
        accelerometer.stop()
        gyroscope.stop()
        battery.stop()
        heartRate.stop()
        heartRate.release()
        started = false
        limiter.reset()
        snapshot = snapshot.copy(
            captureActive = false,
            status = WearCaptureStatus.Disconnected,
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

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_wear_notification)
        .setContentTitle(getString(R.string.wear_monitoring_title))
        .setContentText(getString(R.string.wear_monitoring_content))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()

    companion object {
        private const val ACTION_START = "com.example.sos_segundoplano.wear.action.START_CAPTURE"
        private const val ACTION_STOP = "com.example.sos_segundoplano.wear.action.STOP_CAPTURE"
        private const val CHANNEL_ID = "wear_trip_signal_capture"
        private const val NOTIFICATION_ID = 13200

        fun createStartIntent(context: Context): Intent = Intent(context, WearSignalForegroundService::class.java)
            .setAction(ACTION_START)

        fun createStopIntent(context: Context): Intent = Intent(context, WearSignalForegroundService::class.java)
            .setAction(ACTION_STOP)

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
