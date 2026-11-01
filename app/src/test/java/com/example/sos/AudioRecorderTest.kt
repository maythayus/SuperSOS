package com.example.sos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class AudioRecorderTest {

    @Test
    fun fileNamesAreSafeForEveryStorage() {
        val name = AudioRecorder.fileName(Date(0))
        assertTrue(name, Regex("SuperSOS_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.m4a").matches(name))
    }
}
