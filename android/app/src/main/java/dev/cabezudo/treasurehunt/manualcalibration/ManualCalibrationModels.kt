package dev.cabezudo.treasurehunt.manualcalibration

import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDatasetIdentity
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDeviceOrientation
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationGridPosition
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationScale
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect

const val MANUAL_CALIBRATION_SCHEMA_VERSION = 1
const val PHYSICAL_CALIBRATION_DATASET_SHA256 =
    "dd1265969a19a98724cb2feb8f488af1cae49a9288e33dcdfa368bb16acb64fc"
const val PHYSICAL_CALIBRATION_RESULT_SHA256 =
    "678b930eef4bcc6219f28f114631ba8fb9bee8fec32c508966c31022e5e862ac"
const val CANONICAL_BUFFER_COORDINATE_SYSTEM = "IMAGE_ANALYSIS_BUFFER_CANONICAL_UNROTATED"

data class PhysicalBoardPoint(
    val xMillimeters: Double,
    val yMillimeters: Double,
    val zMillimeters: Double = 0.0,
)

data class ManualCalibrationIdentity(
    val cameraId: String,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropRect: CoordinateRect,
    val internalColumns: Int,
    val internalRows: Int,
    val horizontalSquareSizeMm: Double,
    val verticalSquareSizeMm: Double,
    val coordinateSystem: String = CANONICAL_BUFFER_COORDINATE_SYSTEM,
) {
    companion object {
        fun from(dataset: CalibrationDatasetIdentity) = ManualCalibrationIdentity(
            cameraId = dataset.cameraId,
            bufferWidth = dataset.bufferWidth,
            bufferHeight = dataset.bufferHeight,
            cropRect = dataset.cropRect,
            internalColumns = dataset.internalColumns,
            internalRows = dataset.internalRows,
            horizontalSquareSizeMm = dataset.horizontalSquareSizeMm,
            verticalSquareSizeMm = dataset.verticalSquareSizeMm,
        )
    }
}

data class SampleReprojectionError(
    val sampleIndex: Int,
    val rmsPixels: Double,
    val meanPixels: Double,
    val maximumPixels: Double,
    val orientation: CalibrationDeviceOrientation,
    val scale: CalibrationScale,
    val gridPosition: CalibrationGridPosition,
)

data class ReprojectionErrorSummary(
    val meanSampleRmsPixels: Double,
    val medianSampleRmsPixels: Double,
    val maximumSampleRmsPixels: Double,
    val percentile95SampleRmsPixels: Double,
)

data class CalibrationRunResult(
    val usedSampleIndices: List<Int>,
    val excludedSampleIndices: List<Int>,
    val intrinsicMatrix: List<Double>,
    val distortionCoefficients: List<Double>,
    val globalRmsPixels: Double,
    val sampleErrors: List<SampleReprojectionError>,
    val errorSummary: ReprojectionErrorSummary,
) {
    val fx: Double get() = intrinsicMatrix[0]
    val fy: Double get() = intrinsicMatrix[4]
    val cx: Double get() = intrinsicMatrix[2]
    val cy: Double get() = intrinsicMatrix[5]
}

data class ManualCameraCalibrationResult(
    val schemaVersion: Int = MANUAL_CALIBRATION_SCHEMA_VERSION,
    val identity: ManualCalibrationIdentity,
    val datasetSha256: String,
    val calculatedAtEpochMillis: Long,
    val openCvVersion: String,
    val distortionModel: String = "OPENCV_STANDARD_5_K1_K2_P1_P2_K3",
    val outlierCriterion: String,
    val detectedOutlierSampleIndices: List<Int>,
    val comparativeCalibrationDecision: String,
    val primary: CalibrationRunResult,
    val comparativeWithoutOutliers: CalibrationRunResult? = null,
)

enum class ManualCalibrationStatus {
    NOT_AVAILABLE,
    READY,
    CALCULATING,
    CALIBRATED,
    INVALID,
    INCOMPATIBLE,
    ERROR,
    CLOSED,
}

data class ManualCalibrationState(
    val status: ManualCalibrationStatus = ManualCalibrationStatus.NOT_AVAILABLE,
    val result: ManualCameraCalibrationResult? = null,
    val resultFilePath: String = "no disponible",
    val jsonPreview: String? = null,
    val cause: String? = "Todavía no se ha cargado un resultado de calibración manual.",
)

data class CalibrationResultLoad(
    val result: ManualCameraCalibrationResult? = null,
    val incompatible: Boolean = false,
    val error: String? = null,
)
