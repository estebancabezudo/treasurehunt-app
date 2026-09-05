package dev.cabezudo.treasurehunt.aruco.pose

import dev.cabezudo.treasurehunt.aruco.ArucoDetectionState
import dev.cabezudo.treasurehunt.aruco.ArucoDetectionStatus
import dev.cabezudo.treasurehunt.aruco.ExpectedArucoMarker
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationFrameCaptureMetadata
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationFrameCoordinateMapper
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import kotlin.math.hypot
import kotlin.math.sqrt
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.geometry.Geometry

class ArucoPoseEstimator(
    private val baseCalibration: PoseCalibrationState,
    private val coordinateMapper: CalibrationFrameCoordinateMapper =
        CalibrationFrameCoordinateMapper(),
    private val sizeValidator: PhysicalMarkerSizeValidator = PhysicalMarkerSizeValidator(),
    private val monotonicNanos: () -> Long,
) : AutoCloseable {
    private var physicalSize: PhysicalMarkerSize? = null
    private var state = initialState(baseCalibration)
    private var closed = false

    @Synchronized
    fun confirmPhysicalSize(size: PhysicalMarkerSize) {
        check(!closed)
        physicalSize = size
        state = state.copy(physicalMarkerSize = size, result = null, error = null)
    }

    @Synchronized
    fun currentState(): ArucoPoseState = state

    @Synchronized
    fun estimate(
        aruco: ArucoDetectionState,
        metadata: CalibrationFrameCaptureMetadata,
    ): ArucoPoseState {
        if (closed) return state
        val frameCalibration = PoseCalibrationLoader.isCompatibleWithFrame(
            calibration = baseCalibration,
            cameraId = metadata.cameraId,
            bufferWidth = metadata.bufferWidth,
            bufferHeight = metadata.bufferHeight,
            cropRect = metadata.cropRect,
        )
        if (frameCalibration.status != PoseCalibrationStatus.READY) {
            state = state.copy(
                status = if (frameCalibration.status == PoseCalibrationStatus.INCOMPATIBLE) {
                    ArucoPoseStatus.CALIBRATION_INCOMPATIBLE
                } else {
                    ArucoPoseStatus.CALIBRATION_NOT_AVAILABLE
                },
                calibration = frameCalibration,
                result = null,
                rejectionReason = null,
                error = frameCalibration.cause,
            )
            return state
        }
        if (aruco.status != ArucoDetectionStatus.DETECTED || aruco.expectedCorners.size != 4) {
            state = state.copy(
                status = ArucoPoseStatus.SEARCHING,
                calibration = frameCalibration,
                result = null,
                rejectionReason = ArucoPoseRejectionReason.MARKER_NOT_DETECTED,
                error = null,
            )
            return state
        }
        val size = physicalSize
        when (sizeValidator.rejection(size)) {
            ArucoPoseStatus.PHYSICAL_SIZE_NOT_CONFIRMED -> return updateSizeFailure(
                ArucoPoseStatus.PHYSICAL_SIZE_NOT_CONFIRMED,
                "Mide y confirma el ancho y alto exteriores del cuadrado negro.",
            )
            ArucoPoseStatus.PHYSICAL_MARKER_DEFORMED -> return updateSizeFailure(
                ArucoPoseStatus.PHYSICAL_MARKER_DEFORMED,
                "Ancho y alto difieren más del 2 %; IPPE_SQUARE requiere un cuadrado físico.",
            )
            ArucoPoseStatus.REJECTED -> return updateRejected(
                ArucoPoseRejectionReason.INVALID_PHYSICAL_SIZE,
                "El tamaño físico debe ser finito y positivo.",
            )
            else -> Unit
        }
        val validSize = requireNotNull(size)
        val canonicalCorners = try {
            val canonicalCorners = aruco.expectedCorners.map { corner ->
                coordinateMapper.preparedToCanonical(corner, metadata).also { canonical ->
                    val roundTrip = coordinateMapper.canonicalToPrepared(canonical, metadata)
                    require(hypot(roundTrip.x - corner.x, roundTrip.y - corner.y) <= 1e-6) {
                        "El round trip de una esquina excede 1e-6 px."
                    }
                }
            }
            require(PoseGeometryValidator.hasValidIppeCornerOrder(canonicalCorners)) {
                "El orden canónico de las esquinas no corresponde a TL,TR,BR,BL."
            }
            canonicalCorners
        } catch (error: IllegalArgumentException) {
            return updateRejected(
                if (error.message?.contains("orden") == true) {
                    ArucoPoseRejectionReason.INVALID_CORNER_ORDER
                } else {
                    ArucoPoseRejectionReason.COORDINATE_TRANSFORM_FAILED
                },
                rootCauseMessage(error),
            )
        }
        val start = monotonicNanos()
        return try {
            calculate(
                canonicalCorners = canonicalCorners,
                metadata = metadata,
                size = validSize,
                calibration = frameCalibration,
                startNanos = start,
            )
        } catch (error: Throwable) {
            state = state.copy(
                status = ArucoPoseStatus.ERROR,
                calibration = frameCalibration,
                result = null,
                rejectionReason = ArucoPoseRejectionReason.OPENCV_ERROR,
                error = rootCauseMessage(error),
            )
            state
        }
    }

    private fun calculate(
        canonicalCorners: List<RecognitionPoint>,
        metadata: CalibrationFrameCaptureMetadata,
        size: PhysicalMarkerSize,
        calibration: PoseCalibrationState,
        startNanos: Long,
    ): ArucoPoseState {
        val run = requireNotNull(calibration.result).primary
        val objectPoints = MatOfPoint3f()
        val imagePoints = MatOfPoint2f()
        val intrinsic = Mat(3, 3, CvType.CV_64FC1)
        val distortion = MatOfDouble()
        val rvec = Mat()
        val tvec = Mat()
        val rotation = Mat()
        val projectedCornersMat = MatOfPoint2f()
        val axesObject = MatOfPoint3f()
        val projectedAxesMat = MatOfPoint2f()
        try {
            objectPoints.fromArray(*IppeSquareObjectPoints.create(size.meanSideMillimeters).map {
                Point3(it.x, it.y, it.z)
            }.toTypedArray())
            imagePoints.fromArray(*canonicalCorners.map { Point(it.x, it.y) }.toTypedArray())
            check(intrinsic.put(0, 0, *run.intrinsicMatrix.toDoubleArray()) == 9)
            distortion.fromArray(*run.distortionCoefficients.toDoubleArray())
            val solved = Geometry.solvePnP(
                objectPoints,
                imagePoints,
                intrinsic,
                distortion,
                rvec,
                tvec,
                false,
                Geometry.SOLVEPNP_IPPE_SQUARE,
            )
            if (!solved) return updateRejected(ArucoPoseRejectionReason.SOLVE_PNP_FAILED, null)
            val r = readVector(rvec)
            val t = readVector(tvec)
            Geometry.Rodrigues(rvec, rotation)
            val rotationValues = readMatrix(rotation, 3, 3)
            Geometry.projectPoints(
                objectPoints, rvec, tvec, intrinsic, distortion, projectedCornersMat,
            )
            val projectedCanonicalCorners = projectedCornersMat.toRecognitionPoints()
            val rms = reprojectionRms(canonicalCorners, projectedCanonicalCorners)
            val rejection = PoseResultValidator.rejection(
                rotationVector = r,
                translation = t,
                rotationMatrix = rotationValues,
                reprojectionRms = rms,
                projectedCorners = projectedCanonicalCorners,
            )
            if (rejection != null) return updateRejected(rejection, rejection.diagnostic)

            axesObject.fromArray(*IppeSquareObjectPoints.axes(size.meanSideMillimeters).map {
                Point3(it.x, it.y, it.z)
            }.toTypedArray())
            Geometry.projectPoints(axesObject, rvec, tvec, intrinsic, distortion, projectedAxesMat)
            val preparedAxes = projectedAxesMat.toRecognitionPoints().map {
                coordinateMapper.canonicalToPrepared(it, metadata)
            }
            val identity = requireNotNull(calibration.result).identity
            val result = ArucoPoseResult(
                translationMillimeters = t,
                euclideanDistanceMillimeters = sqrt(t.sumOf { it * it }),
                rotationVector = r,
                rotationMatrix = rotationValues,
                eulerDegreesXyz = PoseResultValidator.eulerDegreesXyz(rotationValues),
                reprojectionRmsPixels = rms,
                canonicalCorners = canonicalCorners,
                projectedCanonicalCorners = projectedCanonicalCorners,
                projectedAxesPrepared = preparedAxes,
                calculationDurationNanos = monotonicNanos() - startNanos,
            )
            state = ArucoPoseState(
                status = ArucoPoseStatus.VALID,
                calibration = calibration,
                physicalMarkerSize = size,
                testIdentity = ArucoPoseTestIdentity(
                    cameraId = identity.cameraId,
                    canonicalWidth = identity.bufferWidth,
                    canonicalHeight = identity.bufferHeight,
                    markerDictionary = ExpectedArucoMarker.DICTIONARY_NAME,
                    markerId = ExpectedArucoMarker.ID,
                    measuredWidthMillimeters = size.widthMillimeters,
                    measuredHeightMillimeters = size.heightMillimeters,
                    calibrationSha256 = requireNotNull(calibration.resultSha256),
                ),
                result = result,
            )
            return state
        } finally {
            objectPoints.release()
            imagePoints.release()
            intrinsic.release()
            distortion.release()
            rvec.release()
            tvec.release()
            rotation.release()
            projectedCornersMat.release()
            axesObject.release()
            projectedAxesMat.release()
        }
    }

    private fun updateSizeFailure(status: ArucoPoseStatus, cause: String): ArucoPoseState {
        state = state.copy(
            status = status,
            physicalMarkerSize = physicalSize,
            result = null,
            rejectionReason = null,
            error = cause,
        )
        return state
    }

    private fun updateRejected(
        reason: ArucoPoseRejectionReason,
        cause: String?,
    ): ArucoPoseState {
        state = state.copy(
            status = ArucoPoseStatus.REJECTED,
            physicalMarkerSize = physicalSize,
            result = null,
            rejectionReason = reason,
            error = cause,
        )
        return state
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        state = state.copy(status = ArucoPoseStatus.CLOSED, result = null, error = null)
    }

    private fun MatOfPoint2f.toRecognitionPoints(): List<RecognitionPoint> =
        toArray().map { RecognitionPoint(it.x, it.y) }

    private fun readVector(mat: Mat): List<Double> {
        require(mat.total() * mat.channels() == 3L) {
            "Vector OpenCV inválido: ${mat.rows()}×${mat.cols()}, canales=${mat.channels()}."
        }
        require(mat.channels() == 1) { "El vector OpenCV debe tener un canal." }
        return buildList {
            for (row in 0 until mat.rows()) for (column in 0 until mat.cols()) {
                add(requireNotNull(mat.get(row, column)).single())
            }
        }.also { require(it.size == 3) { "OpenCV no entregó los tres valores del vector." } }
    }

    private fun readMatrix(mat: Mat, rows: Int, columns: Int): List<Double> {
        require(mat.rows() == rows && mat.cols() == columns && mat.channels() == 1) {
            "Matriz OpenCV inválida: ${mat.rows()}×${mat.cols()}, canales=${mat.channels()}."
        }
        return buildList {
            for (row in 0 until rows) for (column in 0 until columns) {
                add(requireNotNull(mat.get(row, column)).single())
            }
        }
    }

    private fun reprojectionRms(
        observed: List<RecognitionPoint>,
        projected: List<RecognitionPoint>,
    ): Double {
        require(observed.size == 4 && projected.size == 4) {
            "La reproyección requiere cuatro puntos observados y cuatro proyectados."
        }
        return sqrt(observed.zip(projected).sumOf { (a, b) ->
            val dx = a.x - b.x
            val dy = a.y - b.y
            dx * dx + dy * dy
        } / observed.size)
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}

private fun initialState(calibration: PoseCalibrationState): ArucoPoseState = ArucoPoseState(
    status = when (calibration.status) {
        PoseCalibrationStatus.READY -> ArucoPoseStatus.PHYSICAL_SIZE_NOT_CONFIRMED
        PoseCalibrationStatus.INCOMPATIBLE -> ArucoPoseStatus.CALIBRATION_INCOMPATIBLE
        PoseCalibrationStatus.NOT_AVAILABLE,
        PoseCalibrationStatus.ERROR,
        -> ArucoPoseStatus.CALIBRATION_NOT_AVAILABLE
    },
    calibration = calibration,
    error = calibration.cause,
)
