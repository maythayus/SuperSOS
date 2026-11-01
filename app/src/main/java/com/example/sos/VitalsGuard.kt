package com.example.sos

/**
 * Rule-based vital sign check.
 *
 * Each metric has its own counter that only moves when a NEW reading of that metric
 * arrives, so a single out-of-range value cannot be counted twice when another vital
 * comes in. An alert fires after [requiredConsecutive] consecutive bad readings.
 */
class VitalsGuard(private val requiredConsecutive: Int = 2) {
    private var spo2Low = 0
    private var hrHigh = 0
    private var hrLow = 0
    private var tempHigh = 0
    private var tempLow = 0
    private var bpHigh = 0

    /** Returns an alert reason (French, spoken to the user) or null. */
    fun onMeasurement(m: EctriBleManager.Measurement): String? {
        m.spo2Percent?.let { spo2 ->
            spo2Low = if (spo2 < SPO2_MIN) spo2Low + 1 else 0
            if (spo2Low >= requiredConsecutive) return "SpO2 basse (${spo2}%)"
        }

        m.heartRateBpm?.let { hr ->
            hrHigh = if (hr > HR_MAX) hrHigh + 1 else 0
            hrLow = if (hr < HR_MIN) hrLow + 1 else 0
            if (hrHigh >= requiredConsecutive) return "Fréquence cardiaque élevée ($hr bpm)"
            if (hrLow >= requiredConsecutive) return "Fréquence cardiaque basse ($hr bpm)"
        }

        m.temperatureC?.let { temp ->
            tempHigh = if (temp > TEMP_MAX) tempHigh + 1 else 0
            tempLow = if (temp < TEMP_MIN) tempLow + 1 else 0
            if (tempHigh >= requiredConsecutive) return "Température élevée (${temp}°C)"
            if (tempLow >= requiredConsecutive) return "Température basse (${temp}°C)"
        }

        val sys = m.bpHigh
        val dia = m.bpLow
        if (sys != null && dia != null) {
            bpHigh = if (sys > SYS_MAX || dia > DIA_MAX) bpHigh + 1 else 0
            if (bpHigh >= requiredConsecutive) return "Tension élevée ($sys/$dia)"
        }

        return null
    }

    fun reset() {
        spo2Low = 0
        hrHigh = 0
        hrLow = 0
        tempHigh = 0
        tempLow = 0
        bpHigh = 0
    }

    companion object {
        const val SPO2_MIN = 88f
        const val HR_MAX = 160
        const val HR_MIN = 40
        const val TEMP_MAX = 40f
        const val TEMP_MIN = 35f
        const val SYS_MAX = 180
        const val DIA_MAX = 120
    }
}
