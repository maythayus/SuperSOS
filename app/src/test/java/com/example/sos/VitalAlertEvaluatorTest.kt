package com.example.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VitalAlertEvaluatorTest {

    private fun spo2(value: Float) =
        EctriBleManager.Measurement(spo2Percent = value, rawType = 0x12, raw = ByteArray(20))

    private fun temp(value: Float) =
        EctriBleManager.Measurement(temperatureC = value, rawType = 0x40, raw = ByteArray(20))

    private fun hr(value: Int) =
        EctriBleManager.Measurement(heartRateBpm = value, rawType = 0x0A, raw = ByteArray(20))

    @Test
    fun twoIdenticalAbnormalReadingsRaiseAnAlert() {
        val evaluator = VitalAlertEvaluator()
        assertNull(evaluator.onMeasurement(spo2(85f)))
        assertEquals("SpO2 basse (85.0%)", evaluator.onMeasurement(spo2(85f)))
    }

    @Test
    fun otherMetricsDoNotRecountAnOldReading() {
        val evaluator = VitalAlertEvaluator()
        assertNull(evaluator.onMeasurement(spo2(85f)))
        assertNull(evaluator.onMeasurement(temp(37f)))
        assertNull(evaluator.onMeasurement(temp(37.2f)))
    }

    @Test
    fun aNormalReadingResetsTheCounter() {
        val evaluator = VitalAlertEvaluator()
        assertNull(evaluator.onMeasurement(hr(170)))
        assertNull(evaluator.onMeasurement(hr(90)))
        assertNull(evaluator.onMeasurement(hr(170)))
        assertNotNull(evaluator.onMeasurement(hr(175)))
    }

    @Test
    fun resetClearsPendingViolations() {
        val evaluator = VitalAlertEvaluator()
        assertNull(evaluator.onMeasurement(hr(35)))
        evaluator.reset()
        assertNull(evaluator.onMeasurement(hr(35)))
    }
}
