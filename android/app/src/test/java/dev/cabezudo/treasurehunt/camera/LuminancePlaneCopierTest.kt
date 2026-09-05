package dev.cabezudo.treasurehunt.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LuminancePlaneCopierTest {
    private val copier = LuminancePlaneCopier()

    @Test
    fun copiesContiguousRowsWithoutPadding() {
        val result = copier.copy(plane(bytes(1, 2, 3, 4, 5, 6), 3, 2, 3, 1))

        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), result.pixels)
    }

    @Test
    fun skipsPaddingWhenRowStrideExceedsWidth() {
        val result = copier.copy(
            plane(bytes(1, 2, 3, 99, 99, 4, 5, 6), 3, 2, rowStride = 5, pixelStride = 1),
        )

        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), result.pixels)
    }

    @Test
    fun samplesPixelsWhenPixelStrideExceedsOne() {
        val result = copier.copy(
            plane(bytes(1, 99, 2, 99, 3, 4, 99, 5, 99, 6), 3, 2, 5, 2),
        )

        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), result.pixels)
    }

    @Test
    fun respectsInitialPositionWithoutChangingSharedBuffer() {
        val buffer = ByteBuffer.wrap(bytes(88, 77, 1, 2, 3, 4, 66), 2, 4)
        val initialPosition = buffer.position()

        val result = copier.copy(LuminancePlane(buffer, 2, 2, 2, 1))

        assertArrayEquals(bytes(1, 2, 3, 4), result.pixels)
        assertEquals(initialPosition, buffer.position())
    }

    @Test
    fun rejectsInsufficientAccessibleBuffer() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            copier.copy(plane(bytes(1, 2, 3, 4, 5), 3, 2, 3, 1))
        }

        assertTrue(error.message!!.contains("suficientes datos"))
    }

    @Test
    fun rejectsInvalidDimensionsAndStrides() {
        assertThrows(IllegalArgumentException::class.java) {
            copier.copy(plane(bytes(1), 0, 1, 1, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            copier.copy(plane(bytes(1), 1, 1, 0, 1))
        }
    }

    private fun plane(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ) = LuminancePlane(ByteBuffer.wrap(data), width, height, rowStride, pixelStride)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
