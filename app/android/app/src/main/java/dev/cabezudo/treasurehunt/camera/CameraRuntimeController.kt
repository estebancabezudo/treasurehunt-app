package dev.cabezudo.treasurehunt.camera

import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationController
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationState
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationStatus

class CameraRuntimeController(
    activity: ComponentActivity,
    launchPermissionRequest: () -> Unit,
    private val onStateChanged: (CameraRuntimeState) -> Unit,
) {
    private var state = CameraRuntimeState()
    private var closed = false
    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val manualCalibrationController = ManualCalibrationController(
        directory = java.io.File(activity.filesDir, "calibration"),
        postResult = { action -> mainExecutor.execute(action) },
        onStateChanged = ::onManualCalibrationChanged,
    )
    private val previewController = CameraPreviewController(
        context = activity,
        lifecycleOwner = activity,
        onPreviewEvent = ::onPreviewEvent,
        onAnalysisEvent = ::onAnalysisEvent,
    )
    private val permissionController = CameraPermissionController(
        activity = activity,
        launchPermissionRequest = launchPermissionRequest,
        onStatusChanged = ::onPermissionChanged,
    )
    private val capabilityDetector = ArCoreCapabilityDetector(
        context = activity,
        onCapabilityChanged = ::onCapabilityChanged,
    )

    fun initialize() {
        permissionController.initialize()
        capabilityDetector.detect()
        manualCalibrationController.initialize()
    }

    fun attachPreview(view: PreviewView) {
        previewController.attachPreview(view)
    }

    fun requestCameraPermission() {
        permissionController.requestPermission()
    }

    fun setDiagnosticMode(mode: DiagnosticProcessingMode) {
        if (closed || state.diagnosticMode == mode) return
        state = state.copy(diagnosticMode = mode)
        previewController.setDiagnosticMode(mode)
        publish()
    }

    fun confirmPhysicalSquareMeasurement(
        horizontalMillimeters: Double,
        verticalMillimeters: Double,
    ) {
        previewController.confirmPhysicalSquareMeasurement(
            horizontalMillimeters,
            verticalMillimeters,
        )
    }

    fun confirmPhysicalMarkerSize(widthMillimeters: Double, heightMillimeters: Double) {
        previewController.confirmPhysicalMarkerSize(widthMillimeters, heightMillimeters)
    }

    fun requestCalibrationSample() = previewController.requestCalibrationSample()

    fun cancelCalibrationSampleRequest() = previewController.cancelCalibrationSampleRequest()

    fun resetCalibrationDataset() = previewController.resetCalibrationDataset()

    fun showCalibrationDatasetJson() = previewController.showCalibrationDatasetJson()

    fun calculateManualCalibration() = manualCalibrationController.calculate()

    fun showManualCalibrationJson() = manualCalibrationController.showJson()

    fun onCameraPermissionResult(granted: Boolean) {
        permissionController.onPermissionResult(granted)
    }

    fun onHostResume() {
        if (closed) return
        permissionController.refresh()
        previewController.onHostResume()
    }

    fun onHostPause() {
        if (closed) return
        previewController.onHostPause()
    }

    fun close() {
        if (closed) return
        closed = true
        capabilityDetector.close()
        previewController.close()
        manualCalibrationController.close()
    }

    fun currentState(): CameraRuntimeState = state

    private fun onPermissionChanged(permission: CameraPermissionStatus) {
        if (closed) return
        state = state.copy(permission = permission, error = null)
        previewController.setPermissionGranted(permission == CameraPermissionStatus.GRANTED)
        publish()
    }

    private fun onCapabilityChanged(capability: CameraCapability) {
        if (closed) return
        state = state.copy(
            capability = capability,
            mode = selectCameraMode(capability),
        )
        publish()
    }

    private fun onPreviewEvent(event: CameraPreviewEvent) {
        if (closed && event !is CameraPreviewEvent.Closed) return
        state = when (event) {
            CameraPreviewEvent.Starting -> state.copy(
                cameraX = CameraXStatus.STARTING,
                selectedCamera = SelectedCamera.BACK,
                error = null,
            )
            CameraPreviewEvent.Paused -> state.copy(cameraX = CameraXStatus.PAUSED)
            CameraPreviewEvent.Closed -> state.copy(cameraX = CameraXStatus.CLOSED)
            is CameraPreviewEvent.Started -> state.copy(
                cameraX = CameraXStatus.STARTED,
                selectedCamera = SelectedCamera.BACK,
                frameResolution = event.resolution ?: state.frameResolution,
                rotationDegrees = event.rotationDegrees,
                error = null,
            )
            is CameraPreviewEvent.Error -> state.copy(
                cameraX = CameraXStatus.ERROR,
                error = event.cause,
            )
        }
        publish()
    }

    private fun onAnalysisEvent(event: FrameAnalysisEvent) {
        if (closed && event.state.status != FrameAnalysisStatus.CLOSED) return
        state = state.copy(
            analysis = event.state,
            monochromeSample = when (event) {
                is FrameAnalysisEvent.MetricsUpdated -> event.sample ?: state.monochromeSample
                is FrameAnalysisEvent.StateChanged -> state.monochromeSample
            },
        )
        publish()
    }

    private fun onManualCalibrationChanged(manualCalibration: ManualCalibrationState) {
        if (closed && manualCalibration.status != ManualCalibrationStatus.CLOSED) return
        state = state.copy(manualCalibration = manualCalibration)
        publish()
    }

    private fun publish() {
        onStateChanged(state)
    }
}
