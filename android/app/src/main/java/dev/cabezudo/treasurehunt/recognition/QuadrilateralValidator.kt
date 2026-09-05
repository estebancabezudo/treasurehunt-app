package dev.cabezudo.treasurehunt.recognition

import kotlin.math.abs
import kotlin.math.hypot

data class GeometryValidation(
    val valid: Boolean,
    val reason: RecognitionRejectionReason? = null,
    val areaFraction: Double? = null,
)

class QuadrilateralValidator(private val config: ImageRecognitionConfig) {
    fun validate(
        corners: List<RecognitionPoint>,
        frameWidth: Int,
        frameHeight: Int,
    ): GeometryValidation {
        if (corners.size != 4 || frameWidth <= 0 || frameHeight <= 0) {
            return rejected(RecognitionRejectionReason.HOMOGRAPHY_DEGENERATE)
        }
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return rejected(RecognitionRejectionReason.CORNERS_NON_FINITE)
        }
        if (segmentsIntersect(corners[0], corners[1], corners[2], corners[3]) ||
            segmentsIntersect(corners[1], corners[2], corners[3], corners[0])
        ) {
            return rejected(RecognitionRejectionReason.QUADRILATERAL_SELF_INTERSECTING)
        }

        val signedArea = signedArea(corners)
        if (signedArea <= 0.0) {
            return rejected(RecognitionRejectionReason.QUADRILATERAL_INVERTED)
        }
        val crossProducts = corners.indices.map { index ->
            cross(
                corners[index],
                corners[(index + 1) % 4],
                corners[(index + 2) % 4],
            )
        }
        if (crossProducts.any { it <= 1e-6 }) {
            return rejected(RecognitionRejectionReason.QUADRILATERAL_NON_CONVEX)
        }

        val frameArea = frameWidth.toDouble() * frameHeight
        val areaFraction = signedArea / frameArea
        if (areaFraction < config.minimumQuadrilateralAreaFraction) {
            return rejected(RecognitionRejectionReason.QUADRILATERAL_TOO_SMALL, areaFraction)
        }
        if (areaFraction > config.maximumQuadrilateralAreaFraction) {
            return rejected(RecognitionRejectionReason.QUADRILATERAL_TOO_LARGE, areaFraction)
        }

        val edges = corners.indices.map { index ->
            val start = corners[index]
            val end = corners[(index + 1) % 4]
            hypot(end.x - start.x, end.y - start.y)
        }
        val shortest = edges.minOrNull() ?: 0.0
        val longest = edges.maxOrNull() ?: Double.POSITIVE_INFINITY
        if (shortest < config.minimumEdgeLengthPixels ||
            shortest == 0.0 || longest / shortest > config.maximumEdgeLengthRatio
        ) {
            return rejected(
                RecognitionRejectionReason.QUADRILATERAL_DISPROPORTIONATE,
                areaFraction,
            )
        }
        return GeometryValidation(valid = true, areaFraction = areaFraction)
    }

    fun isNonDegenerateHomography(values: DoubleArray): Boolean {
        if (values.size != 9 || values.any { !it.isFinite() }) return false
        val scale = values[8]
        if (abs(scale) < 1e-12) return false
        val h = DoubleArray(9) { values[it] / scale }
        val determinant =
            h[0] * (h[4] * h[8] - h[5] * h[7]) -
                h[1] * (h[3] * h[8] - h[5] * h[6]) +
                h[2] * (h[3] * h[7] - h[4] * h[6])
        return determinant.isFinite() && abs(determinant) >= 1e-8
    }

    private fun rejected(
        reason: RecognitionRejectionReason,
        areaFraction: Double? = null,
    ) = GeometryValidation(false, reason, areaFraction)

    private fun signedArea(points: List<RecognitionPoint>): Double {
        var sum = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            sum += current.x * next.y - next.x * current.y
        }
        return sum / 2.0
    }

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

    private fun orientation(a: RecognitionPoint, b: RecognitionPoint, c: RecognitionPoint): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}
