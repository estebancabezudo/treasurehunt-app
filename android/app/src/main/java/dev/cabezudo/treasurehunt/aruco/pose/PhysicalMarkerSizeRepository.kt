package dev.cabezudo.treasurehunt.aruco.pose

import dev.cabezudo.treasurehunt.calibrationdataset.AtomicCalibrationDatasetWriter
import java.io.File

class PhysicalMarkerSizeRepository(directory: File) {
    private val file = File(directory, "aruco_27_physical_size_v1.json")
    private val writer = AtomicCalibrationDatasetWriter()

    fun save(widthMillimeters: Double, heightMillimeters: Double): PhysicalMarkerSize {
        val size = PhysicalMarkerSize(widthMillimeters, heightMillimeters, confirmed = true)
        validate(size)
        writer.writeAtomically(
            file,
            """{"schemaVersion":1,"dictionary":"DICT_4X4_50","markerId":27,"widthMillimeters":$widthMillimeters,"heightMillimeters":$heightMillimeters}
""",
        )
        return size
    }

    fun load(): PhysicalMarkerSize? {
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        require(Regex("\"schemaVersion\"\\s*:\\s*1").containsMatchIn(text))
        require(Regex("\"dictionary\"\\s*:\\s*\"DICT_4X4_50\"").containsMatchIn(text))
        require(Regex("\"markerId\"\\s*:\\s*27").containsMatchIn(text))
        val width = number(text, "widthMillimeters")
        val height = number(text, "heightMillimeters")
        return PhysicalMarkerSize(width, height, confirmed = true).also(::validate)
    }

    private fun number(text: String, name: String): Double = Regex(
        "\"$name\"\\s*:\\s*(-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)",
    ).find(text)?.groupValues?.get(1)?.toDouble()
        ?: error("Falta $name en la medida física del marcador.")

    private fun validate(size: PhysicalMarkerSize) {
        require(size.widthMillimeters.isFinite() && size.widthMillimeters > 0.0)
        require(size.heightMillimeters.isFinite() && size.heightMillimeters > 0.0)
    }
}
