package com.example.sos

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/** Voice, location and call helpers, shared by the screen and the background monitoring. */
class EmergencyActions(private val context: Context) {
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val tts = TextToSpeech(context) { }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun emergencyNumberForCountryIso(countryIso: String?): String {
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

    fun getCountryIso(): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm?.networkCountryIso?.takeIf { it.isNotBlank() }
    }

    fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sos")
    }

    // placeCall works from a background service, where starting an activity is blocked.
    @SuppressLint("MissingPermission")
    fun startEmergencyCall(number: String) {
        val uri = Uri.parse("tel:$number")
        try {
            if (hasPermission(Manifest.permission.CALL_PHONE)) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                telecom.placeCall(uri, null)
            } else {
                context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: ActivityNotFoundException) {
            // Devices without telephony (tablets) have nothing to handle the intent.
            speak("Appel impossible sur cet appareil")
        } catch (_: SecurityException) {
            speak("Appel impossible sur cet appareil")
        }
    }

    @SuppressLint("MissingPermission")
    fun requestOneLocation(onResult: (Double, Double) -> Unit, onError: (String) -> Unit) {
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
}
