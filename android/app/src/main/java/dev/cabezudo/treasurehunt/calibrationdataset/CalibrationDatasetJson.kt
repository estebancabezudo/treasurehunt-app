package dev.cabezudo.treasurehunt.calibrationdataset

import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint

class CalibrationDatasetJson {
    fun encode(dataset: CalibrationDataset): String = buildString {
        append("{\n  \"schemaVersion\": ").append(dataset.identity.schemaVersion)
        append(",\n  \"identity\": ").appendIdentity(dataset.identity, "  ")
        append(",\n  \"createdAtEpochMillis\": ").append(dataset.createdAtEpochMillis)
        append(",\n  \"updatedAtEpochMillis\": ").append(dataset.updatedAtEpochMillis)
        append(",\n  \"rejectedSamples\": ").append(dataset.rejectedSamples)
        append(",\n  \"lastRejection\": ")
        dataset.lastRejection?.let { appendRejection(it, "  ") } ?: append("null")
        append(",\n  \"samples\": [")
        dataset.samples.forEachIndexed { index, sample ->
            if (index > 0) append(',')
            append("\n    ").appendSample(sample, "    ")
        }
        if (dataset.samples.isNotEmpty()) append('\n').append("  ")
        append("]\n}\n")
    }

    fun decode(text: String): CalibrationDataset {
        val root = SimpleJsonParser(text).parse().asObject("raíz")
        val schema = root.requiredInt("schemaVersion")
        require(schema == CALIBRATION_DATASET_SCHEMA_VERSION) {
            "Esquema incompatible: $schema; esperado $CALIBRATION_DATASET_SCHEMA_VERSION."
        }
        val identity = root.requiredObject("identity").toIdentity()
        require(identity.schemaVersion == schema)
        val samples = root.requiredArray("samples").mapIndexed { index, value ->
            value.asObject("samples[$index]").toSample()
        }
        val indices = samples.map { it.index }
        require(indices == (1..samples.size).toList()) { "Los índices de muestras no son consecutivos." }
        return CalibrationDataset(
            identity = identity,
            createdAtEpochMillis = root.requiredLong("createdAtEpochMillis"),
            updatedAtEpochMillis = root.requiredLong("updatedAtEpochMillis"),
            samples = samples,
            rejectedSamples = root.requiredInt("rejectedSamples"),
            lastRejection = root["lastRejection"]?.asObject("lastRejection")?.toRejection(),
        ).also(::validateDataset)
    }

    private fun validateDataset(dataset: CalibrationDataset) {
        val identity = dataset.identity
        require(identity.cameraId.isNotBlank())
        require(identity.bufferWidth > 0 && identity.bufferHeight > 0)
        require(identity.cropRect.isValid())
        require(identity.internalColumns == 9 && identity.internalRows == 6)
        require(identity.horizontalSquareSizeMm.isFinite() && identity.horizontalSquareSizeMm > 0.0)
        require(identity.verticalSquareSizeMm.isFinite() && identity.verticalSquareSizeMm > 0.0)
        require(dataset.rejectedSamples >= 0)
        dataset.samples.forEach { sample ->
            require(sample.canonicalCorners.size == 54)
            require(sample.canonicalWidth == identity.bufferWidth && sample.canonicalHeight == identity.bufferHeight)
            require(sample.originalCropRect == identity.cropRect)
            require(sample.horizontalSquareSizeMm == identity.horizontalSquareSizeMm)
            require(sample.verticalSquareSizeMm == identity.verticalSquareSizeMm)
            require(sample.canonicalCorners.all { it.x.isFinite() && it.y.isFinite() })
        }
    }

    private fun StringBuilder.appendIdentity(identity: CalibrationDatasetIdentity, indent: String): StringBuilder {
        append("{\n${indent}  \"schemaVersion\": ${identity.schemaVersion},")
        append("\n${indent}  \"cameraId\": ").appendQuoted(identity.cameraId).append(',')
        append("\n${indent}  \"bufferWidth\": ${identity.bufferWidth},")
        append("\n${indent}  \"bufferHeight\": ${identity.bufferHeight},")
        append("\n${indent}  \"cropRect\": ").appendRect(identity.cropRect).append(',')
        append("\n${indent}  \"internalColumns\": ${identity.internalColumns},")
        append("\n${indent}  \"internalRows\": ${identity.internalRows},")
        append("\n${indent}  \"horizontalSquareSizeMm\": ${identity.horizontalSquareSizeMm},")
        append("\n${indent}  \"verticalSquareSizeMm\": ${identity.verticalSquareSizeMm}")
        return append("\n$indent}")
    }

