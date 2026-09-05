package dev.cabezudo.treasurehunt.camera

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import dev.cabezudo.treasurehunt.camera.calibration.CameraCalibrationSource

sealed interface CameraPreviewEvent {
    data object Starting : CameraPreviewEvent
    data object Paused : CameraPreviewEvent
    data object Closed : CameraPreviewEvent
    data class Started(
        val resolution: FrameResolution?,
        val rotationDegrees: Int,
    ) : CameraPreviewEvent
    data class Error(val cause: String) : CameraPreviewEvent
}

class CameraPreviewController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onPreviewEvent: (CameraPreviewEvent) -> Unit,
    private val onAnalysisEvent: (FrameAnalysisEvent) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "treasure-hunt-frame-analysis")
    }
    private val frameAnalyzer = CameraFrameAnalyzer(applicationContext, ::postAnalysisEvent)
    private val calibrationSource = CameraCalibrationSource()
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var permissionGranted = false
    private var hostResumed = false
    private var bindingRequested = false
    private var bound = false
    private var closed = false

    fun attachPreview(view: PreviewView) {
        if (closed) return
        previewView = view.apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        view.previewStreamState.observe(lifecycleOwner) { streamState ->
            if (!closed && hostResumed && streamState == PreviewView.StreamState.STREAMING) {
                publishStarted()
            }
        }
        startIfReady()
    }

    fun setPermissionGranted(granted: Boolean) {
        permissionGranted = granted
        if (granted) {
            startIfReady()
        } else if (bound) {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
            bound = false
            preview = null
            imageAnalysis = null
            frameAnalyzer.onIdle()
        }
    }

    fun setDiagnosticMode(mode: DiagnosticProcessingMode) {
        frameAnalyzer.setDiagnosticMode(mode)
    }

    fun confirmPhysicalSquareMeasurement(
        horizontalMillimeters: Double,
        verticalMillimeters: Double,
    ) {
        frameAnalyzer.confirmPhysicalSquareMeasurement(
            horizontalMillimeters,
            verticalMillimeters,
        )
    }

    fun confirmPhysicalMarkerSize(widthMillimeters: Double, heightMillimeters: Double) {
        frameAnalyzer.confirmPhysicalMarkerSize(widthMillimeters, heightMillimeters)
    }

    fun requestCalibrationSample() = frameAnalyzer.requestCalibrationSample()

    fun cancelCalibrationSampleRequest() = frameAnalyzer.cancelCalibrationSampleRequest()

    fun resetCalibrationDataset() = frameAnalyzer.resetCalibrationDataset()

    fun showCalibrationDatasetJson() = frameAnalyzer.showCalibrationDatasetJson()

    fun onHostResume() {
        if (closed) return
        hostResumed = true
        if (bound) {
            onPreviewEvent(CameraPreviewEvent.Starting)
            frameAnalyzer.onStarting()
        }
        startIfReady()
    }

    fun onHostPause() {
        hostResumed = false
        if (bound) {
            onPreviewEvent(CameraPreviewEvent.Paused)
            frameAnalyzer.onPaused()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        hostResumed = false
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        bound = false
        preview = null
        imageAnalysis = null
        previewView = null
        analysisExecutor.execute {
            val closedAnalysisState = frameAnalyzer.close()
            mainExecutor.execute {
                onAnalysisEvent(FrameAnalysisEvent.StateChanged(closedAnalysisState))
            }
        }
        analysisExecutor.shutdown()
        onPreviewEvent(CameraPreviewEvent.Closed)
    }

    private fun startIfReady() {
        if (closed || !hostResumed || !permissionGranted || previewView == null || bound) return
        onPreviewEvent(CameraPreviewEvent.Starting)
        frameAnalyzer.onStarting()
        if (cameraProvider != null) {
            bindCameraUseCases(cameraProvider!!)
            return
        }
        if (bindingRequested) return
        bindingRequested = true
        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener(
            {
                bindingRequested = false
                try {
                    cameraProvider = providerFuture.get()
                    bindCameraUseCases(providerFuture.get())
                } catch (error: Exception) {
                    publishCameraError(
                        "No se pudo inicializar CameraX: ${rootCauseMessage(error)}",
                    )
                }
            },
            mainExecutor,
        )
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        if (closed || !hostResumed || !permissionGranted || bound) return
        val view = previewView ?: return
        try {
            if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                publishCameraError("No hay una cámara trasera disponible.")
                return
            }
            val targetRotation = view.display?.rotation ?: Surface.ROTATION_0
            val previewUseCase = Preview.Builder()
                .setTargetRotation(targetRotation)
                .build()
            previewUseCase.setSurfaceProvider(view.surfaceProvider)
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setTargetRotation(targetRotation)
                .build()
            analysisUseCase.setAnalyzer(analysisExecutor, frameAnalyzer)
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase,
                analysisUseCase,
            )
            frameAnalyzer.updateCalibrationSource(calibrationSource.read(camera.cameraInfo))
            preview = previewUseCase
            imageAnalysis = analysisUseCase
            bound = true
            camera.cameraInfo.cameraState.observe(lifecycleOwner) { cameraState ->
                if (closed) return@observe
                val error = cameraState.error
                if (error != null) {
                    publishCameraError(describeCameraError(error.code))
                } else if (hostResumed && cameraState.type == CameraState.Type.OPEN) {
                    publishStarted()
                }
            }
        } catch (error: SecurityException) {
            publishCameraError("CameraX no tiene permiso para abrir la cámara.")
        } catch (error: IllegalArgumentException) {
            publishCameraError(
                "La cámara trasera no admite la combinación de vista previa y análisis solicitada.",
            )
        } catch (error: IllegalStateException) {
            publishCameraError("CameraX no pudo vincularse al ciclo de vida: ${error.message}")
        } catch (error: Exception) {
            publishCameraError("No se pudo abrir la cámara: ${rootCauseMessage(error)}")
        }
    }

    private fun publishStarted() {
        val resolutionInfo = preview?.resolutionInfo
        onPreviewEvent(
            CameraPreviewEvent.Started(
                resolution = resolutionInfo?.resolution?.let {
                    FrameResolution(width = it.width, height = it.height)
                },
                rotationDegrees = resolutionInfo?.rotationDegrees
                    ?: surfaceRotationToDegrees(previewView?.display?.rotation ?: Surface.ROTATION_0),
            ),
        )
    }

    private fun publishCameraError(cause: String) {
        onPreviewEvent(CameraPreviewEvent.Error(cause))
        frameAnalyzer.onError(cause)
    }

    private fun postAnalysisEvent(event: FrameAnalysisEvent) {
        mainExecutor.execute {
            if (!closed) onAnalysisEvent(event)
        }
    }

    private fun describeCameraError(code: Int): String = when (code) {
        CameraState.ERROR_CAMERA_IN_USE -> "La cámara está siendo usada por otra aplicación."
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> "El dispositivo ya tiene demasiadas cámaras abiertas."
        CameraState.ERROR_CAMERA_DISABLED -> "La cámara está deshabilitada por una política del sistema."
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "La cámara informó un error fatal."
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
            "El modo No molestar impide abrir la cámara en este dispositivo."
        }
        CameraState.ERROR_STREAM_CONFIG -> "La cámara no pudo configurar los flujos solicitados."
        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "La cámara informó un error recuperable."
        else -> "CameraX informó el error de cámara $code."
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }

    private fun surfaceRotationToDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
