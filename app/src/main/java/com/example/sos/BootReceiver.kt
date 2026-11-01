package com.example.sos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Resumes monitoring after a reboot if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!VitalMonitor.isEnabled(context)) return

        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
        } catch (_: RuntimeException) {
            // Android refused the start: monitoring resumes when the app is next opened.
        }
    }
}
