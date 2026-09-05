package dev.cabezudo.treasurehunt.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun normalizesCaseAndDiacritics() {
        assertEquals("PUERTA DEL GUARDIAN 27", TextNormalizer.normalize("Puerta del Guardián 27"))
        assertEquals("GUARDIAN", TextNormalizer.normalize("GUARDIAN"))
        assertEquals("GUARDIAN", TextNormalizer.normalize("GUARDIÁN"))
    }

    @Test
    fun collapsesLinesSpacesAndPunctuationIntoSeparators() {
        assertEquals(
            ExpectedText.NORMALIZED,
            TextNormalizer.normalize("  puerta, del\n\n  guardián... 27  "),
        )
    }

    @Test
    fun acceptsExactPhraseInsideAdditionalTextAndAcrossLines() {
        assertTrue(ExpectedTextMatcher.evaluate(ExpectedText.RAW).matched)
        assertTrue(ExpectedTextMatcher.evaluate("ANTES ${ExpectedText.RAW} DESPUÉS").matched)
        assertTrue(ExpectedTextMatcher.evaluate("PUERTA DEL\nGUARDIÁN\n27").matched)
    }

    @Test
    fun rejectsMissingWrongOrIncompleteNumberAndWrongOrder() {
        assertFalse(ExpectedTextMatcher.evaluate("PUERTA DEL GUARDIÁN").matched)
        assertFalse(ExpectedTextMatcher.evaluate("PUERTA DEL GUARDIÁN 28").matched)
        assertFalse(ExpectedTextMatcher.evaluate("GUARDIÁN 27").matched)
        assertFalse(ExpectedTextMatcher.evaluate("GUARDIÁN PUERTA 27 DEL").matched)
        assertFalse(ExpectedTextMatcher.evaluate("PUERTA DEL GUARDI 27").matched)
    }

    @Test
    fun rejectsEmptyText() {
        val decision = ExpectedTextMatcher.evaluate(" \n ")
        assertFalse(decision.matched)
        assertEquals("", decision.normalizedText)
    }
}
