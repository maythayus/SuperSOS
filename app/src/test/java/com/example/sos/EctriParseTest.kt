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
    fun temperatureRequestsMatchEctri() {
        val count = EctriBleManager.READ_TEMP_COUNT_PACKET
        val history = EctriBleManager.READ_TEMP_HISTORY_PACKET
        assertEquals(20, count.size)
        assertEquals(0xFC.toByte(), count[0])
        assertEquals(0x40.toByte(), count[1])
        assertEquals(2.toByte(), count[2])
        assertEquals(1.toByte(), history[2])
    }

    @Test
    fun tapModePacketMatchesEctri() {
        val packet = EctriBleManager.MUSIC_CONTROL_MODE_PACKET
        val expected = listOf(0xFC, 0x5E, 0x62, 0x03, 0x09, 0x00, 0x08)
        assertEquals(20, packet.size)
        expected.forEachIndexed { index, value -> assertEquals(value.toByte(), packet[index]) }
        assertEquals(0x30.toByte(), packet[19])
        assertEquals(0, packet.sumOf { it.toInt() } and 0xFF)
    }

    private fun ringFrame(second: Int, third: Int) = ByteArray(20).also {
        it[0] = 0xFC.toByte()
        it[1] = second.toByte()
        it[2] = third.toByte()
    }

    @Test
    fun ringGesturesCarryTheirCode() {
        assertEquals(EctriBleManager.GESTURE_RECORD, EctriBleManager.ringGesture(ringFrame(0x3C, 3)))
        assertEquals(EctriBleManager.GESTURE_SOS, EctriBleManager.ringGesture(ringFrame(0x3C, 2)))
    }

    @Test
    fun otherRingFramesAreNotGestures() {
        assertNull(EctriBleManager.ringGesture(ringFrame(0x0F, 0x91)))
        assertNull(EctriBleManager.ringGesture(EctriBleManager.MUSIC_CONTROL_MODE_PACKET))
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
