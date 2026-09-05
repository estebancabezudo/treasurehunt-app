package dev.cabezudo.treasurehunt.camera

import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationState

enum class CameraPermissionStatus {
    PENDING,
    GRANTED,
    DENIED,
}

enum class CameraXStatus {
    IDLE,
    STARTING,
    STARTED,
    PAUSED,
    CLOSED,
    ERROR,
}

enum class SelectedCamera {
    BACK,
    UNAVAILABLE,
}

enum class ArCoreAvailability {
    CHECKING,
    AVAILABLE,
    NOT_INSTALLED,
    UNSUPPORTED,
    UNKNOWN,
}

data class CameraCapability(
    val arCore: ArCoreAvailability = ArCoreAvailability.CHECKING,
)

enum class CameraMode {
    STANDARD_CAMERA,
    ARCORE,
}

enum class DiagnosticProcessingMode {
    RECOGNITION,
    CAMERA_CALIBRATION,
}

data class FrameResolution(
    val width: Int,
    val height: Int,
)

data class CameraRuntimeState(
    val permission: CameraPermissionStatus = CameraPermissionStatus.PENDING,
    val cameraX: CameraXStatus = CameraXStatus.IDLE,
    val selectedCamera: SelectedCamera = SelectedCamera.UNAVAILABLE,
    val frameResolution: FrameResolution? = null,
    val rotationDegrees: Int? = null,
    val analysis: FrameAnalysisState = FrameAnalysisState(),
    val monochromeSample: MonochromeFrameSample? = null,
    val capability: CameraCapability = CameraCapability(),
    val mode: CameraMode = CameraMode.STANDARD_CAMERA,
    val diagnosticMode: DiagnosticProcessingMode = DiagnosticProcessingMode.RECOGNITION,
    val manualCalibration: ManualCalibrationState = ManualCalibrationState(),
    val error: String? = null,
)

internal fun selectCameraMode(capability: CameraCapability): CameraMode = when (capability.arCore) {
    ArCoreAvailability.CHECKING,
    ArCoreAvailability.AVAILABLE,
    ArCoreAvailability.NOT_INSTALLED,
    ArCoreAvailability.UNSUPPORTED,
    ArCoreAvailability.UNKNOWN,
    -> CameraMode.STANDARD_CAMERA
}
