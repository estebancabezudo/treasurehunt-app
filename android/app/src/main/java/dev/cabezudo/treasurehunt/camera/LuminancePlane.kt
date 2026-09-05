package dev.cabezudo.treasurehunt.camera

import java.nio.ByteBuffer

data class LuminancePlane(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
)

data class CopiedLuminance(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
)

class LuminancePlaneCopier {
    fun copy(plane: LuminancePlane): CopiedLuminance {
        require(plane.width > 0 && plane.height > 0) {
            "Las dimensiones del plano Y deben ser positivas."
        }
        require(plane.rowStride > 0 && plane.pixelStride > 0) {
            "Los strides del plano Y deben ser positivos."
        }

        val requiredRowBytes = (plane.width - 1L) * plane.pixelStride + 1L
        require(plane.rowStride.toLong() >= requiredRowBytes) {
            "rowStride=${plane.rowStride} no alcanza para width=${plane.width} " +
                "y pixelStride=${plane.pixelStride}."
        }

        val source = plane.buffer.duplicate()
        val firstByte = source.position().toLong()
        val lastByte = firstByte +
            (plane.height - 1L) * plane.rowStride +
            (plane.width - 1L) * plane.pixelStride
        require(lastByte < source.limit().toLong()) {
            "El buffer Y no contiene suficientes datos accesibles: " +
                "último índice requerido=$lastByte, límite=${source.limit()}."
        }

        val outputSize = plane.width.toLong() * plane.height
        require(outputSize <= Int.MAX_VALUE) { "El plano Y es demasiado grande." }
        val output = ByteArray(outputSize.toInt())
        var outputOffset = 0
        for (row in 0 until plane.height) {
            val rowStart = (firstByte + row.toLong() * plane.rowStride).toInt()
            if (plane.pixelStride == 1) {
                source.position(rowStart)
                source.get(output, outputOffset, plane.width)
                outputOffset += plane.width
            } else {
                for (column in 0 until plane.width) {
                    output[outputOffset++] = source.get(rowStart + column * plane.pixelStride)
                }
            }
        }
        return CopiedLuminance(plane.width, plane.height, output)
    }
}

object MonochromeRotation {
    fun rotate(source: CopiedLuminance, rotationDegrees: Int): CopiedLuminance {
        require(source.width > 0 && source.height > 0) {
            "Las dimensiones de luminancia deben ser positivas."
        }
        require(source.pixels.size == source.width * source.height) {
            "La luminancia no coincide con sus dimensiones."
        }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "Rotación no compatible: $rotationDegrees°."
        }
        if (rotationDegrees == 0) return source

        val outputWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            source.height
        } else {
            source.width
        }
        val outputHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            source.width
        } else {
            source.height
        }
        val output = ByteArray(source.pixels.size)
        for (sourceY in 0 until source.height) {
            for (sourceX in 0 until source.width) {
                val destinationX: Int
                val destinationY: Int
                when (rotationDegrees) {
                    90 -> {
                        destinationX = source.height - 1 - sourceY
                        destinationY = sourceX
                    }
                    180 -> {
                        destinationX = source.width - 1 - sourceX
                        destinationY = source.height - 1 - sourceY
                    }
                    else -> {
                        destinationX = sourceY
                        destinationY = source.width - 1 - sourceX
                    }
                }
                output[destinationY * outputWidth + destinationX] =
                    source.pixels[sourceY * source.width + sourceX]
            }
        }
        return CopiedLuminance(outputWidth, outputHeight, output)
    }
}
