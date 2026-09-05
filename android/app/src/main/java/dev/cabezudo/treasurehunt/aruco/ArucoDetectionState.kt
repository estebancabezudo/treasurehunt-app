package dev.cabezudo.treasurehunt.aruco

import dev.cabezudo.treasurehunt.recognition.RecognitionPoint

object ExpectedArucoMarker {
    const val DICTIONARY_NAME = "DICT_4X4_50"
    const val DICTIONARY_CODE = 0
    const val ID = 27
    const val PHYSICAL_SIZE_MILLIMETERS = 40.0
}

enum class ArucoDetectionStatus {
    NOT_INITIALIZED,
    SEARCHING,
    DETECTED,
    UNEXPECTED_MARKER,
    REJECTED,
    ERROR,
    CLOSED,
}

enum class ArucoRejectionReason(val diagnostic: String) {
    NO_MARKER_FOUND("no se encontró marcador"),
    UNEXPECTED_IDS_ONLY("sólo se encontraron IDs diferentes"),
    INVALID_CORNERS("esquinas inválidas"),
    NON_FINITE_CORNERS("esquinas con coordenadas no finitas"),
    SELF_INTERSECTING("cuadrilátero no simple"),
    NON_CONVEX("cuadrilátero no convexo"),
    INCOHERENT_ORIENTATION("orientación de esquinas incoherente"),
    AREA_TOO_SMALL("área insuficiente"),
    AREA_TOO_LARGE("área excesiva"),
    DEGENERATE_QUADRILATERAL("cuadrilátero degenerado"),
    OUTSIDE_FRAME_TOLERANCE("esquinas fuera de la tolerancia del cuadro"),
    OPENCV_ERROR("error de OpenCV"),
}

data class DetectedMarker(
    val id: Int,
    val corners: List<RecognitionPoint>,
    val geometryValid: Boolean,
    val areaFraction: Double? = null,
    val rejectionReason: ArucoRejectionReason? = null,
)

data class ArucoDetectionState(
    val status: ArucoDetectionStatus = ArucoDetectionStatus.NOT_INITIALIZED,
    val detectorInitialized: Boolean = false,
    val dictionaryName: String = ExpectedArucoMarker.DICTIONARY_NAME,
    val expectedId: Int = ExpectedArucoMarker.ID,
    val foundIds: List<Int> = emptyList(),
    val markers: List<DetectedMarker> = emptyList(),
    val expectedCorners: List<RecognitionPoint> = emptyList(),
    val expectedAreaFraction: Double? = null,
    val markerCount: Int = 0,
    val rejectedCandidateCount: Int = 0,
    val totalDurationNanos: Long? = null,
    val analyzedFrames: Long = 0,
    val skippedByRateLimit: Long = 0,
    val rejectionReason: ArucoRejectionReason? = null,
    val error: String? = null,
)

object ArucoDetectionDecision {
    fun decide(
        markers: List<DetectedMarker>,
        rejectedCandidateCount: Int = 0,
        durationNanos: Long = 0,
        analyzedFrames: Long = 1,
        skippedByRateLimit: Long = 0,
    ): ArucoDetectionState {
        val expected = markers.firstOrNull { it.id == ExpectedArucoMarker.ID }
        val status: ArucoDetectionStatus
        val reason: ArucoRejectionReason?
        when {
            expected?.geometryValid == true -> {
                status = ArucoDetectionStatus.DETECTED
                reason = null
            }
            expected != null -> {
                status = ArucoDetectionStatus.REJECTED
                reason = expected.rejectionReason ?: ArucoRejectionReason.INVALID_CORNERS
            }
            markers.isNotEmpty() -> {
                status = ArucoDetectionStatus.UNEXPECTED_MARKER
                reason = ArucoRejectionReason.UNEXPECTED_IDS_ONLY
            }
            else -> {
                status = ArucoDetectionStatus.SEARCHING
                reason = ArucoRejectionReason.NO_MARKER_FOUND
            }
        }
        return ArucoDetectionState(
            status = status,
            detectorInitialized = true,
            foundIds = markers.map(DetectedMarker::id),
            markers = markers,
            expectedCorners = expected?.corners.orEmpty(),
            expectedAreaFraction = expected?.areaFraction,
            markerCount = markers.size,
            rejectedCandidateCount = rejectedCandidateCount,
            totalDurationNanos = durationNanos,
            analyzedFrames = analyzedFrames,
            skippedByRateLimit = skippedByRateLimit,
            rejectionReason = reason,
        )
    }
}
