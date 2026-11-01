package com.example.sos

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Foreground service that keeps [VitalMonitor] alive while the app is closed or the phone locked. */
class MonitoringService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        VitalMonitor.init(this)
        VitalMonitor.serviceRunning = true
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuperSOS:monitoring").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The service already runs: going foreground again would drop the microphone type.
        if (intent?.action == ACTION_CANCEL_ALERT) {
            VitalMonitor.cancelAlert()
            return START_STICKY
        }

        val foreground = goForeground(intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true)
        if (!foreground) {
            VitalMonitor.reportServiceRefused()
            stopSelf()
            return START_NOT_STICKY
        }

        VitalMonitor.startLoops()
        return START_STICKY
    }

    override fun onDestroy() {
        VitalMonitor.serviceRunning = false
        VitalMonitor.stopRecording("service arrêté")
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // Location and microphone are only allowed from the foreground: restarts run without them,
    // so a call goes out without a position and a recording needs the app to be opened once.
    private fun goForeground(fromUi: Boolean): Boolean {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, VitalMonitor.SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("SuperSOS est actif")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (fromUi && VitalMonitor.emergency.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (fromUi && VitalMonitor.emergency.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        const val ACTION_CANCEL_ALERT = "com.example.sos.CANCEL_ALERT"
        const val EXTRA_FROM_UI = "from_ui"
        private const val NOTIFICATION_ID = 1
    }
}
