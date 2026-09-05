package dev.cabezudo.treasurehunt.manualcalibration

import dev.cabezudo.treasurehunt.calibrationdataset.AtomicCalibrationDatasetWriter
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDatasetWriter
import java.io.File

class ManualCalibrationResultRepository(
    directory: File,
    private val codec: ManualCalibrationResultJson = ManualCalibrationResultJson(),
    private val writer: CalibrationDatasetWriter = AtomicCalibrationDatasetWriter(),
) {
    val file = File(directory, "camera_calibration_v1.json")

    fun save(result: ManualCameraCalibrationResult) {
        writer.writeAtomically(file, codec.encode(result))
    }

    fun load(
        expectedIdentity: ManualCalibrationIdentity,
        expectedDatasetSha256: String,
    ): CalibrationResultLoad {
        if (!file.exists()) return CalibrationResultLoad()
        return try {
            val result = codec.decode(file.readText(Charsets.UTF_8))
            if (result.identity != expectedIdentity || result.datasetSha256 != expectedDatasetSha256) {
                CalibrationResultLoad(
                    incompatible = true,
                    error = "La identidad de cámara, buffer, crop, coordenadas o dataset no coincide.",
                )
            } else {
                CalibrationResultLoad(result = result)
            }
        } catch (error: Exception) {
            CalibrationResultLoad(error = rootCauseMessage(error))
        }
    }

    fun readJson(): String? = file.takeIf(File::isFile)?.readText(Charsets.UTF_8)

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
