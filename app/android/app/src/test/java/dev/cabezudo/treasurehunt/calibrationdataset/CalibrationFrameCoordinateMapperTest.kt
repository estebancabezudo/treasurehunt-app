package dev.cabezudo.treasurehunt.calibrationdataset

import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationFrameCoordinateMapperTest {
    private val mapper = CalibrationFrameCoordinateMapper()

    @Test fun inverseRotation0() = assertInverse(0, RecognitionPoint(23.25, 41.5))
    @Test fun inverseRotation90() = assertInverse(90, RecognitionPoint(23.25, 41.5))
    @Test fun inverseRotation180() = assertInverse(180, RecognitionPoint(23.25, 41.5))
    @Test fun inverseRotation270() = assertInverse(270, RecognitionPoint(23.25, 41.5))

    @Test
    fun cropOffsetIsAddedAfterInverseRotation() {
        val metadata = metadata(rotation = 0, crop = CoordinateRect(50, 30, 250, 130))
        assertPoint(RecognitionPoint(62.5, 46.25), mapper.preparedToCanonical(RecognitionPoint(12.5, 16.25), metadata))
    }

    @Test
    fun roundTripIsSubpixelForEveryRotation() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val metadata = metadata(rotation)
            val prepared = RecognitionPoint(metadata.preparedWidth * 0.37, metadata.preparedHeight * 0.61)
            val canonical = mapper.preparedToCanonical(prepared, metadata)
            assertPoint(prepared, mapper.canonicalToPrepared(canonical, metadata), 1e-9)
        }
    }

    @Test
    fun portraitAndLandscapeRepresentTheSameCanonicalCorners() {
        val landscape = metadata(0)
        val portrait = metadata(90)
        val canonical = grid()
        val landscapePrepared = canonical.map { mapper.canonicalToPrepared(it, landscape) }
        val portraitPrepared = canonical.map { mapper.canonicalToPrepared(it, portrait) }
        assertPoints(
            mapper.mapAndValidate(landscapePrepared, landscape),
            mapper.mapAndValidate(portraitPrepared, portrait),
        )
    }

    private fun assertInverse(rotation: Int, canonical: RecognitionPoint) {
        val metadata = metadata(rotation)
        val prepared = mapper.canonicalToPrepared(canonical, metadata)
        assertPoint(canonical, mapper.preparedToCanonical(prepared, metadata), 1e-9)
    }

    private fun metadata(
        rotation: Int,
        crop: CoordinateRect = CoordinateRect(0, 0, 200, 100),
    ): CalibrationFrameCaptureMetadata {
        val rotated = rotation == 90 || rotation == 270
        return CalibrationFrameCaptureMetadata(
            cameraId = "0",
            bufferWidth = 300,
            bufferHeight = 200,
            cropRect = crop,
            rotationDegrees = rotation,
            preparedWidth = if (rotated) crop.height else crop.width,
            preparedHeight = if (rotated) crop.width else crop.height,
            sensorToBufferTransform = Matrix3.IDENTITY,
        )
    }

    private fun grid() = List(54) { index ->
        RecognitionPoint(20.0 + (index % 9) * 14.0, 12.0 + (index / 9) * 13.0)
    }

    private fun assertPoints(expected: List<RecognitionPoint>, actual: List<RecognitionPoint>) {
        expected.zip(actual).forEach { (first, second) -> assertPoint(first, second) }
    }

    private fun assertPoint(expected: RecognitionPoint, actual: RecognitionPoint, delta: Double = 1e-6) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
    }
}
