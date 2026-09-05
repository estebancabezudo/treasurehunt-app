package dev.cabezudo.treasurehunt.camera

import android.graphics.ImageFormat
import android.content.Context
import android.os.SystemClock
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.cabezudo.treasurehunt.recognition.ReferenceImageDetector
import dev.cabezudo.treasurehunt.ocr.CameraTextRecognizer
import dev.cabezudo.treasurehunt.ocr.MlKitTextRecognitionEngine
import dev.cabezudo.treasurehunt.ocr.OpenCvOcrImageFactory
import dev.cabezudo.treasurehunt.ocr.TextRecognitionState
import dev.cabezudo.treasurehunt.aruco.ArucoMarkerDetector
import dev.cabezudo.treasurehunt.camera.calibration.CameraCalibrationState
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.FrameCalibrationTransformer
import dev.cabezudo.treasurehunt.camera.calibration.FrameCoordinateTransform
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetector
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationFrameCaptureMetadata
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationSampleCollector
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationSampleRepository
import dev.cabezudo.treasurehunt.calibrationdataset.ExecutorCalibrationRequestTimeoutScheduler
import java.io.File
import dev.cabezudo.treasurehunt.aruco.pose.ArucoPoseEstimator
import dev.cabezudo.treasurehunt.aruco.pose.PhysicalMarkerSizeRepository
import dev.cabezudo.treasurehunt.aruco.pose.PoseCalibrationLoader

sealed interface FrameAnalysisEvent {
    val state: FrameAnalysisState

    data class StateChanged(override val state: FrameAnalysisState) : FrameAnalysisEvent
    data class MetricsUpdated(
        override val state: FrameAnalysisState,
        val sample: MonochromeFrameSample?,
    ) : FrameAnalysisEvent
}

