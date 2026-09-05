package dev.cabezudo.treasurehunt.aruco

import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArucoDetectionTest {
    private val validCorners = listOf(
        RecognitionPoint(100.0, 100.0),
        RecognitionPoint(220.0, 100.0),
        RecognitionPoint(220.0, 220.0),
        RecognitionPoint(100.0, 220.0),
    )

    @Test
    fun expectedIdIsSelected() {
        val result = ArucoDetectionDecision.decide(listOf(marker(27)))
        assertEquals(ArucoDetectionStatus.DETECTED, result.status)
        assertEquals(27, result.expectedId)
    }

    @Test
    fun unexpectedIdIsReportedButRejected() {
        val result = ArucoDetectionDecision.decide(listOf(marker(12)))
        assertEquals(ArucoDetectionStatus.UNEXPECTED_MARKER, result.status)
        assertEquals(ArucoRejectionReason.UNEXPECTED_IDS_ONLY, result.rejectionReason)
    }

    @Test
    fun expectedIdWinsAmongSeveralIdsWithoutChangingOtherEvidence() {
        val result = ArucoDetectionDecision.decide(listOf(marker(12), marker(27), marker(8)))
        assertEquals(ArucoDetectionStatus.DETECTED, result.status)
        assertEquals(listOf(12, 27, 8), result.foundIds)
    }

    @Test
    fun validGeometryIsAccepted() {
        val result = validator().validate(validCorners, 640, 480)
        assertTrue(result.valid)
        assertTrue(requireNotNull(result.areaFraction) > 0.0)
    }

    @Test
    fun insufficientAreaIsRejected() {
        val corners = listOf(
            RecognitionPoint(10.0, 10.0), RecognitionPoint(14.0, 10.0),
            RecognitionPoint(14.0, 14.0), RecognitionPoint(10.0, 14.0),
        )
        assertEquals(
            ArucoRejectionReason.AREA_TOO_SMALL,
            validator().validate(corners, 640, 480).reason,
        )
    }

    @Test
    fun defaultLimitRejectsDecodedMarkerBelowQuarterPercentOfFrame() {
        val corners = listOf(
            RecognitionPoint(300.0, 220.0), RecognitionPoint(318.0, 220.0),
            RecognitionPoint(318.0, 238.0), RecognitionPoint(300.0, 238.0),
        )
        assertEquals(
            ArucoRejectionReason.AREA_TOO_SMALL,
            ArucoGeometryValidator(ArucoDetectionConfig()).validate(corners, 640, 480).reason,
        )
    }

    @Test
    fun excessiveAreaIsRejected() {
        val corners = listOf(
            RecognitionPoint(0.0, 0.0), RecognitionPoint(639.0, 0.0),
            RecognitionPoint(639.0, 479.0), RecognitionPoint(0.0, 479.0),
        )
        assertEquals(
            ArucoRejectionReason.AREA_TOO_LARGE,
            validator().validate(corners, 640, 480).reason,
        )
    }

    @Test
    fun nonFiniteCoordinatesAreRejected() {
        val corners = validCorners.toMutableList().also {
            it[2] = RecognitionPoint(Double.NaN, 220.0)
        }
        assertEquals(
            ArucoRejectionReason.NON_FINITE_CORNERS,
            validator().validate(corners, 640, 480).reason,
        )
    }

    @Test
    fun degenerateQuadrilateralIsRejected() {
        val corners = listOf(
            RecognitionPoint(100.0, 100.0), RecognitionPoint(220.0, 100.0),
            RecognitionPoint(221.0, 101.0), RecognitionPoint(100.0, 101.0),
        )
        val result = validator().validate(corners, 640, 480)
        assertFalse(result.valid)
        assertTrue(result.reason != null)
    }

    @Test
    fun frequencyLimiterUsesMonotonicIntervals() {
        val limiter = ArucoRateLimiter(100_000_000L)
        assertTrue(limiter.tryAcquire(1_000_000_000L))
        assertFalse(limiter.tryAcquire(1_050_000_000L))
        assertTrue(limiter.tryAcquire(1_100_000_000L))
    }

    @Test
    fun closeIsIdempotentBeforeNativeInitialization() {
        val detector = ArucoMarkerDetector(monotonicNanos = { 0L })
        detector.close()
        detector.close()
        assertEquals(ArucoDetectionStatus.CLOSED, detector.currentState().status)
    }

    private fun validator() = ArucoGeometryValidator(
        ArucoDetectionConfig(
            minimumAreaFraction = 0.01,
            maximumAreaFraction = 0.80,
            minimumSideLengthPixels = 2.0,
        ),
    )

    private fun marker(id: Int) = DetectedMarker(
        id = id,
        corners = validCorners,
        geometryValid = true,
        areaFraction = 0.05,
    )
}
