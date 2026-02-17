package com.example.sos

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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

        var monitoringEnabled by remember { mutableStateOf(false) }
        var alertActive by remember { mutableStateOf(false) }
        var alertReason by remember { mutableStateOf<String?>(null) }
        var countdownSeconds by remember { mutableStateOf(10) }

        var spo2LowCount by remember { mutableStateOf(0) }
        var hrHighCount by remember { mutableStateOf(0) }
        var hrLowCount by remember { mutableStateOf(0) }
        var tempHighCount by remember { mutableStateOf(0) }
        var tempLowCount by remember { mutableStateOf(0) }
        var bpHighCount by remember { mutableStateOf(0) }

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
                        val name = device.name ?: "(sans nom)"
                        mainHandler.post {
                            val exists = devices.any { it.second == addr }
                            if (!exists) devices.add(name to addr)
                        }
                    }

                    override fun onConnected(device: android.bluetooth.BluetoothDevice) {
                        val name = device.name ?: device.address
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
                    onResult = { lat, lon ->
                        lastLat = lat
                        lastLon = lon

                        val number = emergencyNumberForCountryIso(getCountryIso())
                        val hrText = lastHr?.let { "$it battements par minute" } ?: "inconnu"
                        val spo2Text = lastSpo2?.let { "$it pourcent" } ?: "inconnu"
                        val bpText = if (lastBpHigh != null && lastBpLow != null) {
                            "${lastBpHigh} sur ${lastBpLow}"
                        } else {
                            "inconnue"
                        }
                        val tempText = lastTemp?.let { "$it degrés" } ?: "inconnue"
                        val maps = "https://maps.google.com/?q=$lat,$lon"

                        val message = "Je ne peux pas parler. J'ai besoin d'aide. Je consens à recevoir des soins d'urgence. " +
                            "Je suis au pays numéro d'urgence $number. " +
                            "Ma position est latitude $lat et longitude $lon. " +
                            "Lien carte $maps. " +
                            "Mes constantes: fréquence cardiaque $hrText. Oxygénation $spo2Text. Tension $bpText. Température $tempText."

                        speak(message)
                        status = "Message vocal envoyé"
                    },
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
                    onResult = { lat, lon ->
                        lastLat = lat
                        lastLon = lon

                        val number = emergencyNumberForCountryIso(getCountryIso())
                        val hrText = lastHr?.let { "$it battements par minute" } ?: "inconnu"
                        val spo2Text = lastSpo2?.let { "$it pourcent" } ?: "inconnu"
                        val bpText = if (lastBpHigh != null && lastBpLow != null) {
                            "${lastBpHigh} sur ${lastBpLow}"
                        } else {
                            "inconnue"
                        }
                        val tempText = lastTemp?.let { "$it degrés" } ?: "inconnue"
                        val maps = "https://maps.google.com/?q=$lat,$lon"

                        val message = "Je ne peux pas parler. J'ai besoin d'aide. Je consens à recevoir des soins d'urgence. " +
                            "Je suis au pays numéro d'urgence $number. " +
                            "Ma position est latitude $lat et longitude $lon. " +
                            "Lien carte $maps. " +
                            "Mes constantes: fréquence cardiaque $hrText. Oxygénation $spo2Text. Tension $bpText. Température $tempText."

                        speak(message)
                        status = "Message vocal envoyé"
                    },
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

        // Evaluate vital rules with 2 consecutive violations guard.
        LaunchedEffect(monitoringEnabled, lastSpo2, lastHr, lastTemp, lastBpHigh, lastBpLow) {
            if (!monitoringEnabled) return@LaunchedEffect
            if (alertActive) return@LaunchedEffect

            val spo2 = lastSpo2
            val hr = lastHr
            val temp = lastTemp
            val bpH = lastBpHigh
            val bpL = lastBpLow

            val spo2Low = (spo2 != null && spo2 < 88f)
            val hrHigh = (hr != null && hr > 160)
            val hrLow = (hr != null && hr < 40)
            val tempHigh = (temp != null && temp > 40f)
            val tempLow = (temp != null && temp < 35f)
            val bpDanger = (bpH != null && bpL != null && (bpH > 180 || bpL > 120))

            spo2LowCount = if (spo2Low) spo2LowCount + 1 else 0
            hrHighCount = if (hrHigh) hrHighCount + 1 else 0
            hrLowCount = if (hrLow) hrLowCount + 1 else 0
            tempHighCount = if (tempHigh) tempHighCount + 1 else 0
            tempLowCount = if (tempLow) tempLowCount + 1 else 0
            bpHighCount = if (bpDanger) bpHighCount + 1 else 0

            val reason = when {
                spo2LowCount >= 2 -> "SpO2 basse (${spo2}%)"
                hrHighCount >= 2 -> "Fréquence cardiaque élevée (${hr} bpm)"
                hrLowCount >= 2 -> "Fréquence cardiaque basse (${hr} bpm)"
                tempHighCount >= 2 -> "Température élevée (${temp}°C)"
                tempLowCount >= 2 -> "Température basse (${temp}°C)"
                bpHighCount >= 2 -> "Tension élevée (${bpH}/${bpL})"
                else -> null
            }

            if (reason != null) {
                alertReason = reason
                alertActive = true
                countdownSeconds = 10
                speak("Alerte vitale détectée. $reason. Appel d'urgence dans dix secondes. Appuyez sur annuler.")
            }
        }

        // Countdown + auto SOS.
        LaunchedEffect(alertActive, countdownSeconds) {
            if (!alertActive) return@LaunchedEffect
            if (countdownSeconds <= 0) {
                alertActive = false
                val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                val call = hasPermission(Manifest.permission.CALL_PHONE)
                if (!fine && !coarse) {
                    status = "Permissions SOS manquantes: localisation"
                    speak("Permissions manquantes pour envoyer votre position")
                    return@LaunchedEffect
                }
                if (!call) {
                    status = "Permission appel refusée: composeur"
                }

                requestSosPermissionsAndStart()
                return@LaunchedEffect
            }

            delay(1_000)
            countdownSeconds -= 1
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "SOS")
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
                            spo2LowCount = 0
                            hrHighCount = 0
                            hrLowCount = 0
                            tempHighCount = 0
                            tempLowCount = 0
                            bpHighCount = 0
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
                devices.take(5).forEach { (name, addr) ->
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