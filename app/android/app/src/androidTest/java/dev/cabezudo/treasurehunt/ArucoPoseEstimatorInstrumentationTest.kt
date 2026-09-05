package dev.cabezudo.treasurehunt

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cabezudo.treasurehunt.aruco.ArucoDetectionState
import dev.cabezudo.treasurehunt.aruco.ArucoDetectionStatus
import dev.cabezudo.treasurehunt.aruco.pose.ArucoPoseEstimator
import dev.cabezudo.treasurehunt.aruco.pose.ArucoPoseStatus
import dev.cabezudo.treasurehunt.aruco.pose.IppeSquareObjectPoints
import dev.cabezudo.treasurehunt.aruco.pose.PhysicalMarkerSize
import dev.cabezudo.treasurehunt.aruco.pose.PoseCalibrationLoader
import dev.cabezudo.treasurehunt.aruco.pose.PoseCalibrationState
import dev.cabezudo.treasurehunt.aruco.pose.PoseCalibrationStatus
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationFrameCaptureMetadata
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationFrameCoordinateMapper
import dev.cabezudo.treasurehunt.camera.OpenCvRuntime
import dev.cabezudo.treasurehunt.camera.OpenCvStatus
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationResultJson
import dev.cabezudo.treasurehunt.manualcalibration.PHYSICAL_CALIBRATION_RESULT_SHA256
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.geometry.Geometry

@RunWith(AndroidJUnit4::class)
class ArucoPoseEstimatorInstrumentationTest {
    private val intrinsicValues = listOf(
        600.0, 0.0, 320.0,
        0.0, 610.0, 240.0,
        0.0, 0.0, 1.0,
    )
    private val distortionValues = listOf(0.01, -0.005, 0.001, -0.001, 0.0005)

    @Test
    fun packagedPhysicalCalibrationHasExactHashAndReadyIdentity() {
        assertEquals(OpenCvStatus.READY, OpenCvRuntime.initialize().status)
        val state = PoseCalibrationLoader(
            InstrumentationRegistry.getInstrumentation().targetContext.assets,
        ).load()
        assertEquals(PoseCalibrationStatus.READY, state.status)
        assertEquals(PHYSICAL_CALIBRATION_RESULT_SHA256, state.resultSha256)
        assertEquals("0", state.result?.identity?.cameraId)
        assertEquals(640, state.result?.identity?.bufferWidth)
        assertEquals(480, state.result?.identity?.bufferHeight)
    }

    @Test
    fun syntheticKnownPosesRecoverTranslationRotationAndReprojection() {
        assertEquals(OpenCvStatus.READY, OpenCvRuntime.initialize().status)
        val cases = listOf(
            SyntheticCase("frontal casi perpendicular", listOf(3.05, 0.08, -0.03), listOf(0.0, 0.0, 260.0), 0),
            SyntheticCase("lateral", listOf(3.04, 0.10, 0.02), listOf(35.0, -18.0, 420.0), 0),
            SyntheticCase("inclinada", listOf(2.82, 0.18, -0.12), listOf(15.0, 12.0, 480.0), 0),
            SyntheticCase("lejana", listOf(3.03, -0.11, 0.04), listOf(-20.0, 8.0, 800.0), 0),
            SyntheticCase("rotación cuadro 90", listOf(2.95, -0.10, 0.08), listOf(4.0, 6.0, 500.0), 90),
            SyntheticCase("rotación cuadro 180", listOf(2.95, -0.10, 0.08), listOf(4.0, 6.0, 500.0), 180),
            SyntheticCase("rotación cuadro 270", listOf(2.95, -0.10, 0.08), listOf(4.0, 6.0, 500.0), 270),
        )
        cases.forEachIndexed { caseIndex, case ->
            val metadata = metadata(case.frameRotation)
            val canonical = project(case.rvec, case.tvec).mapIndexed { index, point ->
                val noise = if (case.name == "inclinada") {
                    listOf(-0.12, 0.08, 0.10, -0.06)[index]
                } else 0.0
                RecognitionPoint(point.x + noise, point.y - noise * 0.5)
            }
            val prepared = canonical.map {
                CalibrationFrameCoordinateMapper().canonicalToPrepared(it, metadata)
            }
            val estimator = estimator()
            try {
                val state = estimator.estimate(
                    ArucoDetectionState(
                        status = ArucoDetectionStatus.DETECTED,
                        detectorInitialized = true,
                        expectedCorners = prepared,
                    ),
                    metadata,
                )
                assertEquals(
                    "${case.name}: ${state.rejectionReason}; ${state.error}",
                    ArucoPoseStatus.VALID,
                    state.status,
                )
                val result = requireNotNull(state.result)
                val translationError = sqrt(result.translationMillimeters.zip(case.tvec).sumOf {
                    (estimated, expected) -> (estimated - expected) * (estimated - expected)
                })
                assertTrue("${case.name}: error traslación $translationError mm", translationError < 5.0)
                val rotationError = rotationErrorDegrees(result.rotationMatrix, rotationMatrix(case.rvec))
                assertTrue("${case.name}: error rotación $rotationError°", rotationError < 5.0)
                assertTrue("${case.name}: RMS ${result.reprojectionRmsPixels}", result.reprojectionRmsPixels < 1.0)
                assertEquals(4, result.projectedAxesPrepared.size)
                assertTrue(result.calculationDurationNanos >= 0)
                assertTrue(result.tzMillimeters > 0.0)
            } finally {
                estimator.close()
            }
            assertTrue("case $caseIndex", true)
        }
    }

