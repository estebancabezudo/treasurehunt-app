package dev.cabezudo.treasurehunt.recognition

import org.opencv.core.Core
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.geometry.Geometry

data class HomographyOutcome(
    val valid: Boolean,
    val inliers: Int,
    val inlierRatio: Double,
    val status: String,
    val corners: List<RecognitionPoint> = emptyList(),
    val rejectionReason: RecognitionRejectionReason? = null,
)

class HomographyEstimator(
    private val config: ImageRecognitionConfig,
    private val validator: QuadrilateralValidator = QuadrilateralValidator(config),
) {
    fun estimate(
        referenceKeypoints: Array<KeyPoint>,
        frameKeypoints: Array<KeyPoint>,
        matches: List<DMatch>,
        referenceWidth: Int,
        referenceHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): HomographyOutcome {
        val referencePoints = MatOfPoint2f()
        val framePoints = MatOfPoint2f()
        val inlierMask = Mat()
        var homography: Mat? = null
        val sourceCorners = MatOfPoint2f()
        val projectedCorners = MatOfPoint2f()
        try {
            referencePoints.fromArray(*matches.map { referenceKeypoints[it.queryIdx].pt }.toTypedArray())
            framePoints.fromArray(*matches.map { frameKeypoints[it.trainIdx].pt }.toTypedArray())
            val computedHomography = Geometry.findHomography(
                referencePoints,
                framePoints,
                Geometry.RANSAC,
                config.ransacReprojectionThresholdPixels,
                inlierMask,
                config.ransacMaximumIterations,
                config.ransacConfidence,
            )
            homography = computedHomography
            if (computedHomography.empty()) {
                return rejected(RecognitionRejectionReason.HOMOGRAPHY_EMPTY, "vacía")
            }
            val values = DoubleArray(9)
            if (computedHomography.get(0, 0, values) <= 0 ||
                !validator.isNonDegenerateHomography(values)
            ) {
                return rejected(RecognitionRejectionReason.HOMOGRAPHY_DEGENERATE, "degenerada")
            }

            val inliers = Core.countNonZero(inlierMask)
            val ratio = if (matches.isEmpty()) 0.0 else inliers.toDouble() / matches.size
            if (inliers < config.minimumRansacInliers) {
                return rejected(
                    RecognitionRejectionReason.INLIERS_INSUFFICIENT,
                    "rechazada: $inliers inliers",
                    inliers,
                    ratio,
                )
            }
            if (ratio < config.minimumInlierRatio) {
                return rejected(
                    RecognitionRejectionReason.INLIER_RATIO_INSUFFICIENT,
                    "rechazada: proporción de inliers",
                    inliers,
                    ratio,
                )
            }

            sourceCorners.fromArray(
                Point(0.0, 0.0),
                Point(referenceWidth.toDouble(), 0.0),
                Point(referenceWidth.toDouble(), referenceHeight.toDouble()),
                Point(0.0, referenceHeight.toDouble()),
            )
            Core.perspectiveTransform(sourceCorners, projectedCorners, computedHomography)
            val corners = projectedCorners.toArray().map { RecognitionPoint(it.x, it.y) }
            val geometry = validator.validate(corners, frameWidth, frameHeight)
            if (!geometry.valid) {
                return rejected(
                    requireNotNull(geometry.reason),
                    "rechazada: ${geometry.reason.diagnostic}",
                    inliers,
                    ratio,
                    corners,
                )
            }
            return HomographyOutcome(
                valid = true,
                inliers = inliers,
                inlierRatio = ratio,
                status = "válida (RANSAC)",
                corners = corners,
            )
        } finally {
            referencePoints.release()
            framePoints.release()
            inlierMask.release()
            homography?.release()
            sourceCorners.release()
            projectedCorners.release()
        }
    }

    private fun rejected(
        reason: RecognitionRejectionReason,
        status: String,
        inliers: Int = 0,
        ratio: Double = 0.0,
        corners: List<RecognitionPoint> = emptyList(),
    ) = HomographyOutcome(
        valid = false,
        inliers = inliers,
        inlierRatio = ratio,
        status = status,
        corners = corners,
        rejectionReason = reason,
    )
}
