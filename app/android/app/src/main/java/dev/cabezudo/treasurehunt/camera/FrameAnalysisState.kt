package dev.cabezudo.treasurehunt.camera

import dev.cabezudo.treasurehunt.recognition.ImageRecognitionState
import dev.cabezudo.treasurehunt.ocr.TextRecognitionState
import dev.cabezudo.treasurehunt.aruco.ArucoDetectionState
import java.util.ArrayDeque
import dev.cabezudo.treasurehunt.camera.calibration.CameraCalibrationState
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetectionState
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationCaptureState
import dev.cabezudo.treasurehunt.aruco.pose.ArucoPoseState

enum class FrameAnalysisStatus {
    IDLE,
    STARTING,
    RUNNING,
    PAUSED,
    CLOSED,
    ERROR,
}

data class FrameMetadata(
    val width: Int,
    val height: Int,
    val format: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
    val planeCount: Int,
    val interFrameNanos: Long?,
)

data class FrameAnalysisState(
    val status: FrameAnalysisStatus = FrameAnalysisStatus.IDLE,
    val totalFrames: Long = 0,
    val framesLastSecond: Int = 0,
    val approximateFps: Double = 0.0,
    val resolution: FrameResolution? = null,
    val rotationDegrees: Int? = null,
    val format: Int? = null,
    val planeCount: Int? = null,
    val lastFrameTimestampNanos: Long? = null,
    val lastInterFrameNanos: Long? = null,
    val lastFrameAgeMillis: Long? = null,
    val openCvStatus: OpenCvStatus = OpenCvStatus.NOT_INITIALIZED,
    val preparedFrames: Long = 0,
    val sourceResolution: FrameResolution? = null,
    val preparedResolution: FrameResolution? = null,
    val luminanceRowStride: Int? = null,
    val luminancePixelStride: Int? = null,
    val copyDurationNanos: Long? = null,
    val rotationDurationNanos: Long? = null,
    val preparationDurationNanos: Long? = null,
    val meanLuminance: Double? = null,
    val lastConversionError: String? = null,
    val recognition: ImageRecognitionState = ImageRecognitionState(),
    val textRecognition: TextRecognitionState = TextRecognitionState(),
    val arucoDetection: ArucoDetectionState = ArucoDetectionState(),
    val arucoPose: ArucoPoseState = ArucoPoseState(),
    val calibration: CameraCalibrationState = CameraCalibrationState(),
    val calibrationBoard: CalibrationBoardDetectionState = CalibrationBoardDetectionState(),
    val calibrationCapture: CalibrationCaptureState = CalibrationCaptureState(
        datasetFilePath = "no disponible",
    ),
    val lastError: String? = null,
)

