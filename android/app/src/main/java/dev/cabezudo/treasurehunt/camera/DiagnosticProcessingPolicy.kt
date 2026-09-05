package dev.cabezudo.treasurehunt.camera

data class DiagnosticDetectorSelection(
    val runRecognition: Boolean,
    val runCalibrationBoard: Boolean,
)

fun DiagnosticProcessingMode.detectorSelection(): DiagnosticDetectorSelection = when (this) {
    DiagnosticProcessingMode.RECOGNITION -> DiagnosticDetectorSelection(
        runRecognition = true,
        runCalibrationBoard = false,
    )
    DiagnosticProcessingMode.CAMERA_CALIBRATION -> DiagnosticDetectorSelection(
        runRecognition = false,
        runCalibrationBoard = true,
    )
}
