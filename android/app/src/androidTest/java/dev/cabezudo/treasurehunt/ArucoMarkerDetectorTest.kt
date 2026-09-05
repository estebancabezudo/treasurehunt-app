package dev.cabezudo.treasurehunt

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cabezudo.treasurehunt.aruco.ArucoDetectionStatus
import dev.cabezudo.treasurehunt.aruco.ArucoMarkerDetector
import dev.cabezudo.treasurehunt.camera.OpenCvRuntime
import dev.cabezudo.treasurehunt.camera.OpenCvStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class ArucoMarkerDetectorTest {
    @Test
    fun exactMarker27IsAccepted() = withGray(R.drawable.aruco_marker_27) { assertDetected(it) }

    @Test
    fun composedTargetAcceptsMarker27() =
        withGray(R.drawable.guardian_door_target_with_marker) { assertDetected(it) }

    @Test
    fun marker27Rotated90IsAccepted() = assertRotation(Core.ROTATE_90_CLOCKWISE)

    @Test
    fun marker27Rotated180IsAccepted() = assertRotation(Core.ROTATE_180)

    @Test
    fun marker27Rotated270IsAccepted() = assertRotation(Core.ROTATE_90_COUNTERCLOCKWISE)

    @Test
    fun scaledMarker27IsAccepted() = withGray(R.drawable.aruco_marker_27) { source ->
        val scaled = Mat()
        val canvas = Mat(480, 480, CvType.CV_8UC1, Scalar(255.0))
        try {
            Imgproc.resize(source, scaled, Size(300.0, 300.0), 0.0, 0.0, Imgproc.INTER_NEAREST)
            val region = canvas.submat(90, 390, 90, 390)
            try {
                scaled.copyTo(region)
            } finally {
                region.release()
            }
            assertDetected(canvas)
        } finally {
            scaled.release()
            canvas.release()
        }
    }

    @Test
    fun moderatePerspectiveIsAccepted() = withGray(R.drawable.aruco_marker_27) { source ->
        warp(
            source,
            listOf(point(35, 55), point(450, 25), point(420, 455), point(65, 430)),
        ).useMat(::assertDetected)
    }

    @Test
    fun reducedBrightnessIsAccepted() = withGray(R.drawable.aruco_marker_27) { source ->
        val dark = Mat()
        try {
            source.convertTo(dark, CvType.CV_8UC1, 0.45, 0.0)
            assertDetected(dark)
        } finally {
            dark.release()
        }
    }

    @Test
    fun moderateBlurIsAccepted() = withGray(R.drawable.aruco_marker_27) { source ->
        val blurred = Mat()
        try {
            Imgproc.GaussianBlur(source, blurred, Size(9.0, 9.0), 2.0)
            assertDetected(blurred)
        } finally {
            blurred.release()
        }
    }

    @Test
    fun partialOcclusionIsRejected() = withGray(R.drawable.aruco_marker_27) { source ->
        val occluded = source.clone()
        try {
            Imgproc.rectangle(
                occluded,
                Point(195.0, 45.0),
                Point(285.0, 435.0),
                Scalar(255.0),
                Imgproc.FILLED,
            )
            assertRejected(occluded)
        } finally {
            occluded.release()
        }
    }

    @Test
    fun differentIdIsReportedAndNotAccepted() =
        withGray(R.drawable.guardian_door_target_with_marker_12) { frame ->
            val result = analyze(frame)
            assertEquals(ArucoDetectionStatus.UNEXPECTED_MARKER, result.status)
            assertTrue(12 in result.foundIds)
            assertTrue(27 !in result.foundIds)
        }

    @Test
    fun imageWithoutMarkerIsRejected() =
        withGray(R.drawable.guardian_door_target) { assertRejected(it) }

    @Test
    fun ordinarySquarePatternIsRejected() {
        val frame = Mat(480, 640, CvType.CV_8UC1, Scalar(255.0))
        try {
            Imgproc.rectangle(frame, point(190, 110), point(450, 370), Scalar(0.0), 18)
            Imgproc.line(frame, point(210, 130), point(430, 350), Scalar(0.0), 20)
            Imgproc.line(frame, point(430, 130), point(210, 350), Scalar(0.0), 20)
            assertRejected(frame)
        } finally {
            frame.release()
        }
    }

    @Test
    fun markerThatIsTooSmallIsNotAccepted() = withGray(R.drawable.aruco_marker_27) { source ->
        val tiny = Mat()
        val canvas = Mat(480, 640, CvType.CV_8UC1, Scalar(255.0))
        try {
            Imgproc.resize(source, tiny, Size(24.0, 24.0), 0.0, 0.0, Imgproc.INTER_NEAREST)
            val region = canvas.submat(228, 252, 308, 332)
            try {
                tiny.copyTo(region)
            } finally {
                region.release()
            }
            assertRejected(canvas)
        } finally {
            tiny.release()
            canvas.release()
        }
    }

    private fun assertRotation(rotation: Int) = withGray(R.drawable.aruco_marker_27) { source ->
        val rotated = Mat()
        try {
            Core.rotate(source, rotated, rotation)
            assertDetected(rotated)
        } finally {
            rotated.release()
        }
    }

    private fun assertDetected(frame: Mat) {
        val result = analyze(frame)
        assertEquals("Resultado inesperado: $result", ArucoDetectionStatus.DETECTED, result.status)
        assertTrue(27 in result.foundIds)
        assertEquals(4, result.expectedCorners.size)
        assertTrue(requireNotNull(result.expectedAreaFraction) > 0.0)
    }

    private fun assertRejected(frame: Mat) {
        val result = analyze(frame)
        assertNotEquals(ArucoDetectionStatus.DETECTED, result.status)
        assertNotEquals(ArucoDetectionStatus.ERROR, result.status)
        assertTrue(result.rejectionReason != null)
    }

    private fun analyze(frame: Mat) =
        ArucoMarkerDetector(monotonicNanos = { 1_000_000_000L }).use { detector ->
            detector.analyzeIfDue(frame, 0L)
        }

    private fun withGray(@DrawableRes resource: Int, block: (Mat) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(BitmapFactory.decodeResource(context.resources, resource))
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            block(gray)
        } finally {
            gray.release()
            rgba.release()
            bitmap.recycle()
        }
    }

    private fun warp(source: Mat, destination: List<Point>): Mat {
        val sourcePoints = MatOfPoint2f(
            point(0, 0), point(source.cols(), 0),
            point(source.cols(), source.rows()), point(0, source.rows()),
        )
        val destinationPoints = MatOfPoint2f(*destination.toTypedArray())
        val transform = Geometry.getPerspectiveTransform(sourcePoints, destinationPoints)
        val output = Mat()
        try {
            Imgproc.warpPerspective(
                source,
                output,
                transform,
                Size(source.cols().toDouble(), source.rows().toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(255.0),
            )
            return output
        } catch (error: Throwable) {
            output.release()
            throw error
        } finally {
            sourcePoints.release()
            destinationPoints.release()
            transform.release()
        }
    }

    private fun point(x: Int, y: Int) = Point(x.toDouble(), y.toDouble())

    private inline fun Mat.useMat(block: (Mat) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeOpenCv() {
            assertEquals(OpenCvStatus.READY, OpenCvRuntime.initialize().status)
        }
    }
}
