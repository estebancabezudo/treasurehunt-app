package dev.cabezudo.treasurehunt.camera.calibration

import kotlin.math.abs
import kotlin.math.hypot

class FrameCalibrationTransformer {
    fun transform(
        source: CameraCalibrationState,
        frame: FrameCoordinateTransform,
    ): CameraCalibrationState {
        if (source.status == CameraCalibrationStatus.ERROR) return source.copy(frameTransform = frame)
        val raw = source.raw ?: return source.copy(frameTransform = frame)
        val intrinsic = raw.intrinsics ?: return source.copy(
            status = CameraCalibrationStatus.NOT_AVAILABLE,
            frameTransform = frame,
            prepared = null,
            cause = raw.unavailableCause ?: "LENS_INTRINSIC_CALIBRATION no está disponible.",
        )
        val active = raw.activeArray
        val preCorrection = raw.preCorrectionActiveArray
        if (active == null || preCorrection == null) {
            return source.copy(
                status = CameraCalibrationStatus.NOT_AVAILABLE,
                frameTransform = frame,
                prepared = null,
                cause = "No están disponibles ambos arreglos activos del sensor.",
            )
        }
        if (active != preCorrection || raw.distortionState == AndroidDistortionState.REPORTED_NON_ZERO) {
            return source.copy(
                status = CameraCalibrationStatus.NOT_AVAILABLE,
                frameTransform = frame,
                prepared = null,
                cause = "La calibración está en el arreglo previo a corrección y no existe una " +
                    "equivalencia lineal demostrada con el arreglo activo procesado.",
            )
        }

        return try {
            validateFrame(frame)
            validateRaw(intrinsic)
            val bufferToCrop = translation(
                -frame.cropRect.left.toDouble(),
                -frame.cropRect.top.toDouble(),
            )
            // Camera2 principal points use pixel-boundary coordinates. OpenCV Mat pixel centers use
            // integer coordinates, hence the half-pixel shift after CameraX maps into the buffer.
            val boundaryToOpenCvCenters = translation(-0.5, -0.5)
            val rotation = rotationMatrix(
                frame.rotationDegrees,
                frame.cropRect.width,
                frame.cropRect.height,
            )
            val sensorToPrepared = rotation * boundaryToOpenCvCenters *
                bufferToCrop * frame.sensorToBuffer
            val (cx, cy) = sensorToPrepared.map(intrinsic.cx, intrinsic.cy)

            val a = sensorToPrepared.values
            val sensorLinear = doubleArrayOf(
                a[0] * intrinsic.fx,
                a[0] * intrinsic.skew + a[1] * intrinsic.fy,
                a[3] * intrinsic.fx,
                a[3] * intrinsic.skew + a[4] * intrinsic.fy,
            )
            val canonical = upperTriangularIntrinsic(sensorLinear)
            val matrix = Matrix3(
                listOf(
                    canonical[0], canonical[1], cx,
                    0.0, canonical[2], cy,
                    0.0, 0.0, 1.0,
                ),
            )
            val prepared = PreparedFrameCalibration(
                fx = canonical[0],
                fy = canonical[2],
                cx = cx,
                cy = cy,
                skew = canonical[1],
                width = frame.preparedWidth,
                height = frame.preparedHeight,
                rotationDegrees = frame.rotationDegrees,
                intrinsicMatrix = matrix,
                source = "Camera2 LENS_INTRINSIC_CALIBRATION + CameraX " +
                    "sensorToBufferTransformMatrix + cropRect + rotación OpenCV",
                distortionState = raw.distortionState,
            )
            validatePrepared(prepared)
            source.copy(
                status = CameraCalibrationStatus.TRANSFORMED,
                frameTransform = frame,
                prepared = prepared,
                cause = null,
            )
        } catch (error: IllegalArgumentException) {
            source.copy(
                status = CameraCalibrationStatus.INVALID,
                frameTransform = frame,
                prepared = null,
                cause = error.message,
            )
        } catch (error: Exception) {
            source.copy(
                status = CameraCalibrationStatus.ERROR,
                frameTransform = frame,
                prepared = null,
                cause = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun validateFrame(frame: FrameCoordinateTransform) {
        require(frame.imageWidth > 0 && frame.imageHeight > 0) { "Resolución inválida del ImageProxy." }
        require(frame.cropRect.isValid()) { "cropRect inválido." }
        require(frame.cropRect.left >= 0 && frame.cropRect.top >= 0 &&
            frame.cropRect.right <= frame.imageWidth && frame.cropRect.bottom <= frame.imageHeight
        ) { "cropRect fuera del ImageProxy." }
        require(frame.rotationDegrees in setOf(0, 90, 180, 270)) { "Rotación no compatible." }
        val expectedWidth = if (frame.rotationDegrees in setOf(90, 270)) {
            frame.cropRect.height
        } else frame.cropRect.width
        val expectedHeight = if (frame.rotationDegrees in setOf(90, 270)) {
            frame.cropRect.width
        } else frame.cropRect.height
        require(frame.preparedWidth == expectedWidth && frame.preparedHeight == expectedHeight) {
            "Las dimensiones preparadas no coinciden con crop y rotación."
        }
        require(frame.sensorToBuffer.values.all(Double::isFinite)) {
            "sensorToBufferTransformMatrix contiene valores no finitos."
        }
        require(abs(frame.sensorToBuffer.determinant()) > 1e-12) {
            "sensorToBufferTransformMatrix es singular."
        }
    }

    private fun validateRaw(intrinsic: IntrinsicParameters) {
        require(listOf(intrinsic.fx, intrinsic.fy, intrinsic.cx, intrinsic.cy, intrinsic.skew)
            .all(Double::isFinite)) { "La calibración cruda contiene valores no finitos." }
        require(intrinsic.fx > 0.0 && intrinsic.fy > 0.0) {
            "Las focales crudas deben ser positivas."
        }
    }

    private fun validatePrepared(calibration: PreparedFrameCalibration) {
        require(listOf(
            calibration.fx,
            calibration.fy,
            calibration.cx,
            calibration.cy,
            calibration.skew,
        ).all(Double::isFinite)) { "La calibración transformada contiene valores no finitos." }
        require(calibration.fx > 0.0 && calibration.fy > 0.0) {
            "Las focales transformadas deben ser positivas."
        }
        val toleranceX = calibration.width * 0.25
        val toleranceY = calibration.height * 0.25
        require(calibration.cx in -toleranceX..(calibration.width + toleranceX) &&
            calibration.cy in -toleranceY..(calibration.height + toleranceY)
        ) { "El punto principal transformado queda fuera de la tolerancia del cuadro." }
        require(abs(calibration.intrinsicMatrix.determinant()) > 1e-12) {
            "La matriz intrínseca transformada es singular."
        }
    }

    /** RQ decomposition of the transformed 2 × 2 camera matrix. */
    private fun upperTriangularIntrinsic(matrix: DoubleArray): DoubleArray {
        val a = matrix[0]
        val b = matrix[1]
        val c = matrix[2]
        val d = matrix[3]
        val fy = hypot(c, d)
        require(fy > 1e-12) { "La transformación produjo una focal vertical degenerada." }
        val cosine = d / fy
        val sine = -c / fy
        var fx = a * cosine + b * sine
        var skew = -a * sine + b * cosine
        var positiveFy = fy
        if (fx < 0.0) {
            fx = -fx
            skew = -skew
        }
        if (positiveFy < 0.0) positiveFy = -positiveFy
        return doubleArrayOf(fx, skew, positiveFy)
    }

    private fun translation(x: Double, y: Double) = Matrix3(
        listOf(1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0),
    )

    private fun rotationMatrix(rotation: Int, width: Int, height: Int): Matrix3 = when (rotation) {
        0 -> Matrix3.IDENTITY
        90 -> Matrix3(listOf(0.0, -1.0, height - 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        180 -> Matrix3(listOf(-1.0, 0.0, width - 1.0, 0.0, -1.0, height - 1.0, 0.0, 0.0, 1.0))
        270 -> Matrix3(listOf(0.0, 1.0, 0.0, -1.0, 0.0, width - 1.0, 0.0, 0.0, 1.0))
        else -> throw IllegalArgumentException("Rotación no compatible: $rotation°.")
    }
}
