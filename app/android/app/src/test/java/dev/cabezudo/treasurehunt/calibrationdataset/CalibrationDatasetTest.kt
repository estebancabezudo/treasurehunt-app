package dev.cabezudo.treasurehunt.calibrationdataset

import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationDatasetTest {
    private val evaluator = CalibrationDiversityEvaluator()

    @Test
    fun identitiesIncludeCameraResolutionCropAndBoard() {
        val identity = identity()
        assertEquals(identity, identity())
        assertFalse(identity == identity(cameraId = "1"))
        assertFalse(identity == identity(crop = CoordinateRect(1, 0, 640, 480)))
        assertFalse(identity == identity(width = 1280))
        assertFalse(identity == identity(horizontalSquareSizeMm = 18.0))
        assertFalse(identity == identity(verticalSquareSizeMm = 18.2))
    }

    @Test
    fun duplicateUsesNormalized54PointGeometry() {
        val corners = grid(100.0, 80.0, 30.0)
        val signature = evaluator.signature(corners, 640, 480, 0.15, 0)
        assertTrue(evaluator.isDuplicate(signature, listOf(sample(1, signature))))
        val moved = evaluator.signature(grid(220.0, 80.0, 30.0), 640, 480, 0.15, 0)
        assertFalse(evaluator.isDuplicate(moved, listOf(sample(1, signature))))
    }

    @Test
    fun gridScaleTiltAndOrientationCategoriesAreDeterministic() {
        val topLeft = evaluator.signature(grid(20.0, 20.0, 10.0), 640, 480, 0.02, 90)
        assertEquals(CalibrationGridPosition.TOP_LEFT, topLeft.gridPosition)
        assertEquals(CalibrationScale.SMALL, topLeft.scale)
        assertEquals(CalibrationDeviceOrientation.PORTRAIT, topLeft.deviceOrientation)

        val large = evaluator.signature(trapezoid(), 640, 480, 0.20, 0)
        assertEquals(CalibrationScale.LARGE, large.scale)
        assertEquals(CalibrationTilt.NEGATIVE, large.horizontalTilt)
        assertEquals(CalibrationTilt.POSITIVE, large.verticalTilt)
    }

    @Test
    fun datasetIsInsufficientByCount() {
        val coverage = evaluator.coverage(listOf(sample(1, signature(grid(100.0, 80.0, 25.0), 0.10, 0))))
        assertFalse(coverage.ready)
        assertTrue(coverage.recommendations.any { "14 muestras" in it })
    }

    @Test
    fun datasetIsInsufficientByDiversityEvenWith15Samples() {
        val one = signature(grid(100.0, 80.0, 25.0), 0.10, 0)
        val coverage = evaluator.coverage(List(15) { sample(it + 1, one) })
        assertFalse(coverage.ready)
        assertTrue(coverage.recommendations.any { "cuadrícula" in it })
    }

    @Test
    fun diverseDatasetBecomesReadyAtTechnicalMinimum() {
        val positions = CalibrationGridPosition.entries
        val samples = List(15) { index ->
            val base = signature(grid(80.0 + index, 60.0, 22.0), 0.10, 0)
            sample(index + 1, base.copy(
                gridPosition = positions[index % positions.size],
                scale = CalibrationScale.entries[index % 3],
                horizontalTilt = if (index % 2 == 0) CalibrationTilt.NEGATIVE else CalibrationTilt.POSITIVE,
                verticalTilt = if (index % 2 == 0) CalibrationTilt.POSITIVE else CalibrationTilt.NEGATIVE,
                deviceOrientation = if (index % 2 == 0) CalibrationDeviceOrientation.PORTRAIT else CalibrationDeviceOrientation.LANDSCAPE,
            ))
        }
        assertTrue(evaluator.coverage(samples).ready)
    }

    @Test
    fun jsonRoundTripPreservesDataset() {
        val original = dataset()
        val encoded = CalibrationDatasetJson().encode(original)
        val decoded = CalibrationDatasetJson().decode(encoded)
        assertEquals(original, decoded)
        assertEquals(18.20, decoded.identity.horizontalSquareSizeMm, 0.0)
        assertEquals(18.142857, decoded.identity.verticalSquareSizeMm, 0.0)
        assertFalse(encoded.contains("confirmedSquareSizeMm"))
    }

    @Test
    fun exportedPhysicalDatasetHasExpectedSchemaIdentityAndDiversity() {
        val relativePath = "reference/calibration_datasets/25078RA3EL_camera0_dataset_v1.json"
        val file = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("No se encontró $relativePath desde ${File(".").absolutePath}")

        val exportedJson = file.readText()
        val dataset = CalibrationDatasetJson().decode(exportedJson)

        assertEquals(1, dataset.identity.schemaVersion)
        assertEquals("0", dataset.identity.cameraId)
        assertEquals(640, dataset.identity.bufferWidth)
        assertEquals(480, dataset.identity.bufferHeight)
        assertEquals(CoordinateRect(0, 0, 640, 480), dataset.identity.cropRect)
        assertEquals(9, dataset.identity.internalColumns)
        assertEquals(6, dataset.identity.internalRows)
        assertEquals(18.20, dataset.identity.horizontalSquareSizeMm, 0.0)
        assertEquals(18.142857, dataset.identity.verticalSquareSizeMm, 0.0)
        assertFalse(exportedJson.contains("nominalHorizontalSquareSizeMm"))
        assertFalse(exportedJson.contains("nominalVerticalSquareSizeMm"))
        assertEquals(20, dataset.samples.size)
        assertEquals(0, dataset.rejectedSamples)
        assertTrue(dataset.samples.all { it.canonicalCorners.size == 54 })
        assertTrue(dataset.samples.all { sample -> sample.canonicalCorners.all { it.x.isFinite() && it.y.isFinite() } })
        assertEquals(dataset.samples.indices.map { it + 1 }, dataset.samples.map { it.index })
        assertTrue(evaluator.coverage(dataset.samples).ready)
    }

    @Test
    fun repositoryCanonicallyRemovesLegacyNominalMeasurements() {
        val directory = Files.createTempDirectory("calibration-legacy-nominal").toFile()
        val repository = CalibrationSampleRepository(directory)
        val canonical = CalibrationDatasetJson().encode(dataset())
        val legacy = canonical.replace(
            "    \"horizontalSquareSizeMm\": 18.2,",
            "    \"nominalHorizontalSquareSizeMm\": 20.0,\n" +
                "    \"nominalVerticalSquareSizeMm\": 20.0,\n" +
                "    \"horizontalSquareSizeMm\": 18.2,",
        )
        requireNotNull(repository.file.parentFile).mkdirs()
        repository.file.writeText(legacy)

        assertNotNull(repository.load().dataset)
        val rewritten = requireNotNull(repository.readJson())
        assertFalse(rewritten.contains("nominalHorizontalSquareSizeMm"))
        assertFalse(rewritten.contains("nominalVerticalSquareSizeMm"))
        assertTrue(rewritten.contains("\"horizontalSquareSizeMm\": 18.2"))
        assertTrue(rewritten.contains("\"verticalSquareSizeMm\": 18.142857"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptJsonIsRejected() {
        CalibrationDatasetJson().decode("{not-json")
    }

    @Test
    fun repositoryReportsCorruptFileWithoutInventingDataset() {
        val directory = Files.createTempDirectory("calibration-corrupt").toFile()
        val repository = CalibrationSampleRepository(directory)
        repository.file.writeText("{\"schemaVersion\":99}")
        val loaded = repository.load()
        assertNull(loaded.dataset)
        assertNotNull(loaded.error)
    }

    @Test
    fun atomicWriterReplacesCompleteJsonAndLeavesNoTemporaryFile() {
        val directory = Files.createTempDirectory("calibration-atomic").toFile()
        val target = File(directory, "dataset.json").apply { writeText("old") }
        AtomicCalibrationDatasetWriter().writeAtomically(target, "new-complete-json")
        assertEquals("new-complete-json", target.readText())
        assertFalse(File(directory, "dataset.json.tmp").exists())
    }

    private fun dataset() = CalibrationDataset(
        identity = identity(),
        createdAtEpochMillis = 1_700_000_000_000,
        updatedAtEpochMillis = 1_700_000_001_000,
        samples = listOf(sample(1, signature(grid(100.0, 80.0, 25.0), 0.10, 90))),
        rejectedSamples = 2,
        lastRejection = CalibrationCaptureRejection(CalibrationCaptureRejectionReason.DUPLICATE, "duplicado", 1_700_000_000_500),
    )

    private fun identity(
        cameraId: String = "0",
        width: Int = 640,
        crop: CoordinateRect = CoordinateRect(0, 0, 640, 480),
        horizontalSquareSizeMm: Double = 18.20,
        verticalSquareSizeMm: Double = 18.142857,
    ) = CalibrationDatasetIdentity(
        cameraId = cameraId,
        bufferWidth = width,
        bufferHeight = 480,
        cropRect = crop,
        horizontalSquareSizeMm = horizontalSquareSizeMm,
        verticalSquareSizeMm = verticalSquareSizeMm,
    )

    private fun signature(corners: List<RecognitionPoint>, area: Double, rotation: Int) =
        evaluator.signature(corners, 640, 480, area, rotation)

    private fun sample(index: Int, diversity: CalibrationDiversitySignature) = CalibrationSample(
        index = index,
        canonicalCorners = diversity.normalizedCorners.map { RecognitionPoint(it.x * 640, it.y * 480) },
        canonicalWidth = 640,
        canonicalHeight = 480,
        originalRotationDegrees = if (diversity.deviceOrientation == CalibrationDeviceOrientation.PORTRAIT) 90 else 0,
        originalCropRect = CoordinateRect(0, 0, 640, 480),
        sensorToBufferTransform = Matrix3.IDENTITY,
        areaFraction = when (diversity.scale) { CalibrationScale.SMALL -> 0.02; CalibrationScale.MEDIUM -> 0.10; CalibrationScale.LARGE -> 0.20 },
        normalizedCenter = RecognitionPoint(diversity.normalizedCorners.map { it.x }.average(), diversity.normalizedCorners.map { it.y }.average()),
        approximateWidthPixels = 200.0,
        approximateHeightPixels = 120.0,
        capturedAtEpochMillis = 1_700_000_000_000 + index,
        geometricQuality = 0.9,
        diversity = diversity,
        horizontalSquareSizeMm = 18.20,
        verticalSquareSizeMm = 18.142857,
    )

    private fun grid(startX: Double, startY: Double, step: Double) = List(54) { index ->
        RecognitionPoint(startX + (index % 9) * step, startY + (index / 9) * step)
    }

    private fun trapezoid() = List(54) { index ->
        val row = index / 9
        val column = index % 9
        val rowScale = 1.0 + row * 0.04
        RecognitionPoint(100.0 + column * 24.0 * rowScale, 80.0 + row * (35.0 - column * 0.8))
    }
}
