package dev.cabezudo.treasurehunt.recognition

import android.content.Context
import org.opencv.core.Mat

class ReferenceImageDetector(
    context: Context,
    private val config: ImageRecognitionConfig = ImageRecognitionConfig(),
    private val monotonicNanos: () -> Long,
) : AutoCloseable {
    private val loader = ReferenceImageLoader(context.applicationContext)
    private val homographyEstimator = HomographyEstimator(config)
    private var extractor: OrbFeatureExtractor? = null
    private var matcher: OrbFeatureMatcher? = null
    private var reference: ReferenceFeatures? = null
    private var state = ImageRecognitionState()
    private var lastAnalysisNanos: Long? = null
    private var closed = false

    fun currentState(): ImageRecognitionState = state

    fun analyzeIfDue(frame: Mat, nowNanos: Long): ImageRecognitionState? {
        check(!closed) { "El detector de referencia está cerrado." }
        val previous = lastAnalysisNanos
        if (previous != null && nowNanos - previous < config.analysisIntervalNanos) return null
        lastAnalysisNanos = nowNanos
        val totalStart = monotonicNanos()
        return try {
            val preparedReference = reference ?: initializeReference()
            if (preparedReference.features.keypointCount < config.minimumReferenceKeypoints) {
                updateRejected(
                    RecognitionRejectionReason.REFERENCE_FEATURES_INSUFFICIENT,
                    preparedReference,
                    totalStart,
                )
            } else {
                analyzeFrame(frame, preparedReference, totalStart)
            }
        } catch (error: Throwable) {
            state = state.copy(
                status = ImageRecognitionStatus.ERROR,
                error = "No se pudo analizar la referencia: ${rootCauseMessage(error)}",
                rejectionReason = null,
                processedFrames = state.processedFrames + 1,
                totalDurationNanos = monotonicNanos() - totalStart,
            )
            state
        }
    }

    private fun initializeReference(): ReferenceFeatures {
        ensureNativeComponents()
        val loaded = loader.load()
        try {
            val features = requireNotNull(extractor).extract(loaded.grayscale)
            val prepared = ReferenceFeatures(
                name = loaded.name,
                width = loaded.width,
                height = loaded.height,
                features = features,
            )
            reference = prepared
            state = state.copy(
                status = ImageRecognitionStatus.SEARCHING,
                detectorInitialized = true,
                referenceLoaded = true,
                referenceName = prepared.name,
                referenceWidth = prepared.width,
                referenceHeight = prepared.height,
                referenceKeypoints = features.keypointCount,
                error = null,
            )
            return prepared
        } finally {
            loaded.close()
        }
    }

    private fun analyzeFrame(
        frame: Mat,
        preparedReference: ReferenceFeatures,
        totalStart: Long,
    ): ImageRecognitionState {
        val extractionStart = monotonicNanos()
        requireNotNull(extractor).extract(frame).use { frameFeatures ->
            val extractionDuration = monotonicNanos() - extractionStart
            if (frameFeatures.keypointCount < config.minimumFrameKeypoints) {
                return updateRejected(
                    reason = RecognitionRejectionReason.FRAME_FEATURES_INSUFFICIENT,
                    reference = preparedReference,
                    totalStart = totalStart,
                    frameKeypoints = frameFeatures.keypointCount,
                    extractionDuration = extractionDuration,
                )
            }

            val matchingStart = monotonicNanos()
            val matches = requireNotNull(matcher).compare(
                preparedReference.features.descriptors,
                frameFeatures.descriptors,
            )
            val matchingDuration = monotonicNanos() - matchingStart
            if (matches.accepted.size < config.minimumRatioMatches) {
                return updateRejected(
                    reason = RecognitionRejectionReason.MATCHES_INSUFFICIENT,
                    reference = preparedReference,
                    totalStart = totalStart,
                    frameKeypoints = frameFeatures.keypointCount,
                    knnMatches = matches.knnMatchCount,
                    ratioMatches = matches.accepted.size,
                    extractionDuration = extractionDuration,
                    matchingDuration = matchingDuration,
                )
            }

            val homographyStart = monotonicNanos()
            val outcome = homographyEstimator.estimate(
                referenceKeypoints = preparedReference.features.keypoints.toArray(),
                frameKeypoints = frameFeatures.keypoints.toArray(),
                matches = matches.accepted,
                referenceWidth = preparedReference.width,
                referenceHeight = preparedReference.height,
                frameWidth = frame.cols(),
                frameHeight = frame.rows(),
            )
            val homographyDuration = monotonicNanos() - homographyStart
            state = state.copy(
                status = if (outcome.valid) {
                    ImageRecognitionStatus.DETECTED
                } else {
                    ImageRecognitionStatus.REJECTED
                },
                detectorInitialized = true,
                referenceLoaded = true,
                referenceName = preparedReference.name,
                referenceWidth = preparedReference.width,
                referenceHeight = preparedReference.height,
                referenceKeypoints = preparedReference.features.keypointCount,
                frameKeypoints = frameFeatures.keypointCount,
                knnMatches = matches.knnMatchCount,
                ratioAcceptedMatches = matches.accepted.size,
                ransacInliers = outcome.inliers,
                inlierRatio = outcome.inlierRatio,
                homographyStatus = outcome.status,
                corners = outcome.corners,
                extractionDurationNanos = extractionDuration,
                matchingDurationNanos = matchingDuration,
                homographyDurationNanos = homographyDuration,
                totalDurationNanos = monotonicNanos() - totalStart,
                processedFrames = state.processedFrames + 1,
                rejectionReason = outcome.rejectionReason,
                error = null,
            )
            return state
        }
    }

    private fun updateRejected(
        reason: RecognitionRejectionReason,
        reference: ReferenceFeatures,
        totalStart: Long,
        frameKeypoints: Int = 0,
        knnMatches: Int = 0,
        ratioMatches: Int = 0,
        extractionDuration: Long? = null,
        matchingDuration: Long? = null,
    ): ImageRecognitionState {
        state = state.copy(
            status = ImageRecognitionStatus.REJECTED,
            detectorInitialized = true,
            referenceLoaded = true,
            referenceName = reference.name,
            referenceWidth = reference.width,
            referenceHeight = reference.height,
            referenceKeypoints = reference.features.keypointCount,
            frameKeypoints = frameKeypoints,
            knnMatches = knnMatches,
            ratioAcceptedMatches = ratioMatches,
            ransacInliers = 0,
            inlierRatio = 0.0,
            homographyStatus = "no calculada",
            corners = emptyList(),
            extractionDurationNanos = extractionDuration,
            matchingDurationNanos = matchingDuration,
            homographyDurationNanos = null,
            totalDurationNanos = monotonicNanos() - totalStart,
            processedFrames = state.processedFrames + 1,
            rejectionReason = reason,
            error = null,
        )
        return state
    }

    override fun close() {
        if (closed) return
        closed = true
        reference?.close()
        reference = null
        matcher?.close()
        matcher = null
        extractor?.close()
        extractor = null
    }

    private fun ensureNativeComponents() {
        if (extractor == null) extractor = OrbFeatureExtractor(config)
        if (matcher == null) matcher = OrbFeatureMatcher(config.ratioTestThreshold)
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }

    private class ReferenceFeatures(
        val name: String,
        val width: Int,
        val height: Int,
        val features: OrbFeatureSet,
    ) : AutoCloseable {
        override fun close() = features.close()
    }
}
