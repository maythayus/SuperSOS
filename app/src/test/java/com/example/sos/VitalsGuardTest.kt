package com.example.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VitalsGuardTest {
    private fun spo2(v: Float) = EctriBleManager.Measurement(spo2Percent = v, rawType = 0x12, raw = ByteArray(20))
    private fun hr(v: Int) = EctriBleManager.Measurement(heartRateBpm = v, rawType = 0x0A, raw = ByteArray(20))
    private fun bp(sys: Int, dia: Int) =
        EctriBleManager.Measurement(bpHigh = sys, bpLow = dia, rawType = 0x11, raw = ByteArray(20))

    @Test
    fun singleBadReadingDoesNotAlert() {
        val guard = VitalsGuard()
        assertNull(guard.onMeasurement(spo2(80f)))
    }

    @Test
    fun twoConsecutiveBadReadingsAlert() {
        val guard = VitalsGuard()
        assertNull(guard.onMeasurement(spo2(80f)))
        assertNotNull(guard.onMeasurement(spo2(81f)))
    }

    @Test
    fun otherVitalsDoNotRecountAStaleBadReading() {
        val guard = VitalsGuard()
        assertNull(guard.onMeasurement(spo2(80f)))
        assertNull(guard.onMeasurement(hr(70)))
        assertNull(guard.onMeasurement(hr(72)))
    }

    @Test
    fun normalReadingResetsCounter() {
        val guard = VitalsGuard()
        assertNull(guard.onMeasurement(hr(170)))
        assertNull(guard.onMeasurement(hr(80)))
        assertNull(guard.onMeasurement(hr(170)))
    }

    @Test
    fun highBloodPressureAlerts() {
        val guard = VitalsGuard()
        assertNull(guard.onMeasurement(bp(190, 100)))
        assertEquals("Tension élevée (185/90)", guard.onMeasurement(bp(185, 90)))
    }

    @Test
    fun resetClearsCounters() {
        val guard = VitalsGuard()
        assertNull(guard.onMeasurement(hr(30)))
        guard.reset()
        assertNull(guard.onMeasurement(hr(30)))
    }
}
