package dev.cabezudo.treasurehunt.calibrationdataset

import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetectionState
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetectionStatus
import dev.cabezudo.treasurehunt.camera.DiagnosticProcessingMode
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSampleCollectorTest {
    @Test
    fun emptyDatasetReportsMissingCoverageInsteadOfCompletion() {
        val state = fixture().collector.currentState()
        assertFalse(state.coverage.ready)
        assertTrue(state.coverage.recommendations.any { "15" in it })
        assertTrue(state.coverage.recommendations.any { "cuadrícula" in it })
    }

    @Test
    fun requestIsRejectedOutsideCalibrationMode() {
        val collector = fixture().collector
        val state = collector.requestCapture(DiagnosticProcessingMode.RECOGNITION)
        assertEquals(CalibrationCaptureRejectionReason.OUTSIDE_CALIBRATION_MODE, state.lastRejection?.reason)
        assertNull(state.pendingRequest)
    }

    @Test
    fun captureRequiresConfirmedPhysicalMeasurement() {
        val collector = fixture().collector
        val state = collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        assertEquals(CalibrationCaptureRejectionReason.PHYSICAL_MEASUREMENT_NOT_CONFIRMED, state.lastRejection?.reason)
    }

    @Test
    fun validCurrentFrameIsPersistedAndRecovered() {
        val fixture = fixture()
        fixture.collector.confirmPhysicalMeasurement(18.20, 18.142857)
        fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        val accepted = fixture.collector.processCurrentFrame(
            DiagnosticProcessingMode.CAMERA_CALIBRATION,
            detected(fixture.now),
            metadata(),
            fixture.now,
        )
        assertEquals(1, accepted.acceptedSamples)
        assertTrue(fixture.repository.file.exists())
        val recovered = fixture().copy(repository = fixture.repository).collector.currentState()
        assertEquals(1, recovered.acceptedSamples)
        assertEquals(18.20, recovered.confirmedHorizontalSquareSizeMm!!, 0.0)
        assertEquals(18.142857, recovered.confirmedVerticalSquareSizeMm!!, 0.0)
    }

    @Test
    fun staleDetectionDoesNotSatisfyPendingRequest() {
        val fixture = fixture()
        fixture.collector.confirmPhysicalMeasurement(18.20, 18.142857)
        fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        val state = fixture.collector.processCurrentFrame(
            DiagnosticProcessingMode.CAMERA_CALIBRATION,
            detected(fixture.now - 1),
            metadata(),
            fixture.now,
        )
        assertNotNull(state.pendingRequest)
        assertEquals(0, state.acceptedSamples)
    }

    @Test
    fun duplicateIsRejected() {
        val fixture = fixture()
        fixture.collector.confirmPhysicalMeasurement(18.20, 18.142857)
        repeat(2) {
            fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
            fixture.collector.processCurrentFrame(DiagnosticProcessingMode.CAMERA_CALIBRATION, detected(fixture.now), metadata(), fixture.now)
        }
        val state = fixture.collector.currentState()
        assertEquals(1, state.acceptedSamples)
        assertEquals(CalibrationCaptureRejectionReason.DUPLICATE, state.lastRejection?.reason)
    }

    @Test
    fun incompatibleIdentityDoesNotJoinExistingDataset() {
        val fixture = fixture()
        fixture.collector.confirmPhysicalMeasurement(18.20, 18.142857)
        fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        fixture.collector.processCurrentFrame(DiagnosticProcessingMode.CAMERA_CALIBRATION, detected(fixture.now), metadata(), fixture.now)
        fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        val incompatible = metadata().copy(cameraId = "1")
        val state = fixture.collector.processCurrentFrame(DiagnosticProcessingMode.CAMERA_CALIBRATION, detected(fixture.now), incompatible, fixture.now)
        assertEquals(CalibrationCaptureRejectionReason.DATASET_IDENTITY_MISMATCH, state.lastRejection?.reason)
        assertEquals(1, state.acceptedSamples)
    }

    @Test
    fun existingDatasetRejectsChangingEitherPhysicalDimension() {
        val fixture = fixture()
        fixture.collector.confirmPhysicalMeasurement(18.20, 18.142857)
        fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        fixture.collector.processCurrentFrame(
            DiagnosticProcessingMode.CAMERA_CALIBRATION,
            detected(fixture.now),
            metadata(),
            fixture.now,
        )

        val horizontalChange = fixture.collector.confirmPhysicalMeasurement(18.19, 18.142857)
        assertEquals(CalibrationCaptureRejectionReason.DATASET_IDENTITY_MISMATCH, horizontalChange.lastRejection?.reason)
        assertEquals(18.20, horizontalChange.confirmedHorizontalSquareSizeMm!!, 0.0)

        val verticalChange = fixture.collector.confirmPhysicalMeasurement(18.20, 18.20)
        assertEquals(CalibrationCaptureRejectionReason.DATASET_IDENTITY_MISMATCH, verticalChange.lastRejection?.reason)
        assertEquals(18.142857, verticalChange.confirmedVerticalSquareSizeMm!!, 0.0)
    }

    @Test
    fun timeoutAfterNoAnalyzedFrameIsExplicit() {
        val fixture = fixture(now = 100L, timeout = 50L)
        fixture.collector.confirmPhysicalMeasurement(18.20, 18.142857)
        fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        fixture.now = 151L
        val state = fixture.collector.processCurrentFrame(
            DiagnosticProcessingMode.CAMERA_CALIBRATION,
            CalibrationBoardDetectionState(),
            metadata(),
            fixture.now,
        )
        assertEquals(CalibrationCaptureRejectionReason.DETECTION_EXPIRED, state.lastRejection?.reason)
        assertNull(state.pendingRequest)
    }

    @Test
    fun scheduledTimeoutWorksWithoutAnyFollowingCameraFrame() {
        var now = 100L
        val scheduler = FakeTimeoutScheduler()
        val repository = CalibrationSampleRepository(Files.createTempDirectory("collector-timeout").toFile())
        val collector = CalibrationSampleCollector(
            repository = repository,
            config = CalibrationSampleCollectorConfig(50L),
            monotonicNanos = { now },
            wallClockMillis = { 1_700_000_000_000L },
            onStateChanged = {},
            timeoutScheduler = scheduler,
        )
        collector.confirmPhysicalMeasurement(18.20, 18.142857)
        collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        assertEquals(50L, scheduler.delayNanos)
        now = 150L
        scheduler.runPending()
        assertNull(collector.currentState().pendingRequest)
        assertEquals(
            CalibrationCaptureRejectionReason.DETECTION_EXPIRED,
            collector.currentState().lastRejection?.reason,
        )
        collector.close()
        assertTrue(scheduler.closed)
    }

    @Test
    fun closeCancelsPendingAndIsIdempotent() {
        val fixture = fixture()
        fixture.collector.confirmPhysicalMeasurement(18.20, 18.142857)
        fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        fixture.collector.close()
        fixture.collector.close()
        assertTrue(fixture.collector.currentState().closed)
        assertNull(fixture.collector.currentState().pendingRequest)
        assertEquals(CalibrationCaptureRejectionReason.CLOSED, fixture.collector.currentState().lastRejection?.reason)
    }

    @Test
    fun resetPreservesMeasurementButRemovesDatasetOnlyAfterExplicitCall() {
        val fixture = fixture()
        fixture.collector.confirmPhysicalMeasurement(18.20, 18.142857)
        fixture.collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        fixture.collector.processCurrentFrame(DiagnosticProcessingMode.CAMERA_CALIBRATION, detected(fixture.now), metadata(), fixture.now)
        assertTrue(fixture.repository.file.exists())
        val reset = fixture.collector.resetDataset()
        assertFalse(fixture.repository.file.exists())
        assertEquals(18.20, reset.confirmedHorizontalSquareSizeMm!!, 0.0)
        assertEquals(18.142857, reset.confirmedVerticalSquareSizeMm!!, 0.0)
        assertEquals(0, reset.acceptedSamples)
    }

    private data class Fixture(
        val repository: CalibrationSampleRepository,
        var now: Long,
        val timeout: Long,
    ) {
        val collector = CalibrationSampleCollector(
            repository = repository,
            config = CalibrationSampleCollectorConfig(timeout),
            monotonicNanos = { now },
            wallClockMillis = { 1_700_000_000_000 + now },
            onStateChanged = {},
        )

        fun copy(repository: CalibrationSampleRepository = this.repository): Fixture =
            Fixture(repository, now, timeout)
    }

    private fun fixture(now: Long = 1_000L, timeout: Long = 5_000L) = Fixture(
        CalibrationSampleRepository(Files.createTempDirectory("collector").toFile()),
        now,
        timeout,
    )

    private fun detected(now: Long) = CalibrationBoardDetectionState(
        status = CalibrationBoardDetectionStatus.DETECTED,
        corners = grid(),
        areaFraction = 0.18,
        analyzedThisFrame = true,
        analyzedAtNanos = now,
    )

    private fun metadata() = CalibrationFrameCaptureMetadata(
        cameraId = "0",
        bufferWidth = 640,
        bufferHeight = 480,
        cropRect = CoordinateRect(0, 0, 640, 480),
        rotationDegrees = 0,
        preparedWidth = 640,
        preparedHeight = 480,
        sensorToBufferTransform = Matrix3.IDENTITY,
    )

    private fun grid() = List(54) { index ->
        RecognitionPoint(120.0 + (index % 9) * 40.0, 90.0 + (index / 9) * 40.0)
    }

    private class FakeTimeoutScheduler : CalibrationRequestTimeoutScheduler {
        var delayNanos: Long? = null
        var closed = false
        private var action: (() -> Unit)? = null

        override fun schedule(delayNanos: Long, action: () -> Unit): CalibrationTimeoutTask {
            this.delayNanos = delayNanos
            this.action = action
            return CalibrationTimeoutTask { this.action = null }
        }

        fun runPending() {
            val pending = action
            action = null
            pending?.invoke()
        }

        override fun close() {
            closed = true
            action = null
        }
    }
}
