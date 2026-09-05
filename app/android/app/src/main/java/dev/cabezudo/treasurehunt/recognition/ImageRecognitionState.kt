package dev.cabezudo.treasurehunt.recognition

data class RecognitionPoint(
    val x: Double,
    val y: Double,
)

enum class ImageRecognitionStatus {
    NOT_INITIALIZED,
    SEARCHING,
    DETECTED,
    REJECTED,
    ERROR,
}

enum class RecognitionRejectionReason(val diagnostic: String) {
    REFERENCE_FEATURES_INSUFFICIENT("referencia sin suficientes características"),
    FRAME_FEATURES_INSUFFICIENT("cuadro sin suficientes características"),
    MATCHES_INSUFFICIENT("coincidencias que superan la razón insuficientes"),
    INLIERS_INSUFFICIENT("inliers de RANSAC insuficientes"),
    INLIER_RATIO_INSUFFICIENT("proporción de inliers insuficiente"),
    HOMOGRAPHY_EMPTY("homografía vacía"),
    HOMOGRAPHY_DEGENERATE("homografía degenerada"),
    CORNERS_NON_FINITE("cuadrilátero con coordenadas no finitas"),
    QUADRILATERAL_SELF_INTERSECTING("cuadrilátero auto-intersectado"),
    QUADRILATERAL_INVERTED("cuadrilátero invertido o con orden incoherente"),
    QUADRILATERAL_NON_CONVEX("cuadrilátero no convexo"),
    QUADRILATERAL_TOO_SMALL("área del cuadrilátero demasiado pequeña"),
    QUADRILATERAL_TOO_LARGE("área del cuadrilátero demasiado grande"),
    QUADRILATERAL_DISPROPORTIONATE("cuadrilátero extremadamente desproporcionado"),
}

data class ImageRecognitionState(
    val status: ImageRecognitionStatus = ImageRecognitionStatus.NOT_INITIALIZED,
    val detectorInitialized: Boolean = false,
    val referenceLoaded: Boolean = false,
    val referenceName: String = ReferenceTarget.NAME,
    val referenceWidth: Int? = null,
    val referenceHeight: Int? = null,
    val referenceKeypoints: Int = 0,
    val frameKeypoints: Int = 0,
    val knnMatches: Int = 0,
    val ratioAcceptedMatches: Int = 0,
    val ransacInliers: Int = 0,
    val inlierRatio: Double = 0.0,
    val homographyStatus: String = "no calculada",
    val corners: List<RecognitionPoint> = emptyList(),
    val extractionDurationNanos: Long? = null,
    val matchingDurationNanos: Long? = null,
    val homographyDurationNanos: Long? = null,
    val totalDurationNanos: Long? = null,
    val processedFrames: Long = 0,
    val rejectionReason: RecognitionRejectionReason? = null,
    val error: String? = null,
)

object ReferenceTarget {
    const val NAME = "guardian_door_target"
    const val PIXEL_WIDTH = 1200
    const val PIXEL_HEIGHT = 1600
    const val PHYSICAL_WIDTH_MILLIMETERS = 180
    const val PHYSICAL_HEIGHT_MILLIMETERS = 240
}
