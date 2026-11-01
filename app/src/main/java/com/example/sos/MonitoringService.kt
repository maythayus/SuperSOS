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
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuperSOS:monitoring").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foreground = goForeground(intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true)
        if (!foreground) {
            VitalMonitor.reportServiceRefused()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CANCEL_ALERT) VitalMonitor.cancelAlert() else VitalMonitor.startLoops()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // The location type is only allowed from the foreground: restarts run without it, and the
    // call then goes out without a position.
    private fun goForeground(fromUi: Boolean): Boolean {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, VitalMonitor.SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("SuperSOS surveille vos constantes")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (fromUi && VitalMonitor.emergency.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
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