    private fun StringBuilder.appendSample(sample: CalibrationSample, indent: String): StringBuilder {
        append("{\n${indent}  \"index\": ${sample.index},")
        append("\n${indent}  \"canonicalCorners\": ").appendPoints(sample.canonicalCorners).append(',')
        append("\n${indent}  \"canonicalWidth\": ${sample.canonicalWidth},")
        append("\n${indent}  \"canonicalHeight\": ${sample.canonicalHeight},")
        append("\n${indent}  \"originalRotationDegrees\": ${sample.originalRotationDegrees},")
        append("\n${indent}  \"originalCropRect\": ").appendRect(sample.originalCropRect).append(',')
        append("\n${indent}  \"sensorToBufferTransform\": ").appendNumbers(sample.sensorToBufferTransform.values).append(',')
        append("\n${indent}  \"areaFraction\": ${sample.areaFraction},")
        append("\n${indent}  \"normalizedCenter\": ").appendPoint(sample.normalizedCenter).append(',')
        append("\n${indent}  \"approximateWidthPixels\": ${sample.approximateWidthPixels},")
        append("\n${indent}  \"approximateHeightPixels\": ${sample.approximateHeightPixels},")
        append("\n${indent}  \"capturedAtEpochMillis\": ${sample.capturedAtEpochMillis},")
        append("\n${indent}  \"geometricQuality\": ${sample.geometricQuality},")
        append("\n${indent}  \"sharpness\": ${sample.sharpness ?: "null"},")
        append("\n${indent}  \"diversity\": ").appendDiversity(sample.diversity, "$indent  ").append(',')
        append("\n${indent}  \"horizontalSquareSizeMm\": ${sample.horizontalSquareSizeMm},")
        append("\n${indent}  \"verticalSquareSizeMm\": ${sample.verticalSquareSizeMm}")
        return append("\n$indent}")
    }

    private fun StringBuilder.appendDiversity(value: CalibrationDiversitySignature, indent: String): StringBuilder {
        append("{\n${indent}  \"gridPosition\": ").appendQuoted(value.gridPosition.name).append(',')
        append("\n${indent}  \"scale\": ").appendQuoted(value.scale.name).append(',')
        append("\n${indent}  \"horizontalTilt\": ").appendQuoted(value.horizontalTilt.name).append(',')
        append("\n${indent}  \"verticalTilt\": ").appendQuoted(value.verticalTilt.name).append(',')
        append("\n${indent}  \"deviceOrientation\": ").appendQuoted(value.deviceOrientation.name).append(',')
        append("\n${indent}  \"normalizedCorners\": ").appendPoints(value.normalizedCorners)
        return append("\n$indent}")
    }

    private fun StringBuilder.appendRejection(value: CalibrationCaptureRejection, indent: String): StringBuilder =
        append("{\"reason\": ").appendQuoted(value.reason.name)
            .append(", \"detail\": ").appendQuoted(value.detail)
            .append(", \"atEpochMillis\": ${value.atEpochMillis}}")

    private fun StringBuilder.appendRect(rect: CoordinateRect): StringBuilder =
        append("{\"left\": ${rect.left}, \"top\": ${rect.top}, \"right\": ${rect.right}, \"bottom\": ${rect.bottom}}")

    private fun StringBuilder.appendPoints(points: List<RecognitionPoint>): StringBuilder {
        append('[')
        points.forEachIndexed { index, point ->
            if (index > 0) append(',')
            appendPoint(point)
        }
        return append(']')
    }

    private fun StringBuilder.appendPoint(point: RecognitionPoint): StringBuilder =
        append('[').append(point.x).append(',').append(point.y).append(']')

    private fun StringBuilder.appendNumbers(values: List<Double>): StringBuilder =
        append(values.joinToString(prefix = "[", postfix = "]", separator = ","))

    private fun StringBuilder.appendQuoted(value: String): StringBuilder {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        return append('"')
    }

    private fun Map<String, Any?>.toIdentity() = CalibrationDatasetIdentity(
        schemaVersion = requiredInt("schemaVersion"),
        cameraId = requiredString("cameraId"),
        bufferWidth = requiredInt("bufferWidth"),
        bufferHeight = requiredInt("bufferHeight"),
        cropRect = requiredObject("cropRect").toRect(),
        internalColumns = requiredInt("internalColumns"),
        internalRows = requiredInt("internalRows"),
        horizontalSquareSizeMm = requiredDouble("horizontalSquareSizeMm"),
        verticalSquareSizeMm = requiredDouble("verticalSquareSizeMm"),
    )

    private fun Map<String, Any?>.toSample(): CalibrationSample {
        val diversity = requiredObject("diversity")
        return CalibrationSample(
            index = requiredInt("index"),
            canonicalCorners = requiredPoints("canonicalCorners"),
            canonicalWidth = requiredInt("canonicalWidth"),
            canonicalHeight = requiredInt("canonicalHeight"),
            originalRotationDegrees = requiredInt("originalRotationDegrees"),
            originalCropRect = requiredObject("originalCropRect").toRect(),
            sensorToBufferTransform = Matrix3(requiredArray("sensorToBufferTransform").map { it.asDouble("matriz") }),
            areaFraction = requiredDouble("areaFraction"),
            normalizedCenter = requiredPoint("normalizedCenter"),
            approximateWidthPixels = requiredDouble("approximateWidthPixels"),
            approximateHeightPixels = requiredDouble("approximateHeightPixels"),
            capturedAtEpochMillis = requiredLong("capturedAtEpochMillis"),
            geometricQuality = requiredDouble("geometricQuality"),
            sharpness = get("sharpness")?.asDouble("sharpness"),
            diversity = CalibrationDiversitySignature(
                gridPosition = enumValueOf(diversity.requiredString("gridPosition")),
                scale = enumValueOf(diversity.requiredString("scale")),
                horizontalTilt = enumValueOf(diversity.requiredString("horizontalTilt")),
                verticalTilt = enumValueOf(diversity.requiredString("verticalTilt")),
                deviceOrientation = enumValueOf(diversity.requiredString("deviceOrientation")),
                normalizedCorners = diversity.requiredPoints("normalizedCorners"),
            ),
            horizontalSquareSizeMm = requiredDouble("horizontalSquareSizeMm"),
            verticalSquareSizeMm = requiredDouble("verticalSquareSizeMm"),
        )
    }

