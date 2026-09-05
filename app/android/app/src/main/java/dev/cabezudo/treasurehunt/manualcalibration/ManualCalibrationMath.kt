package dev.cabezudo.treasurehunt.manualcalibration

import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDataset
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationGridPosition
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationScale
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationTilt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

object CalibrationObjectPointFactory {
    fun create(
        columns: Int,
        rows: Int,
        horizontalSquareSizeMm: Double,
        verticalSquareSizeMm: Double,
    ): List<PhysicalBoardPoint> {
        require(columns > 1 && rows > 1) { "El tablero debe contener al menos 2 × 2 esquinas." }
        require(horizontalSquareSizeMm.isFinite() && horizontalSquareSizeMm > 0.0)
        require(verticalSquareSizeMm.isFinite() && verticalSquareSizeMm > 0.0)
        return List(columns * rows) { index ->
            val column = index % columns
            val row = index / columns
            PhysicalBoardPoint(
                xMillimeters = column * horizontalSquareSizeMm,
                yMillimeters = row * verticalSquareSizeMm,
            )
        }
    }
}

data class OutlierDecision(
    val medianPixels: Double,
    val madPixels: Double,
    val scaledMadPixels: Double,
    val thresholdPixels: Double,
    val sampleIndices: List<Int>,
    val criterion: String,
)

object ReprojectionStatistics {
    const val MAD_SCALE = 1.4826
    const val MAD_MULTIPLIER = 3.5

    fun summarize(sampleErrors: List<SampleReprojectionError>): ReprojectionErrorSummary {
        require(sampleErrors.isNotEmpty())
        val values = sampleErrors.map { it.rmsPixels }.also(::requireFiniteNonNegative).sorted()
        return ReprojectionErrorSummary(
            meanSampleRmsPixels = values.average(),
            medianSampleRmsPixels = median(values),
            maximumSampleRmsPixels = values.last(),
            percentile95SampleRmsPixels = nearestRankPercentile(values, 0.95),
        )
    }

    fun detectOutliers(sampleErrors: List<SampleReprojectionError>): OutlierDecision {
        require(sampleErrors.isNotEmpty())
        val values = sampleErrors.map { it.rmsPixels }.also(::requireFiniteNonNegative)
        val median = median(values.sorted())
        val deviations = values.map { abs(it - median) }.sorted()
        val mad = median(deviations)
        val scaledMad = MAD_SCALE * mad
        val threshold = if (mad <= 1e-12) Double.POSITIVE_INFINITY else {
            median + MAD_MULTIPLIER * scaledMad
        }
        val indices = sampleErrors.filter { it.rmsPixels > threshold }.map { it.sampleIndex }
        return OutlierDecision(
            medianPixels = median,
            madPixels = mad,
            scaledMadPixels = scaledMad,
            thresholdPixels = threshold,
            sampleIndices = indices,
            criterion = "RMS por muestra > mediana + 3.5 × 1.4826 × MAD; " +
                "si MAD=0 no se excluyen muestras.",
        )
    }

    fun pointErrors(
        observed: List<Pair<Double, Double>>,
        projected: List<Pair<Double, Double>>,
    ): Triple<Double, Double, Double> {
        require(observed.size == projected.size && observed.isNotEmpty())
        val distances = observed.indices.map { index ->
            val dx = observed[index].first - projected[index].first
            val dy = observed[index].second - projected[index].second
            sqrt(dx * dx + dy * dy)
        }.also(::requireFiniteNonNegative)
        val rms = sqrt(distances.sumOf { it * it } / distances.size)
        return Triple(rms, distances.average(), distances.max())
    }

