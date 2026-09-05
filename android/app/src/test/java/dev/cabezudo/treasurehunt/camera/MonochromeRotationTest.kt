package dev.cabezudo.treasurehunt.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MonochromeRotationTest {
    private val source = CopiedLuminance(
        width = 3,
        height = 2,
        pixels = bytes(1, 2, 3, 4, 5, 6),
    )

    @Test
    fun rotationZeroKeepsPixelsAndDimensions() {
        assertRotation(0, 3, 2, bytes(1, 2, 3, 4, 5, 6))
    }

    @Test
    fun rotationNinetyIsClockwiseAndSwapsDimensions() {
        assertRotation(90, 2, 3, bytes(4, 1, 5, 2, 6, 3))
    }

    @Test
    fun rotationOneEightyReversesBothAxes() {
        assertRotation(180, 3, 2, bytes(6, 5, 4, 3, 2, 1))
    }

    @Test
    fun rotationTwoSeventyIsClockwiseAndSwapsDimensions() {
        assertRotation(270, 2, 3, bytes(3, 6, 2, 5, 1, 4))
    }

    private fun assertRotation(
        degrees: Int,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedPixels: ByteArray,
    ) {
        val result = MonochromeRotation.rotate(source, degrees)

        assertEquals(expectedWidth, result.width)
        assertEquals(expectedHeight, result.height)
        assertArrayEquals(expectedPixels, result.pixels)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
