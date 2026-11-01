package com.example.sos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EctriUuidTest {

    @Test
    fun vendorBaseMatches() {
        val uuid = UUID.fromString("F000EFE0-0451-4000-0000-00000000B000")
        assertTrue(EctriBleManager.hasShortId(uuid, EctriBleManager.ECTRI_SERVICE_ID))
    }

    @Test
    fun standardBaseMatches() {
        val uuid = UUID.fromString("0000EFE0-0000-1000-8000-00805F9B34FB")
        assertTrue(EctriBleManager.hasShortId(uuid, EctriBleManager.ECTRI_SERVICE_ID))
    }

    @Test
    fun otherServicesDoNotMatch() {
        val uuid = UUID.fromString("00001800-0000-1000-8000-00805F9B34FB")
        assertFalse(EctriBleManager.hasShortId(uuid, EctriBleManager.ECTRI_SERVICE_ID))
    }
}
