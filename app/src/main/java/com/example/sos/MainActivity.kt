package com.example.sos

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.sos.ui.theme.SOSTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val prefs by lazy { getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE) }
    private var tts: TextToSpeech? = null

    private enum class SosAction {
        /** "Obtenir position": speak the position, then enable the call button. */
        GET_POSITION,

        /** "Je ne peux pas parler": speak the full message for responders. */
        SPEAK_MESSAGE,

        /** Vital alert not cancelled: speak the message and call, with whatever permissions exist. */
        AUTO_SOS,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.FRANCE
        }

        enableEdgeToEdge()
        setContent {
            SOSTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SosScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun emergencyNumberForCountryIso(countryIso: String?): String {
        val iso = countryIso?.trim()?.lowercase().orEmpty()
        return when (iso) {
            "us", "ca" -> "911"
            "mx" -> "911"
            "br" -> "190"
            "gb" -> "999"
            "au" -> "000"
            else -> "112"
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sos")
    }

    private fun startEmergencyCall(number: String) {
        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        if (hasPermission(Manifest.permission.CALL_PHONE)) {
            startActivity(callIntent)
        } else {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            startActivity(dialIntent)
        }
    }

    private fun getCountryIso(): String? {
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        return tm?.networkCountryIso?.takeIf { it.isNotBlank() }
    }

    private fun requestOneLocation(onResult: (Double, Double) -> Unit, onError: (String) -> Unit) {
        val tokenSource = CancellationTokenSource()
        try {
            fusedLocationClient
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                .addOnSuccessListener { loc ->
                    if (loc == null) {
                        onError("Position indisponible (localisation désactivée ?)")
                    } else {
                        onResult(loc.latitude, loc.longitude)
                    }
                }
                .addOnFailureListener { e ->
                    onError(e.message ?: "Erreur localisation")
                }
        } catch (e: SecurityException) {
            onError("Permission localisation refusée")
        }
    }

    /** Spoken coordinates use a French decimal comma so TTS reads them naturally. */
    private fun spokenCoord(v: Double): String = String.format(Locale.FRANCE, "%.5f", v)

    private fun mapsLink(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)

    private fun buildResponderMessage(
        lat: Double?,
        lon: Double?,
        heightCm: String,
        weightKg: String,
        medicalInfo: String,
        hr: Int?,
        spo2: Float?,
        bpHigh: Int?,
        bpLow: Int?,
        temp: Float?,
    ): String = buildString {
        append("Je ne peux pas parler. J'ai besoin d'aide. Je consens à recevoir des soins d'urgence. ")
        if (lat != null && lon != null) {
            append("Ma position GPS est latitude ${spokenCoord(lat)}, longitude ${spokenCoord(lon)}. ")
        } else {
            append("Ma position GPS n'est pas disponible. ")
        }
        if (heightCm.isNotBlank()) append("Je mesure ${heightCm.trim()} centimètres. ")
        if (weightKg.isNotBlank()) append("Je pèse ${weightKg.trim()} kilos. ")
        if (medicalInfo.isNotBlank()) append("Informations médicales: ${medicalInfo.trim()}. ")

        val hrText = hr?.let { "$it battements par minute" } ?: "inconnue"
        val spo2Text = spo2?.let { "$it pourcent" } ?: "inconnue"
        val bpText = if (bpHigh != null && bpLow != null) "$bpHigh sur $bpLow" else "inconnue"
        val tempText = temp?.let { "$it degrés" } ?: "inconnue"
        append("Mes constantes: fréquence cardiaque $hrText. Oxygénation $spo2Text. ")
        append("Tension $bpText. Température $tempText.")
    }

    @Composable
    private fun SosScreen(modifier: Modifier = Modifier) {
        var status by remember { mutableStateOf("Prêt") }
        var lastLat by remember { mutableStateOf<Double?>(null) }
        var lastLon by remember { mutableStateOf<Double?>(null) }
        var emergencyNumber by remember { mutableStateOf<String?>(null) }
        var canCallDirectly by remember { mutableStateOf(false) }
        var readyToCall by remember { mutableStateOf(false) }
        var pendingAction by remember { mutableStateOf(SosAction.GET_POSITION) }

        // Profile, persisted so it survives app restarts.
        var heightCm by remember { mutableStateOf(prefs.getString(KEY_HEIGHT, "").orEmpty()) }
        var weightKg by remember { mutableStateOf(prefs.getString(KEY_WEIGHT, "").orEmpty()) }
        var medicalInfo by remember { mutableStateOf(prefs.getString(KEY_MEDICAL, "").orEmpty()) }

        var bleStatus by remember { mutableStateOf("prêt") }
        var scanning by remember { mutableStateOf(false) }
        var showLocationHint by remember { mutableStateOf(false) }
        var connectedDeviceName by remember { mutableStateOf<String?>(null) }
        var ringReady by remember { mutableStateOf(false) }
        val devices = remember { mutableStateListOf<EctriBleManager.FoundDevice>() }

        var lastHr by remember { mutableStateOf<Int?>(null) }
        var lastSpo2 by remember { mutableStateOf<Float?>(null) }
        var lastBpHigh by remember { mutableStateOf<Int?>(null) }
        var lastBpLow by remember { mutableStateOf<Int?>(null) }
        var lastTemp by remember { mutableStateOf<Float?>(null) }

        var monitoringEnabled by remember { mutableStateOf(false) }
        var alertActive by remember { mutableStateOf(false) }
        var alertReason by remember { mutableStateOf<String?>(null) }
        var countdownSeconds by remember { mutableStateOf(ALERT_COUNTDOWN_S) }

        val vitalsGuard = remember { VitalsGuard() }
        val mainHandler = remember { Handler(Looper.getMainLooper()) }

        val ectriBleManager = remember {
            EctriBleManager(
                context = this,
                listener = object : EctriBleManager.Listener {
                    override fun onStatus(status: String) {
                        mainHandler.post { bleStatus = status }
                    }

                    override fun onScanStateChanged(isScanning: Boolean) {
                        mainHandler.post { scanning = isScanning }
                    }

                    override fun onDeviceFound(device: EctriBleManager.FoundDevice) {
                        mainHandler.post {
                            val index = devices.indexOfFirst { it.address == device.address }
                            if (index >= 0) devices[index] = device else devices.add(device)
                        }
                    }

                    override fun onConnected(device: BluetoothDevice) {
                        val name = runCatching { device.name }.getOrNull() ?: device.address
                        mainHandler.post { connectedDeviceName = name }
                    }

                    override fun onReady() {
                        mainHandler.post { ringReady = true }
                    }

                    override fun onDisconnected() {
                        mainHandler.post {
                            connectedDeviceName = null
                            ringReady = false
                        }
                    }

                    override fun onMeasurement(measurement: EctriBleManager.Measurement) {
                        mainHandler.post {
                            measurement.heartRateBpm?.let { lastHr = it }
                            measurement.spo2Percent?.let { lastSpo2 = it }
                            if (measurement.bpHigh != null && measurement.bpLow != null) {
                                lastBpHigh = measurement.bpHigh
                                lastBpLow = measurement.bpLow
                            }
                            measurement.temperatureC?.let { lastTemp = it }

                            if (monitoringEnabled && !alertActive) {
                                val reason = vitalsGuard.onMeasurement(measurement)
                                if (reason != null) {
                                    alertReason = reason
                                    countdownSeconds = ALERT_COUNTDOWN_S
                                    alertActive = true
                                    speak(
                                        "Alerte vitale détectée. $reason. " +
                                            "Appel d'urgence dans dix secondes. Appuyez sur annuler."
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }

        DisposableEffect(Unit) {
            onDispose { ectriBleManager.release() }
        }

        val responderMessage = { lat: Double?, lon: Double? ->
            buildResponderMessage(
                lat, lon, heightCm, weightKg, medicalInfo,
                lastHr, lastSpo2, lastBpHigh, lastBpLow, lastTemp,
            )
        }

        val fetchLocation: ((Double?, Double?) -> Unit) -> Unit = { onDone ->
            status = "Récupération de la position…"
            requestOneLocation(
                onResult = { lat, lon ->
                    lastLat = lat
                    lastLon = lon
                    onDone(lat, lon)
                },
                onError = { err ->
                    status = err
                    onDone(null, null)
                }
            )
        }

        val runAction: (SosAction) -> Unit = { action ->
            canCallDirectly = hasPermission(Manifest.permission.CALL_PHONE)
            val number = emergencyNumberForCountryIso(getCountryIso())

            when (action) {
                SosAction.GET_POSITION -> {
                    if (!hasLocationPermission()) {
                        status = "Permission localisation refusée"
                        speak("Permission localisation refusée")
                        readyToCall = false
                    } else {
                        fetchLocation { lat, lon ->
                            if (lat != null && lon != null) {
                                speak(
                                    "Je suis en urgence. Ma position GPS est latitude ${spokenCoord(lat)} " +
                                        "et longitude ${spokenCoord(lon)}"
                                )
                                status = "Position obtenue"
                            } else {
                                speak(status)
                            }
                            // The call must stay possible even without a position.
                            emergencyNumber = number
                            readyToCall = true
                        }
                    }
                }

                SosAction.SPEAK_MESSAGE -> {
                    if (!hasLocationPermission()) {
                        speak(responderMessage(null, null))
                        status = "Message vocal lu (sans position)"
                    } else {
                        fetchLocation { lat, lon ->
                            speak(responderMessage(lat, lon))
                            status = "Message vocal lu"
                        }
                    }
                }

                SosAction.AUTO_SOS -> {
                    emergencyNumber = number
                    var done = false
                    val finish: (Double?, Double?) -> Unit = { lat, lon ->
                        if (!done) {
                            done = true
                            speak(responderMessage(lat, lon))
                            status = "Appel d'urgence ($number)…"
                            startEmergencyCall(number)
                        }
                    }
                    if (hasLocationPermission()) {
                        fetchLocation(finish)
                        // Never let a slow GPS fix block the call.
                        mainHandler.postDelayed({ finish(lastLat, lastLon) }, AUTO_SOS_LOCATION_TIMEOUT_MS)
                    } else {
                        finish(null, null)
                    }
                }
            }
        }

        val sosPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { runAction(pendingAction) }
        )

        val startAction = { action: SosAction ->
            pendingAction = action
            if (action == SosAction.GET_POSITION) readyToCall = false
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (action == SosAction.GET_POSITION) add(Manifest.permission.CALL_PHONE)
            }
            sosPermissionLauncher.launch(perms.toTypedArray())
        }

        val startScanNow = {
            devices.clear()
            showLocationHint = !ectriBleManager.isLocationEnabled()
            ectriBleManager.startScan()
        }

        val enableBluetoothLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
            onResult = {
                if (ectriBleManager.isBluetoothEnabled()) {
                    startScanNow()
                } else {
                    bleStatus = "Bluetooth désactivé, active-le pour trouver la bague"
                }
            }
        )

        val blePermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result ->
                val btGranted = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    result[Manifest.permission.BLUETOOTH_SCAN] == true &&
                        result[Manifest.permission.BLUETOOTH_CONNECT] == true
                } else {
                    true
                }

                when {
                    !btGranted -> {
                        bleStatus = "Permission « Appareils à proximité » refusée"
                    }
                    // Below Android 12, BLE scans return nothing without location permission.
                    android.os.Build.VERSION.SDK_INT < 31 && !hasLocationPermission() -> {
                        bleStatus = "Permission localisation requise pour scanner"
                    }
                    !ectriBleManager.isBluetoothEnabled() -> {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                    else -> startScanNow()
                }
            }
        )

        LaunchedEffect(Unit) {
            if (!hasLocationPermission()) {
                status = "Autorise la localisation pour fonctionner"
            }
        }

        // Periodic measurements. The ring runs one test at a time, so they are staggered.
        LaunchedEffect(monitoringEnabled, ringReady) {
            if (!monitoringEnabled || !ringReady) return@LaunchedEffect
            var cycle = 0
            while (true) {
                ectriBleManager.startTest(EctriBleManager.TestType.HR)
                delay(30_000)
                ectriBleManager.startTest(EctriBleManager.TestType.SPO2)
                delay(30_000)
                ectriBleManager.startTest(EctriBleManager.TestType.TEMP)
                delay(20_000)
                // Blood pressure is slower / more intrusive: every third cycle only.
                if (cycle % 3 == 0) {
                    ectriBleManager.startTest(EctriBleManager.TestType.BP)
                    delay(45_000)
                }
                cycle++
            }
        }

        // Countdown, then automatic SOS. Cancelling sets alertActive = false, which stops it.
        LaunchedEffect(alertActive) {
            if (!alertActive) return@LaunchedEffect
            while (countdownSeconds > 0) {
                delay(1_000)
                countdownSeconds -= 1
            }
            alertActive = false
            vitalsGuard.reset()
            runAction(SosAction.AUTO_SOS)
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Alert banner (top, impossible to miss) ----
            if (alertActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("ALERTE : ${alertReason.orEmpty()}", fontWeight = FontWeight.Bold)
                        Text("Appel d'urgence dans ${countdownSeconds}s", fontSize = 22.sp)
                        Button(
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            onClick = {
                                alertActive = false
                                alertReason = null
                                vitalsGuard.reset()
                                speak("Alerte annulée")
                            }
                        ) {
                            Text("Annuler", fontSize = 18.sp)
                        }
                    }
                }
            }

            // ---- SOS ----
            Section(title = "SOS") {
                Text(status, style = MaterialTheme.typography.bodyMedium)

                val lat = lastLat
                val lon = lastLon
                if (lat != null && lon != null) {
                    Text(
                        "${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lon)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsLink(lat, lon))))
                        }
                    ) { Text("Voir sur la carte") }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { startAction(SosAction.GET_POSITION) }
                ) {
                    Text("Obtenir position")
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { startAction(SosAction.SPEAK_MESSAGE) }
                ) {
                    Text("Je ne peux pas parler (message vocal)")
                }

                Button(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    enabled = readyToCall && emergencyNumber != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        val number = emergencyNumber ?: return@Button
                        if (!canCallDirectly) {
                            status = "Permission appel refusée, ouverture du composeur"
                            speak("Permission appel refusée, ouverture du composeur")
                        }
                        startEmergencyCall(number)
                    }
                ) {
                    Text(
                        text = emergencyNumber?.let { "Appeler les secours ($it)" }
                            ?: "Confirmer l'appel d'urgence",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (!readyToCall) {
                    Text(
                        "Appuie d'abord sur « Obtenir position »",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---- Profile ----
            Section(title = "Profil (lu aux secours)") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = heightCm,
                        onValueChange = { v ->
                            heightCm = v.filter { it.isDigit() }.take(3)
                            prefs.edit().putString(KEY_HEIGHT, heightCm).apply()
                        },
                        label = { Text("Taille (cm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = weightKg,
                        onValueChange = { v ->
                            weightKg = v.filter { it.isDigit() }.take(3)
                            prefs.edit().putString(KEY_WEIGHT, weightKg).apply()
                        },
                        label = { Text("Poids (kg)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = medicalInfo,
                    onValueChange = { v ->
                        medicalInfo = v.take(300)
                        prefs.edit().putString(KEY_MEDICAL, medicalInfo).apply()
                    },
                    label = { Text("Infos médicales") },
                    placeholder = { Text("Allergies, traitements, groupe sanguin…") },
                    minLines = 2,
                )
                Text(
                    "Enregistré automatiquement sur le téléphone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- Ring ----
            Section(title = "Bague ECTRI") {
                Text("État : $bleStatus", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Connectée : ${connectedDeviceName ?: "non"}" +
                        if (connectedDeviceName != null && !ringReady) " (préparation…)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (connectedDeviceName == null) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (scanning) {
                                ectriBleManager.stopScan()
                                bleStatus = "Recherche arrêtée"
                            } else {
                                val perms = buildList {
                                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                                        add(Manifest.permission.BLUETOOTH_SCAN)
                                        add(Manifest.permission.BLUETOOTH_CONNECT)
                                    }
                                }
                                blePermissionLauncher.launch(perms.toTypedArray())
                            }
                        }
                    ) {
                        Text(if (scanning) "Arrêter la recherche" else "Scanner / trouver la bague")
                    }

                    if (showLocationHint) {
                        Text(
                            "La localisation du téléphone est désactivée : sur beaucoup de " +
                                "téléphones, aucune bague n'apparaît sans elle.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        ) { Text("Activer la localisation") }
                    }

                    val sorted = devices.sortedWith(
                        compareByDescending<EctriBleManager.FoundDevice> { it.isLikelyRing }
                            .thenByDescending { it.name != "(sans nom)" }
                            .thenByDescending { it.rssi }
                    )

                    if (sorted.isEmpty()) {
                        Text(
                            if (scanning) {
                                "Recherche en cours… Ferme l'app officielle de la bague si elle est " +
                                    "ouverte : la bague ne se connecte qu'à une seule app."
                            } else {
                                "Aucun appareil trouvé (pour l'instant)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Appareils trouvés :", style = MaterialTheme.typography.bodyMedium)
                        sorted.take(MAX_DEVICES_SHOWN).forEach { d ->
                            val label = "${if (d.isLikelyRing) "★ " else ""}${d.name} · ${d.rssi} dBm"
                            val onClick = {
                                val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
                                val dev = manager?.adapter?.getRemoteDevice(d.address)
                                if (dev != null) ectriBleManager.connect(dev)
                            }
                            if (d.isLikelyRing) {
                                Button(modifier = Modifier.fillMaxWidth(), onClick = onClick) { Text(label) }
                            } else {
                                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onClick) { Text(label) }
                            }
                        }
                        if (sorted.none { it.isLikelyRing }) {
                            Text(
                                "★ = bague détectée. Si elle n'apparaît pas, essaie les appareils " +
                                    "au signal le plus fort (dBm proche de 0).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { ectriBleManager.disconnect() }
                    ) { Text("Déconnecter") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Monitoring vital", fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                !monitoringEnabled -> "Désactivé"
                                !ringReady -> "En attente de la bague"
                                else -> "Actif (mesure toutes les ~30 s)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = monitoringEnabled,
                        onCheckedChange = { enabled ->
                            monitoringEnabled = enabled
                            vitalsGuard.reset()
                            if (enabled) {
                                speak("Monitoring activé")
                            } else {
                                alertActive = false
                                alertReason = null
                                speak("Monitoring désactivé")
                            }
                        }
                    )
                }
            }

            // ---- Vitals ----
            Section(title = "Constantes") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile(
                        modifier = Modifier.weight(1f),
                        label = "FC",
                        value = lastHr?.toString() ?: "--",
                        unit = "bpm",
                        enabled = ringReady,
                        onMeasure = { ectriBleManager.startTest(EctriBleManager.TestType.HR) },
                    )
                    VitalTile(
                        modifier = Modifier.weight(1f),
                        label = "SpO2",
                        value = lastSpo2?.toString() ?: "--",
                        unit = "%",
                        enabled = ringReady,
                        onMeasure = { ectriBleManager.startTest(EctriBleManager.TestType.SPO2) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalTile(
                        modifier = Modifier.weight(1f),
                        label = "Tension",
                        value = "${lastBpHigh ?: "--"}/${lastBpLow ?: "--"}",
                        unit = "mmHg",
                        enabled = ringReady,
                        onMeasure = { ectriBleManager.startTest(EctriBleManager.TestType.BP) },
                    )
                    VitalTile(
                        modifier = Modifier.weight(1f),
                        label = "Temp",
                        value = lastTemp?.toString() ?: "--",
                        unit = "°C",
                        enabled = ringReady,
                        onMeasure = { ectriBleManager.startTest(EctriBleManager.TestType.TEMP) },
                    )
                }
                if (!ringReady) {
                    Text(
                        "Connecte la bague pour mesurer.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "Pas un dispositif médical. Les mesures sont indicatives.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    companion object {
        private const val PREFS_PROFILE = "profile"
        private const val KEY_HEIGHT = "height_cm"
        private const val KEY_WEIGHT = "weight_kg"
        private const val KEY_MEDICAL = "medical_info"

        private const val ALERT_COUNTDOWN_S = 10
        private const val AUTO_SOS_LOCATION_TIMEOUT_MS = 15_000L
        private const val MAX_DEVICES_SHOWN = 8
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun VitalTile(
    label: String,
    value: String,
    unit: String,
    enabled: Boolean,
    onMeasure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(unit, style = MaterialTheme.typography.labelSmall)
            TextButton(enabled = enabled, onClick = onMeasure) { Text("Mesurer") }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SOSTheme {
        Text("SOS")
    }
}
