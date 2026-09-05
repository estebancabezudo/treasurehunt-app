package dev.cabezudo.treasurehunt.calibrationboard

import dev.cabezudo.treasurehunt.camera.DiagnosticProcessingMode
import dev.cabezudo.treasurehunt.camera.detectorSelection
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationBoardTest {
    @Test
    fun specificationIsNineBySixAndExpects54Corners() {
        assertEquals(9, CalibrationBoard.INTERNAL_COLUMNS)
        assertEquals(6, CalibrationBoard.INTERNAL_ROWS)
        assertEquals(54, CalibrationBoard.EXPECTED_CORNERS)
        assertEquals(20.0, CalibrationBoard.SQUARE_SIZE_MM, 0.0)
    }

    @Test
    fun regularGridIsAccepted() {
        assertTrue(validator().validate(grid(), 640, 480).valid)
    }

    @Test
    fun wrongCornerCountIsRejected() {
        val result = validator().validate(grid().dropLast(1), 640, 480)
        assertEquals(CalibrationBoardRejectionReason.INCORRECT_CORNER_COUNT, result.rejectionReason)
    }

    @Test
    fun nonFiniteCoordinateIsRejected() {
        val corners = grid().toMutableList().apply { this[10] = RecognitionPoint(Double.NaN, 80.0) }
        assertEquals(
            CalibrationBoardRejectionReason.NON_FINITE_COORDINATES,
            validator().validate(corners, 640, 480).rejectionReason,
        )
    }

    @Test
    fun insufficientAreaIsRejected() {
        assertEquals(
            CalibrationBoardRejectionReason.AREA_TOO_SMALL,
            validator().validate(grid(step = 4.0), 640, 480).rejectionReason,
        )
    }

    @Test
    fun excessiveAreaIsRejected() {
        val custom = CalibrationBoardGeometryValidator(
            CalibrationBoardConfig(maximumAreaFraction = 0.20),
        )
        assertEquals(
            CalibrationBoardRejectionReason.AREA_TOO_LARGE,
            custom.validate(grid(step = 55.0, startX = 20.0, startY = 20.0), 640, 480).rejectionReason,
        )
    }

    @Test
    fun degenerateSeparationIsRejected() {
        val corners = grid().toMutableList().apply { this[1] = this[0] }
        val reason = validator().validate(corners, 640, 480).rejectionReason
        assertTrue(
            reason == CalibrationBoardRejectionReason.INSUFFICIENT_SEPARATION ||
                reason == CalibrationBoardRejectionReason.DEGENERATE_BOARD,
        )
    }

    @Test
    fun rateLimiterUsesMonotonicInterval() {
        val limiter = CalibrationBoardRateLimiter(5.0)
        assertTrue(limiter.shouldAnalyze(1_000_000_000L))
        assertFalse(limiter.shouldAnalyze(1_100_000_000L))
        assertTrue(limiter.shouldAnalyze(1_200_000_000L))
    }

    @Test
    fun processingModesSelectOnlyTheirOwnDetectorFamily() {
        val recognition = DiagnosticProcessingMode.RECOGNITION.detectorSelection()
        val calibration = DiagnosticProcessingMode.CAMERA_CALIBRATION.detectorSelection()
        assertTrue(recognition.runRecognition)
        assertFalse(recognition.runCalibrationBoard)
        assertFalse(calibration.runRecognition)
        assertTrue(calibration.runCalibrationBoard)
    }

    @Test
    fun detectorCloseIsIdempotent() {
        val detector = CalibrationBoardDetector(monotonicNanos = { 0L })
        detector.close()
        detector.close()
        assertEquals(CalibrationBoardDetectionStatus.CLOSED, detector.currentState().status)
    }

    private fun validator() = CalibrationBoardGeometryValidator()

    private fun grid(
        step: Double = 40.0,
        startX: Double = 120.0,
        startY: Double = 90.0,
    ): List<RecognitionPoint> = List(54) { index ->
        RecognitionPoint(
            x = startX + (index % 9) * step,
            y = startY + (index / 9) * step,
        )
    }
}
