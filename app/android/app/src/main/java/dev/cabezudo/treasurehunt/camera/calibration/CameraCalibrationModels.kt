package dev.cabezudo.treasurehunt.camera.calibration

data class CoordinateRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isValid(): Boolean = width > 0 && height > 0
}

data class IntrinsicParameters(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val skew: Double,
)

enum class AndroidDistortionState {
    NOT_REPORTED,
    REPORTED_ZERO,
    REPORTED_NON_ZERO,
}

/** Immutable snapshot of Camera2 characteristics; it retains no CameraInfo or Context. */
data class RawCameraCalibration(
    val cameraId: String,
    val intrinsics: IntrinsicParameters?,
    val lensDistortion: List<Double>?,
    val activeArray: CoordinateRect?,
    val preCorrectionActiveArray: CoordinateRect?,
    val availableFocalLengthsMm: List<Double>,
    val sensorPhysicalWidthMm: Double?,
    val sensorPhysicalHeightMm: Double?,
    val availableDistortionCorrectionModes: List<Int>,
    val sensorOrientationDegrees: Int?,
    val distortionState: AndroidDistortionState,
    val unavailableCause: String? = null,
)

data class Matrix3(val values: List<Double>) {
    init {
        require(values.size == 9) { "Una matriz 3 × 3 requiere nueve valores." }
    }

    operator fun times(other: Matrix3): Matrix3 {
        val result = MutableList(9) { 0.0 }
        for (row in 0..2) {
            for (column in 0..2) {
                result[row * 3 + column] = (0..2).sumOf { index ->
                    values[row * 3 + index] * other.values[index * 3 + column]
                }
            }
        }
        return Matrix3(result)
    }

    fun map(x: Double, y: Double): Pair<Double, Double> {
        val denominator = values[6] * x + values[7] * y + values[8]
        require(denominator.isFinite() && kotlin.math.abs(denominator) > 1e-12) {
            "La transformación produce una coordenada homogénea inválida."
        }
        return Pair(
            (values[0] * x + values[1] * y + values[2]) / denominator,
            (values[3] * x + values[4] * y + values[5]) / denominator,
        )
    }

    fun determinant(): Double =
        values[0] * (values[4] * values[8] - values[5] * values[7]) -
            values[1] * (values[3] * values[8] - values[5] * values[6]) +
            values[2] * (values[3] * values[7] - values[4] * values[6])

    companion object {
        val IDENTITY = Matrix3(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    }
}

data class FrameCoordinateTransform(
    val sensorToBuffer: Matrix3,
    val imageWidth: Int,
    val imageHeight: Int,
    val cropRect: CoordinateRect,
    val rotationDegrees: Int,
    val preparedWidth: Int,
    val preparedHeight: Int,
)

data class PreparedFrameCalibration(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val skew: Double,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val intrinsicMatrix: Matrix3,
    val source: String,
    val distortionState: AndroidDistortionState,
)

enum class CameraCalibrationStatus {
    NOT_AVAILABLE,
    RAW_AVAILABLE,
    TRANSFORMED,
    INVALID,
    ERROR,
}

data class CameraCalibrationState(
    val status: CameraCalibrationStatus = CameraCalibrationStatus.NOT_AVAILABLE,
    val raw: RawCameraCalibration? = null,
    val frameTransform: FrameCoordinateTransform? = null,
    val prepared: PreparedFrameCalibration? = null,
    val cause: String? = "La cámara todavía no ha publicado características de calibración.",
)
