package com.example.sos

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Ring connection, vital checks and alert countdown. It lives with the process rather than with
 * the screen, so that [MonitoringService] keeps it running when the app is closed.
 */
object VitalMonitor {
    const val SERVICE_CHANNEL = "monitoring"
    private const val ALERT_CHANNEL = "alert"
    private const val ALERT_NOTIFICATION = 2
    private const val CALL_NOTIFICATION = 3
    private const val PREFS = "monitoring"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ADDRESS = "ring_address"
    private const val MEASURE_INTERVAL_MS = 45_000L
    private const val RECONNECT_INTERVAL_MS = 40_000L

    // The ring runs one test at a time: starting them together would cancel all but the last.
    private val MEASURE_ORDER = listOf(
        EctriBleManager.TestType.HR,
        EctriBleManager.TestType.SPO2,
        EctriBleManager.TestType.BP,
        EctriBleManager.TestType.TEMP,
    )

    var bleStatus by mutableStateOf("BLE: prêt")
        private set
    var sosStatus by mutableStateOf<String?>(null)
        private set
    var connectedDeviceName by mutableStateOf<String?>(null)
        private set
    val devices = mutableStateListOf<Pair<String, String>>()
    var lastHr by mutableStateOf<Int?>(null)
        private set
    var lastSpo2 by mutableStateOf<Float?>(null)
        private set
    var lastBpHigh by mutableStateOf<Int?>(null)
        private set
    var lastBpLow by mutableStateOf<Int?>(null)
        private set
    var lastTemp by mutableStateOf<Float?>(null)
        private set
    var lastTempTime by mutableStateOf<String?>(null)
        private set
    var monitoringEnabled by mutableStateOf(false)
        private set
    var alertActive by mutableStateOf(false)
        private set
    var alertReason by mutableStateOf<String?>(null)
        private set
    var countdownSeconds by mutableStateOf(10)
        private set
    var profile by mutableStateOf(HealthProfile())
        private set

    lateinit var emergency: EmergencyActions
        private set
    private lateinit var appContext: Context
    private lateinit var ble: EctriBleManager
    private val handler = Handler(Looper.getMainLooper())
    private val vitalAlerts = VitalAlertEvaluator()
    private var measureIndex = 0

    private val prefs by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val profilePrefs by lazy { appContext.getSharedPreferences("health_profile", Context.MODE_PRIVATE) }
    private val notifications by lazy { appContext.getSystemService(NotificationManager::class.java) }

    private val measureLoop = object : Runnable {
        override fun run() {
            if (!monitoringEnabled) return
            if (connectedDeviceName != null) {
                ble.startTest(MEASURE_ORDER[measureIndex % MEASURE_ORDER.size])
                measureIndex += 1
            }
            handler.postDelayed(this, MEASURE_INTERVAL_MS)
        }
    }

    private val reconnectLoop = object : Runnable {
        override fun run() {
            if (!monitoringEnabled) return
            if (connectedDeviceName == null) {
                prefs.getString(KEY_ADDRESS, null)?.let { connect(it) }
            }
            handler.postDelayed(this, RECONNECT_INTERVAL_MS)
        }
    }

    private val countdown = object : Runnable {
        override fun run() {
            if (!alertActive) return
            if (countdownSeconds > 0) {
                countdownSeconds -= 1
                handler.postDelayed(this, 1_000)
            } else {
                triggerSos()
            }
        }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return

        appContext = context.applicationContext
        emergency = EmergencyActions(appContext)
        ble = EctriBleManager(appContext, bleListener)
        monitoringEnabled = prefs.getBoolean(KEY_ENABLED, false)
        profile = HealthProfile(
            heightCm = profilePrefs.getInt("height_cm", 0).takeIf { it > 0 },
            weightKg = profilePrefs.getInt("weight_kg", 0).takeIf { it > 0 }
        )
        notifications.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Surveillance", NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Alertes vitales", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    fun updateProfile(updated: HealthProfile) {
        profile = updated
        profilePrefs.edit()
            .putInt("height_cm", updated.heightCm ?: 0)
            .putInt("weight_kg", updated.weightKg ?: 0)
            .apply()
    }

    fun startScan() {
        devices.clear()
        ble.startScan()
    }

    fun startTest(testType: EctriBleManager.TestType) = ble.startTest(testType)

    fun connect(address: String) {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val device = manager?.adapter?.getRemoteDevice(address) ?: return
        prefs.edit().putString(KEY_ADDRESS, address).apply()
        ble.connect(device)
    }

    fun setMonitoring(enabled: Boolean) {
        monitoringEnabled = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            ensureServiceRunning()
            emergency.speak("Monitoring activé")
        } else {
            handler.removeCallbacks(measureLoop)
            handler.removeCallbacks(reconnectLoop)
            cancelAlert(silent = true)
            appContext.stopService(Intent(appContext, MonitoringService::class.java))
            emergency.speak("Monitoring désactivé")
        }
    }

    /** Must be called with the app in the foreground. */
    fun ensureServiceRunning() {
        val service = Intent(appContext, MonitoringService::class.java)
            .putExtra(MonitoringService.EXTRA_FROM_UI, true)
        ContextCompat.startForegroundService(appContext, service)
    }

    /** Called by the service, also after Android restarted it. */
    fun startLoops() {
        monitoringEnabled = true
        handler.removeCallbacks(measureLoop)
        handler.removeCallbacks(reconnectLoop)
        handler.post(measureLoop)
        handler.post(reconnectLoop)
    }

    fun reportBleStatus(status: String) {
        bleStatus = "BLE: $status"
    }

