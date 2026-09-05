package dev.cabezudo.treasurehunt.ocr

import java.text.Normalizer
import java.util.Locale

object ExpectedText {
    const val RAW = "PUERTA DEL GUARDIÁN 27"
    const val NORMALIZED = "PUERTA DEL GUARDIAN 27"
}

object TextNormalizer {
    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(text: String): String {
        val uppercase = text.uppercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(uppercase, Normalizer.Form.NFD)
        return combiningMarks.replace(decomposed, "")
            .replace(separators, " ")
            .trim()
    }
}

data class ExpectedTextDecision(
    val normalizedText: String,
    val matched: Boolean,
    val rejectionReason: String?,
)

object ExpectedTextMatcher {
    fun evaluate(text: String): ExpectedTextDecision {
        val normalized = TextNormalizer.normalize(text)
        val matched = normalized.isNotEmpty() &&
            " $normalized ".contains(" ${ExpectedText.NORMALIZED} ")
        return ExpectedTextDecision(
            normalizedText = normalized,
            matched = matched,
            rejectionReason = when {
                normalized.isEmpty() -> "ML Kit no encontró texto en el cuadro."
                matched -> null
                else -> "La secuencia completa '${ExpectedText.NORMALIZED}' no está presente."
            },
        )
    }
}
