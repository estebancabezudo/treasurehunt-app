package dev.cabezudo.treasurehunt.calibrationdataset

import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import kotlin.math.hypot

class CalibrationFrameCoordinateMapper(
    private val roundTripTolerancePixels: Double = 1e-6,
) {
    fun preparedToCanonical(
        point: RecognitionPoint,
        metadata: CalibrationFrameCaptureMetadata,
    ): RecognitionPoint {
        requireValidMetadata(metadata)
        val cropWidth = metadata.cropRect.width
        val cropHeight = metadata.cropRect.height
        val cropPoint = when (metadata.rotationDegrees) {
            0 -> RecognitionPoint(point.x, point.y)
            90 -> RecognitionPoint(point.y, cropHeight - 1.0 - point.x)
            180 -> RecognitionPoint(cropWidth - 1.0 - point.x, cropHeight - 1.0 - point.y)
            270 -> RecognitionPoint(cropWidth - 1.0 - point.y, point.x)
            else -> error("Rotación no compatible: ${metadata.rotationDegrees}°.")
        }
        return RecognitionPoint(
            cropPoint.x + metadata.cropRect.left,
            cropPoint.y + metadata.cropRect.top,
        )
    }

    fun canonicalToPrepared(
        point: RecognitionPoint,
        metadata: CalibrationFrameCaptureMetadata,
    ): RecognitionPoint {
        requireValidMetadata(metadata)
        val x = point.x - metadata.cropRect.left
        val y = point.y - metadata.cropRect.top
        return when (metadata.rotationDegrees) {
            0 -> RecognitionPoint(x, y)
            90 -> RecognitionPoint(metadata.cropRect.height - 1.0 - y, x)
            180 -> RecognitionPoint(metadata.cropRect.width - 1.0 - x, metadata.cropRect.height - 1.0 - y)
            270 -> RecognitionPoint(y, metadata.cropRect.width - 1.0 - x)
            else -> error("Rotación no compatible: ${metadata.rotationDegrees}°.")
        }
    }

    fun mapAndValidate(
        preparedCorners: List<RecognitionPoint>,
        metadata: CalibrationFrameCaptureMetadata,
    ): List<RecognitionPoint> {
        require(preparedCorners.size == 54) { "Se requieren exactamente 54 esquinas." }
        val canonical = preparedCorners.map { preparedToCanonical(it, metadata) }
        preparedCorners.zip(canonical).forEach { (prepared, mapped) ->
            val roundTrip = canonicalToPrepared(mapped, metadata)
            require(hypot(roundTrip.x - prepared.x, roundTrip.y - prepared.y) <= roundTripTolerancePixels) {
                "El round trip excede la tolerancia de $roundTripTolerancePixels px."
            }
        }
        require(canonical.all {
            it.x.isFinite() && it.y.isFinite() &&
                it.x >= -roundTripTolerancePixels && it.x <= metadata.bufferWidth - 1.0 + roundTripTolerancePixels &&
                it.y >= -roundTripTolerancePixels && it.y <= metadata.bufferHeight - 1.0 + roundTripTolerancePixels
        }) { "La transformación canónica produjo puntos fuera del buffer." }
        return canonicalizeEndpointOrder(canonical)
    }

    private fun canonicalizeEndpointOrder(points: List<RecognitionPoint>): List<RecognitionPoint> {
        val first = points.first()
        val last = points.last()
        return if (last.y < first.y || (last.y == first.y && last.x < first.x)) points.reversed() else points
    }

    private fun requireValidMetadata(metadata: CalibrationFrameCaptureMetadata) {
        require(metadata.bufferWidth > 0 && metadata.bufferHeight > 0)
        require(metadata.cropRect.isValid())
        require(metadata.cropRect.left >= 0 && metadata.cropRect.top >= 0)
        require(metadata.cropRect.right <= metadata.bufferWidth && metadata.cropRect.bottom <= metadata.bufferHeight)
        val expectedPrepared = if (metadata.rotationDegrees in setOf(90, 270)) {
            Pair(metadata.cropRect.height, metadata.cropRect.width)
        } else {
            Pair(metadata.cropRect.width, metadata.cropRect.height)
        }
        require(metadata.preparedWidth == expectedPrepared.first && metadata.preparedHeight == expectedPrepared.second) {
            "Las dimensiones preparadas no corresponden al crop y la rotación."
        }
    }
}
