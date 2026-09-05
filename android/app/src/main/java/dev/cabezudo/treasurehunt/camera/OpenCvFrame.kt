package dev.cabezudo.treasurehunt.camera

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import dev.cabezudo.treasurehunt.recognition.ImageRecognitionState
import dev.cabezudo.treasurehunt.recognition.ImageRecognitionStatus
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import dev.cabezudo.treasurehunt.aruco.ArucoDetectionState
import dev.cabezudo.treasurehunt.aruco.ArucoDetectionStatus
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetectionState
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetectionStatus
import dev.cabezudo.treasurehunt.aruco.pose.ArucoPoseState
import dev.cabezudo.treasurehunt.aruco.pose.ArucoPoseStatus

enum class OpenCvStatus {
    NOT_INITIALIZED,
    READY,
    ERROR,
}

data class OpenCvPreparationMetrics(
    val sourceResolution: FrameResolution,
    val preparedResolution: FrameResolution,
    val rowStride: Int,
    val pixelStride: Int,
    val copyDurationNanos: Long,
    val rotationDurationNanos: Long,
    val totalDurationNanos: Long,
    val meanLuminance: Double,
)

data class MonochromeFrameSample(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val detectionOverlays: List<DetectionSampleOverlay> = emptyList(),
)

enum class DetectionOverlayKind {
    ORB,
    ARUCO_EXPECTED,
    ARUCO_UNEXPECTED,
    ARUCO_POSE_AXES,
    CALIBRATION_BOARD,
}

data class DetectionSampleOverlay(
    val corners: List<RecognitionPoint>,
    val label: String,
    val kind: DetectionOverlayKind,
    val gridColumns: Int? = null,
    val gridRows: Int? = null,
)

class OpenCvFrame internal constructor(
    val mat: Mat,
    val metrics: OpenCvPreparationMetrics,
) : AutoCloseable {
    private var released = false

    fun createDiagnosticSample(
        recognition: ImageRecognitionState? = null,
        aruco: ArucoDetectionState? = null,
        arucoPose: ArucoPoseState? = null,
        calibrationBoard: CalibrationBoardDetectionState? = null,
        maxDimension: Int = 160,
    ): MonochromeFrameSample {
        check(!released) { "El Mat del cuadro ya fue liberado." }
        require(maxDimension > 0) { "El tamaño máximo de muestra debe ser positivo." }
        val width = metrics.preparedResolution.width
        val height = metrics.preparedResolution.height
        val step = maxOf(1, (maxOf(width, height) + maxDimension - 1) / maxDimension)
        val sampleWidth = (width + step - 1) / step
        val sampleHeight = (height + step - 1) / step
        val fullFrame = ByteArray(width * height)
        val read = mat.get(0, 0, fullFrame)
        check(read == fullFrame.size) {
            "OpenCV leyó $read de ${fullFrame.size} bytes para la muestra."
        }
        val sample = ByteArray(sampleWidth * sampleHeight)
        var destination = 0
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                sample[destination++] = fullFrame[y * width + x]
            }
        }
        val overlays = mutableListOf<DetectionSampleOverlay>()
        recognition
            ?.takeIf { it.status == ImageRecognitionStatus.DETECTED && it.corners.size == 4 }
            ?.let {
                overlays += DetectionSampleOverlay(
                    corners = it.corners.map { corner ->
                        RecognitionPoint(corner.x / step, corner.y / step)
                    },
                    label = "DETECTED",
                    kind = DetectionOverlayKind.ORB,
                )
            }
        aruco?.markers.orEmpty().forEach { marker ->
            if (marker.corners.size == 4) {
                val expected = marker.id == aruco?.expectedId &&
                    aruco.status == ArucoDetectionStatus.DETECTED
                overlays += DetectionSampleOverlay(
                    corners = marker.corners.map { corner ->
                        RecognitionPoint(corner.x / step, corner.y / step)
                    },
                    label = "ARUCO ${marker.id}",
                    kind = if (expected) {
                        DetectionOverlayKind.ARUCO_EXPECTED
                    } else {
                        DetectionOverlayKind.ARUCO_UNEXPECTED
                    },
                )
            }
        }
        arucoPose
            ?.takeIf { it.status == ArucoPoseStatus.VALID }
            ?.result
            ?.projectedAxesPrepared
            ?.takeIf { it.size == 4 }
            ?.let { axes ->
                overlays += DetectionSampleOverlay(
                    corners = axes.map { point ->
                        RecognitionPoint(point.x / step, point.y / step)
                    },
                    label = "POSE AXES",
                    kind = DetectionOverlayKind.ARUCO_POSE_AXES,
                )
            }
        calibrationBoard
            ?.takeIf {
                it.status == CalibrationBoardDetectionStatus.DETECTED && it.corners.size == 54
            }
            ?.let {
                overlays += DetectionSampleOverlay(
                    corners = it.corners.map { corner ->
                        RecognitionPoint(corner.x / step, corner.y / step)
                    },
                    label = "BOARD DETECTED",
                    kind = DetectionOverlayKind.CALIBRATION_BOARD,
                    gridColumns = 9,
                    gridRows = 6,
                )
            }
        return MonochromeFrameSample(sampleWidth, sampleHeight, sample, overlays)
    }

    override fun close() {
        if (released) return
        released = true
        mat.release()
    }
}

class OpenCvFrameConverter(
    private val copier: LuminancePlaneCopier = LuminancePlaneCopier(),
    private val monotonicNanos: () -> Long,
) {
    fun convert(plane: LuminancePlane, rotationDegrees: Int): OpenCvFrame {
        val totalStart = monotonicNanos()
        val copyStart = totalStart
        val copied = copier.copy(plane)
        val copyDuration = monotonicNanos() - copyStart

        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "Rotación no compatible: $rotationDegrees°."
        }
        var sourceMat: Mat? = Mat(copied.height, copied.width, CvType.CV_8UC1)
        var resultMat: Mat? = null
        try {
            val written = sourceMat!!.put(0, 0, copied.pixels)
            check(written == copied.pixels.size) {
                "OpenCV escribió $written de ${copied.pixels.size} bytes."
            }

            val rotationStart = monotonicNanos()
            val preparedWidth: Int
            val preparedHeight: Int
            if (rotationDegrees == 0) {
                resultMat = sourceMat
                sourceMat = null
                preparedWidth = copied.width
                preparedHeight = copied.height
            } else {
                resultMat = Mat()
                val rotationCode = when (rotationDegrees) {
                    90 -> Core.ROTATE_90_CLOCKWISE
                    180 -> Core.ROTATE_180
                    else -> Core.ROTATE_90_COUNTERCLOCKWISE
                }
                Core.rotate(sourceMat, resultMat, rotationCode)
                preparedWidth = resultMat.cols()
                preparedHeight = resultMat.rows()
            }
            val rotationDuration = monotonicNanos() - rotationStart
            sourceMat?.release()
            sourceMat = null

            val mean = Core.mean(resultMat).`val`[0]
            val ownedResult = resultMat
            val frame = OpenCvFrame(
                mat = ownedResult,
                metrics = OpenCvPreparationMetrics(
                    sourceResolution = FrameResolution(copied.width, copied.height),
                    preparedResolution = FrameResolution(preparedWidth, preparedHeight),
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    copyDurationNanos = copyDuration,
                    rotationDurationNanos = rotationDuration,
                    totalDurationNanos = monotonicNanos() - totalStart,
                    meanLuminance = mean,
                ),
            )
            resultMat = null
            return frame
        } catch (error: Throwable) {
            sourceMat?.release()
            resultMat?.release()
            throw error
        }
    }
}
