package dev.cabezudo.treasurehunt.calibrationdataset

import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetectionState
import dev.cabezudo.treasurehunt.calibrationboard.CalibrationBoardDetectionStatus
import dev.cabezudo.treasurehunt.camera.DiagnosticProcessingMode
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import kotlin.math.abs
import kotlin.math.hypot
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class CalibrationSampleCollectorConfig(
    val requestTimeoutNanos: Long = 5_000_000_000L,
)

fun interface CalibrationTimeoutTask {
    fun cancel()
}

interface CalibrationRequestTimeoutScheduler : AutoCloseable {
    fun schedule(delayNanos: Long, action: () -> Unit): CalibrationTimeoutTask
}

class ExecutorCalibrationRequestTimeoutScheduler : CalibrationRequestTimeoutScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "calibration-sample-timeout").apply { isDaemon = true }
    }

    override fun schedule(delayNanos: Long, action: () -> Unit): CalibrationTimeoutTask {
        val future: ScheduledFuture<*> = executor.schedule(
            action,
            delayNanos.coerceAtLeast(0L),
            TimeUnit.NANOSECONDS,
        )
        return CalibrationTimeoutTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

class CalibrationSampleCollector(
    private val repository: CalibrationSampleRepository,
    private val coordinateMapper: CalibrationFrameCoordinateMapper = CalibrationFrameCoordinateMapper(),
    private val diversityEvaluator: CalibrationDiversityEvaluator = CalibrationDiversityEvaluator(),
    private val config: CalibrationSampleCollectorConfig = CalibrationSampleCollectorConfig(),
    private val monotonicNanos: () -> Long,
    private val wallClockMillis: () -> Long,
    private val onStateChanged: (CalibrationCaptureState) -> Unit,
    private val timeoutScheduler: CalibrationRequestTimeoutScheduler? = null,
) : AutoCloseable {
    private var nextRequestId = 1L
    private var state: CalibrationCaptureState
    private var timeoutTask: CalibrationTimeoutTask? = null

    init {
        val loaded = repository.load()
        state = if (loaded.dataset != null) {
            stateForDataset(
                loaded.dataset,
                confirmedHorizontalMeasurement = loaded.dataset.identity.horizontalSquareSizeMm,
                confirmedVerticalMeasurement = loaded.dataset.identity.verticalSquareSizeMm,
            )
        } else {
            CalibrationCaptureState(
                datasetFilePath = repository.file.absolutePath,
                coverage = diversityEvaluator.coverage(emptyList()),
                loadError = loaded.error,
                lastRejection = loaded.error?.let {
                    CalibrationCaptureRejection(
                        CalibrationCaptureRejectionReason.CORRUPT_DATASET,
                        it,
                        wallClockMillis(),
                    )
                },
            )
        }
    }

    @Synchronized
    fun currentState(): CalibrationCaptureState = state

    @Synchronized
    fun confirmPhysicalMeasurement(
        horizontalSquareSizeMm: Double,
        verticalSquareSizeMm: Double,
    ): CalibrationCaptureState {
        if (
            !horizontalSquareSizeMm.isFinite() || horizontalSquareSizeMm <= 0.0 ||
            !verticalSquareSizeMm.isFinite() || verticalSquareSizeMm <= 0.0
        ) {
            return reject(
                CalibrationCaptureRejectionReason.PHYSICAL_MEASUREMENT_NOT_CONFIRMED,
                "Las medidas horizontal y vertical deben ser números positivos en milímetros.",
            )
        }
        val identity = state.dataset?.identity
        if (
            identity != null &&
            (
                abs(identity.horizontalSquareSizeMm - horizontalSquareSizeMm) > 1e-9 ||
                    abs(identity.verticalSquareSizeMm - verticalSquareSizeMm) > 1e-9
                )
        ) {
            return reject(
                CalibrationCaptureRejectionReason.DATASET_IDENTITY_MISMATCH,
                "El dataset usa X=${identity.horizontalSquareSizeMm} mm, " +
                    "Y=${identity.verticalSquareSizeMm} mm y no puede cambiarse a " +
                    "X=$horizontalSquareSizeMm mm, Y=$verticalSquareSizeMm mm.",
            )
        }
        state = state.copy(
            confirmedHorizontalSquareSizeMm = horizontalSquareSizeMm,
            confirmedVerticalSquareSizeMm = verticalSquareSizeMm,
            lastRejection = null,
        )
        publish()
        return state
    }

    @Synchronized
    fun requestCapture(mode: DiagnosticProcessingMode): CalibrationCaptureState {
        if (state.closed) return state
        if (mode != DiagnosticProcessingMode.CAMERA_CALIBRATION) {
            return reject(
                CalibrationCaptureRejectionReason.OUTSIDE_CALIBRATION_MODE,
                "Modo actual: ${mode.name}.",
            )
        }
        if (state.loadError != null) {
            return reject(CalibrationCaptureRejectionReason.CORRUPT_DATASET, state.loadError!!)
        }
        if (
            state.confirmedHorizontalSquareSizeMm == null ||
            state.confirmedVerticalSquareSizeMm == null
        ) {
            return reject(
                CalibrationCaptureRejectionReason.PHYSICAL_MEASUREMENT_NOT_CONFIRMED,
                "Imprime el PDF al 100 %, mide varios cuadros y confirma la medida observada.",
            )
        }
        if (state.pendingRequest != null) return state
        val now = monotonicNanos()
        state = state.copy(
            pendingRequest = CalibrationSampleRequest(
                id = nextRequestId++,
                requestedAtNanos = now,
                deadlineNanos = now + config.requestTimeoutNanos,
            ),
            lastRejection = null,
            jsonPreview = null,
        )
        scheduleTimeout(requireNotNull(state.pendingRequest))
        publish()
        return state
    }

    @Synchronized
    fun cancelPending(): CalibrationCaptureState {
        if (state.pendingRequest == null) return state
        return reject(
            CalibrationCaptureRejectionReason.REQUEST_CANCELLED,
            "La solicitud fue cancelada antes de capturar un cuadro válido.",
        )
    }

    @Synchronized
    fun processCurrentFrame(
        mode: DiagnosticProcessingMode,
        detection: CalibrationBoardDetectionState,
        metadata: CalibrationFrameCaptureMetadata,
        nowNanos: Long,
    ): CalibrationCaptureState {
        val request = state.pendingRequest ?: return state
        if (mode != DiagnosticProcessingMode.CAMERA_CALIBRATION) {
            return reject(
                CalibrationCaptureRejectionReason.OUTSIDE_CALIBRATION_MODE,
                "La solicitud dejó de estar en CAMERA_CALIBRATION.",
            )
        }
        if (nowNanos > request.deadlineNanos) {
            return reject(
                if (request.lastInvalidDetectionAtNanos != null) {
                    CalibrationCaptureRejectionReason.BOARD_NOT_DETECTED
                } else {
                    CalibrationCaptureRejectionReason.DETECTION_EXPIRED
                },
                "No llegó una detección válida en ${config.requestTimeoutNanos / 1_000_000} ms.",
            )
        }
        if (!detection.analyzedThisFrame || detection.analyzedAtNanos != nowNanos) return state
        if (
            detection.status != CalibrationBoardDetectionStatus.DETECTED ||
            detection.corners.size != 54
        ) {
            state = state.copy(
                pendingRequest = request.copy(lastInvalidDetectionAtNanos = nowNanos),
            )
            return state
        }
        val horizontalMeasurement = state.confirmedHorizontalSquareSizeMm ?: return reject(
            CalibrationCaptureRejectionReason.PHYSICAL_MEASUREMENT_NOT_CONFIRMED,
            "La medida horizontal dejó de estar disponible.",
        )
        val verticalMeasurement = state.confirmedVerticalSquareSizeMm ?: return reject(
            CalibrationCaptureRejectionReason.PHYSICAL_MEASUREMENT_NOT_CONFIRMED,
            "La medida vertical dejó de estar disponible.",
        )
        val canonical = try {
            coordinateMapper.mapAndValidate(detection.corners, metadata)
        } catch (error: Exception) {
            return reject(
                CalibrationCaptureRejectionReason.INVALID_GEOMETRY,
                rootCauseMessage(error),
            )
        }
        val cameraId = metadata.cameraId?.takeIf(String::isNotBlank) ?: return reject(
            CalibrationCaptureRejectionReason.DATASET_IDENTITY_MISMATCH,
            "Camera2 no informó el ID real de cámara.",
        )
        val identity = CalibrationDatasetIdentity(
            cameraId = cameraId,
            bufferWidth = metadata.bufferWidth,
            bufferHeight = metadata.bufferHeight,
            cropRect = metadata.cropRect,
            horizontalSquareSizeMm = horizontalMeasurement,
            verticalSquareSizeMm = verticalMeasurement,
        )
        val existing = state.dataset
        if (existing != null && existing.identity != identity) {
            return reject(
                CalibrationCaptureRejectionReason.DATASET_IDENTITY_MISMATCH,
                "Actual=$identity; dataset=${existing.identity}. Inicia otro dataset.",
            )
        }
        val areaFraction = polygonArea(extremeCorners(canonical)) /
            (metadata.bufferWidth.toDouble() * metadata.bufferHeight)
        val signature = diversityEvaluator.signature(
            canonical,
            metadata.bufferWidth,
            metadata.bufferHeight,
            areaFraction,
            metadata.rotationDegrees,
        )
        if (diversityEvaluator.isDuplicate(signature, existing?.samples.orEmpty())) {
            return reject(
                CalibrationCaptureRejectionReason.DUPLICATE,
                "RMS normalizado menor que el umbral experimental 0.012.",
            )
        }
        val quality = diversityEvaluator.geometricQuality(canonical, areaFraction)
        if (!diversityEvaluator.isQualitySufficient(quality)) {
            return reject(
                CalibrationCaptureRejectionReason.INSUFFICIENT_QUALITY,
                "Calidad geométrica $quality menor que el umbral 0.15.",
            )
        }
        val nowMillis = wallClockMillis()
        val sample = CalibrationSample(
            index = (existing?.samples?.size ?: 0) + 1,
            canonicalCorners = canonical,
            canonicalWidth = metadata.bufferWidth,
            canonicalHeight = metadata.bufferHeight,
            originalRotationDegrees = metadata.rotationDegrees,
            originalCropRect = metadata.cropRect,
            sensorToBufferTransform = metadata.sensorToBufferTransform,
            areaFraction = areaFraction,
            normalizedCenter = RecognitionPoint(
                canonical.map { it.x }.average() / metadata.bufferWidth,
                canonical.map { it.y }.average() / metadata.bufferHeight,
            ),
            approximateWidthPixels = (
                distance(canonical[0], canonical[8]) + distance(canonical[45], canonical[53])
                ) / 2.0,
            approximateHeightPixels = (
                distance(canonical[0], canonical[45]) + distance(canonical[8], canonical[53])
                ) / 2.0,
            capturedAtEpochMillis = nowMillis,
            geometricQuality = quality,
            diversity = signature,
            horizontalSquareSizeMm = horizontalMeasurement,
            verticalSquareSizeMm = verticalMeasurement,
        )
        val updated = if (existing == null) {
            CalibrationDataset(
                identity = identity,
                createdAtEpochMillis = nowMillis,
                updatedAtEpochMillis = nowMillis,
                samples = listOf(sample),
                rejectedSamples = state.rejectedSamples,
                lastRejection = state.lastRejection,
            )
        } else {
            existing.copy(updatedAtEpochMillis = nowMillis, samples = existing.samples + sample)
        }
        try {
            repository.save(updated)
        } catch (error: Exception) {
            return reject(CalibrationCaptureRejectionReason.WRITE_ERROR, rootCauseMessage(error))
        }
        cancelTimeout()
        state = stateForDataset(
            updated,
            horizontalMeasurement,
            verticalMeasurement,
        ).copy(pendingRequest = null)
        publish()
        return state
    }

    @Synchronized
    fun showJson(): CalibrationCaptureState {
        state = state.copy(jsonPreview = repository.readJson())
        publish()
        return state
    }

    @Synchronized
    fun resetDataset(): CalibrationCaptureState {
        cancelTimeout()
        return try {
            repository.reset()
            state = CalibrationCaptureState(
                confirmedHorizontalSquareSizeMm = state.confirmedHorizontalSquareSizeMm,
                confirmedVerticalSquareSizeMm = state.confirmedVerticalSquareSizeMm,
                datasetFilePath = repository.file.absolutePath,
                coverage = diversityEvaluator.coverage(emptyList()),
            )
            publish()
            state
        } catch (error: Exception) {
            reject(CalibrationCaptureRejectionReason.WRITE_ERROR, rootCauseMessage(error))
        }
    }

    @Synchronized
    override fun close() {
        if (state.closed) return
        if (state.pendingRequest != null) {
            reject(CalibrationCaptureRejectionReason.CLOSED, "El analizador se cerró antes del siguiente cuadro válido.")
        }
        state = state.copy(closed = true, pendingRequest = null)
        cancelTimeout()
        timeoutScheduler?.close()
        publish()
    }

    private fun reject(
        reason: CalibrationCaptureRejectionReason,
        detail: String,
    ): CalibrationCaptureState {
        cancelTimeout()
        val rejection = CalibrationCaptureRejection(reason, detail, wallClockMillis())
        val dataset = state.dataset?.copy(
            updatedAtEpochMillis = wallClockMillis(),
            rejectedSamples = state.rejectedSamples + 1,
            lastRejection = rejection,
        )
        if (dataset != null && reason != CalibrationCaptureRejectionReason.WRITE_ERROR) {
            try {
                repository.save(dataset)
            } catch (error: Exception) {
                state = state.copy(
                    pendingRequest = null,
                    rejectedSamples = state.rejectedSamples + 1,
                    lastRejection = CalibrationCaptureRejection(
                        CalibrationCaptureRejectionReason.WRITE_ERROR,
                        rootCauseMessage(error),
                        wallClockMillis(),
                    ),
                )
                publish()
                return state
            }
        }
        state = state.copy(
            dataset = dataset ?: state.dataset,
            pendingRequest = null,
            rejectedSamples = state.rejectedSamples + 1,
            lastRejection = rejection,
            jsonPreview = null,
        )
        publish()
        return state
    }

    private fun stateForDataset(
        dataset: CalibrationDataset,
        confirmedHorizontalMeasurement: Double,
        confirmedVerticalMeasurement: Double,
    ): CalibrationCaptureState = CalibrationCaptureState(
        dataset = dataset,
        confirmedHorizontalSquareSizeMm = confirmedHorizontalMeasurement,
        confirmedVerticalSquareSizeMm = confirmedVerticalMeasurement,
        acceptedSamples = dataset.samples.size,
        rejectedSamples = dataset.rejectedSamples,
        lastRejection = dataset.lastRejection,
        coverage = diversityEvaluator.coverage(dataset.samples),
        datasetFilePath = repository.file.absolutePath,
        datasetIdentity = dataset.identity,
    )

    private fun publish() = onStateChanged(state)

    private fun scheduleTimeout(request: CalibrationSampleRequest) {
        val scheduler = timeoutScheduler ?: return
        timeoutTask = scheduler.schedule(request.deadlineNanos - monotonicNanos()) {
            expireRequest(request.id)
        }
    }

    @Synchronized
    private fun expireRequest(requestId: Long) {
        val request = state.pendingRequest ?: return
        if (state.closed || request.id != requestId) return
        val remaining = request.deadlineNanos - monotonicNanos()
        if (remaining > 0L) {
            timeoutTask = timeoutScheduler?.schedule(remaining) { expireRequest(requestId) }
            return
        }
        reject(
            if (request.lastInvalidDetectionAtNanos != null) {
                CalibrationCaptureRejectionReason.BOARD_NOT_DETECTED
            } else {
                CalibrationCaptureRejectionReason.DETECTION_EXPIRED
            },
            "No llegó una detección válida en ${config.requestTimeoutNanos / 1_000_000} ms.",
        )
    }

    private fun cancelTimeout() {
        timeoutTask?.cancel()
        timeoutTask = null
    }

    private fun extremeCorners(points: List<RecognitionPoint>) =
        listOf(points[0], points[8], points[53], points[45])

    private fun polygonArea(points: List<RecognitionPoint>): Double = kotlin.math.abs(
        points.indices.sumOf { index ->
            val next = points[(index + 1) % points.size]
            points[index].x * next.y - next.x * points[index].y
        } / 2.0,
    )

    private fun distance(first: RecognitionPoint, second: RecognitionPoint) =
        hypot(second.x - first.x, second.y - first.y)

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
