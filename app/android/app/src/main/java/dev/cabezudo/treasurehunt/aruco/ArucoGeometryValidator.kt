package dev.cabezudo.treasurehunt.aruco

import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import kotlin.math.hypot

data class ArucoDetectionConfig(
    val maximumAnalysesPerSecond: Double = 10.0,
    val minimumAreaFraction: Double = 0.0025,
    val maximumAreaFraction: Double = 0.80,
    val minimumSideLengthPixels: Double = 8.0,
    val maximumSideLengthRatio: Double = 4.0,
    val frameToleranceFraction: Double = 0.10,
    val adaptiveThresholdWindowMinimum: Int = 3,
    val adaptiveThresholdWindowMaximum: Int = 23,
    val adaptiveThresholdWindowStep: Int = 10,
    val adaptiveThresholdConstant: Double = 7.0,
    val minimumMarkerPerimeterRate: Double = 0.03,
    val maximumMarkerPerimeterRate: Double = 4.0,
    val polygonalApproximationAccuracyRate: Double = 0.03,
    val minimumCornerDistanceRate: Double = 0.05,
    val minimumDistanceToBorderPixels: Int = 3,
    val markerBorderBits: Int = 1,
    val cornerRefinementWindowSize: Int = 5,
    val cornerRefinementMaximumIterations: Int = 30,
    val cornerRefinementMinimumAccuracy: Double = 0.1,
    val errorCorrectionRate: Double = 0.6,
) {
    init {
        require(maximumAnalysesPerSecond > 0.0)
        require(minimumAreaFraction in 0.0..1.0)
        require(maximumAreaFraction in minimumAreaFraction..1.0)
        require(minimumSideLengthPixels > 0.0)
        require(maximumSideLengthRatio >= 1.0)
        require(frameToleranceFraction >= 0.0)
    }

    val minimumIntervalNanos: Long
        get() = (1_000_000_000.0 / maximumAnalysesPerSecond).toLong()
}

data class ArucoGeometryValidation(
    val valid: Boolean,
    val areaFraction: Double? = null,
    val reason: ArucoRejectionReason? = null,
)

class ArucoGeometryValidator(private val config: ArucoDetectionConfig) {
    fun validate(
        corners: List<RecognitionPoint>,
        frameWidth: Int,
        frameHeight: Int,
    ): ArucoGeometryValidation {
        if (corners.size != 4 || frameWidth <= 0 || frameHeight <= 0) {
            return rejected(ArucoRejectionReason.INVALID_CORNERS)
        }
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return rejected(ArucoRejectionReason.NON_FINITE_CORNERS)
        }
        val toleranceX = frameWidth * config.frameToleranceFraction
        val toleranceY = frameHeight * config.frameToleranceFraction
        if (corners.any {
                it.x < -toleranceX || it.x > frameWidth + toleranceX ||
                    it.y < -toleranceY || it.y > frameHeight + toleranceY
            }
        ) {
            return rejected(ArucoRejectionReason.OUTSIDE_FRAME_TOLERANCE)
        }
        if (segmentsIntersect(corners[0], corners[1], corners[2], corners[3]) ||
            segmentsIntersect(corners[1], corners[2], corners[3], corners[0])
        ) {
            return rejected(ArucoRejectionReason.SELF_INTERSECTING)
        }
        val signedArea = signedArea(corners)
        if (signedArea <= 1e-6) {
            return rejected(ArucoRejectionReason.INCOHERENT_ORIENTATION)
        }
        val crossProducts = corners.indices.map { index ->
            cross(corners[index], corners[(index + 1) % 4], corners[(index + 2) % 4])
        }
        if (crossProducts.any { it <= 1e-6 }) {
            return rejected(ArucoRejectionReason.NON_CONVEX)
        }
        val areaFraction = signedArea / (frameWidth.toDouble() * frameHeight)
        if (areaFraction < config.minimumAreaFraction) {
            return rejected(ArucoRejectionReason.AREA_TOO_SMALL, areaFraction)
        }
        if (areaFraction > config.maximumAreaFraction) {
            return rejected(ArucoRejectionReason.AREA_TOO_LARGE, areaFraction)
        }
        val sides = corners.indices.map { index ->
            val a = corners[index]
            val b = corners[(index + 1) % 4]
            hypot(b.x - a.x, b.y - a.y)
        }
        val shortest = sides.minOrNull() ?: 0.0
        val longest = sides.maxOrNull() ?: Double.POSITIVE_INFINITY
        if (shortest < config.minimumSideLengthPixels ||
            shortest <= 0.0 || longest / shortest > config.maximumSideLengthRatio
        ) {
            return rejected(ArucoRejectionReason.DEGENERATE_QUADRILATERAL, areaFraction)
        }
        return ArucoGeometryValidation(valid = true, areaFraction = areaFraction)
    }

    private fun rejected(reason: ArucoRejectionReason, area: Double? = null) =
        ArucoGeometryValidation(valid = false, areaFraction = area, reason = reason)

    private fun signedArea(points: List<RecognitionPoint>): Double = points.indices.sumOf { index ->
        val current = points[index]
        val next = points[(index + 1) % points.size]
        current.x * next.y - next.x * current.y
    } / 2.0

    private fun cross(a: RecognitionPoint, b: RecognitionPoint, c: RecognitionPoint): Double =
        (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)

    private fun segmentsIntersect(
        a: RecognitionPoint,
        b: RecognitionPoint,
        c: RecognitionPoint,
        d: RecognitionPoint,
    ): Boolean {
        val abC = orientation(a, b, c)
        val abD = orientation(a, b, d)
        val cdA = orientation(c, d, a)
        val cdB = orientation(c, d, b)
        return abC * abD < 0.0 && cdA * cdB < 0.0
    }

    private fun orientation(a: RecognitionPoint, b: RecognitionPoint, c: RecognitionPoint) =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}

class ArucoRateLimiter(private val minimumIntervalNanos: Long) {
    private var lastAcceptedNanos: Long? = null

    fun tryAcquire(nowNanos: Long): Boolean {
        val previous = lastAcceptedNanos
        if (previous != null && nowNanos - previous < minimumIntervalNanos) return false
        lastAcceptedNanos = nowNanos
        return true
    }
}
