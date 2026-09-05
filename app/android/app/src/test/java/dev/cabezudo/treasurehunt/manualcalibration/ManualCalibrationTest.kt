package dev.cabezudo.treasurehunt.manualcalibration

import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDatasetJson
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDeviceOrientation
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationGridPosition
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationScale
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCalibrationTest {
    @Test
    fun rectangularPhysicalPointsUseColumnThenRowOrder() {
        val points = CalibrationObjectPointFactory.create(9, 6, 18.20, 18.142857)
        assertEquals(54, points.size)
        assertEquals(0.0, points[0].xMillimeters, 0.0)
        assertEquals(0.0, points[0].yMillimeters, 0.0)
        assertEquals(8 * 18.20, points[8].xMillimeters, 0.0)
        assertEquals(0.0, points[8].yMillimeters, 0.0)
        assertEquals(0.0, points[9].xMillimeters, 0.0)
        assertEquals(18.142857, points[9].yMillimeters, 0.0)
        assertEquals(8 * 18.20, points[53].xMillimeters, 0.0)
        assertEquals(5 * 18.142857, points[53].yMillimeters, 0.0)
        assertTrue(points.all { it.zMillimeters == 0.0 })
    }

    @Test
    fun pointErrorsCalculateRmsMeanAndMaximum() {
        val (rms, mean, maximum) = ReprojectionStatistics.pointErrors(
            observed = listOf(0.0 to 0.0, 3.0 to 4.0),
            projected = listOf(0.0 to 0.0, 0.0 to 0.0),
        )
        assertEquals(kotlin.math.sqrt(12.5), rms, 1e-12)
        assertEquals(2.5, mean, 1e-12)
        assertEquals(5.0, maximum, 1e-12)
    }

    @Test
    fun outliersUseReproducibleMedianAndMadCriterion() {
        val errors = (1..7).map { index -> sampleError(index, if (index == 7) 10.0 else 1.0 + index * 0.01) }
        val decision = ReprojectionStatistics.detectOutliers(errors)
        assertEquals(listOf(7), decision.sampleIndices)
        assertTrue("MAD" in decision.criterion)
        assertTrue(decision.thresholdPixels < 10.0)
    }

    @Test
    fun zeroMadDoesNotInventOutliers() {
        val decision = ReprojectionStatistics.detectOutliers(
            listOf(sampleError(1, 1.0), sampleError(2, 1.0), sampleError(3, 1.0)),
        )
        assertTrue(decision.sampleIndices.isEmpty())
        assertEquals(Double.POSITIVE_INFINITY, decision.thresholdPixels, 0.0)
    }

    @Test
    fun physicalDatasetPassesExactPreflight() {
        val file = physicalDatasetFile()
        assertEquals(PHYSICAL_CALIBRATION_DATASET_SHA256, Sha256.of(file))
        val dataset = CalibrationDatasetJson().decode(file.readText())
        ManualCalibrationValidator.validateDataset(dataset, Sha256.of(file))
        assertEquals(20, dataset.samples.size)
        assertTrue(dataset.samples.all { sample ->
            sample.canonicalCorners.size == 54 &&
                sample.canonicalCorners.all { it.x.isFinite() && it.y.isFinite() }
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun physicalDatasetRejectsWrongHash() {
        val dataset = CalibrationDatasetJson().decode(physicalDatasetFile().readText())
        ManualCalibrationValidator.validateDataset(dataset, "0".repeat(64))
    }

    @Test
    fun exportedPhysicalResultHasExpectedSchemaIdentityAndSourceDataset() {
        val file = physicalResultFile()
        assertEquals(PHYSICAL_CALIBRATION_RESULT_SHA256, Sha256.of(file))
        val result = ManualCalibrationResultJson().decode(file.readText())
        assertEquals("0", result.identity.cameraId)
        assertEquals(640, result.identity.bufferWidth)
        assertEquals(480, result.identity.bufferHeight)
        assertEquals(CoordinateRect(0, 0, 640, 480), result.identity.cropRect)
        assertEquals(CANONICAL_BUFFER_COORDINATE_SYSTEM, result.identity.coordinateSystem)
        assertEquals(PHYSICAL_CALIBRATION_DATASET_SHA256, result.datasetSha256)
        assertEquals((1..20).toList(), result.primary.usedSampleIndices)
        assertTrue(result.primary.excludedSampleIndices.isEmpty())
        assertEquals(5, result.primary.distortionCoefficients.size)
        ManualCalibrationValidator.validateRun(result.primary, result.identity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteSampleIsRejected() {
        val file = physicalDatasetFile()
        val dataset = CalibrationDatasetJson().decode(file.readText())
        val broken = dataset.copy(samples = dataset.samples.mapIndexed { index, sample ->
            if (index == 0) sample.copy(canonicalCorners = sample.canonicalCorners.dropLast(1)) else sample
        })
        ManualCalibrationValidator.validateDataset(broken, Sha256.of(file))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFinitePointIsRejected() {
        val file = physicalDatasetFile()
        val dataset = CalibrationDatasetJson().decode(file.readText())
        val first = dataset.samples.first()
        val broken = dataset.copy(samples = listOf(
            first.copy(canonicalCorners = first.canonicalCorners.toMutableList().also {
                it[0] = it[0].copy(x = Double.NaN)
            }),
        ) + dataset.samples.drop(1))
        ManualCalibrationValidator.validateDataset(broken, Sha256.of(file))
    }

    @Test
    fun calibrationJsonRoundTripPreservesAuditData() {
        val original = result()
        val json = ManualCalibrationResultJson().encode(original)
        assertEquals(original, ManualCalibrationResultJson().decode(json))
        assertTrue(json.contains("OPENCV_STANDARD_5_K1_K2_P1_P2_K3"))
        assertTrue(json.contains("detectedOutlierSampleIndices"))
    }

    @Test
    fun repositoryPersistsAndRecoversCompatibleResult() {
        val repository = ManualCalibrationResultRepository(
            Files.createTempDirectory("manual-calibration").toFile(),
        )
        val original = result()
        repository.save(original)
        val loaded = repository.load(original.identity, original.datasetSha256)
        assertEquals(original, loaded.result)
        assertFalse(loaded.incompatible)
        assertTrue(repository.file.isFile)
        assertFalse(File(repository.file.parentFile, "${repository.file.name}.tmp").exists())
    }

    @Test
    fun incompatibleIdentityIsExplicitAndNeverLoaded() {
        val repository = ManualCalibrationResultRepository(
            Files.createTempDirectory("manual-calibration-incompatible").toFile(),
        )
        val original = result()
        repository.save(original)
        val loaded = repository.load(original.identity.copy(cameraId = "1"), original.datasetSha256)
        assertNull(loaded.result)
        assertTrue(loaded.incompatible)
    }

    @Test
    fun corruptResultIsRejected() {
        val repository = ManualCalibrationResultRepository(
            Files.createTempDirectory("manual-calibration-corrupt").toFile(),
        )
        repository.file.parentFile!!.mkdirs()
        repository.file.writeText("{not-json")
        val original = result()
        val loaded = repository.load(original.identity, original.datasetSha256)
        assertNull(loaded.result)
        assertFalse(loaded.incompatible)
        assertTrue(loaded.error != null)
    }

    private fun sampleError(index: Int, rms: Double) = SampleReprojectionError(
        sampleIndex = index,
        rmsPixels = rms,
        meanPixels = rms * 0.9,
        maximumPixels = rms * 1.2,
        orientation = CalibrationDeviceOrientation.PORTRAIT,
        scale = CalibrationScale.MEDIUM,
        gridPosition = CalibrationGridPosition.CENTER,
    )

    private fun result(): ManualCameraCalibrationResult {
        val errors = (1..20).map { sampleError(it, 0.2 + it / 100.0) }
        val run = CalibrationRunResult(
            usedSampleIndices = (1..20).toList(),
            excludedSampleIndices = emptyList(),
            intrinsicMatrix = listOf(510.0, 0.0, 320.0, 0.0, 512.0, 240.0, 0.0, 0.0, 1.0),
            distortionCoefficients = listOf(0.01, -0.02, 0.001, -0.001, 0.003),
            globalRmsPixels = 0.3,
            sampleErrors = errors,
            errorSummary = ReprojectionStatistics.summarize(errors),
        )
        return ManualCameraCalibrationResult(
            identity = ManualCalibrationIdentity(
                cameraId = "0",
                bufferWidth = 640,
                bufferHeight = 480,
                cropRect = CoordinateRect(0, 0, 640, 480),
                internalColumns = 9,
                internalRows = 6,
                horizontalSquareSizeMm = 18.20,
                verticalSquareSizeMm = 18.142857,
            ),
            datasetSha256 = PHYSICAL_CALIBRATION_DATASET_SHA256,
            calculatedAtEpochMillis = 1_700_000_000_000,
            openCvVersion = "5.0.0",
            outlierCriterion = "median + MAD",
            detectedOutlierSampleIndices = emptyList(),
            comparativeCalibrationDecision = "No outliers.",
            primary = run,
        )
    }

    private fun physicalDatasetFile(): File {
        val relative = "reference/calibration_datasets/25078RA3EL_camera0_dataset_v1.json"
        return listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull(File::isFile)
            ?: error("No se encontró el dataset físico desde ${File(".").absolutePath}.")
    }

    private fun physicalResultFile(): File {
        val relative = "reference/calibration_results/25078RA3EL_camera0_calibration_v1.json"
        return listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull(File::isFile)
            ?: error("No se encontró el resultado físico desde ${File(".").absolutePath}.")
    }
}
