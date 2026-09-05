package dev.cabezudo.treasurehunt.manualcalibration

import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationDeviceOrientation
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationGridPosition
import dev.cabezudo.treasurehunt.calibrationdataset.CalibrationScale
import dev.cabezudo.treasurehunt.calibrationdataset.SimpleJsonParser
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect

class ManualCalibrationResultJson {
    fun encode(result: ManualCameraCalibrationResult): String = buildString {
        append("{\n  \"schemaVersion\": ${result.schemaVersion},")
        append("\n  \"identity\": ").appendIdentity(result.identity, "  ").append(',')
        append("\n  \"datasetSha256\": ").quoted(result.datasetSha256).append(',')
        append("\n  \"calculatedAtEpochMillis\": ${result.calculatedAtEpochMillis},")
        append("\n  \"openCvVersion\": ").quoted(result.openCvVersion).append(',')
        append("\n  \"distortionModel\": ").quoted(result.distortionModel).append(',')
        append("\n  \"outlierCriterion\": ").quoted(result.outlierCriterion).append(',')
        append("\n  \"detectedOutlierSampleIndices\": ")
            .appendInts(result.detectedOutlierSampleIndices).append(',')
        append("\n  \"comparativeCalibrationDecision\": ")
            .quoted(result.comparativeCalibrationDecision).append(',')
        append("\n  \"primary\": ").appendRun(result.primary, "  ").append(',')
        append("\n  \"comparativeWithoutOutliers\": ")
        result.comparativeWithoutOutliers?.let { appendRun(it, "  ") } ?: append("null")
        append("\n}\n")
    }

    fun decode(text: String): ManualCameraCalibrationResult {
        val root = SimpleJsonParser(text).parse().objectValue("raíz")
        val schema = root.int("schemaVersion")
        require(schema == MANUAL_CALIBRATION_SCHEMA_VERSION) {
            "Esquema de calibración incompatible: $schema."
        }
        val result = ManualCameraCalibrationResult(
            schemaVersion = schema,
            identity = root.obj("identity").identity(),
            datasetSha256 = root.string("datasetSha256"),
            calculatedAtEpochMillis = root.long("calculatedAtEpochMillis"),
            openCvVersion = root.string("openCvVersion"),
            distortionModel = root.string("distortionModel"),
            outlierCriterion = root.string("outlierCriterion"),
            detectedOutlierSampleIndices = root.array("detectedOutlierSampleIndices").map {
                it.number("outlier").toIntExact()
            },
            comparativeCalibrationDecision = root.string("comparativeCalibrationDecision"),
            primary = root.obj("primary").run(),
            comparativeWithoutOutliers = root["comparativeWithoutOutliers"]?.objectValue(
                "comparativeWithoutOutliers",
            )?.run(),
        )
        validateResult(result)
        return result
    }

    private fun validateResult(result: ManualCameraCalibrationResult) {
        require(result.datasetSha256.length == 64)
        require(result.calculatedAtEpochMillis > 0)
        require(result.openCvVersion.isNotBlank())
        require(result.distortionModel == "OPENCV_STANDARD_5_K1_K2_P1_P2_K3")
        ManualCalibrationValidator.validateRun(result.primary, result.identity)
        result.comparativeWithoutOutliers?.let {
            require(it.excludedSampleIndices == result.detectedOutlierSampleIndices.sorted())
            ManualCalibrationValidator.validateRun(it, result.identity)
        }
    }

    private fun StringBuilder.appendIdentity(
        value: ManualCalibrationIdentity,
        indent: String,
    ): StringBuilder {
        append("{\n${indent}  \"cameraId\": ").quoted(value.cameraId).append(',')
        append("\n${indent}  \"bufferWidth\": ${value.bufferWidth},")
        append("\n${indent}  \"bufferHeight\": ${value.bufferHeight},")
        append("\n${indent}  \"cropRect\": {\"left\": ${value.cropRect.left}, ")
        append("\"top\": ${value.cropRect.top}, \"right\": ${value.cropRect.right}, ")
        append("\"bottom\": ${value.cropRect.bottom}},")
        append("\n${indent}  \"internalColumns\": ${value.internalColumns},")
        append("\n${indent}  \"internalRows\": ${value.internalRows},")
        append("\n${indent}  \"horizontalSquareSizeMm\": ${value.horizontalSquareSizeMm},")
        append("\n${indent}  \"verticalSquareSizeMm\": ${value.verticalSquareSizeMm},")
        append("\n${indent}  \"coordinateSystem\": ").quoted(value.coordinateSystem)
        return append("\n$indent}")
    }