/** Accumulates only current metrics and frame arrival times from the last second. */
class FrameMetricsAccumulator(
    private val publicationIntervalNanos: Long = 250_000_000L,
) {
    private val recentArrivalNanos = ArrayDeque<Long>()
    private var state = FrameAnalysisState()
    private var lastArrivalNanos: Long? = null
    private var lastPublicationNanos: Long? = null

    @Synchronized
    fun markStarting(nowNanos: Long): FrameAnalysisState = updateStatus(
        status = FrameAnalysisStatus.STARTING,
        nowNanos = nowNanos,
    )

    @Synchronized
    fun recordFrame(
        metadata: FrameMetadata,
        nowNanos: Long,
        preparation: OpenCvPreparationMetrics? = null,
        recognition: ImageRecognitionState? = null,
        arucoDetection: ArucoDetectionState? = null,
        arucoPose: ArucoPoseState? = null,
        calibrationBoard: CalibrationBoardDetectionState? = null,
    ): FrameAnalysisState {
        recentArrivalNanos.addLast(nowNanos)
        pruneOldArrivals(nowNanos)
        lastArrivalNanos = nowNanos
        state = state.copy(
            status = FrameAnalysisStatus.RUNNING,
            totalFrames = state.totalFrames + 1,
            resolution = FrameResolution(metadata.width, metadata.height),
            rotationDegrees = metadata.rotationDegrees,
            format = metadata.format,
            planeCount = metadata.planeCount,
            lastFrameTimestampNanos = metadata.timestampNanos,
            lastInterFrameNanos = metadata.interFrameNanos,
            lastFrameAgeMillis = 0,
            openCvStatus = if (preparation != null) OpenCvStatus.READY else state.openCvStatus,
            preparedFrames = state.preparedFrames + if (preparation != null) 1 else 0,
            sourceResolution = preparation?.sourceResolution ?: state.sourceResolution,
            preparedResolution = preparation?.preparedResolution ?: state.preparedResolution,
            luminanceRowStride = preparation?.rowStride ?: state.luminanceRowStride,
            luminancePixelStride = preparation?.pixelStride ?: state.luminancePixelStride,
            copyDurationNanos = preparation?.copyDurationNanos ?: state.copyDurationNanos,
            rotationDurationNanos = preparation?.rotationDurationNanos ?: state.rotationDurationNanos,
            preparationDurationNanos = preparation?.totalDurationNanos
                ?: state.preparationDurationNanos,
            meanLuminance = preparation?.meanLuminance ?: state.meanLuminance,
            lastConversionError = if (preparation != null) null else state.lastConversionError,
            recognition = recognition ?: state.recognition,
            arucoDetection = arucoDetection ?: state.arucoDetection,
            arucoPose = arucoPose ?: state.arucoPose,
            calibrationBoard = calibrationBoard ?: state.calibrationBoard,
            lastError = null,
        )
        return refreshTimeMetrics(nowNanos)
    }

    @Synchronized
    fun snapshotIfPublicationDue(nowNanos: Long): FrameAnalysisState? {
        val previous = lastPublicationNanos
        if (previous != null && nowNanos - previous < publicationIntervalNanos) return null
        lastPublicationNanos = nowNanos
        return refreshTimeMetrics(nowNanos)
    }

    @Synchronized
    fun snapshot(nowNanos: Long): FrameAnalysisState = refreshTimeMetrics(nowNanos)

    @Synchronized
    fun updateTextRecognition(
        textRecognition: TextRecognitionState,
        nowNanos: Long,
    ): FrameAnalysisState {
        state = refreshTimeMetrics(nowNanos).copy(textRecognition = textRecognition)
        return state
    }

    @Synchronized
    fun updateCalibration(
        calibration: CameraCalibrationState,
        nowNanos: Long,
    ): FrameAnalysisState {
        state = refreshTimeMetrics(nowNanos).copy(calibration = calibration)
        return state
    }

    @Synchronized
    fun updateCalibrationCapture(
        capture: CalibrationCaptureState,
        nowNanos: Long,
    ): FrameAnalysisState {
        state = refreshTimeMetrics(nowNanos).copy(calibrationCapture = capture)
        return state
    }

    @Synchronized
    fun updateArucoPose(
        pose: ArucoPoseState,
        nowNanos: Long,
    ): FrameAnalysisState {
        state = refreshTimeMetrics(nowNanos).copy(arucoPose = pose)
        return state
    }

    @Synchronized
    fun markPaused(nowNanos: Long): FrameAnalysisState = updateStatus(
        status = FrameAnalysisStatus.PAUSED,
        nowNanos = nowNanos,
    )

    @Synchronized
    fun markIdle(nowNanos: Long): FrameAnalysisState = updateStatus(
        status = FrameAnalysisStatus.IDLE,
        nowNanos = nowNanos,
    )

    @Synchronized
    fun markClosed(nowNanos: Long): FrameAnalysisState = updateStatus(
        status = FrameAnalysisStatus.CLOSED,
        nowNanos = nowNanos,
    )

    @Synchronized
    fun markError(cause: String, nowNanos: Long): FrameAnalysisState {
        state = refreshTimeMetrics(nowNanos).copy(
            status = FrameAnalysisStatus.ERROR,
            lastError = cause,
        )
        return state
    }

    @Synchronized
    fun markConversionError(
        cause: String,
        openCvStatus: OpenCvStatus,
        nowNanos: Long,
    ): FrameAnalysisState {
        state = refreshTimeMetrics(nowNanos).copy(
            status = FrameAnalysisStatus.ERROR,
            openCvStatus = openCvStatus,
            lastConversionError = cause,
            lastError = cause,
        )
        return state
    }

    private fun updateStatus(status: FrameAnalysisStatus, nowNanos: Long): FrameAnalysisState {
        state = refreshTimeMetrics(nowNanos).copy(status = status)
        return state
    }

    private fun refreshTimeMetrics(nowNanos: Long): FrameAnalysisState {
        pruneOldArrivals(nowNanos)
        val first = recentArrivalNanos.peekFirst()
        val last = recentArrivalNanos.peekLast()
        val fps = if (recentArrivalNanos.size >= 2 && first != null && last != null && last > first) {
            (recentArrivalNanos.size - 1) * 1_000_000_000.0 / (last - first)
        } else {
            0.0
        }
        state = state.copy(
            framesLastSecond = recentArrivalNanos.size,
            approximateFps = fps,
            lastFrameAgeMillis = lastArrivalNanos?.let { arrival ->
                ((nowNanos - arrival).coerceAtLeast(0L)) / 1_000_000L
            },
        )
        return state
    }

    private fun pruneOldArrivals(nowNanos: Long) {
        val cutoff = nowNanos - 1_000_000_000L
        while (recentArrivalNanos.isNotEmpty()) {
            val oldest = recentArrivalNanos.peekFirst() ?: break
            if (oldest >= cutoff) break
            recentArrivalNanos.removeFirst()
        }
    }
}