class CameraFrameAnalyzer(
    context: Context,
    private val onEvent: (FrameAnalysisEvent) -> Unit,
    private val monotonicNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : ImageAnalysis.Analyzer {
    private val metrics = FrameMetricsAccumulator()
    private val converter = OpenCvFrameConverter(monotonicNanos = monotonicNanos)
    private val referenceDetector = ReferenceImageDetector(
        context = context.applicationContext,
        monotonicNanos = monotonicNanos,
    )
    private val arucoDetector = ArucoMarkerDetector(monotonicNanos = monotonicNanos)
    private val markerSizeRepository = PhysicalMarkerSizeRepository(File(context.filesDir, "pose"))
    private val arucoPoseEstimator = ArucoPoseEstimator(
        baseCalibration = PoseCalibrationLoader(context.assets).load(),
        monotonicNanos = monotonicNanos,
    ).also { estimator ->
        runCatching { markerSizeRepository.load() }.getOrNull()?.let(estimator::confirmPhysicalSize)
    }
    private val calibrationBoardDetector = CalibrationBoardDetector(monotonicNanos = monotonicNanos)
    private val calibrationSampleCollector = CalibrationSampleCollector(
        repository = CalibrationSampleRepository(File(context.filesDir, "calibration")),
        monotonicNanos = monotonicNanos,
        wallClockMillis = System::currentTimeMillis,
        onStateChanged = ::onCalibrationCaptureChanged,
        timeoutScheduler = ExecutorCalibrationRequestTimeoutScheduler(),
    )
    private val calibrationTransformer = FrameCalibrationTransformer()
    private val textRecognizer = CameraTextRecognizer(
        engine = MlKitTextRecognitionEngine(),
        onStateChanged = ::onTextRecognitionChanged,
        monotonicNanos = monotonicNanos,
        analysesPerSecond = 2.0,
    ).also { it.initialize() }
    private var previousArrivalNanos: Long? = null
    private var lastSampleNanos: Long? = null
    @Volatile
    private var closed = false
    @Volatile
    private var calibrationSource = CameraCalibrationState()
    private var effectiveCalibration = CameraCalibrationState()
    @Volatile
    private var diagnosticMode = DiagnosticProcessingMode.RECOGNITION

    override fun analyze(image: ImageProxy) {
        val arrivalNanos = monotonicNanos()
        try {
            if (closed) return
            val openCv = OpenCvRuntime.initialize()
            if (openCv.status != OpenCvStatus.READY) {
                val cause = openCv.error ?: "OpenCV no está disponible."
                metrics.markConversionError(cause, openCv.status, arrivalNanos)
                metrics.snapshotIfPublicationDue(arrivalNanos)?.let {
                    onEvent(FrameAnalysisEvent.StateChanged(it))
                }
                return
            }
            require(image.format == ImageFormat.YUV_420_888) {
                "Formato de cuadro no compatible: ${image.format}; se esperaba YUV_420_888."
            }
            require(image.width > 0 && image.height > 0) {
                "Las dimensiones del ImageProxy deben ser positivas."
            }
            val planes = image.planes
            require(planes.isNotEmpty()) { "El cuadro YUV no contiene el plano Y." }
            val yPlane = planes[0]
            val crop = image.cropRect
            require(crop.width() > 0 && crop.height() > 0 && crop.left >= 0 && crop.top >= 0 &&
                crop.right <= image.width && crop.bottom <= image.height
            ) { "cropRect inválido para el ImageProxy: $crop." }
            val croppedBuffer = yPlane.buffer.duplicate().apply {
                val offset = position().toLong() + crop.top.toLong() * yPlane.rowStride +
                    crop.left.toLong() * yPlane.pixelStride
                require(offset <= Int.MAX_VALUE && offset < limit()) {
                    "El inicio del crop queda fuera del plano Y."
                }
                position(offset.toInt())
            }
            val previous = previousArrivalNanos
            previousArrivalNanos = arrivalNanos
            var sample: MonochromeFrameSample? = null
            converter.convert(
                plane = LuminancePlane(
                    buffer = croppedBuffer,
                    width = crop.width(),
                    height = crop.height(),
                    rowStride = yPlane.rowStride,
                    pixelStride = yPlane.pixelStride,
                ),
                rotationDegrees = image.imageInfo.rotationDegrees,
            ).use { frame ->
                val sensorToBuffer = image.imageInfo.sensorToBufferTransformMatrix.toMatrix3()
                val coordinateTransform = FrameCoordinateTransform(
                    sensorToBuffer = sensorToBuffer,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    cropRect = CoordinateRect(crop.left, crop.top, crop.right, crop.bottom),
                    rotationDegrees = image.imageInfo.rotationDegrees,
                    preparedWidth = frame.mat.cols(),
                    preparedHeight = frame.mat.rows(),
                )
                val captureMetadata = CalibrationFrameCaptureMetadata(
                    cameraId = calibrationSource.raw?.cameraId,
                    bufferWidth = image.width,
                    bufferHeight = image.height,
                    cropRect = CoordinateRect(crop.left, crop.top, crop.right, crop.bottom),
                    rotationDegrees = image.imageInfo.rotationDegrees,
                    preparedWidth = frame.mat.cols(),
                    preparedHeight = frame.mat.rows(),
                    sensorToBufferTransform = sensorToBuffer,
                )
                val transformedCalibration = calibrationTransformer.transform(
                    calibrationSource,
                    coordinateTransform,
                )
                if (transformedCalibration != effectiveCalibration) {
                    effectiveCalibration = transformedCalibration
                    metrics.updateCalibration(transformedCalibration, arrivalNanos)
                }
                val detectorSelection = diagnosticMode.detectorSelection()
                val recognition = if (detectorSelection.runRecognition) {
                    referenceDetector.analyzeIfDue(frame.mat, arrivalNanos)
                } else null
                val aruco = if (detectorSelection.runRecognition) {
                    arucoDetector.analyzeIfDue(frame.mat, arrivalNanos)
                } else null
                val arucoPose = if (detectorSelection.runRecognition && aruco != null) {
                    arucoPoseEstimator.estimate(aruco, captureMetadata)
                } else null
                val board = if (detectorSelection.runCalibrationBoard) {
                    calibrationBoardDetector.analyzeIfDue(frame.mat, arrivalNanos)
                } else null
                if (board != null) {
                    calibrationSampleCollector.processCurrentFrame(
                        mode = diagnosticMode,
                        detection = board,
                        metadata = captureMetadata,
                        nowNanos = arrivalNanos,
                    )
                }
                if (detectorSelection.runRecognition) {
                    textRecognizer.tryRecognize(arrivalNanos) {
                        OpenCvOcrImageFactory.create(frame.mat)
                    }
                }
                val previousSample = lastSampleNanos
                if (previousSample == null || arrivalNanos - previousSample >= 1_000_000_000L) {
                    sample = frame.createDiagnosticSample(recognition, aruco, arucoPose, board)
                    lastSampleNanos = arrivalNanos
                }
                metrics.recordFrame(
                    metadata = FrameMetadata(
                        width = image.width,
                        height = image.height,
                        format = image.format,
                        rotationDegrees = image.imageInfo.rotationDegrees,
                        timestampNanos = image.imageInfo.timestamp,
                        planeCount = planes.size,
                        interFrameNanos = previous?.let { arrivalNanos - it },
                    ),
                    nowNanos = arrivalNanos,
                    preparation = frame.metrics,
                    recognition = recognition,
                    arucoDetection = aruco,
                    arucoPose = arucoPose,
                    calibrationBoard = board,
                )
            }
            val diagnosticState = metrics.snapshotIfPublicationDue(arrivalNanos)
            if (sample != null) {
                onEvent(
                    FrameAnalysisEvent.MetricsUpdated(
                        diagnosticState ?: metrics.snapshot(arrivalNanos),
                        sample,
                    ),
                )
            } else if (diagnosticState != null) {
                onEvent(FrameAnalysisEvent.MetricsUpdated(diagnosticState, null))
            }
        } catch (error: Exception) {
            val cause = "No se pudo preparar el cuadro para OpenCV: ${rootCauseMessage(error)}"
            metrics.markConversionError(
                cause = cause,
                openCvStatus = OpenCvRuntime.current().status,
                nowNanos = arrivalNanos,
            )
            metrics.snapshotIfPublicationDue(arrivalNanos)?.let {
                onEvent(FrameAnalysisEvent.StateChanged(it))
            }
        } finally {
            image.close()
        }
    }

    fun onStarting() {
        if (!closed) {
            val now = monotonicNanos()
            metrics.updateCalibrationCapture(calibrationSampleCollector.currentState(), now)
            onEvent(FrameAnalysisEvent.StateChanged(metrics.markStarting(now)))
        }
    }

    fun updateCalibrationSource(source: CameraCalibrationState) {
        calibrationSource = source
        if (!closed) {
            onEvent(
                FrameAnalysisEvent.StateChanged(
                    metrics.updateCalibration(source, monotonicNanos()),
                ),
            )
        }
    }

    fun setDiagnosticMode(mode: DiagnosticProcessingMode) {
        diagnosticMode = mode
    }

    fun confirmPhysicalSquareMeasurement(
        horizontalMillimeters: Double,
        verticalMillimeters: Double,
    ) {
        calibrationSampleCollector.confirmPhysicalMeasurement(
            horizontalMillimeters,
            verticalMillimeters,
        )
    }

    fun confirmPhysicalMarkerSize(
        widthMillimeters: Double,
        heightMillimeters: Double,
    ) {
        if (closed) return
        val size = markerSizeRepository.save(widthMillimeters, heightMillimeters)
        arucoPoseEstimator.confirmPhysicalSize(size)
        onEvent(
            FrameAnalysisEvent.StateChanged(
                metrics.updateArucoPose(arucoPoseEstimator.currentState(), monotonicNanos()),
            ),
        )
    }

    fun requestCalibrationSample() {
        calibrationSampleCollector.requestCapture(diagnosticMode)
    }

    fun cancelCalibrationSampleRequest() {
        calibrationSampleCollector.cancelPending()
    }

    fun resetCalibrationDataset() {
        calibrationSampleCollector.resetDataset()
    }

    fun showCalibrationDatasetJson() {
        calibrationSampleCollector.showJson()
    }

    fun onPaused() {
        if (!closed) {
            onEvent(FrameAnalysisEvent.StateChanged(metrics.markPaused(monotonicNanos())))
        }
    }

    fun onIdle() {
        if (!closed) {
            onEvent(FrameAnalysisEvent.StateChanged(metrics.markIdle(monotonicNanos())))
        }
    }

    fun onError(cause: String) {
        if (!closed) {
            onEvent(FrameAnalysisEvent.StateChanged(metrics.markError(cause, monotonicNanos())))
        }
    }

    fun close(): FrameAnalysisState {
        closed = true
        textRecognizer.close()
        arucoDetector.close()
        arucoPoseEstimator.close()
        calibrationBoardDetector.close()
        calibrationSampleCollector.close()
        referenceDetector.close()
        return metrics.markClosed(monotonicNanos())
    }

    private fun onTextRecognitionChanged(state: TextRecognitionState) {
        if (closed && state.status != dev.cabezudo.treasurehunt.ocr.TextRecognitionStatus.CLOSED) {
            return
        }
        onEvent(
            FrameAnalysisEvent.StateChanged(
                metrics.updateTextRecognition(state, monotonicNanos()),
            ),
        )
    }

    private fun onCalibrationCaptureChanged(
        state: dev.cabezudo.treasurehunt.calibrationdataset.CalibrationCaptureState,
    ) {
        if (closed && !state.closed) return
        onEvent(
            FrameAnalysisEvent.StateChanged(
                metrics.updateCalibrationCapture(state, monotonicNanos()),
            ),
        )
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}

private fun Matrix.toMatrix3(): Matrix3 {
    val androidValues = FloatArray(9)
    getValues(androidValues)
    return Matrix3(androidValues.map(Float::toDouble))
}
