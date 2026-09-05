package dev.cabezudo.treasurehunt.calibrationboard

import dev.cabezudo.treasurehunt.camera.FrameResolution
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.objdetect.Objdetect

class CalibrationBoardDetector(
    private val config: CalibrationBoardConfig = CalibrationBoardConfig(),
    private val geometryValidator: CalibrationBoardGeometryValidator =
        CalibrationBoardGeometryValidator(config),
    private val monotonicNanos: () -> Long,
) : AutoCloseable {
    private val rateLimiter = CalibrationBoardRateLimiter(config.analysesPerSecond)
    private var state = CalibrationBoardDetectionState(
        status = CalibrationBoardDetectionStatus.SEARCHING,
    )
    private var closed = false

    fun analyzeIfDue(grayFrame: Mat, nowNanos: Long): CalibrationBoardDetectionState {
        if (closed) return state
        if (!rateLimiter.shouldAnalyze(nowNanos)) {
            state = state.copy(
                skippedByRateLimit = state.skippedByRateLimit + 1,
                analyzedThisFrame = false,
            )
            return state
        }
        val start = monotonicNanos()
        val detectedCorners = Mat()
        try {
            require(!grayFrame.empty() && grayFrame.cols() > 0 && grayFrame.rows() > 0) {
                "El cuadro monocromático está vacío."
            }
            val found = Objdetect.findChessboardCornersSB(
                grayFrame,
                Size(config.internalColumns.toDouble(), config.internalRows.toDouble()),
                detectedCorners,
                Objdetect.CALIB_CB_NORMALIZE_IMAGE or
                    Objdetect.CALIB_CB_EXHAUSTIVE or
                    Objdetect.CALIB_CB_ACCURACY,
            )
            val analyzed = state.analyzedFrames + 1
            if (!found) {
                state = state.copy(
                    status = CalibrationBoardDetectionStatus.SEARCHING,
                    corners = emptyList(),
                    frameResolution = FrameResolution(grayFrame.cols(), grayFrame.rows()),
                    areaFraction = null,
                    center = null,
                    approximateWidthPixels = null,
                    approximateHeightPixels = null,
                    durationNanos = monotonicNanos() - start,
                    analyzedFrames = analyzed,
                    rejectionReason = CalibrationBoardRejectionReason.BOARD_NOT_FOUND,
                    error = null,
                    analyzedThisFrame = true,
                    analyzedAtNanos = nowNanos,
                )
                return state
            }
            val values = FloatArray((detectedCorners.total() * detectedCorners.channels()).toInt())
            detectedCorners.get(0, 0, values)
            val corners = values.toList().chunked(2).map { pair ->
                RecognitionPoint(pair[0].toDouble(), pair[1].toDouble())
            }
            val geometry = geometryValidator.validate(corners, grayFrame.cols(), grayFrame.rows())
            state = state.copy(
                status = if (geometry.valid) {
                    CalibrationBoardDetectionStatus.DETECTED
                } else {
                    CalibrationBoardDetectionStatus.REJECTED
                },
                corners = corners,
                frameResolution = FrameResolution(grayFrame.cols(), grayFrame.rows()),
                areaFraction = geometry.areaFraction,
                center = geometry.center,
                approximateWidthPixels = geometry.approximateWidthPixels,
                approximateHeightPixels = geometry.approximateHeightPixels,
                durationNanos = monotonicNanos() - start,
                analyzedFrames = analyzed,
                rejectionReason = geometry.rejectionReason,
                error = null,
                analyzedThisFrame = true,
                analyzedAtNanos = nowNanos,
            )
        } catch (error: Exception) {
            state = state.copy(
                status = CalibrationBoardDetectionStatus.ERROR,
                durationNanos = monotonicNanos() - start,
                analyzedFrames = state.analyzedFrames + 1,
                rejectionReason = CalibrationBoardRejectionReason.OPENCV_ERROR,
                error = rootCauseMessage(error),
                analyzedThisFrame = true,
                analyzedAtNanos = nowNanos,
            )
        } finally {
            detectedCorners.release()
        }
        return state
    }

    fun currentState(): CalibrationBoardDetectionState = state

    override fun close() {
        if (closed) return
        closed = true
        state = state.copy(status = CalibrationBoardDetectionStatus.CLOSED)
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
