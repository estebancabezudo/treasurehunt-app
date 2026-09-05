package dev.cabezudo.treasurehunt.camera.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCalibrationTransformerTest {
    private val transformer = FrameCalibrationTransformer()

    @Test
    fun transformsWithoutCropOrRotation() {
        val result = transformer.transform(source(), frame())

        assertEquals(CameraCalibrationStatus.TRANSFORMED, result.status)
        val prepared = requireNotNull(result.prepared)
        assertNear(320.0, prepared.fx)
        assertNear(288.0, prepared.fy)
        assertNear(319.5, prepared.cx)
        assertNear(239.5, prepared.cy)
        assertNear(1.6, prepared.skew)
        assertEquals(640, prepared.width)
        assertEquals(480, prepared.height)
    }

    @Test
    fun scalesSensorToBuffer() {
        val result = transformer.transform(
            source(),
            frame(
                sensorToBuffer = scale(0.2, 0.1),
                imageWidth = 800,
                imageHeight = 300,
                crop = CoordinateRect(0, 0, 800, 300),
            ),
        )

        val prepared = requireNotNull(result.prepared)
        assertNear(400.0, prepared.fx)
        assertNear(180.0, prepared.fy)
        assertNear(399.5, prepared.cx)
        assertNear(149.5, prepared.cy)
    }

    @Test
    fun appliesCenteredCrop() {
        val result = transformer.transform(
            source(),
            frame(crop = CoordinateRect(80, 60, 560, 420)),
        )

        val prepared = requireNotNull(result.prepared)
        assertEquals(480, prepared.width)
        assertEquals(360, prepared.height)
        assertNear(239.5, prepared.cx)
        assertNear(179.5, prepared.cy)
    }

    @Test
    fun appliesDisplacedCrop() {
        val result = transformer.transform(
            source(),
            frame(crop = CoordinateRect(100, 20, 600, 400)),
        )

        val prepared = requireNotNull(result.prepared)
        assertNear(219.5, prepared.cx)
        assertNear(219.5, prepared.cy)
    }

    @Test
    fun transformsAllSupportedRotationsAndDimensions() {
        val expected = mapOf(
            0 to listOf(640.0, 480.0, 319.5, 239.5),
            90 to listOf(480.0, 640.0, 239.5, 319.5),
            180 to listOf(640.0, 480.0, 319.5, 239.5),
            270 to listOf(480.0, 640.0, 239.5, 319.5),
        )

        expected.forEach { (rotation, values) ->
            val result = transformer.transform(source(), frame(rotation = rotation))
            val prepared = requireNotNull(result.prepared)
            assertEquals(values[0].toInt(), prepared.width)
            assertEquals(values[1].toInt(), prepared.height)
            assertNear(values[2], prepared.cx)
            assertNear(values[3], prepared.cy)
            assertTrue(prepared.fx > 0.0)
            assertTrue(prepared.fy > 0.0)
        }
    }

    @Test
    fun preservesSkewInUnrotatedCalibration() {
        val result = transformer.transform(source(), frame())

        assertNear(1.6, requireNotNull(result.prepared).skew)
    }

    @Test
    fun rejectsInvalidFocalLength() {
        val result = transformer.transform(
            source(intrinsics = validIntrinsics().copy(fx = 0.0)),
            frame(),
        )

        assertEquals(CameraCalibrationStatus.INVALID, result.status)
        assertNull(result.prepared)
    }

    @Test
    fun rejectsNonFiniteCoordinates() {
        val result = transformer.transform(
            source(intrinsics = validIntrinsics().copy(cx = Double.NaN)),
            frame(),
        )

        assertEquals(CameraCalibrationStatus.INVALID, result.status)
    }

    @Test
    fun rejectsSingularSensorTransform() {
        val result = transformer.transform(
            source(),
            frame(sensorToBuffer = Matrix3(List(9) { 0.0 })),
        )

        assertEquals(CameraCalibrationStatus.INVALID, result.status)
        assertTrue(result.cause!!.contains("singular"))
    }

    @Test
    fun reportsMissingCalibrationExplicitly() {
        val result = transformer.transform(
            source(intrinsics = null).copy(
                status = CameraCalibrationStatus.NOT_AVAILABLE,
                cause = "LENS_INTRINSIC_CALIBRATION ausente",
            ),
            frame(),
        )

        assertEquals(CameraCalibrationStatus.NOT_AVAILABLE, result.status)
        assertNull(result.prepared)
        assertTrue(result.cause!!.contains("LENS_INTRINSIC_CALIBRATION"))
    }

    @Test
    fun doesNotInventLinearMappingForNonZeroAndroidDistortion() {
        val result = transformer.transform(
            source(
                distortion = AndroidDistortionState.REPORTED_NON_ZERO,
                coefficients = listOf(0.1, 0.01, 0.001, 0.0, 0.0),
            ),
            frame(),
        )

        assertEquals(CameraCalibrationStatus.NOT_AVAILABLE, result.status)
        assertNull(result.prepared)
        assertTrue(result.cause!!.contains("equivalencia lineal"))
    }

    @Test
    fun doesNotInventMappingBetweenDifferentActiveArrays() {
        val raw = requireNotNull(source().raw).copy(
            preCorrectionActiveArray = CoordinateRect(0, 0, 4100, 3100),
        )
        val result = transformer.transform(source().copy(raw = raw), frame())

        assertEquals(CameraCalibrationStatus.NOT_AVAILABLE, result.status)
        assertNull(result.prepared)
    }

    @Test
    fun rejectsPreparedDimensionsInconsistentWithRotation() {
        val invalidFrame = frame(rotation = 90).copy(preparedWidth = 640, preparedHeight = 480)

        val result = transformer.transform(source(), invalidFrame)

        assertEquals(CameraCalibrationStatus.INVALID, result.status)
        assertTrue(result.cause!!.contains("dimensiones preparadas"))
    }

    @Test
    fun matrixIsFiniteAndNonSingular() {
        val matrix = requireNotNull(transformer.transform(source(), frame()).prepared).intrinsicMatrix

        assertTrue(matrix.values.all(Double::isFinite))
        assertTrue(kotlin.math.abs(matrix.determinant()) > 1e-12)
    }

    private fun source(
        intrinsics: IntrinsicParameters? = validIntrinsics(),
        distortion: AndroidDistortionState = AndroidDistortionState.NOT_REPORTED,
        coefficients: List<Double>? = null,
    ): CameraCalibrationState = CameraCalibrationState(
        status = CameraCalibrationStatus.RAW_AVAILABLE,
        raw = RawCameraCalibration(
            cameraId = "0",
            intrinsics = intrinsics,
            lensDistortion = coefficients,
            activeArray = CoordinateRect(0, 0, 4000, 3000),
            preCorrectionActiveArray = CoordinateRect(0, 0, 4000, 3000),
            availableFocalLengthsMm = listOf(4.5),
            sensorPhysicalWidthMm = 5.6,
            sensorPhysicalHeightMm = 4.2,
            availableDistortionCorrectionModes = emptyList(),
            sensorOrientationDegrees = 90,
            distortionState = distortion,
        ),
        cause = null,
    )

    private fun validIntrinsics() = IntrinsicParameters(2000.0, 1800.0, 2000.0, 1500.0, 10.0)

    private fun frame(
        rotation: Int = 0,
        sensorToBuffer: Matrix3 = scale(0.16, 0.16),
        imageWidth: Int = 640,
        imageHeight: Int = 480,
        crop: CoordinateRect = CoordinateRect(0, 0, imageWidth, imageHeight),
    ): FrameCoordinateTransform = FrameCoordinateTransform(
        sensorToBuffer = sensorToBuffer,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        cropRect = crop,
        rotationDegrees = rotation,
        preparedWidth = if (rotation in setOf(90, 270)) crop.height else crop.width,
        preparedHeight = if (rotation in setOf(90, 270)) crop.width else crop.height,
    )

    private fun scale(x: Double, y: Double) = Matrix3(
        listOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 1.0),
    )

    private fun assertNear(expected: Double, actual: Double) {
        assertEquals(expected, actual, 1e-6)
    }
}
