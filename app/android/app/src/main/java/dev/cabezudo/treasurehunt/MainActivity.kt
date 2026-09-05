package dev.cabezudo.treasurehunt

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import dev.cabezudo.treasurehunt.camera.CameraRuntimeController
import dev.cabezudo.treasurehunt.camera.CameraRuntimeState
import dev.cabezudo.treasurehunt.camera.toDiagnosticUiState
import dev.cabezudo.treasurehunt.camera.DiagnosticProcessingMode
import dev.cabezudo.treasurehunt.ui.TreasureHuntCameraApp

class MainActivity : ComponentActivity() {
    private val runtimeState = mutableStateOf(CameraRuntimeState())
    private lateinit var runtimeController: CameraRuntimeController

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        runtimeController.onCameraPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtimeController = CameraRuntimeController(
            activity = this,
            launchPermissionRequest = {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onStateChanged = { state -> runtimeState.value = state },
        ).also { it.initialize() }
        diagnosticModeFromSavedValue(savedInstanceState?.getString(DIAGNOSTIC_MODE_KEY))?.let {
            runtimeController.setDiagnosticMode(it)
        }

        setContent {
            TreasureHuntCameraApp(
                runtimeState = runtimeState.value,
                onPreviewReady = runtimeController::attachPreview,
                onRequestCameraPermission = runtimeController::requestCameraPermission,
                onDiagnosticModeChanged = runtimeController::setDiagnosticMode,
                onConfirmPhysicalSquareMeasurement =
                    runtimeController::confirmPhysicalSquareMeasurement,
                onCaptureCalibrationSample = runtimeController::requestCalibrationSample,
                onCancelCalibrationSample = runtimeController::cancelCalibrationSampleRequest,
                onResetCalibrationDataset = runtimeController::resetCalibrationDataset,
                onShowCalibrationDatasetJson = runtimeController::showCalibrationDatasetJson,
                onCalculateManualCalibration = runtimeController::calculateManualCalibration,
                onShowManualCalibrationJson = runtimeController::showManualCalibrationJson,
                onConfirmPhysicalMarkerSize = runtimeController::confirmPhysicalMarkerSize,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        runtimeController.onHostResume()
    }

    override fun onPause() {
        runtimeController.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        runtimeController.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(
            DIAGNOSTIC_MODE_KEY,
            runtimeController.currentState().diagnosticMode.name,
        )
        super.onSaveInstanceState(outState)
    }

    internal fun currentDiagnosticHeadline(): String =
        runtimeState.value.toDiagnosticUiState().headline

    internal fun currentCameraRuntimeState(): CameraRuntimeState = runtimeController.currentState()

    internal fun setDiagnosticModeForTest(mode: DiagnosticProcessingMode) {
        runtimeController.setDiagnosticMode(mode)
    }

    internal fun calculateManualCalibrationForTest() {
        runtimeController.calculateManualCalibration()
    }

    private companion object {
        const val DIAGNOSTIC_MODE_KEY = "diagnostic_processing_mode"
    }
}

internal fun diagnosticModeFromSavedValue(value: String?): DiagnosticProcessingMode? =
    value?.let { saved ->
        DiagnosticProcessingMode.entries.firstOrNull { it.name == saved }
    }