    private fun StringBuilder.appendRun(value: CalibrationRunResult, indent: String): StringBuilder {
        append("{\n${indent}  \"usedSampleIndices\": ").appendInts(value.usedSampleIndices).append(',')
        append("\n${indent}  \"excludedSampleIndices\": ").appendInts(value.excludedSampleIndices).append(',')
        append("\n${indent}  \"intrinsicMatrix\": ").appendDoubles(value.intrinsicMatrix).append(',')
        append("\n${indent}  \"distortionCoefficients\": ")
            .appendDoubles(value.distortionCoefficients).append(',')
        append("\n${indent}  \"globalRmsPixels\": ${value.globalRmsPixels},")
        append("\n${indent}  \"errorSummary\": {")
        append("\"meanSampleRmsPixels\": ${value.errorSummary.meanSampleRmsPixels}, ")
        append("\"medianSampleRmsPixels\": ${value.errorSummary.medianSampleRmsPixels}, ")
        append("\"maximumSampleRmsPixels\": ${value.errorSummary.maximumSampleRmsPixels}, ")
        append("\"percentile95SampleRmsPixels\": ${value.errorSummary.percentile95SampleRmsPixels}},")
        append("\n${indent}  \"sampleErrors\": [")
        value.sampleErrors.forEachIndexed { index, error ->
            if (index > 0) append(',')
            append("\n${indent}    {\"sampleIndex\": ${error.sampleIndex}, ")
            append("\"rmsPixels\": ${error.rmsPixels}, \"meanPixels\": ${error.meanPixels}, ")
            append("\"maximumPixels\": ${error.maximumPixels}, \"orientation\": ")
                .quoted(error.orientation.name)
            append(", \"scale\": ").quoted(error.scale.name)
            append(", \"gridPosition\": ").quoted(error.gridPosition.name).append('}')
        }
        if (value.sampleErrors.isNotEmpty()) append("\n${indent}  ")
        append("]\n$indent}")
        return this
    }

    private fun StringBuilder.appendInts(values: List<Int>): StringBuilder =
        append(values.joinToString(prefix = "[", postfix = "]", separator = ","))

    private fun StringBuilder.appendDoubles(values: List<Double>): StringBuilder =
        append(values.joinToString(prefix = "[", postfix = "]", separator = ","))

    private fun StringBuilder.quoted(value: String): StringBuilder {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        return append('"')
    }

    private fun Map<String, Any?>.identity(): ManualCalibrationIdentity {
        val crop = obj("cropRect")
        return ManualCalibrationIdentity(
            cameraId = string("cameraId"),
            bufferWidth = int("bufferWidth"),
            bufferHeight = int("bufferHeight"),
            cropRect = CoordinateRect(crop.int("left"), crop.int("top"), crop.int("right"), crop.int("bottom")),
            internalColumns = int("internalColumns"),
            internalRows = int("internalRows"),
            horizontalSquareSizeMm = double("horizontalSquareSizeMm"),
            verticalSquareSizeMm = double("verticalSquareSizeMm"),
            coordinateSystem = string("coordinateSystem"),
        )
    }

    private fun Map<String, Any?>.run(): CalibrationRunResult {
        val summary = obj("errorSummary")
        return CalibrationRunResult(
            usedSampleIndices = array("usedSampleIndices").map { it.number("used").toIntExact() },
            excludedSampleIndices = array("excludedSampleIndices").map { it.number("excluded").toIntExact() },
            intrinsicMatrix = array("intrinsicMatrix").map { it.number("intrinsic") },
            distortionCoefficients = array("distortionCoefficients").map { it.number("distortion") },
            globalRmsPixels = double("globalRmsPixels"),
            sampleErrors = array("sampleErrors").map { raw ->
                val value = raw.objectValue("sampleError")
                SampleReprojectionError(
                    sampleIndex = value.int("sampleIndex"),
                    rmsPixels = value.double("rmsPixels"),
                    meanPixels = value.double("meanPixels"),
                    maximumPixels = value.double("maximumPixels"),
                    orientation = enumValueOf(value.string("orientation")),
                    scale = enumValueOf(value.string("scale")),
                    gridPosition = enumValueOf(value.string("gridPosition")),
                )
            },
            errorSummary = ReprojectionErrorSummary(
                meanSampleRmsPixels = summary.double("meanSampleRmsPixels"),
                medianSampleRmsPixels = summary.double("medianSampleRmsPixels"),
                maximumSampleRmsPixels = summary.double("maximumSampleRmsPixels"),
                percentile95SampleRmsPixels = summary.double("percentile95SampleRmsPixels"),
            ),
        )
    }

    private fun Map<String, Any?>.obj(key: String) = get(key).objectValue(key)
    private fun Map<String, Any?>.array(key: String) = get(key) as? List<Any?> ?: error("$key no es arreglo.")
    private fun Map<String, Any?>.string(key: String) = get(key) as? String ?: error("$key no es string.")
    private fun Map<String, Any?>.double(key: String) = get(key).number(key)
    private fun Map<String, Any?>.int(key: String) = double(key).toIntExact()
    private fun Map<String, Any?>.long(key: String): Long = double(key).toLong().also {
        require(it.toDouble() == double(key))
    }
    @Suppress("UNCHECKED_CAST")
    private fun Any?.objectValue(name: String) = this as? Map<String, Any?>
        ?: error("$name no es objeto.")
    private fun Any?.number(name: String) = (this as? Number)?.toDouble()
        ?: error("$name no es número.")
    private fun Double.toIntExact(): Int = toInt().also { require(it.toDouble() == this) }
}
