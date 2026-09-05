package dev.cabezudo.treasurehunt

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetector
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationCaptureRejectionReason
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationFrameCaptureMetadata
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationSampleCollector
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationSampleCollectorConfig
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationSampleRepository
import dev.cabezudo.treasurehunt.camera.DiagnosticProcessingMode
import dev.cabezudo.treasurehunt.camera.OpenCvRuntime
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class CalibrationSampleInstrumentationTest {
    @Test
    fun exactBoardCapturesPersistsRecoversAndRejectsDuplicate() = withExactBoard { board ->
        var now = 1_000_000_000L
        val repository = repository()
        val collector = collector(repository, { now })
        collector.confirmPhysicalMeasurement(18.20, 18.142857)
        collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        val first = collector.processCurrentFrame(
            DiagnosticProcessingMode.CAMERA_CALIBRATION,
            detect(board, now),
            metadata(board),
            now,
        )
        assertEquals(1, first.acceptedSamples)
        assertTrue(repository.file.exists())
        assertTrue(repository.readJson()!!.contains("\"canonicalCorners\""))
        assertTrue(!repository.readJson()!!.contains("bitmap", ignoreCase = true))

        val recovered = collector(repository, { now }).currentState()
        assertEquals(1, recovered.acceptedSamples)
        assertEquals(first.datasetIdentity, recovered.datasetIdentity)

        now += 1_000_000_000L
        collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
        val duplicate = collector.processCurrentFrame(
            DiagnosticProcessingMode.CAMERA_CALIBRATION,
            detect(board, now),
            metadata(board),
            now,
        )
        assertEquals(1, duplicate.acceptedSamples)
        assertEquals(CalibrationCaptureRejectionReason.DUPLICATE, duplicate.lastRejection?.reason)
    }

    @Test
    fun imageWithoutBoardExpiresWithConcreteRejection() {
        OpenCvRuntime.initialize()
        val blank = Mat(480, 640, CvType.CV_8UC1, Scalar(127.0))
        try {
            var now = 10_000L
            val collector = collector(repository(), { now }, timeoutNanos = 100L)
            collector.confirmPhysicalMeasurement(18.20, 18.142857)
            collector.requestCapture(DiagnosticProcessingMode.CAMERA_CALIBRATION)
            collector.processCurrentFrame(
                DiagnosticProcessingMode.CAMERA_CALIBRATION,
                detect(blank, now),
                metadata(blank),
                now,
            )
            now += 101L
            val rejected = collector.processCurrentFrame(
                DiagnosticProcessingMode.CAMERA_CALIBRATION,
                detect(blank, now),
                metadata(blank),
                now,
            )
            assertEquals(CalibrationCaptureRejectionReason.BOARD_NOT_DETECTED, rejected.lastRejection?.reason)
            assertNull(rejected.pendingRequest)
        } finally {
            blank.release()
        }
    }

    @Test
    fun captureIsSuspendedOutsideCalibrationMode() {
        val collector = collector(repository(), { 1_000L })
        collector.confirmPhysicalMeasurement(18.20, 18.142857)
        val state = collector.requestCapture(DiagnosticProcessingMode.RECOGNITION)
        assertEquals(CalibrationCaptureRejectionReason.OUTSIDE_CALIBRATION_MODE, state.lastRejection?.reason)
        assertNotNull(state.lastRejection?.detail)
    }

    private fun detect(frame: Mat, now: Long) =
        CalibrationBoardDetector(monotonicNanos = { now }).use { detector ->
            detector.analyzeIfDue(frame, now)
        }

    private fun metadata(frame: Mat) = CalibrationFrameCaptureMetadata(
        cameraId = "instrumented-camera-0",
        bufferWidth = frame.cols(),
        bufferHeight = frame.rows(),
        cropRect = CoordinateRect(0, 0, frame.cols(), frame.rows()),
        rotationDegrees = 0,
        preparedWidth = frame.cols(),
        preparedHeight = frame.rows(),
        sensorToBufferTransform = Matrix3.IDENTITY,
    )

    private fun repository(): CalibrationSampleRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return CalibrationSampleRepository(
            File(context.cacheDir, "calibration-sample-test-${System.nanoTime()}"),
        )
    }

    private fun collector(
        repository: CalibrationSampleRepository,
        now: () -> Long,
        timeoutNanos: Long = 5_000_000_000L,
    ) = CalibrationSampleCollector(
        repository = repository,
        config = CalibrationSampleCollectorConfig(timeoutNanos),
        monotonicNanos = now,
        wallClockMillis = { 1_700_000_000_000L },
        onStateChanged = {},
    )

    private fun withExactBoard(block: (Mat) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(
            BitmapFactory.decodeResource(context.resources, R.drawable.calibration_chessboard),
        )
        val rgba = Mat()
        val gray = Mat()
        val resized = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, resized, Size(891.0, 630.0), 0.0, 0.0, Imgproc.INTER_AREA)
            block(resized)
        } finally {
            resized.release()
            gray.release()
            rgba.release()
            bitmap.recycle()
        }
    }
}
