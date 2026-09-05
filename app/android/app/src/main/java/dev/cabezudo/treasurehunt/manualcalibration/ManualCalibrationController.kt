package dev.cabezudo.treasurehunt.manualcalibration

import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDiversityEvaluator
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationSampleRepository
import dev.cabezudo.treasurehunt.camera.OpenCvRuntime
import dev.cabezudo.treasurehunt.camera.OpenCvStatus
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ManualCalibrationController(
    directory: File,
    private val postResult: ((() -> Unit) -> Unit),
    private val onStateChanged: (ManualCalibrationState) -> Unit,
    private val calibrator: ManualCameraCalibrator = ManualCameraCalibrator(),
    private val datasetRepository: CalibrationSampleRepository = CalibrationSampleRepository(directory),
    private val resultRepository: ManualCalibrationResultRepository =
        ManualCalibrationResultRepository(directory),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "treasure-hunt-manual-calibration")
    },
) {
    @Volatile
    private var closed = false
    private var state = ManualCalibrationState(resultFilePath = resultRepository.file.absolutePath)

    fun initialize() {
        if (closed) return
        val loadedDataset = datasetRepository.load()
        val dataset = loadedDataset.dataset
        if (dataset == null) {
            update(
                status = if (loadedDataset.error == null) {
                    ManualCalibrationStatus.NOT_AVAILABLE
                } else {
                    ManualCalibrationStatus.INVALID
                },
                cause = loadedDataset.error ?: "No existe el dataset físico interno.",
            )
            return
        }
        val datasetSha = try {
            Sha256.of(datasetRepository.file)
        } catch (error: Exception) {
            update(ManualCalibrationStatus.ERROR, cause = rootCauseMessage(error))
            return
        }
        try {
            ManualCalibrationValidator.validateDataset(dataset, datasetSha)
        } catch (error: Exception) {
            update(ManualCalibrationStatus.INVALID, cause = rootCauseMessage(error))
            return
        }
        if (!CalibrationDiversityEvaluator().coverage(dataset.samples).ready) {
            update(ManualCalibrationStatus.INVALID, cause = "El dataset no tiene diversidad suficiente.")
            return
        }
        val loadedResult = resultRepository.load(
            ManualCalibrationIdentity.from(dataset.identity),
            datasetSha,
        )
        when {
            loadedResult.result != null -> update(
                status = ManualCalibrationStatus.CALIBRATED,
                result = loadedResult.result,
                cause = null,
            )
            loadedResult.incompatible -> update(
                status = ManualCalibrationStatus.INCOMPATIBLE,
                cause = loadedResult.error,
            )
            loadedResult.error != null -> update(
                status = ManualCalibrationStatus.INVALID,
                cause = loadedResult.error,
            )
            else -> update(
                status = ManualCalibrationStatus.READY,
                cause = "Dataset físico verificado y listo; cálculo manual pendiente.",
            )
        }
    }

    fun calculate() {
        if (closed || state.status == ManualCalibrationStatus.CALCULATING) return
        val loaded = datasetRepository.load()
        val dataset = loaded.dataset
        if (dataset == null) {
            update(ManualCalibrationStatus.INVALID, cause = loaded.error ?: "No existe el dataset.")
            return
        }
        if (!CalibrationDiversityEvaluator().coverage(dataset.samples).ready) {
            update(ManualCalibrationStatus.INVALID, cause = "El dataset no es suficiente y diverso.")
            return
        }
        val openCv = OpenCvRuntime.initialize()
        if (openCv.status != OpenCvStatus.READY) {
            update(ManualCalibrationStatus.ERROR, cause = openCv.error ?: "OpenCV no está disponible.")
            return
        }
        update(ManualCalibrationStatus.CALCULATING, cause = null)
        executor.execute {
            val outcome = runCatching {
                val sha = Sha256.of(datasetRepository.file)
                calibrator.calibrate(dataset, sha).also(resultRepository::save)
            }
            postResult {
                if (closed) return@postResult
                outcome.fold(
                    onSuccess = { result ->
                        update(
                            status = ManualCalibrationStatus.CALIBRATED,
                            result = result,
                            cause = null,
                        )
                    },
                    onFailure = { error ->
                        update(ManualCalibrationStatus.ERROR, cause = rootCauseMessage(error))
                    },
                )
            }
        }
    }

    fun showJson() {
        if (closed) return
        state = state.copy(jsonPreview = resultRepository.readJson())
        onStateChanged(state)
    }

    fun currentState(): ManualCalibrationState = state

    fun close() {
        if (closed) return
        closed = true
        executor.shutdown()
        state = state.copy(status = ManualCalibrationStatus.CLOSED, cause = null)
    }

    private fun update(
        status: ManualCalibrationStatus,
        result: ManualCameraCalibrationResult? = state.result,
        cause: String? = state.cause,
    ) {
        if (closed) return
        state = state.copy(status = status, result = result, cause = cause, jsonPreview = null)
        onStateChanged(state)
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