    private fun Map<String, Any?>.toRejection() = CalibrationCaptureRejection(
        reason = enumValueOf(requiredString("reason")),
        detail = requiredString("detail"),
        atEpochMillis = requiredLong("atEpochMillis"),
    )

    private fun Map<String, Any?>.toRect() = CoordinateRect(
        requiredInt("left"), requiredInt("top"), requiredInt("right"), requiredInt("bottom"),
    )

    private fun Map<String, Any?>.requiredPoint(key: String): RecognitionPoint {
        val values = requiredArray(key)
        require(values.size == 2) { "$key no contiene un punto 2D." }
        return RecognitionPoint(values[0].asDouble(key), values[1].asDouble(key))
    }

    private fun Map<String, Any?>.requiredPoints(key: String): List<RecognitionPoint> =
        requiredArray(key).mapIndexed { index, value ->
            val point = value.asArray("$key[$index]")
            require(point.size == 2)
            RecognitionPoint(point[0].asDouble(key), point[1].asDouble(key))
        }

    private fun Map<String, Any?>.requiredObject(key: String) = get(key).asObject(key)
    private fun Map<String, Any?>.requiredArray(key: String) = get(key).asArray(key)
    private fun Map<String, Any?>.requiredString(key: String) = get(key) as? String ?: error("Falta string $key.")
    private fun Map<String, Any?>.requiredDouble(key: String) = get(key).asDouble(key)
    private fun Map<String, Any?>.requiredInt(key: String) = requiredDouble(key).toInt().also { require(it.toDouble() == requiredDouble(key)) }
    private fun Map<String, Any?>.requiredLong(key: String) = requiredDouble(key).toLong().also { require(it.toDouble() == requiredDouble(key)) }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(name: String) = this as? Map<String, Any?> ?: error("$name no es un objeto JSON.")
    @Suppress("UNCHECKED_CAST")
    private fun Any?.asArray(name: String) = this as? List<Any?> ?: error("$name no es un arreglo JSON.")
    private fun Any?.asDouble(name: String) = (this as? Number)?.toDouble() ?: error("$name no es numérico.")
}

internal class SimpleJsonParser(private val text: String) {
    private var index = 0

    fun parse(): Any? {
        val value = parseValue()
        skipWhitespace()
        require(index == text.length) { "Contenido adicional después del JSON." }
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        require(index < text.length) { "JSON incompleto." }
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        index++
        val result = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (consume('}')) return result
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            require(consume(':')) { "Falta ':' después de $key." }
            result[key] = parseValue()
            skipWhitespace()
            if (consume('}')) return result
            require(consume(',')) { "Falta ',' en objeto JSON." }
        }
    }

    private fun parseArray(): List<Any?> {
        index++
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (consume(']')) return result
        while (true) {
            result += parseValue()
            skipWhitespace()
            if (consume(']')) return result
            require(consume(',')) { "Falta ',' en arreglo JSON." }
        }
    }

    private fun parseString(): String {
        require(consume('"')) { "Se esperaba string JSON." }
        val result = StringBuilder()
        while (index < text.length) {
            val character = text[index++]
            if (character == '"') return result.toString()
            if (character != '\\') {
                result.append(character)
                continue
            }
            require(index < text.length)
            when (val escaped = text[index++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    require(index + 4 <= text.length)
                    result.append(text.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }
                else -> error("Escape JSON inválido: $escaped")
            }
        }
        error("String JSON sin cerrar.")
    }

    private fun parseNumber(): Double {
        val start = index
        if (text.getOrNull(index) == '-') index++
        while (text.getOrNull(index)?.isDigit() == true) index++
        if (text.getOrNull(index) == '.') {
            index++
            while (text.getOrNull(index)?.isDigit() == true) index++
        }
        if (text.getOrNull(index) in setOf('e', 'E')) {
            index++
            if (text.getOrNull(index) in setOf('+', '-')) index++
            while (text.getOrNull(index)?.isDigit() == true) index++
        }
        require(index > start) { "Número JSON inválido." }
        return text.substring(start, index).toDouble()
    }

    private fun <T> parseLiteral(literal: String, value: T): T {
        require(text.regionMatches(index, literal, 0, literal.length)) { "Literal JSON inválido." }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (text.getOrNull(index)?.isWhitespace() == true) index++
    }

    private fun consume(character: Char): Boolean {
        if (text.getOrNull(index) != character) return false
        index++
        return true
    }
}
