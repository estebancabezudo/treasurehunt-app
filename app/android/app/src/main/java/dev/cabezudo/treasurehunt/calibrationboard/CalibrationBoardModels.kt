package dev.cabezudo.treasurehunt.calibrationboard

import dev.cabezudo.treasurehunt.camera.FrameResolution
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint

data object CalibrationBoard {
    const val INTERNAL_COLUMNS = 9
    const val INTERNAL_ROWS = 6
    const val EXPECTED_CORNERS = INTERNAL_COLUMNS * INTERNAL_ROWS
    const val SQUARE_SIZE_MM = 20.0
    const val PHYSICAL_WIDTH_MM = 200.0
    const val PHYSICAL_HEIGHT_MM = 140.0
    const val RESOURCE_NAME = "calibration_chessboard.png"
}

data class CalibrationBoardConfig(
    val internalColumns: Int = CalibrationBoard.INTERNAL_COLUMNS,
    val internalRows: Int = CalibrationBoard.INTERNAL_ROWS,
    val analysesPerSecond: Double = 7.5,
    val minimumAreaFraction: Double = 0.015,
    val maximumAreaFraction: Double = 0.92,
    val minimumCornerSeparationPixels: Double = 3.0,
    val frameToleranceFraction: Double = 0.05,
)

enum class CalibrationBoardDetectionStatus {
    NOT_INITIALIZED,
    SEARCHING,
    DETECTED,
    REJECTED,
    ERROR,
    CLOSED,
}

enum class CalibrationBoardRejectionReason(val diagnostic: String) {
    BOARD_NOT_FOUND("No se encontró el tablero 9 × 6."),
    INCORRECT_CORNER_COUNT("La cantidad de esquinas no es exactamente 54."),
    NON_FINITE_COORDINATES("Hay coordenadas de esquina no finitas."),
    INCONSISTENT_ORDER("Las esquinas no conservan una distribución 9 × 6 coherente."),
    AREA_TOO_SMALL("El tablero ocupa un área insuficiente del cuadro."),
    AREA_TOO_LARGE("El tablero ocupa un área excesiva del cuadro."),
    DEGENERATE_BOARD("El contorno del tablero es degenerado."),
    INSUFFICIENT_SEPARATION("La separación entre esquinas adyacentes es insuficiente."),
    OUTSIDE_FRAME("Hay esquinas fuera de la tolerancia alrededor del cuadro."),
    OPENCV_ERROR("OpenCV no pudo detectar el tablero."),
}

data class CalibrationBoardDetectionState(
    val status: CalibrationBoardDetectionStatus = CalibrationBoardDetectionStatus.NOT_INITIALIZED,
    val corners: List<RecognitionPoint> = emptyList(),
    val frameResolution: FrameResolution? = null,
    val areaFraction: Double? = null,
    val center: RecognitionPoint? = null,
    val approximateWidthPixels: Double? = null,
    val approximateHeightPixels: Double? = null,
    val durationNanos: Long? = null,
    val analyzedFrames: Long = 0,
    val skippedByRateLimit: Long = 0,
    val rejectionReason: CalibrationBoardRejectionReason? = null,
    val error: String? = null,
    val analyzedThisFrame: Boolean = false,
    val analyzedAtNanos: Long? = null,
)

data class CalibrationBoardGeometryResult(
    val valid: Boolean,
    val areaFraction: Double? = null,
    val center: RecognitionPoint? = null,
    val approximateWidthPixels: Double? = null,
    val approximateHeightPixels: Double? = null,
    val rejectionReason: CalibrationBoardRejectionReason? = null,
)
