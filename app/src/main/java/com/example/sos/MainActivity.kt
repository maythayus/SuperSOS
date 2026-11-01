package com.example.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.sos.ui.theme.SOSTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var tts: TextToSpeech? = null
    private val profilePrefs by lazy { getSharedPreferences("health_profile", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { }

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

    private fun loadProfile() = HealthProfile(
        heightCm = profilePrefs.getInt("height_cm", 0).takeIf { it > 0 },
        weightKg = profilePrefs.getInt("weight_kg", 0).takeIf { it > 0 }
    )

    private fun saveProfile(profile: HealthProfile) {
        profilePrefs.edit()
            .putInt("height_cm", profile.heightCm ?: 0)
            .putInt("weight_kg", profile.weightKg ?: 0)
            .apply()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sos")
    }

    private fun startEmergencyCall(number: String) {
        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        try {
            if (hasPermission(Manifest.permission.CALL_PHONE)) {
                startActivity(callIntent)
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                startActivity(dialIntent)
            }
        } catch (_: ActivityNotFoundException) {
            // Devices without telephony (tablets) have nothing to handle the intent.
            speak("Appel impossible sur cet appareil")
        }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothNameOf(device: BluetoothDevice): String? {
        val allowed = android.os.Build.VERSION.SDK_INT < 31 ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        return if (allowed) device.name else null
    }

    private fun getCountryIso(): String? {
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        return tm?.networkCountryIso?.takeIf { it.isNotBlank() }
    }

    @SuppressLint("MissingPermission")
    private fun requestOneLocation(onResult: (Double, Double) -> Unit, onError: (String) -> Unit) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            onError("Permission localisation refusée")
            return
        }

        val tokenSource = CancellationTokenSource()
        fusedLocationClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    onError("Position indisponible")
                } else {
                    onResult(loc.latitude, loc.longitude)
                }
            }
            .addOnFailureListener { e ->
                onError(e.message ?: "Erreur localisation")
            }
    }

    @Composable
    private fun SosScreen(modifier: Modifier = Modifier) {
        var status by remember { mutableStateOf("Prêt") }
        var lastLat by remember { mutableStateOf<Double?>(null) }
        var lastLon by remember { mutableStateOf<Double?>(null) }
        var emergencyNumber by remember { mutableStateOf<String?>(null) }
        var canCallDirectly by remember { mutableStateOf(false) }
        var readyToCall by remember { mutableStateOf(false) }

        var bleStatus by remember { mutableStateOf("BLE: prêt") }
        var connectedDeviceName by remember { mutableStateOf<String?>(null) }
        val devices = remember { mutableStateListOf<Pair<String, String>>() }
        var lastHr by remember { mutableStateOf<Int?>(null) }
        var lastSpo2 by remember { mutableStateOf<Float?>(null) }
        var lastBpHigh by remember { mutableStateOf<Int?>(null) }
        var lastBpLow by remember { mutableStateOf<Int?>(null) }
        var lastTemp by remember { mutableStateOf<Float?>(null) }

        var profile by remember { mutableStateOf(loadProfile()) }
        var heightText by remember { mutableStateOf(profile.heightCm?.toString().orEmpty()) }
        var weightText by remember { mutableStateOf(profile.weightKg?.toString().orEmpty()) }

        var monitoringEnabled by remember { mutableStateOf(false) }
        var alertActive by remember { mutableStateOf(false) }
        var alertReason by remember { mutableStateOf<String?>(null) }
        var countdownSeconds by remember { mutableStateOf(10) }

        val vitalAlerts = remember { VitalAlertEvaluator() }

        val raiseAlert = { reason: String ->
            alertReason = reason
            alertActive = true
            countdownSeconds = 10
            speak("Alerte vitale détectée. $reason. Appel d'urgence dans dix secondes. Appuyez sur annuler.")
        }

        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val ectriBleManager = remember {
            EctriBleManager(
                context = this,
                listener = object : EctriBleManager.Listener {
                    override fun onStatus(status: String) {
                        mainHandler.post { bleStatus = "BLE: $status" }
                    }

                    override fun onDeviceFound(device: android.bluetooth.BluetoothDevice, rssi: Int) {
                        val addr = device.address ?: return
                        val name = bluetoothNameOf(device) ?: "(sans nom)"
                        mainHandler.post {
                            val exists = devices.any { it.second == addr }
                            if (!exists) devices.add(name to addr)
                        }
                    }

                    override fun onConnected(device: android.bluetooth.BluetoothDevice) {
                        val name = bluetoothNameOf(device) ?: device.address
                        mainHandler.post { connectedDeviceName = name }
                    }

                    override fun onDisconnected() {
                        mainHandler.post { connectedDeviceName = null }
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
                                vitalAlerts.onMeasurement(measurement)?.let(raiseAlert)
                            }
                        }
                    }
                }
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result ->
                val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                val callGranted = result[Manifest.permission.CALL_PHONE] == true

                if (!fineGranted && !coarseGranted) {
                    status = "Permission localisation refusée"
                    speak("Permission localisation refusée")
                    readyToCall = false
                    return@rememberLauncherForActivityResult
                }

                canCallDirectly = callGranted
                status = if (callGranted) {
                    "Récupération de la position…"
                } else {
                    "Récupération de la position… (appel direct non autorisé)"
                }

                requestOneLocation(
                    onResult = { lat, lon ->
                        lastLat = lat
                        lastLon = lon

                        val spoken = "Je suis en urgence. Ma position GPS est latitude $lat et longitude $lon"
                        speak(spoken)

                        status = "Position: $lat, $lon"
                        emergencyNumber = emergencyNumberForCountryIso(getCountryIso())
                        readyToCall = true
                    },
                    onError = { err ->
                        status = err
                        speak(err)
                        readyToCall = false
                    }
                )
            }
        )

        val consentMessage = { lat: Double?, lon: Double?, number: String ->
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

            "Je ne peux pas parler. J'ai besoin d'aide. Je consens à recevoir des soins d'urgence. " +
                "Je suis au pays numéro d'urgence $number. " +
                position +
                "Mes constantes: fréquence cardiaque $hrText. Oxygénation $spo2Text. Tension $bpText. Température $tempText." +
                profile.spokenText()?.let { " $it" }.orEmpty()
        }

        val speakConsentAt = { lat: Double, lon: Double ->
            lastLat = lat
            lastLon = lon
            speak(consentMessage(lat, lon, emergencyNumberForCountryIso(getCountryIso())))
            status = "Message vocal envoyé"
        }

        val locationOnlyPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result ->
                val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

                if (!fineGranted && !coarseGranted) {
                    status = "Permission localisation refusée"
                    speak("Permission localisation refusée")
                    return@rememberLauncherForActivityResult
                }

                status = "Récupération de la position…"
                requestOneLocation(
                    onResult = { lat, lon -> speakConsentAt(lat, lon) },
                    onError = { err ->
                        status = err
                        speak(err)
                    }
                )
            }
        )

        val requestSosPermissionsAndStart = {
            readyToCall = false
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CALL_PHONE
                )
            )
        }

        val speakConsentWithGps = {
            val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (!fine && !coarse) {
                locationOnlyPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            } else {
                status = "Récupération de la position…"
                requestOneLocation(
                    onResult = { lat, lon -> speakConsentAt(lat, lon) },
                    onError = { err ->
                        status = err
                        speak(err)
                    }
                )
            }
        }

        val blePermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result ->
                val scanGranted = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    result[Manifest.permission.BLUETOOTH_SCAN] == true
                } else {
                    true
                }
                val connectGranted = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    result[Manifest.permission.BLUETOOTH_CONNECT] == true
                } else {
                    true
                }

                val locGranted = (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                    (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) ||
                    (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION))

                if (!scanGranted || !connectGranted) {
                    bleStatus = "BLE: permission bluetooth refusée"
                    return@rememberLauncherForActivityResult
                }

                if (!locGranted) {
                    // Sur certains appareils < Android 12, le scan exige la localisation.
                    bleStatus = "BLE: permission localisation requise pour scanner"
                    return@rememberLauncherForActivityResult
                }

                devices.clear()
                ectriBleManager.startScan()
            }
        )

        LaunchedEffect(Unit) {
            val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!fine && !coarse) {
                status = "Autorise la localisation pour fonctionner"
            }
        }

        // Periodic measurement requests (best-effort).
        LaunchedEffect(monitoringEnabled, connectedDeviceName) {
            if (!monitoringEnabled) return@LaunchedEffect
            if (connectedDeviceName == null) return@LaunchedEffect

            while (monitoringEnabled && connectedDeviceName != null) {
                ectriBleManager.startTest(EctriBleManager.TestType.HR)
                ectriBleManager.startTest(EctriBleManager.TestType.SPO2)
                ectriBleManager.startTest(EctriBleManager.TestType.TEMP)
                // BP is slower / more intrusive; run less frequently.
                ectriBleManager.startTest(EctriBleManager.TestType.BP)
                delay(30_000)
            }
        }

        // Countdown, then the emergency call itself: nobody may be able to confirm it.
        LaunchedEffect(alertActive, countdownSeconds) {
            if (!alertActive) return@LaunchedEffect
            if (countdownSeconds > 0) {
                delay(1_000)
                countdownSeconds -= 1
                return@LaunchedEffect
            }

            alertActive = false
            vitalAlerts.reset()
            val number = emergencyNumberForCountryIso(getCountryIso())
            val callAndSpeak = { lat: Double?, lon: Double? ->
                status = if (hasPermission(Manifest.permission.CALL_PHONE)) {
                    "Appel d'urgence automatique ($number)"
                } else {
                    "Permission appel refusée: composeur ouvert ($number)"
                }
                startEmergencyCall(number)
                speak(consentMessage(lat, lon, number))
            }

            val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!fine && !coarse) {
                // Without location the call matters more than the position.
                callAndSpeak(null, null)
                return@LaunchedEffect
            }
            status = "Récupération de la position avant l'appel…"
            requestOneLocation(
                onResult = { lat, lon ->
                    lastLat = lat
                    lastLon = lon
                    callAndSpeak(lat, lon)
                },
                onError = { callAndSpeak(null, null) }
            )
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "SOS ${packageManager.getPackageInfo(packageName, 0).versionName}")
            Text(text = status)

            if (lastLat != null && lastLon != null) {
                Text(text = "Latitude: ${lastLat}")
                Text(text = "Longitude: ${lastLon}")
                Text(text = "Lien Maps: https://maps.google.com/?q=${lastLat},${lastLon}")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    requestSosPermissionsAndStart()
                }
            ) {
                Text("Obtenir position")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { speakConsentWithGps() }
            ) {
                Text("Je ne peux pas parler (message vocal)")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = readyToCall && emergencyNumber != null,
                onClick = {
                    val number = emergencyNumber ?: return@Button
                    if (!canCallDirectly) {
                        status = "Permission appel refusée, ouverture du composeur"
                        speak("Permission appel refusée, ouverture du composeur")
                    }
                    startEmergencyCall(number)
                }
            ) {
                Text("Confirmer l'appel d'urgence")
            }

            Text(text = "---")
            Text(text = "Profil (lu aux secours)")
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = heightText,
                onValueChange = {
                    heightText = it
                    profile = profile.copy(heightCm = HealthProfile.parseHeightCm(it))
                    saveProfile(profile)
                },
                label = { Text("Taille (cm)") },
                isError = heightText.isNotBlank() && profile.heightCm == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = weightText,
                onValueChange = {
                    weightText = it
                    profile = profile.copy(weightKg = HealthProfile.parseWeightKg(it))
                    saveProfile(profile)
                },
                label = { Text("Poids (kg)") },
                isError = weightText.isNotBlank() && profile.weightKg == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Text(text = "---")
            Text(text = "Bague ECTRI")
            Text(text = bleStatus)
            Text(text = "Connecté: ${connectedDeviceName ?: "non"}")

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Monitoring vital: ${if (monitoringEnabled) "ON" else "OFF"}")
                Switch(
                    checked = monitoringEnabled,
                    onCheckedChange = { enabled ->
                        monitoringEnabled = enabled
                        if (enabled) {
                            speak("Monitoring activé")
                        } else {
                            alertActive = false
                            alertReason = null
                            vitalAlerts.reset()
                            speak("Monitoring désactivé")
                        }
                    }
                )

                if (alertActive) {
                    Text(text = "ALERTE: ${alertReason ?: ""}")
                    Text(text = "Appel SOS dans: ${countdownSeconds}s")
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            alertActive = false
                            alertReason = null
                            vitalAlerts.reset()
                            speak("Alerte annulée")
                        }
                    ) {
                        Text("Annuler")
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val perms = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            add(Manifest.permission.BLUETOOTH_SCAN)
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }.toTypedArray()

                    blePermissionLauncher.launch(perms)
                }
            ) {
                Text("Scanner / trouver la bague")
            }

            if (devices.isEmpty()) {
                Text(text = "Aucun appareil trouvé (pour l’instant)")
            } else {
                Text(text = "Appareils trouvés:")
                // Unfiltered scan: nearby devices may be many, the ring must stay reachable.
                devices.sortedBy { it.first == "(sans nom)" }.forEach { (name, addr) ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
                            val adapter = manager?.adapter
                            val dev = adapter?.getRemoteDevice(addr)
                            if (dev != null) ectriBleManager.connect(dev)
                        }
                    ) {
                        Text("Connecter: $name ($addr)")
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { ectriBleManager.startTest(EctriBleManager.TestType.HR) }
            ) {
                Text("Mesurer FC")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { ectriBleManager.startTest(EctriBleManager.TestType.SPO2) }
            ) {
                Text("Mesurer SpO2")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { ectriBleManager.startTest(EctriBleManager.TestType.BP) }
            ) {
                Text("Mesurer tension")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { ectriBleManager.startTest(EctriBleManager.TestType.TEMP) }
            ) {
                Text("Mesurer température")
            }

            Text(text = "FC: ${lastHr ?: "--"} bpm")
            Text(text = "SpO2: ${lastSpo2 ?: "--"} %")
            Text(text = "Tension: ${lastBpHigh ?: "--"}/${lastBpLow ?: "--"}")
            Text(text = "Temp: ${lastTemp ?: "--"} °C")
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