package dev.cabezudo.treasurehunt.recognition

import org.opencv.core.Core
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features.BFMatcher
import org.opencv.features.ORB

class OrbFeatureSet(
    val keypoints: MatOfKeyPoint,
    val descriptors: Mat,
) : AutoCloseable {
    val keypointCount: Int get() = keypoints.rows()

    override fun close() {
        keypoints.release()
        descriptors.release()
    }
}

class OrbFeatureExtractor(config: ImageRecognitionConfig) : AutoCloseable {
    private val orb = ORB.create(
        config.orbMaximumFeatures,
        config.orbScaleFactor,
        config.orbPyramidLevels,
        config.orbEdgeThresholdPixels,
        config.orbFirstLevel,
        config.orbWtaK,
        ORB.HARRIS_SCORE,
        config.orbPatchSizePixels,
        config.orbFastThreshold,
    )
    private var closed = false

    fun extract(grayscale: Mat): OrbFeatureSet {
        check(!closed) { "El extractor ORB está cerrado." }
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val mask = Mat()
        try {
            orb.detectAndCompute(grayscale, mask, keypoints, descriptors)
            return OrbFeatureSet(keypoints, descriptors)
        } catch (error: Throwable) {
            keypoints.release()
            descriptors.release()
            throw error
        } finally {
            mask.release()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        orb.clear()
    }
}

data class OrbMatchSummary(
    val knnMatchCount: Int,
    val accepted: List<DMatch>,
)

class OrbFeatureMatcher(private val ratioThreshold: Float) : AutoCloseable {
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
    private var closed = false

    fun compare(referenceDescriptors: Mat, frameDescriptors: Mat): OrbMatchSummary {
        check(!closed) { "El matcher ORB está cerrado." }
        if (referenceDescriptors.empty() || frameDescriptors.empty()) {
            return OrbMatchSummary(0, emptyList())
        }
        val neighbors = ArrayList<MatOfDMatch>()
        return try {
            matcher.knnMatch(referenceDescriptors, frameDescriptors, neighbors, 2)
            val accepted = ArrayList<DMatch>()
            neighbors.forEach { pair ->
                val matches = pair.toArray()
                if (matches.size >= 2 && matches[0].distance < ratioThreshold * matches[1].distance) {
                    accepted += matches[0]
                }
            }
            OrbMatchSummary(neighbors.size, accepted)
        } finally {
            neighbors.forEach(MatOfDMatch::release)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        matcher.clear()
    }
}
