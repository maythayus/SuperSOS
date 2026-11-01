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
