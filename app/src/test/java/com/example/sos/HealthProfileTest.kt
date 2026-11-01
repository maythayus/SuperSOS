package com.example.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthProfileTest {

    @Test
    fun bothValuesAreSpoken() {
        assertEquals(
            "Je mesure 175 centimètres. Je pèse 70 kilos.",
            HealthProfile(heightCm = 175, weightKg = 70).spokenText()
        )
    }

    @Test
    fun onlyTheKnownValueIsSpoken() {
        assertEquals("Je pèse 70 kilos.", HealthProfile(weightKg = 70).spokenText())
    }

    @Test
    fun anEmptyProfileSaysNothing() {
        assertNull(HealthProfile().spokenText())
    }

    @Test
    fun implausibleValuesAreRejected() {
        assertEquals(175, HealthProfile.parseHeightCm(" 175 "))
        assertNull(HealthProfile.parseHeightCm("1.75"))
        assertNull(HealthProfile.parseHeightCm("17"))
        assertEquals(70, HealthProfile.parseWeightKg("70"))
        assertNull(HealthProfile.parseWeightKg("7000"))
        assertNull(HealthProfile.parseWeightKg(""))
    }
}
