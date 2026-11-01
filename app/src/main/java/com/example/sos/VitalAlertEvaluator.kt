package com.example.sos

/**
 * Detects a vital danger from the ring measurements.
 *
 * A metric raises an alert after [REQUIRED_CONSECUTIVE] abnormal readings in a row. Each
 * measurement only updates the counters of the metrics it carries: a new temperature reading
 * must not count an old low SpO2 a second time, and two identical abnormal readings must both
 * count.
 */
class VitalAlertEvaluator {

    private var spo2Low = 0
    private var hrHigh = 0
    private var hrLow = 0
    private var tempHigh = 0
    private var tempLow = 0
    private var bpHigh = 0

    /** Returns the alert reason once a metric is abnormal enough, null otherwise. */
    fun onMeasurement(measurement: EctriBleManager.Measurement): String? {
        measurement.spo2Percent?.let { spo2 ->
            spo2Low = next(spo2Low, spo2 < 88f)
            if (spo2Low >= REQUIRED_CONSECUTIVE) return "SpO2 basse (${spo2}%)"
        }
        measurement.heartRateBpm?.let { hr ->
            hrHigh = next(hrHigh, hr > 160)
            hrLow = next(hrLow, hr < 40)
            if (hrHigh >= REQUIRED_CONSECUTIVE) return "Fréquence cardiaque élevée ($hr bpm)"
            if (hrLow >= REQUIRED_CONSECUTIVE) return "Fréquence cardiaque basse ($hr bpm)"
        }
        measurement.temperatureC?.let { temp ->
            tempHigh = next(tempHigh, temp > 40f)
            tempLow = next(tempLow, temp < 35f)
            if (tempHigh >= REQUIRED_CONSECUTIVE) return "Température élevée (${temp}°C)"
            if (tempLow >= REQUIRED_CONSECUTIVE) return "Température basse (${temp}°C)"
        }
        val high = measurement.bpHigh
        val low = measurement.bpLow
        if (high != null && low != null) {
            bpHigh = next(bpHigh, high > 180 || low > 120)
            if (bpHigh >= REQUIRED_CONSECUTIVE) return "Tension élevée ($high/$low)"
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

    private fun next(count: Int, abnormal: Boolean) = if (abnormal) count + 1 else 0

    companion object {
        const val REQUIRED_CONSECUTIVE = 2
    }
}
