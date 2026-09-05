package dev.cabezudo.treasurehunt

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetectionStatus
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetector
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
class CalibrationBoardDetectorTest {
    @Test fun exactBoardIsDetected() = withBoard(::assertDetected)
    @Test fun rotated90IsDetected() = assertRotation(Core.ROTATE_90_CLOCKWISE)
    @Test fun rotated180IsDetected() = assertRotation(Core.ROTATE_180)
    @Test fun rotated270IsDetected() = assertRotation(Core.ROTATE_90_COUNTERCLOCKWISE)

    @Test
    fun scaledBoardIsDetected() = withBoard { source ->
        val scaled = Mat()
        try {
            Imgproc.resize(source, scaled, Size(source.cols() * 0.65, source.rows() * 0.65))
            assertDetected(scaled)
        } finally {
            scaled.release()
        }
    }

    @Test
    fun moderatePerspectiveIsDetected() = withBoard { source ->
        warp(
            source,
            listOf(
                Point(80.0, 35.0),
                Point(source.cols() - 45.0, 65.0),
                Point(source.cols() - 80.0, source.rows() - 40.0),
                Point(55.0, source.rows() - 65.0),
            ),
        ).useMat(::assertDetected)
    }

    @Test
    fun reducedBrightnessIsDetected() = withBoard { source ->
        val dark = Mat()
        try {
            source.convertTo(dark, CvType.CV_8UC1, 0.40, 0.0)
            assertDetected(dark)
        } finally {
            dark.release()
        }
    }

    @Test
    fun moderateBlurIsDetected() = withBoard { source ->
        val blurred = Mat()
        try {
            Imgproc.GaussianBlur(source, blurred, Size(7.0, 7.0), 1.5)
            assertDetected(blurred)
        } finally {
            blurred.release()
        }
    }

    @Test
    fun partialOcclusionIsRejected() = withBoard { source ->
        val occluded = source.clone()
        try {
            Imgproc.rectangle(
                occluded,
                Point(source.cols() * 0.44, source.rows() * 0.20),
                Point(source.cols() * 0.58, source.rows() * 0.80),
                Scalar(255.0),
                Imgproc.FILLED,
            )
            assertRejected(occluded)
        } finally {
            occluded.release()
        }
    }

    @Test
    fun incompleteBoardIsRejected() = withBoard { source ->
        val incomplete = source.clone()
        try {
            Imgproc.rectangle(
                incomplete,
                Point(0.0, 0.0),
                Point(source.cols() * 0.38, source.rows().toDouble()),
                Scalar(255.0),
                Imgproc.FILLED,
            )
            assertRejected(incomplete)
        } finally {
            incomplete.release()
        }
    }

    @Test
    fun differentGridDimensionsAreRejected() {
        val grid = makeGrid(columns = 9, rows = 7, square = 45)
        try {
            assertRejected(grid)
        } finally {
            grid.release()
        }
    }

    @Test
    fun uniformImageIsRejected() {
        val uniform = Mat(480, 640, CvType.CV_8UC1, Scalar(127.0))
        try {
            assertRejected(uniform)
        } finally {
            uniform.release()
        }
    }

    @Test
    fun imageWithoutBoardIsRejected() {
        val image = Mat(480, 640, CvType.CV_8UC1, Scalar(255.0))
        try {
            Imgproc.circle(image, Point(320.0, 240.0), 120, Scalar(0.0), 12)
            assertRejected(image)
        } finally {
            image.release()
        }
    }

    @Test
    fun boardThatIsTooSmallIsRejected() = withBoard { source ->
        val tiny = Mat()
        val canvas = Mat(480, 640, CvType.CV_8UC1, Scalar(255.0))
        try {
            Imgproc.resize(source, tiny, Size(100.0, 71.0))
            val region = canvas.submat(204, 275, 270, 370)
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

    private fun assertRotation(code: Int) = withBoard { source ->
        val rotated = Mat()
        try {
            Core.rotate(source, rotated, code)
            assertDetected(rotated)
        } finally {
            rotated.release()
        }
    }

    private fun assertDetected(frame: Mat) {
        val result = analyze(frame)
        assertEquals("Resultado inesperado: $result", CalibrationBoardDetectionStatus.DETECTED, result.status)
        assertEquals(54, result.corners.size)
        assertTrue(requireNotNull(result.areaFraction) > 0.0)
    }

    private fun assertRejected(frame: Mat) {
        val result = analyze(frame)
        assertNotEquals(CalibrationBoardDetectionStatus.DETECTED, result.status)
        assertNotEquals(CalibrationBoardDetectionStatus.ERROR, result.status)
        assertTrue(result.rejectionReason != null)
    }

    private fun analyze(frame: Mat) =
        CalibrationBoardDetector(monotonicNanos = { 1_000_000_000L }).use { detector ->
            detector.analyzeIfDue(frame, 0L)
        }

    private fun withBoard(block: (Mat) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.calibration_chessboard))
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

    private fun makeGrid(columns: Int, rows: Int, square: Int): Mat {
        val width = (columns + 2) * square
        val height = (rows + 2) * square
        val image = Mat(height, width, CvType.CV_8UC1, Scalar(255.0))
        for (row in 0 until rows) for (column in 0 until columns) {
            if ((row + column) % 2 == 0) {
                Imgproc.rectangle(
                    image,
                    Point((column + 1.0) * square, (row + 1.0) * square),
                    Point((column + 2.0) * square, (row + 2.0) * square),
                    Scalar(0.0),
                    Imgproc.FILLED,
                )
            }
        }
        return image
    }

    private fun warp(source: Mat, destination: List<Point>): Mat {
        val sourcePoints = MatOfPoint2f(
            Point(0.0, 0.0), Point(source.cols().toDouble(), 0.0),
            Point(source.cols().toDouble(), source.rows().toDouble()), Point(0.0, source.rows().toDouble()),
        )
        val destinationPoints = MatOfPoint2f(*destination.toTypedArray())
        val transform = Geometry.getPerspectiveTransform(sourcePoints, destinationPoints)
        val output = Mat()
        try {
            Imgproc.warpPerspective(
                source, output, transform,
                Size(source.cols().toDouble(), source.rows().toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0),
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

    private inline fun Mat.useMat(block: (Mat) -> Unit) {
        try { block(this) } finally { release() }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeOpenCv() {
            assertEquals(OpenCvStatus.READY, OpenCvRuntime.initialize().status)
        }
    }
}
