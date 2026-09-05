package dev.cabezudo.treasurehunt.manualcalibration

import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDataset
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationSample
import java.io.File
import java.security.MessageDigest
import org.opencv.calib.Calib
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.geometry.Geometry

class ManualCameraCalibrator(
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    fun calibrate(
        dataset: CalibrationDataset,
        datasetSha256: String,
    ): ManualCameraCalibrationResult {
        ManualCalibrationValidator.validateDataset(dataset, datasetSha256)
        val identity = ManualCalibrationIdentity.from(dataset.identity)
        val primary = calibrateRun(dataset, emptySet())
        ManualCalibrationValidator.validateRun(primary, identity)
        val outliers = ReprojectionStatistics.detectOutliers(primary.sampleErrors)
        val excluded = outliers.sampleIndices.toSet()
        val canCompare = excluded.isNotEmpty() &&
            ManualCalibrationValidator.canRunComparative(dataset, excluded)
        val comparative = if (canCompare) calibrateRun(dataset, excluded) else null
        comparative?.let { ManualCalibrationValidator.validateRun(it, identity) }
        val decision = when {
            excluded.isEmpty() -> "No se detectaron outliers; no se ejecutó una segunda calibración."
            canCompare -> "Se ejecutó una segunda calibración comparativa sin los outliers detectados."
            else -> "Los outliers no se excluyeron porque se perdería el mínimo de 15 muestras " +
                "o la cobertura completa de regiones, escalas, inclinaciones u orientaciones."
        }
        return ManualCameraCalibrationResult(
            identity = identity,
            datasetSha256 = datasetSha256,
            calculatedAtEpochMillis = wallClockMillis(),
            openCvVersion = Core.VERSION,
            outlierCriterion = outliers.criterion,
            detectedOutlierSampleIndices = outliers.sampleIndices,
            comparativeCalibrationDecision = decision,
            primary = primary,
            comparativeWithoutOutliers = comparative,
        )
    }

    internal fun calibrateRun(
        dataset: CalibrationDataset,
        excluded: Set<Int>,
    ): CalibrationRunResult {
        val samples = dataset.samples.filterNot { it.index in excluded }
        require(samples.size >= 3) { "OpenCV requiere varias observaciones para calibrar." }
        val physicalPoints = CalibrationObjectPointFactory.create(
            columns = dataset.identity.internalColumns,
            rows = dataset.identity.internalRows,
            horizontalSquareSizeMm = dataset.identity.horizontalSquareSizeMm,
            verticalSquareSizeMm = dataset.identity.verticalSquareSizeMm,
        )
        val objectMats = mutableListOf<Mat>()
        val imageMats = mutableListOf<Mat>()
        val rotationVectors = mutableListOf<Mat>()
        val translationVectors = mutableListOf<Mat>()
        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        val distortion = Mat.zeros(5, 1, CvType.CV_64F)
        try {
            samples.forEach { sample ->
                objectMats += MatOfPoint3f(*physicalPoints.map {
                    Point3(it.xMillimeters, it.yMillimeters, it.zMillimeters)
                }.toTypedArray())
                imageMats += MatOfPoint2f(*sample.canonicalCorners.map {
                    Point(it.x, it.y)
                }.toTypedArray())
            }
            val globalRms = Calib.calibrateCamera(
                objectMats,
                imageMats,
                Size(dataset.identity.bufferWidth.toDouble(), dataset.identity.bufferHeight.toDouble()),
                cameraMatrix,
                distortion,
                rotationVectors,
                translationVectors,
                0,
            )
            check(rotationVectors.size == samples.size && translationVectors.size == samples.size) {
                "OpenCV no devolvió un vector extrínseco por muestra."
            }
            val intrinsicValues = readMatrix(cameraMatrix, 3, 3)
            val distortionValues = readVector(distortion, 5)
            val sampleErrors = samples.mapIndexed { position, sample ->
                reprojectionError(
                    sample = sample,
                    physicalPoints = physicalPoints,
                    rotationVector = rotationVectors[position],
                    translationVector = translationVectors[position],
                    cameraMatrix = cameraMatrix,
                    distortionValues = distortionValues,
                )
            }
            return CalibrationRunResult(
                usedSampleIndices = samples.map { it.index },
                excludedSampleIndices = excluded.sorted(),
                intrinsicMatrix = intrinsicValues,
                distortionCoefficients = distortionValues,
                globalRmsPixels = globalRms,
                sampleErrors = sampleErrors,
                errorSummary = ReprojectionStatistics.summarize(sampleErrors),
            )
        } finally {
            objectMats.forEach(Mat::release)
            imageMats.forEach(Mat::release)
            rotationVectors.forEach(Mat::release)
            translationVectors.forEach(Mat::release)
            cameraMatrix.release()
            distortion.release()
        }
    }

    private fun reprojectionError(
        sample: CalibrationSample,
        physicalPoints: List<PhysicalBoardPoint>,
        rotationVector: Mat,
        translationVector: Mat,
        cameraMatrix: Mat,
        distortionValues: List<Double>,
    ): SampleReprojectionError {
        val objectPoints = MatOfPoint3f(*physicalPoints.map {
            Point3(it.xMillimeters, it.yMillimeters, it.zMillimeters)
        }.toTypedArray())
        val distortion = MatOfDouble(*distortionValues.toDoubleArray())
        val projected = MatOfPoint2f()
        try {
            Geometry.projectPoints(
                objectPoints,
                rotationVector,
                translationVector,
                cameraMatrix,
                distortion,
                projected,
            )
            val projectedPairs = projected.toArray().map { it.x to it.y }
            val observedPairs = sample.canonicalCorners.map { it.x to it.y }
            val (rms, mean, maximum) = ReprojectionStatistics.pointErrors(
                observedPairs,
                projectedPairs,
            )
            return SampleReprojectionError(
                sampleIndex = sample.index,
                rmsPixels = rms,
                meanPixels = mean,
                maximumPixels = maximum,
                orientation = sample.diversity.deviceOrientation,
                scale = sample.diversity.scale,
                gridPosition = sample.diversity.gridPosition,
            )
        } finally {
            objectPoints.release()
            distortion.release()
            projected.release()
        }
    }

    private fun readMatrix(mat: Mat, rows: Int, columns: Int): List<Double> = buildList {
        require(mat.rows() == rows && mat.cols() == columns) {
            "La matriz intrínseca no es $rows × $columns."
        }
        for (row in 0 until rows) for (column in 0 until columns) {
            add(requireNotNull(mat.get(row, column)).single())
        }
    }

    private fun readVector(mat: Mat, expected: Int): List<Double> {
        require(mat.total() >= expected.toLong()) {
            "OpenCV devolvió ${mat.total()} coeficientes; se esperaban $expected."
        }
        require(mat.channels() == 1) { "El vector de distorsión debe tener un solo canal." }
        val values = buildList {
            for (row in 0 until mat.rows()) for (column in 0 until mat.cols()) {
                add(requireNotNull(mat.get(row, column)).single())
            }
        }
        return values.take(expected)
    }
}

object Sha256 {
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
