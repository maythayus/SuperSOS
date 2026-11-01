package com.example.sos

/**
 * Height and weight of the wearer, read out to first responders (weight drives drug dosing).
 *
 * They deliberately do not move the alert thresholds: there is no validated adult adjustment of
 * SpO2, heart rate or temperature limits by height or weight, and a looser limit could silence
 * a real emergency.
 */
data class HealthProfile(val heightCm: Int? = null, val weightKg: Int? = null) {

    /** Sentence for the voice message, null when nothing is filled in. */
    fun spokenText(): String? {
        val parts = listOfNotNull(
            heightCm?.let { "Je mesure $it centimètres." },
            weightKg?.let { "Je pèse $it kilos." }
        )
        return parts.joinToString(" ").ifEmpty { null }
    }

    companion object {
        val HEIGHT_RANGE_CM = 50..250
        val WEIGHT_RANGE_KG = 3..350

        fun parseHeightCm(text: String) = text.trim().toIntOrNull()?.takeIf { it in HEIGHT_RANGE_CM }

        fun parseWeightKg(text: String) = text.trim().toIntOrNull()?.takeIf { it in WEIGHT_RANGE_KG }
    }
}
