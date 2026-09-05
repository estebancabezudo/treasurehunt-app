package dev.cabezudo.treasurehunt.calibrationdataset

import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.camera.calibration.Matrix3
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint

const val CALIBRATION_DATASET_SCHEMA_VERSION = 1

data class CalibrationDatasetIdentity(
    val schemaVersion: Int = CALIBRATION_DATASET_SCHEMA_VERSION,
    val cameraId: String,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropRect: CoordinateRect,
    val internalColumns: Int = 9,
    val internalRows: Int = 6,
    val horizontalSquareSizeMm: Double,
    val verticalSquareSizeMm: Double,
)

enum class CalibrationGridPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }
enum class CalibrationScale { SMALL, MEDIUM, LARGE }
enum class CalibrationTilt { NEGATIVE, NEUTRAL, POSITIVE }
enum class CalibrationDeviceOrientation { PORTRAIT, LANDSCAPE }

data class CalibrationDiversitySignature(
    val gridPosition: CalibrationGridPosition,
    val scale: CalibrationScale,
    val horizontalTilt: CalibrationTilt,
    val verticalTilt: CalibrationTilt,
    val deviceOrientation: CalibrationDeviceOrientation,
    val normalizedCorners: List<RecognitionPoint>,
)

data class CalibrationSample(
    val index: Int,
    val canonicalCorners: List<RecognitionPoint>,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val originalRotationDegrees: Int,
    val originalCropRect: CoordinateRect,
    val sensorToBufferTransform: Matrix3,
    val areaFraction: Double,
    val normalizedCenter: RecognitionPoint,
    val approximateWidthPixels: Double,
    val approximateHeightPixels: Double,
    val capturedAtEpochMillis: Long,
    val geometricQuality: Double,
    val sharpness: Double? = null,
    val diversity: CalibrationDiversitySignature,
    val horizontalSquareSizeMm: Double,
    val verticalSquareSizeMm: Double,
)

data class CalibrationDataset(
    val identity: CalibrationDatasetIdentity,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val samples: List<CalibrationSample>,
    val rejectedSamples: Int = 0,
    val lastRejection: CalibrationCaptureRejection? = null,
)

enum class CalibrationCaptureRejectionReason(val diagnostic: String) {
    BOARD_NOT_DETECTED("El siguiente cuadro analizado no contuvo el tablero completo."),
    DETECTION_EXPIRED("La solicitud caducó antes de recibir una detección del cuadro actual."),
    DATASET_IDENTITY_MISMATCH("La cámara, resolución o crop no coincide con el dataset actual."),
    DUPLICATE("La geometría normalizada es prácticamente igual a una muestra existente."),
    INVALID_GEOMETRY("Las 54 esquinas no superaron la validación geométrica o el round trip."),
    INSUFFICIENT_QUALITY("La calidad geométrica de la muestra es insuficiente."),
    WRITE_ERROR("No se pudo escribir el JSON de forma atómica."),
    OUTSIDE_CALIBRATION_MODE("La captura sólo está disponible en CAMERA_CALIBRATION."),
    PHYSICAL_MEASUREMENT_NOT_CONFIRMED("Debe imprimirse y medirse el tablero antes de capturar."),
    REQUEST_CANCELLED("La solicitud pendiente fue cancelada por el operador."),
    CLOSED("El analizador se cerró con una solicitud pendiente."),
    CORRUPT_DATASET("El JSON existente está corrupto o usa un esquema incompatible."),
}

data class CalibrationCaptureRejection(
    val reason: CalibrationCaptureRejectionReason,
    val detail: String,
    val atEpochMillis: Long,
)

data class CalibrationSampleRequest(
    val id: Long,
    val requestedAtNanos: Long,
    val deadlineNanos: Long,
    val lastInvalidDetectionAtNanos: Long? = null,
)

data class CalibrationDiversityCoverage(
    val gridPositions: Set<CalibrationGridPosition> = emptySet(),
    val scales: Set<CalibrationScale> = emptySet(),
    val horizontalTilts: Set<CalibrationTilt> = emptySet(),
    val verticalTilts: Set<CalibrationTilt> = emptySet(),
    val orientations: Set<CalibrationDeviceOrientation> = emptySet(),
    val minimumTechnicalSamples: Int = 15,
    val recommendedMinimum: Int = 20,
    val recommendedMaximum: Int = 25,
    val ready: Boolean = false,
    val recommendations: List<String> = emptyList(),
)

data class CalibrationCaptureState(
    val dataset: CalibrationDataset? = null,
    val pendingRequest: CalibrationSampleRequest? = null,
    val confirmedHorizontalSquareSizeMm: Double? = null,
    val confirmedVerticalSquareSizeMm: Double? = null,
    val acceptedSamples: Int = dataset?.samples?.size ?: 0,
    val rejectedSamples: Int = dataset?.rejectedSamples ?: 0,
    val lastRejection: CalibrationCaptureRejection? = dataset?.lastRejection,
    val coverage: CalibrationDiversityCoverage = CalibrationDiversityCoverage(),
    val datasetFilePath: String,
    val datasetIdentity: CalibrationDatasetIdentity? = dataset?.identity,
    val loadError: String? = null,
    val jsonPreview: String? = null,
    val closed: Boolean = false,
)

data class CalibrationFrameCaptureMetadata(
    val cameraId: String?,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropRect: CoordinateRect,
    val rotationDegrees: Int,
    val preparedWidth: Int,
    val preparedHeight: Int,
    val sensorToBufferTransform: Matrix3,
)
