package dev.cabezudo.treasurehunt.aruco

import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import org.opencv.core.Mat
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Dictionary
import org.opencv.objdetect.Objdetect
import org.opencv.objdetect.RefineParameters

class ArucoMarkerDetector(
    private val config: ArucoDetectionConfig = ArucoDetectionConfig(),
    private val monotonicNanos: () -> Long,
) : AutoCloseable {
    private val geometryValidator = ArucoGeometryValidator(config)
    private val rateLimiter = ArucoRateLimiter(config.minimumIntervalNanos)
    private var dictionary: Dictionary? = null
    private var parameters: DetectorParameters? = null
    private var refineParameters: RefineParameters? = null
    private var detector: ArucoDetector? = null
    private var state = ArucoDetectionState()
    private var closed = false

    @Synchronized
    fun analyzeIfDue(frame: Mat, nowNanos: Long = monotonicNanos()): ArucoDetectionState {
        if (closed) return state
        if (!rateLimiter.tryAcquire(nowNanos)) {
            state = state.copy(skippedByRateLimit = state.skippedByRateLimit + 1)
            return state
        }
        val start = monotonicNanos()
        val ids = Mat()
        val corners = mutableListOf<Mat>()
        val rejectedCandidates = mutableListOf<Mat>()
        try {
            val activeDetector = ensureInitialized()
            activeDetector.detectMarkers(frame, corners, ids, rejectedCandidates)
            val foundIds = readIds(ids)
            val markers = foundIds.mapIndexed { index, id ->
                val markerCorners = corners.getOrNull(index)?.let(::readCorners).orEmpty()
                val geometry = geometryValidator.validate(markerCorners, frame.cols(), frame.rows())
                DetectedMarker(
                    id = id,
                    corners = markerCorners,
                    geometryValid = geometry.valid,
                    areaFraction = geometry.areaFraction,
                    rejectionReason = geometry.reason,
                )
            }
            state = ArucoDetectionDecision.decide(
                markers = markers,
                rejectedCandidateCount = rejectedCandidates.size,
                durationNanos = monotonicNanos() - start,
                analyzedFrames = state.analyzedFrames + 1,
                skippedByRateLimit = state.skippedByRateLimit,
            )
        } catch (error: Throwable) {
            state = state.copy(
                status = ArucoDetectionStatus.ERROR,
                detectorInitialized = detector != null,
                totalDurationNanos = monotonicNanos() - start,
                analyzedFrames = state.analyzedFrames + 1,
                rejectionReason = ArucoRejectionReason.OPENCV_ERROR,
                error = rootCauseMessage(error),
            )
        } finally {
            ids.release()
            corners.forEach(Mat::release)
            rejectedCandidates.forEach(Mat::release)
        }
        return state
    }

    @Synchronized
    fun currentState(): ArucoDetectionState = state

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        detector?.clear()
        detector = null
        dictionary = null
        parameters = null
        refineParameters = null
        state = state.copy(status = ArucoDetectionStatus.CLOSED, error = null)
    }

    private fun ensureInitialized(): ArucoDetector {
        detector?.let { return it }
        dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
        parameters = DetectorParameters().apply {
            set_adaptiveThreshWinSizeMin(config.adaptiveThresholdWindowMinimum)
            set_adaptiveThreshWinSizeMax(config.adaptiveThresholdWindowMaximum)
            set_adaptiveThreshWinSizeStep(config.adaptiveThresholdWindowStep)
            set_adaptiveThreshConstant(config.adaptiveThresholdConstant)
            set_minMarkerPerimeterRate(config.minimumMarkerPerimeterRate)
            set_maxMarkerPerimeterRate(config.maximumMarkerPerimeterRate)
            set_polygonalApproxAccuracyRate(config.polygonalApproximationAccuracyRate)
            set_minCornerDistanceRate(config.minimumCornerDistanceRate)
            set_minDistanceToBorder(config.minimumDistanceToBorderPixels)
            set_markerBorderBits(config.markerBorderBits)
            set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
            set_cornerRefinementWinSize(config.cornerRefinementWindowSize)
            set_cornerRefinementMaxIterations(config.cornerRefinementMaximumIterations)
            set_cornerRefinementMinAccuracy(config.cornerRefinementMinimumAccuracy)
            set_errorCorrectionRate(config.errorCorrectionRate)
            set_detectInvertedMarker(false)
            set_useAruco3Detection(false)
        }
        refineParameters = RefineParameters()
        return ArucoDetector(dictionary!!, parameters!!, refineParameters!!).also {
            detector = it
            state = state.copy(detectorInitialized = true)
        }
    }

    private fun readIds(ids: Mat): List<Int> {
        if (ids.empty()) return emptyList()
        return buildList(ids.rows() * ids.cols()) {
            for (row in 0 until ids.rows()) {
                for (column in 0 until ids.cols()) {
                    val value = ids.get(row, column)
                    check(value.size == 1) { "OpenCV devolvió una matriz de IDs incompleta." }
                    add(value[0].toInt())
                }
            }
        }
    }

    private fun readCorners(corners: Mat): List<RecognitionPoint> {
        if (corners.empty() || corners.total() != 4L || corners.channels() != 2) return emptyList()
        val result = ArrayList<RecognitionPoint>(4)
        for (row in 0 until corners.rows()) {
            for (column in 0 until corners.cols()) {
                val values = corners.get(row, column)
                if (values.size != 2) return emptyList()
                result += RecognitionPoint(values[0], values[1])
            }
        }
        return result
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
