package dev.cabezudo.treasurehunt.calibrationdataset

import java.io.File
import java.io.FileOutputStream

data class CalibrationDatasetLoadResult(
    val dataset: CalibrationDataset? = null,
    val error: String? = null,
)

fun interface CalibrationDatasetWriter {
    fun writeAtomically(target: File, content: String)
}

class AtomicCalibrationDatasetWriter : CalibrationDatasetWriter {
    override fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        check(temporary.renameTo(target)) {
            "No se pudo reemplazar atómicamente ${target.absolutePath}."
        }
    }
}

class CalibrationSampleRepository(
    directory: File,
    private val codec: CalibrationDatasetJson = CalibrationDatasetJson(),
    private val writer: CalibrationDatasetWriter = AtomicCalibrationDatasetWriter(),
) {
    val file = File(directory, "calibration_dataset_v1.json")

    fun load(): CalibrationDatasetLoadResult {
        if (!file.exists()) return CalibrationDatasetLoadResult()
        return try {
            val stored = file.readText(Charsets.UTF_8)
            val dataset = codec.decode(stored)
            val canonical = codec.encode(dataset)
            if (stored != canonical) writer.writeAtomically(file, canonical)
            CalibrationDatasetLoadResult(dataset = dataset)
        } catch (error: Exception) {
            CalibrationDatasetLoadResult(error = rootCauseMessage(error))
        }
    }

    fun save(dataset: CalibrationDataset) {
        writer.writeAtomically(file, codec.encode(dataset))
    }

    fun readJson(): String? = file.takeIf(File::exists)?.readText(Charsets.UTF_8)

    fun reset() {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        if (temporary.exists() && !temporary.delete()) error("No se pudo eliminar el archivo temporal.")
        if (file.exists() && !file.delete()) error("No se pudo eliminar el dataset.")
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