    fun reportServiceRefused() {
        bleStatus = "BLE: surveillance en arrière-plan refusée par Android"
        setMonitoring(false)
    }

    fun cancelAlert(silent: Boolean = false) {
        val wasActive = alertActive
        alertActive = false
        alertReason = null
        handler.removeCallbacks(countdown)
        vitalAlerts.reset()
        notifications.cancel(ALERT_NOTIFICATION)
        if (wasActive && !silent) emergency.speak("Alerte annulée")
    }

    fun consentMessage(lat: Double?, lon: Double?, number: String): String {
        val hrText = lastHr?.let { "$it battements par minute" } ?: "inconnu"
        val spo2Text = lastSpo2?.let { "$it pourcent" } ?: "inconnu"
        val bpText = if (lastBpHigh != null && lastBpLow != null) {
            "${lastBpHigh} sur ${lastBpLow}"
        } else {
            "inconnue"
        }
        val tempText = lastTemp?.let { "$it degrés" } ?: "inconnue"
        val position = if (lat != null && lon != null) {
            "Ma position est latitude $lat et longitude $lon. " +
                "Lien carte https://maps.google.com/?q=$lat,$lon. "
        } else {
            "Ma position est inconnue. "
        }

        return "Je ne peux pas parler. J'ai besoin d'aide. Je consens à recevoir des soins d'urgence. " +
            "Je suis au pays numéro d'urgence $number. " +
            position +
            "Mes constantes: fréquence cardiaque $hrText. Oxygénation $spo2Text. Tension $bpText. Température $tempText." +
            profile.spokenText()?.let { " $it" }.orEmpty()
    }

    private val bleListener = object : EctriBleManager.Listener {
        override fun onStatus(status: String) {
            handler.post { bleStatus = "BLE: $status" }
        }

        override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
            val addr = device.address ?: return
            val name = bluetoothNameOf(device) ?: "(sans nom)"
            handler.post {
                val exists = devices.any { it.second == addr }
                if (!exists) devices.add(name to addr)
            }
        }

        override fun onConnected(device: BluetoothDevice) {
            val name = bluetoothNameOf(device) ?: device.address
            handler.post { connectedDeviceName = name }
        }

        override fun onDisconnected() {
            handler.post { connectedDeviceName = null }
        }

        override fun onMeasurement(measurement: EctriBleManager.Measurement) {
            handler.post { applyMeasurement(measurement) }
        }
    }

    private fun applyMeasurement(measurement: EctriBleManager.Measurement) {
        measurement.heartRateBpm?.let { lastHr = it }
        measurement.spo2Percent?.let { lastSpo2 = it }
        if (measurement.bpHigh != null && measurement.bpLow != null) {
            lastBpHigh = measurement.bpHigh
            lastBpLow = measurement.bpLow
        }
        measurement.temperatureC?.let {
            lastTemp = it
            lastTempTime = measurement.temperatureTime
        }

        if (monitoringEnabled && !alertActive) {
            // Ring temperature is a stored skin value: shown and told to responders, but it must
            // not start an emergency call on its own.
            vitalAlerts.onMeasurement(measurement.copy(temperatureC = null))?.let(::raiseAlert)
        }
    }

    private fun raiseAlert(reason: String) {
        alertReason = reason
        alertActive = true
        countdownSeconds = 10
        emergency.speak("Alerte vitale détectée. $reason. Appel d'urgence dans dix secondes. Appuyez sur annuler.")
        showAlertNotification(reason)
        handler.removeCallbacks(countdown)
        handler.postDelayed(countdown, 1_000)
    }

    // Nobody may be able to confirm it: without a position the call matters more.
    private fun triggerSos() {
        alertActive = false
        vitalAlerts.reset()
        notifications.cancel(ALERT_NOTIFICATION)
        val number = emergency.emergencyNumberForCountryIso(emergency.getCountryIso())
        val callAndSpeak = { lat: Double?, lon: Double? ->
            sosStatus = if (emergency.hasPermission(Manifest.permission.CALL_PHONE)) {
                "Appel d'urgence automatique ($number)"
            } else {
                "Permission appel refusée: composeur ouvert ($number)"
            }
            showCallNotification(number)
            emergency.startEmergencyCall(number)
            emergency.speak(consentMessage(lat, lon, number))
        }
        sosStatus = "Récupération de la position avant l'appel…"
        emergency.requestOneLocation(
            onResult = { lat, lon -> callAndSpeak(lat, lon) },
            onError = { callAndSpeak(null, null) }
        )
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothNameOf(device: BluetoothDevice): String? {
        val allowed = android.os.Build.VERSION.SDK_INT < 31 ||
            emergency.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        return if (allowed) device.name else null
    }

    private fun showAlertNotification(reason: String) {
        val cancel = PendingIntent.getService(
            appContext,
            0,
            Intent(appContext, MonitoringService::class.java).setAction(MonitoringService.ACTION_CANCEL_ALERT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Alerte vitale : appel dans 10 secondes")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .addAction(0, "Annuler", cancel)
            .build()
        notifications.notify(ALERT_NOTIFICATION, notification)
    }

    // Android may only open the dialer for an emergency number: this keeps a one-tap way to
    // call it from the lock screen, where the app cannot start anything by itself.
    private fun showCallNotification(number: String) {
        val dial = PendingIntent.getActivity(
            appContext,
            1,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Appeler les secours ($number)")
            .setContentText("Touchez pour appeler")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(dial, true)
            .setContentIntent(dial)
            .setAutoCancel(true)
            .build()
        notifications.notify(CALL_NOTIFICATION, notification)
    }
}