    @Test
    fun incompatibleFrameAndUnconfirmedOrDeformedSizeNeverProducePose() {
        assertEquals(OpenCvStatus.READY, OpenCvRuntime.initialize().status)
        val corners = project(listOf(PI, 0.0, 0.0), listOf(0.0, 0.0, 400.0))
        val detected = ArucoDetectionState(
            status = ArucoDetectionStatus.DETECTED,
            detectorInitialized = true,
            expectedCorners = corners,
        )
        val missing = ArucoPoseEstimator(syntheticCalibration(), monotonicNanos = SystemClock::elapsedRealtimeNanos)
        assertEquals(ArucoPoseStatus.PHYSICAL_SIZE_NOT_CONFIRMED, missing.estimate(detected, metadata(0)).status)
        missing.close()

        val deformed = ArucoPoseEstimator(syntheticCalibration(), monotonicNanos = SystemClock::elapsedRealtimeNanos)
        deformed.confirmPhysicalSize(PhysicalMarkerSize(40.0, 42.0, true))
        assertEquals(ArucoPoseStatus.PHYSICAL_MARKER_DEFORMED, deformed.estimate(detected, metadata(0)).status)
        deformed.close()

        val incompatible = estimator()
        assertEquals(
            ArucoPoseStatus.CALIBRATION_INCOMPATIBLE,
            incompatible.estimate(detected, metadata(0).copy(cameraId = "1")).status,
        )
        incompatible.close()
    }

    private fun estimator() = ArucoPoseEstimator(
        baseCalibration = syntheticCalibration(),
        monotonicNanos = SystemClock::elapsedRealtimeNanos,
    ).also { it.confirmPhysicalSize(PhysicalMarkerSize(40.0, 40.0, true)) }

    private fun syntheticCalibration(): PoseCalibrationState {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packaged = context.assets.open(PoseCalibrationLoader.ASSET_NAME).bufferedReader().use {
            ManualCalibrationResultJson().decode(it.readText())
        }
        return PoseCalibrationState(
            status = PoseCalibrationStatus.READY,
            result = packaged.copy(
                primary = packaged.primary.copy(
                    intrinsicMatrix = intrinsicValues,
                    distortionCoefficients = distortionValues,
                ),
            ),
            resultSha256 = PHYSICAL_CALIBRATION_RESULT_SHA256,
            cause = null,
        )
    }

    private fun project(rvecValues: List<Double>, tvecValues: List<Double>): List<RecognitionPoint> {
        val objectPoints = MatOfPoint3f()
        val rvec = Mat(3, 1, CvType.CV_64FC1)
        val tvec = Mat(3, 1, CvType.CV_64FC1)
        val intrinsic = Mat(3, 3, CvType.CV_64FC1)
        val distortion = MatOfDouble()
        val projected = MatOfPoint2f()
        try {
            objectPoints.fromArray(*IppeSquareObjectPoints.create(40.0).map {
                Point3(it.x, it.y, it.z)
            }.toTypedArray())
            rvec.put(0, 0, *rvecValues.toDoubleArray())
            tvec.put(0, 0, *tvecValues.toDoubleArray())
            intrinsic.put(0, 0, *intrinsicValues.toDoubleArray())
            distortion.fromArray(*distortionValues.toDoubleArray())
            Geometry.projectPoints(objectPoints, rvec, tvec, intrinsic, distortion, projected)
            return projected.toArray().map { RecognitionPoint(it.x, it.y) }
        } finally {
            objectPoints.release(); rvec.release(); tvec.release(); intrinsic.release()
            distortion.release(); projected.release()
        }
    }

    private fun rotationMatrix(rvecValues: List<Double>): List<Double> {
        val rvec = Mat(3, 1, CvType.CV_64FC1)
        val rotation = Mat()
        try {
            rvec.put(0, 0, *rvecValues.toDoubleArray())
            Geometry.Rodrigues(rvec, rotation)
            val values = DoubleArray(9)
            rotation.get(0, 0, values)
            return values.toList()
        } finally {
            rvec.release(); rotation.release()
        }
    }

    private fun rotationErrorDegrees(estimated: List<Double>, expected: List<Double>): Double {
        val trace = (0..2).sumOf { row ->
            (0..2).sumOf { column -> estimated[row * 3 + column] * expected[row * 3 + column] }
        }
        return acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    private fun metadata(rotation: Int): CalibrationFrameCaptureMetadata {
        val swapped = rotation == 90 || rotation == 270
        return CalibrationFrameCaptureMetadata(
            cameraId = "0",
            bufferWidth = 640,
            bufferHeight = 480,
            cropRect = CoordinateRect(0, 0, 640, 480),
            rotationDegrees = rotation,
            preparedWidth = if (swapped) 480 else 640,
            preparedHeight = if (swapped) 640 else 480,
            sensorToBufferTransform = Matrix3.IDENTITY,
        )
    }

    private data class SyntheticCase(
        val name: String,
        val rvec: List<Double>,
        val tvec: List<Double>,
        val frameRotation: Int,
    )
}