    private fun median(sorted: List<Double>): Double = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private fun nearestRankPercentile(sorted: List<Double>, percentile: Double): Double {
        require(percentile in 0.0..1.0)
        val rank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun requireFiniteNonNegative(values: List<Double>) {
        require(values.all { it.isFinite() && it >= 0.0 }) {
            "Los errores de reproyección deben ser finitos y no negativos."
        }
    }
}

object ManualCalibrationValidator {
    fun validateDataset(dataset: CalibrationDataset, datasetSha256: String) {
        require(datasetSha256 == PHYSICAL_CALIBRATION_DATASET_SHA256) {
            "SHA-256 distinto: $datasetSha256."
        }
        val identity = dataset.identity
        require(dataset.samples.size == 20) { "Se esperaban 20 muestras; hay ${dataset.samples.size}." }
        require(identity.cameraId == "0") { "La cámara debe ser ID 0; es ${identity.cameraId}." }
        require(identity.bufferWidth == 640 && identity.bufferHeight == 480) {
            "El buffer debe ser 640 × 480; es ${identity.bufferWidth} × ${identity.bufferHeight}."
        }
        require(
            identity.cropRect.left == 0 && identity.cropRect.top == 0 &&
                identity.cropRect.right == 640 && identity.cropRect.bottom == 480,
        ) { "El crop debe cubrir el buffer completo; es ${identity.cropRect}." }
        require(identity.internalColumns == 9 && identity.internalRows == 6)
        require(identity.horizontalSquareSizeMm == 18.20) {
            "El paso X debe ser 18.20 mm; es ${identity.horizontalSquareSizeMm}."
        }
        require(identity.verticalSquareSizeMm == 18.142857) {
            "El paso Y debe ser 18.142857 mm; es ${identity.verticalSquareSizeMm}."
        }
        require(dataset.samples.map { it.index } == (1..20).toList())
        dataset.samples.forEach { sample ->
            require(sample.canonicalCorners.size == 54) {
                "La muestra ${sample.index} tiene ${sample.canonicalCorners.size} puntos."
            }
            require(sample.canonicalCorners.all { it.x.isFinite() && it.y.isFinite() }) {
                "La muestra ${sample.index} contiene puntos no finitos."
            }
            require(sample.canonicalWidth == 640 && sample.canonicalHeight == 480)
            require(sample.originalCropRect == identity.cropRect)
            require(sample.horizontalSquareSizeMm == identity.horizontalSquareSizeMm)
            require(sample.verticalSquareSizeMm == identity.verticalSquareSizeMm)
        }
    }

    fun validateRun(run: CalibrationRunResult, identity: ManualCalibrationIdentity) {
        require(run.globalRmsPixels.isFinite() && run.globalRmsPixels >= 0.0)
        require(run.intrinsicMatrix.size == 9 && run.intrinsicMatrix.all(Double::isFinite))
        require(run.fx > 0.0 && run.fy > 0.0)
        require(run.cx.isFinite() && run.cy.isFinite())
        require(abs(run.intrinsicMatrix[6]) <= 1e-9)
        require(abs(run.intrinsicMatrix[7]) <= 1e-9)
        require(abs(run.intrinsicMatrix[8] - 1.0) <= 1e-9)
        require(run.distortionCoefficients.size == 5)
        require(run.distortionCoefficients.all(Double::isFinite))
        require(run.cx in -identity.bufferWidth * 0.25..identity.bufferWidth * 1.25)
        require(run.cy in -identity.bufferHeight * 0.25..identity.bufferHeight * 1.25)
        require(run.sampleErrors.size == run.usedSampleIndices.size)
    }

    fun canRunComparative(dataset: CalibrationDataset, excluded: Set<Int>): Boolean {
        val remaining = dataset.samples.filterNot { it.index in excluded }
        if (remaining.size < 15) return false
        val signatures = remaining.map { it.diversity }
        return signatures.map { it.gridPosition }.toSet() == CalibrationGridPosition.entries.toSet() &&
            signatures.map { it.scale }.toSet() == CalibrationScale.entries.toSet() &&
            signatures.map { it.horizontalTilt }.toSet()
                .containsAll(setOf(CalibrationTilt.NEGATIVE, CalibrationTilt.POSITIVE)) &&
            signatures.map { it.verticalTilt }.toSet()
                .containsAll(setOf(CalibrationTilt.NEGATIVE, CalibrationTilt.POSITIVE)) &&
            signatures.map { it.deviceOrientation }.toSet().size == 2
    }
}
