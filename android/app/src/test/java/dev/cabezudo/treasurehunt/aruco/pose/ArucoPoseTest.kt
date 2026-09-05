package dev.cabezudo.treasurehunt.aruco.pose

import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationFrameCaptureMetadata
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationFrameCoordinateMapper
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationResultJson
import dev.cabezudo.treasurehunt.manualcalibration.PHYSICAL_CALIBRATION_RESULT_SHA256
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArucoPoseTest {
    @Test
    fun ippeSquareUsesRequiredTopLeftClockwiseOrderCenteredAtOrigin() {
        val points = IppeSquareObjectPoints.create(40.0)
        assertEquals(
            listOf(
                PhysicalPosePoint(-20.0, 20.0, 0.0),
                PhysicalPosePoint(20.0, 20.0, 0.0),
                PhysicalPosePoint(20.0, -20.0, 0.0),
                PhysicalPosePoint(-20.0, -20.0, 0.0),
            ),
            points,
        )
    }

    @Test
    fun fourFrameRotationsRoundTripMarkerCornersToSameCanonicalCoordinates() {
        val mapper = CalibrationFrameCoordinateMapper()
        val canonical = validCorners()
        listOf(0, 90, 180, 270).forEach { rotation ->
            val metadata = metadata(rotation)
            canonical.forEach { original ->
                val prepared = mapper.canonicalToPrepared(original, metadata)
                val recovered = mapper.preparedToCanonical(prepared, metadata)
                assertEquals(original.x, recovered.x, 1e-9)
                assertEquals(original.y, recovered.y, 1e-9)
            }
        }
    }

    @Test
    fun cornerOrderRejectsSelfIntersectionAndDegeneration() {
        assertTrue(PoseGeometryValidator.hasValidIppeCornerOrder(validCorners()))
        assertTrue(!PoseGeometryValidator.hasValidIppeCornerOrder(
            listOf(validCorners()[0], validCorners()[2], validCorners()[1], validCorners()[3]),
        ))
        assertTrue(!PoseGeometryValidator.hasValidIppeCornerOrder(
            List(4) { RecognitionPoint(1.0 + it, 2.0) },
        ))
    }

    @Test
    fun sizeMustBeConfirmedPositiveFiniteAndSquareWithinTwoPercent() {
        val validator = PhysicalMarkerSizeValidator()
        assertEquals(ArucoPoseStatus.PHYSICAL_SIZE_NOT_CONFIRMED, validator.rejection(null))
        assertNull(validator.rejection(PhysicalMarkerSize(39.9, 40.1, true)))
        assertEquals(
            ArucoPoseStatus.PHYSICAL_MARKER_DEFORMED,
            validator.rejection(PhysicalMarkerSize(40.0, 41.0, true)),
        )
        assertEquals(
            ArucoPoseStatus.REJECTED,
            validator.rejection(PhysicalMarkerSize(Double.NaN, 40.0, true)),
        )
    }

    @Test
    fun validatorRejectsNegativeZNaNInvalidRotationAndDegenerateProjection() {
        val identity = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(
            ArucoPoseRejectionReason.NON_POSITIVE_Z,
            PoseResultValidator.rejection(listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, -1.0), identity, 0.0, validCorners()),
        )
        assertEquals(
            ArucoPoseRejectionReason.NON_FINITE_RESULT,
            PoseResultValidator.rejection(listOf(Double.NaN, 0.0, 0.0), listOf(0.0, 0.0, 1.0), identity, 0.0, validCorners()),
        )
        assertEquals(
            ArucoPoseRejectionReason.INVALID_ROTATION_MATRIX,
            PoseResultValidator.rejection(listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0), List(9) { 0.0 }, 0.0, validCorners()),
        )
        assertEquals(
            ArucoPoseRejectionReason.DEGENERATE_PROJECTION,
            PoseResultValidator.rejection(listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0), identity, 0.0, List(4) { RecognitionPoint(0.0, 0.0) }),
        )
        assertEquals(
            ArucoPoseRejectionReason.NON_FINITE_REPROJECTION,
            PoseResultValidator.rejection(listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0), identity, Double.POSITIVE_INFINITY, validCorners()),
        )
    }

    @Test
    fun physicalCalibrationIsReadyOnlyForExactFrameIdentity() {
        val result = ManualCalibrationResultJson().decode(physicalResultFile().readText())
        val ready = PoseCalibrationState(
            PoseCalibrationStatus.READY,
            result,
            PHYSICAL_CALIBRATION_RESULT_SHA256,
            null,
        )
        assertEquals(
            PoseCalibrationStatus.READY,
            PoseCalibrationLoader.isCompatibleWithFrame(
                ready, "0", 640, 480, CoordinateRect(0, 0, 640, 480),
            ).status,
        )
        listOf(
            PoseCalibrationLoader.isCompatibleWithFrame(ready, "1", 640, 480, CoordinateRect(0, 0, 640, 480)),
            PoseCalibrationLoader.isCompatibleWithFrame(ready, "0", 1280, 720, CoordinateRect(0, 0, 1280, 720)),
            PoseCalibrationLoader.isCompatibleWithFrame(ready, "0", 640, 480, CoordinateRect(1, 0, 640, 480)),
        ).forEach { assertEquals(PoseCalibrationStatus.INCOMPATIBLE, it.status) }
    }

    @Test
    fun physicalSizePersistenceRoundTripsAndLeavesNoTemporaryFile() {
        val directory = Files.createTempDirectory("aruco-size").toFile()
        val repository = PhysicalMarkerSizeRepository(directory)
        val expected = repository.save(36.4, 36.2)
        assertEquals(expected, repository.load())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    private fun validCorners() = listOf(
        RecognitionPoint(100.0, 100.0),
        RecognitionPoint(200.0, 100.0),
        RecognitionPoint(200.0, 200.0),
        RecognitionPoint(100.0, 200.0),
    )

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

    private fun physicalResultFile(): File {
        val relative = "reference/calibration_results/25078RA3EL_camera0_calibration_v1.json"
        return listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull(File::isFile)
            ?: error("No se encontró el resultado físico.")
    }
}
