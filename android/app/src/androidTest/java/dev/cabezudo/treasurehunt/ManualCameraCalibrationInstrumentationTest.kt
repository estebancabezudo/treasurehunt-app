package dev.cabezudo.treasurehunt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDataset
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDatasetIdentity
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDatasetJson
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDeviceOrientation
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDiversitySignature
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationGridPosition
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationSample
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationScale
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationTilt
import dev.cabezudo.treasurehunt.camera.OpenCvRuntime
import dev.cabezudo.treasurehunt.camera.OpenCvStatus
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.manualcalibration.CalibrationObjectPointFactory
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationResultRepository
import dev.cabezudo.treasurehunt.manualcalibration.ManualCameraCalibrator
import dev.cabezudo.treasurehunt.manualcalibration.PHYSICAL_CALIBRATION_DATASET_SHA256
import dev.cabezudo.treasurehunt.manualcalibration.Sha256
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import java.io.File
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
class ManualCameraCalibrationInstrumentationTest {
    @Test
    fun knownSyntheticCalibrationIsRecoveredWithinDocumentedTolerance() {
        assertEquals(OpenCvStatus.READY, OpenCvRuntime.initialize().status)
        val dataset = syntheticDataset()
        val result = ManualCameraCalibrator().calibrateRun(dataset, emptySet())

        assertTrue(kotlin.math.abs(result.fx - 520.0) / 520.0 < 0.02)
        assertTrue(kotlin.math.abs(result.fy - 515.0) / 515.0 < 0.02)
        assertTrue(kotlin.math.abs(result.cx - 320.0) < 5.0)
        assertTrue(kotlin.math.abs(result.cy - 240.0) < 5.0)
        assertTrue(result.globalRmsPixels < 0.05)
        assertEquals(5, result.distortionCoefficients.size)
    }

    @Test
    fun exactPhysicalDatasetCalibratesReproduciblyAndPersists() {
        assertEquals(OpenCvStatus.READY, OpenCvRuntime.initialize().status)
        val targetDirectory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "calibration",
        ).apply { mkdirs() }
        val datasetFile = File(targetDirectory, "calibration_dataset_v1.json")
        copyPhysicalDatasetAsset(datasetFile)
        assertEquals(PHYSICAL_CALIBRATION_DATASET_SHA256, Sha256.of(datasetFile))
        val dataset = CalibrationDatasetJson().decode(datasetFile.readText())
        val calibrator = ManualCameraCalibrator(wallClockMillis = { 1_787_900_000_000L })

        val first = calibrator.calibrate(dataset, Sha256.of(datasetFile))
        val second = calibrator.calibrate(dataset, Sha256.of(datasetFile))

