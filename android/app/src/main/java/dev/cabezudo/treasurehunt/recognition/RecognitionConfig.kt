package dev.cabezudo.treasurehunt.recognition

data class ImageRecognitionConfig(
    val analysesPerSecond: Double = 7.5,
    val orbMaximumFeatures: Int = 1_200,
    val orbScaleFactor: Float = 1.2f,
    val orbPyramidLevels: Int = 8,
    val orbEdgeThresholdPixels: Int = 31,
    val orbFirstLevel: Int = 0,
    val orbWtaK: Int = 2,
    val orbPatchSizePixels: Int = 31,
    val orbFastThreshold: Int = 12,
    val minimumReferenceKeypoints: Int = 120,
    val minimumFrameKeypoints: Int = 30,
    val ratioTestThreshold: Float = 0.75f,
    val minimumRatioMatches: Int = 16,
    val ransacReprojectionThresholdPixels: Double = 3.0,
    val ransacMaximumIterations: Int = 2_000,
    val ransacConfidence: Double = 0.995,
    val minimumRansacInliers: Int = 10,
    val minimumInlierRatio: Double = 0.30,
    val minimumQuadrilateralAreaFraction: Double = 0.02,
    val maximumQuadrilateralAreaFraction: Double = 1.05,
    val minimumEdgeLengthPixels: Double = 12.0,
    val maximumEdgeLengthRatio: Double = 12.0,
) {
    init {
        require(analysesPerSecond in 5.0..10.0)
        require(ratioTestThreshold in 0f..1f)
        require(minimumInlierRatio in 0.0..1.0)
        require(minimumQuadrilateralAreaFraction in 0.0..1.0)
        require(maximumQuadrilateralAreaFraction > 0.0)
        require(minimumQuadrilateralAreaFraction < maximumQuadrilateralAreaFraction)
    }

    val analysisIntervalNanos: Long
        get() = (1_000_000_000.0 / analysesPerSecond).toLong()
}
