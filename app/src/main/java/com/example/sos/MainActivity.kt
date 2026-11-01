package com.example.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.sos.ui.theme.SOSTheme

class MainActivity : ComponentActivity() {
    private val emergency get() = VitalMonitor.emergency

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VitalMonitor.init(this)
        if (VitalMonitor.monitoringEnabled) VitalMonitor.ensureServiceRunning()

        enableEdgeToEdge()
        setContent {
            SOSTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SosScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    // A ring tap may reach Android as a keyboard or touch device instead of a BLE frame.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            VitalMonitor.logRaw("Touche: ${KeyEvent.keyCodeToString(event.keyCode)} (${event.device?.name})")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val external = android.os.Build.VERSION.SDK_INT >= 29 && event.device?.isExternal == true
        if (external && event.actionMasked == MotionEvent.ACTION_DOWN) {
            VitalMonitor.logRaw("Geste: ${event.device?.name}")
        }
        return super.dispatchTouchEvent(event)
    }

    private fun hasPermission(permission: String) = emergency.hasPermission(permission)

    private fun speak(text: String) = emergency.speak(text)

    private fun emergencyNumberForCountryIso(countryIso: String?) = emergency.emergencyNumberForCountryIso(countryIso)

    private fun getCountryIso() = emergency.getCountryIso()

    private fun startEmergencyCall(number: String) = emergency.startEmergencyCall(number)

    private fun requestOneLocation(onResult: (Double, Double) -> Unit, onError: (String) -> Unit) {
        emergency.requestOneLocation(onResult, onError)
    }

    // Without this, Doze suspends the monitoring on many phones despite the foreground service.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return

        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    @Composable
    private fun SosScreen(modifier: Modifier = Modifier) {
        var status by remember { mutableStateOf("Prêt") }
        var lastLat by remember { mutableStateOf<Double?>(null) }
        var lastLon by remember { mutableStateOf<Double?>(null) }
        var emergencyNumber by remember { mutableStateOf<String?>(null) }
        var canCallDirectly by remember { mutableStateOf(false) }
        var readyToCall by remember { mutableStateOf(false) }

        var heightText by remember { mutableStateOf(VitalMonitor.profile.heightCm?.toString().orEmpty()) }
        var weightText by remember { mutableStateOf(VitalMonitor.profile.weightKg?.toString().orEmpty()) }

        val startMonitoring = {
            VitalMonitor.setMonitoring(true)
            requestBatteryExemption()
        }
        val microphonePermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { granted ->
                if (granted) {
                    // A running service must be told it may now use the microphone.
                    if (VitalMonitor.serviceRunning) VitalMonitor.ensureServiceRunning()
                    VitalMonitor.toggleRecording("bouton")
                }
            }
        )
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { startMonitoring() }
        )

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

        val speakConsentAt = { lat: Double, lon: Double ->
            lastLat = lat
            lastLon = lon
            speak(VitalMonitor.consentMessage(lat, lon, emergencyNumberForCountryIso(getCountryIso())))
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
                    VitalMonitor.reportBleStatus("permission bluetooth refusée")
                    return@rememberLauncherForActivityResult
                }

                if (!locGranted) {
                    // Sur certains appareils < Android 12, le scan exige la localisation.
                    VitalMonitor.reportBleStatus("permission localisation requise pour scanner")
                    return@rememberLauncherForActivityResult
                }

                VitalMonitor.startScan()
            }
        )

        LaunchedEffect(Unit) {
            val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!fine && !coarse) {
                status = "Autorise la localisation pour fonctionner"
            }
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
            VitalMonitor.sosStatus?.let { Text(text = it) }

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
                    VitalMonitor.updateProfile(VitalMonitor.profile.copy(heightCm = HealthProfile.parseHeightCm(it)))
                },
                label = { Text("Taille (cm)") },
                isError = heightText.isNotBlank() && VitalMonitor.profile.heightCm == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = weightText,
                onValueChange = {
                    weightText = it
                    VitalMonitor.updateProfile(VitalMonitor.profile.copy(weightKg = HealthProfile.parseWeightKg(it)))
                },
                label = { Text("Poids (kg)") },
                isError = weightText.isNotBlank() && VitalMonitor.profile.weightKg == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Text(text = "---")
            Text(text = VitalMonitor.recordingFile?.let { "Enregistrement en cours: $it" } ?: "Enregistrement arrêté")
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        VitalMonitor.toggleRecording("bouton")
                    } else {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) {
                Text(if (VitalMonitor.recordingFile != null) "Arrêter l'enregistrement" else "Enregistrer (micro)")
            }

            Text(text = "---")
            Text(text = "Bague ECTRI")
            Text(text = VitalMonitor.bleStatus)
            Text(text = "Connecté: ${VitalMonitor.connectedDeviceName ?: "non"}")

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Monitoring vital: ${if (VitalMonitor.monitoringEnabled) "ON" else "OFF"}")
                Switch(
                    checked = VitalMonitor.monitoringEnabled,
                    onCheckedChange = { enabled ->
                        when {
                            !enabled -> VitalMonitor.setMonitoring(false)
                            android.os.Build.VERSION.SDK_INT >= 33 &&
                                !hasPermission(Manifest.permission.POST_NOTIFICATIONS) ->
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else -> startMonitoring()
                        }
                    }
                )

                if (VitalMonitor.alertActive) {
                    Text(text = "ALERTE: ${VitalMonitor.alertReason ?: ""}")
                    Text(text = "Appel SOS dans: ${VitalMonitor.countdownSeconds}s")
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { VitalMonitor.cancelAlert() }
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

            if (VitalMonitor.devices.isEmpty()) {
                Text(text = "Aucun appareil trouvé (pour l’instant)")
            } else {
                Text(text = "Appareils trouvés:")
                // Unfiltered scan: nearby devices may be many, the ring must stay reachable.
                VitalMonitor.devices.sortedBy { it.first == "(sans nom)" }.forEach { (name, addr) ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { VitalMonitor.connect(addr) }
                    ) {
                        Text("Connecter: $name ($addr)")
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { VitalMonitor.enableTapControl() }
            ) {
                Text("Activer le double tap (enregistrement)")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { VitalMonitor.startTest(EctriBleManager.TestType.HR) }
            ) {
                Text("Mesurer FC")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { VitalMonitor.startTest(EctriBleManager.TestType.SPO2) }
            ) {
                Text("Mesurer SpO2")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { VitalMonitor.startTest(EctriBleManager.TestType.BP) }
            ) {
                Text("Mesurer tension")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { VitalMonitor.startTest(EctriBleManager.TestType.TEMP) }
            ) {
                Text("Mesurer température")
            }

            Text(text = "FC: ${VitalMonitor.lastHr ?: "--"} bpm")
            Text(text = "SpO2: ${VitalMonitor.lastSpo2 ?: "--"} %")
            Text(text = "Tension: ${VitalMonitor.lastBpHigh ?: "--"}/${VitalMonitor.lastBpLow ?: "--"}")
            Text(text = "Temp: ${VitalMonitor.lastTemp ?: "--"} °C${VitalMonitor.lastTempTime?.let { " (mesurée à $it)" }.orEmpty()}")
            Text(text = "Journal bague:")
            VitalMonitor.rawLog.forEach { Text(text = it) }
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