        assertEquals(20, first.primary.usedSampleIndices.size)
        assertEquals(9, first.primary.intrinsicMatrix.size)
        assertEquals(5, first.primary.distortionCoefficients.size)
        assertTrue(first.primary.fx > 0.0 && first.primary.fy > 0.0)
        assertTrue(first.primary.globalRmsPixels.isFinite())
        assertEquals(first.primary.globalRmsPixels, second.primary.globalRmsPixels, 1e-5)
        first.primary.intrinsicMatrix.zip(second.primary.intrinsicMatrix).forEach { (a, b) ->
            assertEquals(a, b, 1e-5)
        }
        val repository = ManualCalibrationResultRepository(
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "manual-calibration-result-test",
            ),
        )
        repository.save(first)
        assertEquals(first, repository.load(first.identity, first.datasetSha256).result)
        println("PHYSICAL_CALIBRATION_RESULT=${repository.file.absolutePath}")
        println("PHYSICAL_CALIBRATION_RESULT_SHA256=${Sha256.of(repository.file)}")
    }

    private fun copyPhysicalDatasetAsset(target: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.context.assets.open(PHYSICAL_DATASET_ASSET).use { input ->
            target.outputStream().use(input::copyTo)
        }
    }

    private fun syntheticDataset(): CalibrationDataset {
        val identity = CalibrationDatasetIdentity(
            cameraId = "synthetic-0",
            bufferWidth = 640,
            bufferHeight = 480,
            cropRect = CoordinateRect(0, 0, 640, 480),
            horizontalSquareSizeMm = 18.20,
            verticalSquareSizeMm = 18.142857,
        )
        val physical = CalibrationObjectPointFactory.create(9, 6, 18.20, 18.142857)
        val camera = Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, 520.0, 0.0, 320.0, 0.0, 515.0, 240.0, 0.0, 0.0, 1.0)
        }
        val distortion = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
        val objectPoints = MatOfPoint3f(*physical.map {
            Point3(it.xMillimeters, it.yMillimeters, 0.0)
        }.toTypedArray())
        try {
            val samples = List(20) { position ->
                val rotation = Mat(3, 1, CvType.CV_64F).apply {
                    put(
                        0,
                        0,
                        (position % 5 - 2) * 0.045,
                        (position % 4 - 1.5) * 0.055,
                        (position % 3 - 1) * 0.025,
                    )
                }
                val translation = Mat(3, 1, CvType.CV_64F).apply {
                    put(
                        0,
                        0,
                        (position % 5 - 2) * 25.0,
                        (position / 5 - 1.5) * 20.0,
                        480.0 + (position % 4) * 65.0,
                    )
                }
                val projected = MatOfPoint2f()
                try {
                    Geometry.projectPoints(
                        objectPoints,
                        rotation,
                        translation,
                        camera,
                        distortion,
                        projected,
                    )
                    val corners = projected.toArray().map { RecognitionPoint(it.x, it.y) }
                    syntheticSample(position + 1, corners)
                } finally {
                    rotation.release()
                    translation.release()
                    projected.release()
                }
            }
            return CalibrationDataset(
                identity = identity,
                createdAtEpochMillis = 1_700_000_000_000,
                updatedAtEpochMillis = 1_700_000_001_000,
                samples = samples,
            )
        } finally {
            camera.release()
            distortion.release()
            objectPoints.release()
        }
    }

    private fun syntheticSample(index: Int, corners: List<RecognitionPoint>): CalibrationSample {
        val orientation = if (index % 2 == 0) {
            CalibrationDeviceOrientation.LANDSCAPE
        } else {
            CalibrationDeviceOrientation.PORTRAIT
        }
        val signature = CalibrationDiversitySignature(
            gridPosition = CalibrationGridPosition.entries[(index - 1) % 9],
            scale = CalibrationScale.entries[(index - 1) % 3],
            horizontalTilt = if (index % 2 == 0) CalibrationTilt.POSITIVE else CalibrationTilt.NEGATIVE,
            verticalTilt = if (index % 2 == 0) CalibrationTilt.NEGATIVE else CalibrationTilt.POSITIVE,
            deviceOrientation = orientation,
            normalizedCorners = corners.map { RecognitionPoint(it.x / 640.0, it.y / 480.0) },
        )
        return CalibrationSample(
            index = index,
            canonicalCorners = corners,
            canonicalWidth = 640,
            canonicalHeight = 480,
            originalRotationDegrees = if (orientation == CalibrationDeviceOrientation.PORTRAIT) 90 else 0,
            originalCropRect = CoordinateRect(0, 0, 640, 480),
            sensorToBufferTransform = Matrix3.IDENTITY,
            areaFraction = when (signature.scale) {
                CalibrationScale.SMALL -> 0.03
                CalibrationScale.MEDIUM -> 0.10
                CalibrationScale.LARGE -> 0.20
            },
            normalizedCenter = RecognitionPoint(
                signature.normalizedCorners.map { it.x }.average(),
                signature.normalizedCorners.map { it.y }.average(),
            ),
            approximateWidthPixels = 150.0,
            approximateHeightPixels = 90.0,
            capturedAtEpochMillis = 1_700_000_000_000 + index,
            geometricQuality = 1.0,
            diversity = signature,
            horizontalSquareSizeMm = 18.20,
            verticalSquareSizeMm = 18.142857,
        )
    }

    private companion object {
        const val PHYSICAL_DATASET_ASSET = "25078RA3EL_camera0_dataset_v1.json"
    }
}
