package dev.cabezudo.treasurehunt.aruco.pose

import dev.cabezudo.treasurehunt.manualcalibration.ManualCameraCalibrationResult
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint

enum class PoseCalibrationStatus {
    NOT_AVAILABLE,
    INCOMPATIBLE,
    READY,
    ERROR,
}

data class PoseCalibrationState(
    val status: PoseCalibrationStatus = PoseCalibrationStatus.NOT_AVAILABLE,
    val result: ManualCameraCalibrationResult? = null,
    val resultSha256: String? = null,
    val cause: String? = "No se ha cargado la calibración manual.",
)

data class PhysicalMarkerSize(
    val widthMillimeters: Double,
    val heightMillimeters: Double,
    val confirmed: Boolean,
) {
    val meanSideMillimeters: Double get() = (widthMillimeters + heightMillimeters) / 2.0
}

data class ArucoPoseTestIdentity(
    val cameraId: String,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val markerDictionary: String,
    val markerId: Int,
    val measuredWidthMillimeters: Double,
    val measuredHeightMillimeters: Double,
    val calibrationSha256: String,
)

enum class ArucoPoseStatus {
    NOT_INITIALIZED,
    SEARCHING,
    CALIBRATION_NOT_AVAILABLE,
    CALIBRATION_INCOMPATIBLE,
    PHYSICAL_SIZE_NOT_CONFIRMED,
    PHYSICAL_MARKER_DEFORMED,
    VALID,
    REJECTED,
    ERROR,
    CLOSED,
}

enum class ArucoPoseRejectionReason(val diagnostic: String) {
    MARKER_NOT_DETECTED("no se detectó el marcador esperado en el cuadro actual"),
    SOLVE_PNP_FAILED("solvePnP no encontró una solución"),
    INVALID_VECTOR_DIMENSIONS("rvec o tvec tienen dimensiones incorrectas"),
    NON_FINITE_RESULT("la pose contiene NaN o infinito"),
    NON_POSITIVE_Z("la traslación Z no es positiva"),
    INVALID_ROTATION_MATRIX("la matriz de rotación no es ortonormal o tiene determinante inválido"),
    NON_FINITE_REPROJECTION("el error de reproyección no es finito"),
    DEGENERATE_PROJECTION("la geometría proyectada es degenerada"),
    INVALID_CORNER_ORDER("el orden de esquinas es incoherente para IPPE_SQUARE"),
    COORDINATE_TRANSFORM_FAILED("no se pudieron transformar las esquinas al buffer canónico"),
    INVALID_PHYSICAL_SIZE("el tamaño físico confirmado no es válido"),
    OPENCV_ERROR("error de OpenCV al calcular la pose"),
}

data class ArucoPoseResult(
    val translationMillimeters: List<Double>,
    val euclideanDistanceMillimeters: Double,
    val rotationVector: List<Double>,
    val rotationMatrix: List<Double>,
    val eulerDegreesXyz: List<Double>,
    val reprojectionRmsPixels: Double,
    val canonicalCorners: List<RecognitionPoint>,
    val projectedCanonicalCorners: List<RecognitionPoint>,
    val projectedAxesPrepared: List<RecognitionPoint>,
    val calculationDurationNanos: Long,
) {
    val txMillimeters: Double get() = translationMillimeters[0]
    val tyMillimeters: Double get() = translationMillimeters[1]
    val tzMillimeters: Double get() = translationMillimeters[2]
}

data class ArucoPoseState(
    val status: ArucoPoseStatus = ArucoPoseStatus.NOT_INITIALIZED,
    val calibration: PoseCalibrationState = PoseCalibrationState(),
    val physicalMarkerSize: PhysicalMarkerSize? = null,
    val testIdentity: ArucoPoseTestIdentity? = null,
    val result: ArucoPoseResult? = null,
    val rejectionReason: ArucoPoseRejectionReason? = null,
    val error: String? = null,
)
