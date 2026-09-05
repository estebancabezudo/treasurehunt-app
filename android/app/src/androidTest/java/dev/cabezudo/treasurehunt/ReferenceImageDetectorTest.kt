package dev.cabezudo.treasurehunt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cabezudo.treasurehunt.recognition.ImageRecognitionStatus
import dev.cabezudo.treasurehunt.recognition.ReferenceImageDetector
import dev.cabezudo.treasurehunt.recognition.ReferenceImageLoader
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.geometry.Geometry

@RunWith(AndroidJUnit4::class)
class ReferenceImageDetectorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun detectsExactReference() = withReference { reference ->
        assertDetected(reference)
    }

    @Test
    fun detectsScaledReference() = withReference { reference ->
        val scaled = Mat()
        try {
            Imgproc.resize(reference, scaled, Size(600.0, 800.0), 0.0, 0.0, Imgproc.INTER_AREA)
            assertDetected(scaled)
        } finally {
            scaled.release()
        }
    }

    @Test
    fun detectsRotatedReference() = withReference { reference ->
        val rotated = Mat()
        try {
            Core.rotate(reference, rotated, Core.ROTATE_90_CLOCKWISE)
            assertDetected(rotated)
        } finally {
            rotated.release()
        }
    }

    @Test
    fun detectsModeratePerspective() = withReference { reference ->
        warp(
            reference,
            listOf(point(130, 90), point(1080, 150), point(1010, 1500), point(180, 1420)),
        ).useMat(::assertDetected)
    }

    @Test
    fun detectsReducedBrightness() = withReference { reference ->
        val dark = Mat()
        try {
            reference.convertTo(dark, CvType.CV_8UC1, 0.45, 0.0)
            assertDetected(dark)
        } finally {
            dark.release()
        }
    }

    @Test
    fun detectsModeratePartialOcclusion() = withReference { reference ->
        val occluded = reference.clone()
        try {
            Imgproc.rectangle(
                occluded,
                Point(760.0, 1040.0),
                Point(1199.0, 1599.0),
                Scalar(127.0),
                Imgproc.FILLED,
            )
            assertDetected(occluded)
        } finally {
            occluded.release()
        }
    }

    @Test
    fun rejectsCompletelyDifferentImage() {
        val random = Random(27)
        val bytes = ByteArray(640 * 480) { random.nextInt(0, 256).toByte() }
        val image = Mat(480, 640, CvType.CV_8UC1)
        try {
            image.put(0, 0, bytes)
            assertRejected(image)
        } finally {
            image.release()
        }
    }

    @Test
    fun rejectsNearlyUniformSurface() {
        val image = Mat(480, 640, CvType.CV_8UC1, Scalar(128.0))
        try {
            Imgproc.circle(image, Point(320.0, 240.0), 8, Scalar(132.0), Imgproc.FILLED)
            assertRejected(image)
        } finally {
            image.release()
        }
    }

    @Test
    fun rejectsRepetitivePattern() {
        val image = Mat(480, 640, CvType.CV_8UC1, Scalar(240.0))
        try {
            for (y in 0 until 480 step 40) {
                for (x in 0 until 640 step 40) {
                    if ((x / 40 + y / 40) % 2 == 0) {
                        Imgproc.rectangle(
                            image,
                            Point(x.toDouble(), y.toDouble()),
                            Point((x + 39).toDouble(), (y + 39).toDouble()),
                            Scalar(20.0),
                            Imgproc.FILLED,
                        )
                    }
                }
            }
            assertRejected(image)
        } finally {
            image.release()
        }
    }

    @Test
    fun rejectsExtremePerspective() = withReference { reference ->
        warp(
            reference,
            listOf(point(570, 100), point(625, 105), point(640, 1510), point(565, 1490)),
        ).useMat(::assertRejected)
    }

    private fun assertDetected(frame: Mat) {
        ReferenceImageDetector(context, monotonicNanos = { 1_000_000_000L }).use { detector ->
            val result = requireNotNull(detector.analyzeIfDue(frame, 0L))
            assertEquals("Unexpected result: $result", ImageRecognitionStatus.DETECTED, result.status)
            assertTrue(result.ransacInliers > 0)
            assertEquals(4, result.corners.size)
        }
    }

    private fun assertRejected(frame: Mat) {
        ReferenceImageDetector(context, monotonicNanos = { 1_000_000_000L }).use { detector ->
            val result = requireNotNull(detector.analyzeIfDue(frame, 0L))
            assertEquals("Unexpected result: $result", ImageRecognitionStatus.REJECTED, result.status)
            assertNotEquals(ImageRecognitionStatus.ERROR, result.status)
            assertTrue(result.rejectionReason != null)
        }
    }

    private fun withReference(block: (Mat) -> Unit) {
        ReferenceImageLoader(context).load().use { loaded -> block(loaded.grayscale) }
    }

    private fun warp(reference: Mat, destination: List<Point>): Mat {
        val sourcePoints = MatOfPoint2f(
            point(0, 0),
            point(reference.cols(), 0),
            point(reference.cols(), reference.rows()),
            point(0, reference.rows()),
        )
        val destinationPoints = MatOfPoint2f(*destination.toTypedArray())
        val transform = Geometry.getPerspectiveTransform(sourcePoints, destinationPoints)
        val output = Mat()
        try {
            Imgproc.warpPerspective(
                reference,
                output,
                transform,
                Size(reference.cols().toDouble(), reference.rows().toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(127.0),
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
            assertEquals(
                dev.cabezudo.treasurehunt.camera.OpenCvStatus.READY,
                dev.cabezudo.treasurehunt.camera.OpenCvRuntime.initialize().status,
            )
        }
    }
}
