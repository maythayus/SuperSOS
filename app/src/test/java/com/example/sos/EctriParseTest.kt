package com.example.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EctriParseTest {

    private fun parse(type: Int, vararg values: Int): EctriBleManager.Measurement? {
        val payload = IntArray(19)
        values.forEachIndexed { index, value -> payload[11 + index] = value }
        return EctriBleManager.parseMeasurement(type, payload, ByteArray(20))
    }

    @Test
    fun realReadingsAreDecoded() {
        assertEquals(78, parse(0x0A, 78)?.heartRateBpm)
        assertEquals(106, parse(0x11, 106, 68, 78)?.bpHigh)
        assertEquals(68, parse(0x11, 106, 68, 78)?.bpLow)
        assertEquals(97.5f, parse(0x12, 97, 5)?.spo2Percent)
        assertEquals(36.6f, parse(0x40, 1, 110)?.temperatureC)
    }

    @Test
    fun temperatureCarriesTheTimeStoredByTheRing() {
        val payload = IntArray(19)
        payload[8] = 0x08
        payload[9] = 0x10
        payload[11] = 1
        payload[12] = 110
        assertEquals("08:10", EctriBleManager.parseMeasurement(0x40, payload, ByteArray(20))?.temperatureTime)
    }

    @Test
    fun temperatureCounterFramesAreIgnored() {
        val payload = IntArray(19)
        payload[0] = 2
        payload[11] = 1
        payload[12] = 110
        assertNull(EctriBleManager.parseMeasurement(0x40, payload, ByteArray(20)))
    }

    @Test
    fun lastTemperatureRequestMatchesEctri() {
        val packet = EctriBleManager.READ_LAST_TEMPERATURE_PACKET
        assertEquals(20, packet.size)
        assertEquals(0xFC.toByte(), packet[0])
        assertEquals(0x40.toByte(), packet[1])
        assertEquals(0.toByte(), packet[2])
    }

    @Test
    fun zeroMeansNoReading() {
        assertNull(parse(0x0A, 0))
        assertNull(parse(0x11, 0, 0, 0))
        assertNull(parse(0x12, 0, 0))
        assertNull(parse(0x40, 0, 0))
    }

    @Test
    fun aRingSendingZerosNeverRaisesAnAlert() {
        val evaluator = VitalAlertEvaluator()
        repeat(5) {
            listOf(parse(0x0A, 0), parse(0x12, 0, 0), parse(0x40, 0, 0))
                .filterNotNull()
                .forEach { assertNull(evaluator.onMeasurement(it)) }
        }
    }
}
