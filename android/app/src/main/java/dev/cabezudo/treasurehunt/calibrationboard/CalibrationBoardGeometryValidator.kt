package dev.cabezudo.treasurehunt.calibrationboard

import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import kotlin.math.hypot
import kotlin.math.abs

class CalibrationBoardGeometryValidator(
    private val config: CalibrationBoardConfig = CalibrationBoardConfig(),
) {
    fun validate(
        corners: List<RecognitionPoint>,
        frameWidth: Int,
        frameHeight: Int,
    ): CalibrationBoardGeometryResult {
        if (corners.size != config.internalColumns * config.internalRows) {
            return invalid(CalibrationBoardRejectionReason.INCORRECT_CORNER_COUNT)
        }
        if (frameWidth <= 0 || frameHeight <= 0 || corners.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return invalid(CalibrationBoardRejectionReason.NON_FINITE_COORDINATES)
        }
        val toleranceX = frameWidth * config.frameToleranceFraction
        val toleranceY = frameHeight * config.frameToleranceFraction
        if (corners.any {
                it.x < -toleranceX || it.x > frameWidth + toleranceX ||
                    it.y < -toleranceY || it.y > frameHeight + toleranceY
            }
        ) return invalid(CalibrationBoardRejectionReason.OUTSIDE_FRAME)

        val horizontalDistances = mutableListOf<Double>()
        val verticalDistances = mutableListOf<Double>()
        var orientationSign = 0
        for (row in 0 until config.internalRows) {
            for (column in 0 until config.internalColumns) {
                val current = corners[index(column, row)]
                if (column + 1 < config.internalColumns) {
                    horizontalDistances += distance(current, corners[index(column + 1, row)])
                }
                if (row + 1 < config.internalRows) {
                    verticalDistances += distance(current, corners[index(column, row + 1)])
                }
                if (column + 1 < config.internalColumns && row + 1 < config.internalRows) {
                    val right = corners[index(column + 1, row)]
                    val down = corners[index(column, row + 1)]
                    val cross = (right.x - current.x) * (down.y - current.y) -
                        (right.y - current.y) * (down.x - current.x)
                    if (!cross.isFinite() || abs(cross) < 1e-6) {
                        return invalid(CalibrationBoardRejectionReason.DEGENERATE_BOARD)
                    }
                    val sign = if (cross > 0) 1 else -1
                    if (orientationSign == 0) orientationSign = sign
                    if (sign != orientationSign) {
                        return invalid(CalibrationBoardRejectionReason.INCONSISTENT_ORDER)
                    }
                }
            }
        }
        if ((horizontalDistances + verticalDistances).any {
                it < config.minimumCornerSeparationPixels
            }
        ) return invalid(CalibrationBoardRejectionReason.INSUFFICIENT_SEPARATION)

        val outline = listOf(
            corners[index(0, 0)],
            corners[index(config.internalColumns - 1, 0)],
            corners[index(config.internalColumns - 1, config.internalRows - 1)],
            corners[index(0, config.internalRows - 1)],
        )
        val area = polygonArea(outline)
        if (!area.isFinite() || area <= 1e-6) {
            return invalid(CalibrationBoardRejectionReason.DEGENERATE_BOARD)
        }
        val areaFraction = area / (frameWidth.toDouble() * frameHeight)
        if (areaFraction < config.minimumAreaFraction) {
            return invalid(CalibrationBoardRejectionReason.AREA_TOO_SMALL)
        }
        if (areaFraction > config.maximumAreaFraction) {
            return invalid(CalibrationBoardRejectionReason.AREA_TOO_LARGE)
        }
        return CalibrationBoardGeometryResult(
            valid = true,
            areaFraction = areaFraction,
            center = RecognitionPoint(corners.map { it.x }.average(), corners.map { it.y }.average()),
            approximateWidthPixels = horizontalDistances.average() * (config.internalColumns - 1),
            approximateHeightPixels = verticalDistances.average() * (config.internalRows - 1),
        )
    }

    private fun index(column: Int, row: Int) = row * config.internalColumns + column

    private fun distance(first: RecognitionPoint, second: RecognitionPoint): Double =
        hypot(second.x - first.x, second.y - first.y)

    private fun polygonArea(points: List<RecognitionPoint>): Double = abs(
        points.indices.sumOf { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            current.x * next.y - next.x * current.y
        } / 2.0,
    )

    private fun invalid(reason: CalibrationBoardRejectionReason) =
        CalibrationBoardGeometryResult(valid = false, rejectionReason = reason)
}

class CalibrationBoardRateLimiter(analysesPerSecond: Double) {
    private val intervalNanos = (1_000_000_000.0 / analysesPerSecond).toLong()
    private var lastAnalysisNanos: Long? = null

    init {
        require(analysesPerSecond in 5.0..10.0) { "La frecuencia debe estar entre 5 y 10 Hz." }
    }

    fun shouldAnalyze(nowNanos: Long): Boolean {
        val previous = lastAnalysisNanos
        if (previous != null && nowNanos - previous < intervalNanos) return false
        lastAnalysisNanos = nowNanos
        return true
    }
}
