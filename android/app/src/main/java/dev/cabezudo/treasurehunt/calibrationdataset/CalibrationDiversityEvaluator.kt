package dev.cabezudo.treasurehunt.calibrationdataset

import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

data class CalibrationDiversityConfig(
    val smallAreaMaximum: Double = 0.05,
    val mediumAreaMaximum: Double = 0.16,
    val tiltLogRatioThreshold: Double = 0.10,
    val duplicateRmsThreshold: Double = 0.012,
    val minimumQuality: Double = 0.15,
    val minimumTechnicalSamples: Int = 15,
    val recommendedMinimum: Int = 20,
    val recommendedMaximum: Int = 25,
    val minimumGridCellsForReady: Int = 6,
)

class CalibrationDiversityEvaluator(
    private val config: CalibrationDiversityConfig = CalibrationDiversityConfig(),
) {
    fun signature(
        corners: List<RecognitionPoint>,
        width: Int,
        height: Int,
        areaFraction: Double,
        rotationDegrees: Int,
    ): CalibrationDiversitySignature {
        require(corners.size == 54 && width > 0 && height > 0)
        val normalized = corners.map { RecognitionPoint(it.x / width, it.y / height) }
        val center = RecognitionPoint(normalized.map { it.x }.average(), normalized.map { it.y }.average())
        val horizontalRatio = edgeLength(corners[8], corners[53]) / edgeLength(corners[0], corners[45])
        val verticalRatio = edgeLength(corners[45], corners[53]) / edgeLength(corners[0], corners[8])
        return CalibrationDiversitySignature(
            gridPosition = gridPosition(center),
            scale = when {
                areaFraction < config.smallAreaMaximum -> CalibrationScale.SMALL
                areaFraction < config.mediumAreaMaximum -> CalibrationScale.MEDIUM
                else -> CalibrationScale.LARGE
            },
            horizontalTilt = tilt(horizontalRatio),
            verticalTilt = tilt(verticalRatio),
            deviceOrientation = if (rotationDegrees in setOf(90, 270)) {
                CalibrationDeviceOrientation.PORTRAIT
            } else {
                CalibrationDeviceOrientation.LANDSCAPE
            },
            normalizedCorners = normalized,
        )
    }

    fun geometricQuality(corners: List<RecognitionPoint>, areaFraction: Double): Double {
        require(corners.size == 54)
        val adjacent = mutableListOf<Double>()
        for (row in 0 until 6) for (column in 0 until 9) {
            val index = row * 9 + column
            if (column < 8) adjacent += edgeLength(corners[index], corners[index + 1])
            if (row < 5) adjacent += edgeLength(corners[index], corners[index + 9])
        }
        val spacingScore = (requireNotNull(adjacent.minOrNull()) / 8.0).coerceIn(0.0, 1.0)
        val areaScore = (areaFraction / 0.04).coerceIn(0.0, 1.0)
        return sqrt(spacingScore * areaScore)
    }

    fun isQualitySufficient(quality: Double): Boolean = quality >= config.minimumQuality

    fun isDuplicate(signature: CalibrationDiversitySignature, samples: List<CalibrationSample>): Boolean =
        samples.any { existing ->
            normalizedRms(signature.normalizedCorners, existing.diversity.normalizedCorners) <
                config.duplicateRmsThreshold
        }

    fun coverage(samples: List<CalibrationSample>): CalibrationDiversityCoverage {
        val positions = samples.map { it.diversity.gridPosition }.toSet()
        val scales = samples.map { it.diversity.scale }.toSet()
        val horizontal = samples.map { it.diversity.horizontalTilt }.toSet()
        val vertical = samples.map { it.diversity.verticalTilt }.toSet()
        val orientations = samples.map { it.diversity.deviceOrientation }.toSet()
        val recommendations = buildList {
            if (samples.size < config.minimumTechnicalSamples) add("Capturar al menos ${config.minimumTechnicalSamples - samples.size} muestras adicionales.")
            if (positions.size < config.minimumGridCellsForReady) add("Cubrir más posiciones de la cuadrícula 3 × 3.")
            if (scales.size < CalibrationScale.entries.size) add("Cubrir escalas pequeña, media y grande.")
            if (!horizontal.containsAll(setOf(CalibrationTilt.NEGATIVE, CalibrationTilt.POSITIVE))) add("Cubrir inclinación horizontal positiva y negativa.")
            if (!vertical.containsAll(setOf(CalibrationTilt.NEGATIVE, CalibrationTilt.POSITIVE))) add("Cubrir inclinación vertical positiva y negativa.")
            if (orientations.size < CalibrationDeviceOrientation.entries.size) add("Cubrir teléfono vertical y horizontal.")
            if (samples.size < config.recommendedMinimum) add("Objetivo recomendado: ${config.recommendedMinimum}–${config.recommendedMaximum} muestras diversas.")
        }
        val ready = samples.size >= config.minimumTechnicalSamples &&
            positions.size >= config.minimumGridCellsForReady &&
            scales.size == CalibrationScale.entries.size &&
            horizontal.containsAll(setOf(CalibrationTilt.NEGATIVE, CalibrationTilt.POSITIVE)) &&
            vertical.containsAll(setOf(CalibrationTilt.NEGATIVE, CalibrationTilt.POSITIVE)) &&
            orientations.size == CalibrationDeviceOrientation.entries.size
        return CalibrationDiversityCoverage(
            gridPositions = positions,
            scales = scales,
            horizontalTilts = horizontal,
            verticalTilts = vertical,
            orientations = orientations,
            minimumTechnicalSamples = config.minimumTechnicalSamples,
            recommendedMinimum = config.recommendedMinimum,
            recommendedMaximum = config.recommendedMaximum,
            ready = ready,
            recommendations = recommendations,
        )
    }

    private fun gridPosition(center: RecognitionPoint): CalibrationGridPosition {
        val column = (center.x * 3).toInt().coerceIn(0, 2)
        val row = (center.y * 3).toInt().coerceIn(0, 2)
        return CalibrationGridPosition.entries[row * 3 + column]
    }

    private fun tilt(ratio: Double): CalibrationTilt {
        val value = ln(ratio.coerceAtLeast(1e-9))
        return when {
            value < -config.tiltLogRatioThreshold -> CalibrationTilt.NEGATIVE
            value > config.tiltLogRatioThreshold -> CalibrationTilt.POSITIVE
            else -> CalibrationTilt.NEUTRAL
        }
    }

    private fun normalizedRms(first: List<RecognitionPoint>, second: List<RecognitionPoint>): Double {
        if (first.size != second.size || first.isEmpty()) return Double.POSITIVE_INFINITY
        val direct = rms(first, second)
        val reversed = rms(first, second.reversed())
        return minOf(direct, reversed)
    }

    private fun rms(first: List<RecognitionPoint>, second: List<RecognitionPoint>): Double = sqrt(
        first.indices.sumOf { index ->
            val dx = first[index].x - second[index].x
            val dy = first[index].y - second[index].y
            dx * dx + dy * dy
        } / first.size,
    )

    private fun edgeLength(first: RecognitionPoint, second: RecognitionPoint): Double =
        hypot(second.x - first.x, second.y - first.y).coerceAtLeast(1e-9)
}
