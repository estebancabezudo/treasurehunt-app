package dev.cabezudo.treasurehunt.camera

import java.util.Locale
import dev.cabezudo.treasurehunt.recognition.ImageRecognitionStatus
import dev.cabezudo.treasurehunt.camera.calibration.AndroidDistortionState

data class CameraDiagnosticUiState(
    val headline: String,
    val permission: String,
    val previewStatus: String,
    val analysisStatus: String,
    val selectedCamera: String,
    val previewResolution: String,
    val previewRotation: String,
    val analysisResolution: String,
    val analysisRotation: String,
    val analysisFormat: String,
    val analysisPlaneCount: String,
    val totalFrames: String,
    val framesLastSecond: String,
    val approximateFps: String,
    val lastFrameAge: String,
    val openCvStatus: String,
    val preparedFrames: String,
    val sourceResolution: String,
    val preparedResolution: String,
    val luminanceStrides: String,
    val copyDuration: String,
    val rotationDuration: String,
    val preparationDuration: String,
    val meanLuminance: String,
    val calibrationCameraId: String,
    val calibrationStatus: String,
    val calibrationSensorOrientation: String,
    val calibrationSensorResolution: String,
    val calibrationPreCorrectionResolution: String,
    val calibrationAnalysisResolution: String,
    val calibrationCrop: String,
    val calibrationRotation: String,
    val calibrationSensorToBufferMatrix: String,
    val calibrationRawIntrinsics: String,
    val calibrationPreparedIntrinsics: String,
    val calibrationFocalLengths: String,
    val calibrationSensorPhysicalSize: String,
    val calibrationDistortion: String,
    val calibrationMatrix: String,
    val calibrationCause: String?,
    val diagnosticMode: String,
    val boardStatus: String,
    val boardCornerCount: String,
    val boardCorners: String,
    val boardFrameResolution: String,
    val boardAreaFraction: String,
    val boardCenter: String,
    val boardApproximateSize: String,
    val boardDuration: String,
    val boardAnalyzedFrames: String,
    val boardSkippedFrames: String,
    val boardReason: String?,
    val capturePending: String,
    val confirmedSquareMeasurement: String,
    val acceptedCalibrationSamples: String,
    val rejectedCalibrationSamples: String,
    val lastCalibrationRejection: String?,
    val coveredGridPositions: String,
    val coveredScales: String,
    val coveredHorizontalTilts: String,
    val coveredVerticalTilts: String,
    val coveredOrientations: String,
    val recommendedCalibrationSamples: String,
    val calibrationDatasetReady: String,
    val calibrationRecommendations: String,
    val calibrationDatasetFile: String,
    val calibrationDatasetIdentity: String,
    val calibrationJsonPreview: String?,
    val arucoStatus: String,
    val arucoDictionary: String,
    val arucoExpectedId: String,
    val arucoFoundIds: String,
    val arucoMarkerCount: String,
    val arucoRejectedCandidates: String,
    val arucoExpectedCorners: String,
    val arucoAreaFraction: String,
    val arucoDuration: String,
    val arucoAnalyzedFrames: String,
    val arucoSkippedFrames: String,
    val arucoReason: String?,
    val arucoPoseStatus: String,
    val arucoPoseCalibrationStatus: String,
    val arucoPosePhysicalSize: String,
    val arucoPoseIdentity: String,
    val arucoPoseTranslation: String,
    val arucoPoseDistance: String,
    val arucoPoseRotationVector: String,
    val arucoPoseEuler: String,
    val arucoPoseRotationMatrix: String,
    val arucoPoseReprojectionRms: String,
    val arucoPoseDuration: String,
    val arucoPoseReason: String?,
    val ocrStatus: String,
    val ocrExpectedText: String,
    val ocrRecognizedText: String,
    val ocrNormalizedText: String,
    val ocrStructureCounts: String,
    val ocrRequestInFlight: String,
    val ocrSkippedRequests: String,
    val ocrDuration: String,
    val ocrMatched: String,
    val ocrReason: String?,
    val referenceName: String,
    val referenceResolution: String,
    val referenceKeypoints: String,
    val frameKeypoints: String,
    val knnMatches: String,
    val ratioMatches: String,
    val ransacInliers: String,
    val inlierRatio: String,
    val homographyStatus: String,
    val detectedCorners: String,
    val recognitionExtractionDuration: String,
    val recognitionMatchingDuration: String,
    val recognitionHomographyDuration: String,
    val recognitionTotalDuration: String,
    val recognitionStatus: String,
    val recognitionReason: String?,
    val arCore: String,
    val mode: String,
    val previewError: String?,
    val analysisError: String?,
    val conversionError: String?,
    val showPermissionAction: Boolean,
    val permissionExplanation: String?,
)

fun CameraRuntimeState.toDiagnosticUiState(): CameraDiagnosticUiState {
    val calibration = analysis.calibration
    val rawCalibration = calibration.raw
    val preparedCalibration = calibration.prepared
    val headline = when {
        error != null -> "Error: $error"
        analysis.status == FrameAnalysisStatus.ERROR -> "Error: ${analysis.lastError}"
        permission != CameraPermissionStatus.GRANTED -> "Permiso de cámara requerido"
        analysis.recognition.status == ImageRecognitionStatus.DETECTED -> "Objetivo detectado"
        analysis.recognition.status == ImageRecognitionStatus.REJECTED -> "Buscando objetivo"
        cameraX == CameraXStatus.STARTED && analysis.status == FrameAnalysisStatus.RUNNING -> {
            "Cámara y análisis activos"
        }
        cameraX == CameraXStatus.STARTED -> "Cámara activa"
        cameraX == CameraXStatus.PAUSED -> "Cámara pausada"
        cameraX == CameraXStatus.CLOSED -> "Cámara cerrada"
        else -> "Iniciando CameraX"
    }

    return CameraDiagnosticUiState(
        headline = headline,
        permission = when (permission) {
            CameraPermissionStatus.PENDING -> "pendiente"
            CameraPermissionStatus.GRANTED -> "concedido"
            CameraPermissionStatus.DENIED -> "rechazado"
        },
        previewStatus = when (cameraX) {
            CameraXStatus.IDLE -> "inactivo"
            CameraXStatus.STARTING -> "iniciando"
            CameraXStatus.STARTED -> "iniciado"
            CameraXStatus.PAUSED -> "pausado"
            CameraXStatus.CLOSED -> "cerrado"
            CameraXStatus.ERROR -> "error"
        },
        analysisStatus = analysis.status.name,
        selectedCamera = when (selectedCamera) {
            SelectedCamera.BACK -> "trasera"
            SelectedCamera.UNAVAILABLE -> "no seleccionada"
        },
        previewResolution = frameResolution?.let { "${it.width} × ${it.height}" }
            ?: "no disponible",
        previewRotation = rotationDegrees?.let { "$it°" } ?: "no disponible",
        analysisResolution = analysis.resolution?.let { "${it.width} × ${it.height}" }
            ?: "no disponible",
        analysisRotation = analysis.rotationDegrees?.let { "$it°" } ?: "no disponible",
        analysisFormat = when (analysis.format) {
            35 -> "YUV_420_888 (35)"
            null -> "no disponible"
            else -> analysis.format.toString()
        },
        analysisPlaneCount = analysis.planeCount?.toString() ?: "no disponible",
        totalFrames = analysis.totalFrames.toString(),
        framesLastSecond = analysis.framesLastSecond.toString(),
        approximateFps = String.format(Locale.US, "%.1f", analysis.approximateFps),
        lastFrameAge = analysis.lastFrameAgeMillis?.let { "$it ms" } ?: "no disponible",
        openCvStatus = when (analysis.openCvStatus) {
            OpenCvStatus.NOT_INITIALIZED -> "no inicializado"
            OpenCvStatus.READY -> "inicializado"
            OpenCvStatus.ERROR -> "error"
        },
        preparedFrames = analysis.preparedFrames.toString(),
        sourceResolution = analysis.sourceResolution.asDiagnosticResolution(),
        preparedResolution = analysis.preparedResolution.asDiagnosticResolution(),
        luminanceStrides = if (
            analysis.luminanceRowStride != null && analysis.luminancePixelStride != null
        ) {
            "row=${analysis.luminanceRowStride}, pixel=${analysis.luminancePixelStride}"
        } else {
            "no disponible"
        },
        copyDuration = analysis.copyDurationNanos.asMilliseconds(),
        rotationDuration = analysis.rotationDurationNanos.asMilliseconds(),
        preparationDuration = analysis.preparationDurationNanos.asMilliseconds(),
        meanLuminance = analysis.meanLuminance?.let {
            String.format(Locale.US, "%.1f", it)
        } ?: "no disponible",
        calibrationCameraId = rawCalibration?.cameraId ?: "no disponible",
        calibrationStatus = calibration.status.name,
        calibrationSensorOrientation = rawCalibration?.sensorOrientationDegrees?.let { "$it°" }
            ?: "no disponible",
        calibrationSensorResolution = rawCalibration?.activeArray?.let {
            "${it.width} × ${it.height} [${it.left},${it.top}–${it.right},${it.bottom}]"
        } ?: "no disponible",
        calibrationPreCorrectionResolution = rawCalibration?.preCorrectionActiveArray?.let {
            "${it.width} × ${it.height} [${it.left},${it.top}–${it.right},${it.bottom}]"
        } ?: "no disponible",
        calibrationAnalysisResolution = analysis.resolution.asDiagnosticResolution(),
        calibrationCrop = calibration.frameTransform?.cropRect?.let {
            "[${it.left},${it.top}–${it.right},${it.bottom}] (${it.width} × ${it.height})"
        } ?: "no disponible",
        calibrationRotation = calibration.frameTransform?.rotationDegrees?.let { "$it°" }
            ?: "no disponible",
        calibrationSensorToBufferMatrix = calibration.frameTransform?.sensorToBuffer
            ?.values.asDiagnosticMatrix(),
        calibrationRawIntrinsics = rawCalibration?.intrinsics?.let {
            String.format(
                Locale.US,
                "fx=%.3f, fy=%.3f, cx=%.3f, cy=%.3f, s=%.6f",
                it.fx, it.fy, it.cx, it.cy, it.skew,
            )
        } ?: "no disponibles",
        calibrationPreparedIntrinsics = preparedCalibration?.let {
            String.format(
                Locale.US,
                "fx=%.3f, fy=%.3f, cx=%.3f, cy=%.3f, s=%.6f (%d × %d)",
                it.fx, it.fy, it.cx, it.cy, it.skew, it.width, it.height,
            )
        } ?: "no disponibles",
        calibrationFocalLengths = rawCalibration?.availableFocalLengthsMm
            ?.takeIf(List<Double>::isNotEmpty)
            ?.joinToString(prefix = "[", postfix = "] mm") {
                String.format(Locale.US, "%.3f", it)
            } ?: "no disponibles",
        calibrationSensorPhysicalSize = if (
            rawCalibration?.sensorPhysicalWidthMm != null &&
            rawCalibration.sensorPhysicalHeightMm != null
        ) {
            String.format(
                Locale.US,
                "%.3f × %.3f mm",
                rawCalibration.sensorPhysicalWidthMm,
                rawCalibration.sensorPhysicalHeightMm,
            )
        } else "no disponible",
        calibrationDistortion = rawCalibration?.let {
            val model = when (it.distortionState) {
                AndroidDistortionState.NOT_REPORTED -> "no informada"
                AndroidDistortionState.REPORTED_ZERO -> "Android Brown–Conrady, coeficientes cero"
                AndroidDistortionState.REPORTED_NON_ZERO -> {
                    "Android Brown–Conrady; no convertido al orden OpenCV"
                }
            }
            val coefficients = it.lensDistortion?.joinToString(prefix = " [", postfix = "]") {
                value -> String.format(Locale.US, "%.6g", value)
            }.orEmpty()
            val modes = it.availableDistortionCorrectionModes.joinToString(
                prefix = "; modos=[",
                postfix = "]",
            )
            "$model$coefficients$modes"
        } ?: "no disponible",
        calibrationMatrix = preparedCalibration?.intrinsicMatrix?.values.asDiagnosticMatrix(),
        calibrationCause = calibration.cause,
        diagnosticMode = diagnosticMode.name,
        boardStatus = analysis.calibrationBoard.status.name,
        boardCornerCount = analysis.calibrationBoard.corners.size.toString(),
        boardCorners = analysis.calibrationBoard.corners
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "[", postfix = "]", limit = 8, truncated = "…") {
                String.format(Locale.US, "(%.1f, %.1f)", it.x, it.y)
            } ?: "ninguna",
        boardFrameResolution = analysis.calibrationBoard.frameResolution.asDiagnosticResolution(),
        boardAreaFraction = analysis.calibrationBoard.areaFraction?.let {
            String.format(Locale.US, "%.4f", it)
        } ?: "no disponible",
        boardCenter = analysis.calibrationBoard.center?.let {
            String.format(Locale.US, "(%.1f, %.1f)", it.x, it.y)
        } ?: "no disponible",
        boardApproximateSize = if (
            analysis.calibrationBoard.approximateWidthPixels != null &&
            analysis.calibrationBoard.approximateHeightPixels != null
        ) {
            String.format(
                Locale.US,
                "%.1f × %.1f px",
                analysis.calibrationBoard.approximateWidthPixels,
                analysis.calibrationBoard.approximateHeightPixels,
            )
        } else "no disponible",
        boardDuration = analysis.calibrationBoard.durationNanos.asMilliseconds(),
        boardAnalyzedFrames = analysis.calibrationBoard.analyzedFrames.toString(),
        boardSkippedFrames = analysis.calibrationBoard.skippedByRateLimit.toString(),
        boardReason = analysis.calibrationBoard.error
            ?: analysis.calibrationBoard.rejectionReason?.diagnostic,
        capturePending = if (analysis.calibrationCapture.pendingRequest != null) "sí" else "no",
        confirmedSquareMeasurement = if (
            analysis.calibrationCapture.confirmedHorizontalSquareSizeMm != null &&
            analysis.calibrationCapture.confirmedVerticalSquareSizeMm != null
        ) {
            String.format(
                Locale.US,
                "X=%.6f mm; Y=%.6f mm",
                analysis.calibrationCapture.confirmedHorizontalSquareSizeMm,
                analysis.calibrationCapture.confirmedVerticalSquareSizeMm,
            )
        } else "no confirmadas",
        acceptedCalibrationSamples = analysis.calibrationCapture.acceptedSamples.toString(),
        rejectedCalibrationSamples = analysis.calibrationCapture.rejectedSamples.toString(),
        lastCalibrationRejection = analysis.calibrationCapture.lastRejection?.let {
            "${it.reason.name}: ${it.detail}"
        } ?: analysis.calibrationCapture.loadError,
        coveredGridPositions = analysis.calibrationCapture.coverage.gridPositions
            .map { it.name }.sorted().joinToString().ifEmpty { "ninguna" },
        coveredScales = analysis.calibrationCapture.coverage.scales
            .map { it.name }.sorted().joinToString().ifEmpty { "ninguna" },
        coveredHorizontalTilts = analysis.calibrationCapture.coverage.horizontalTilts
            .map { it.name }.sorted().joinToString().ifEmpty { "ninguna" },
        coveredVerticalTilts = analysis.calibrationCapture.coverage.verticalTilts
            .map { it.name }.sorted().joinToString().ifEmpty { "ninguna" },
        coveredOrientations = analysis.calibrationCapture.coverage.orientations
            .map { it.name }.sorted().joinToString().ifEmpty { "ninguna" },
        recommendedCalibrationSamples =
            "mínimo ${analysis.calibrationCapture.coverage.minimumTechnicalSamples}; " +
                "objetivo ${analysis.calibrationCapture.coverage.recommendedMinimum}–" +
                analysis.calibrationCapture.coverage.recommendedMaximum,
        calibrationDatasetReady = if (analysis.calibrationCapture.coverage.ready) "listo" else "incompleto",
        calibrationRecommendations = analysis.calibrationCapture.coverage.recommendations
            .joinToString(" ").ifEmpty { "Cobertura técnica mínima completa." },
        calibrationDatasetFile = analysis.calibrationCapture.datasetFilePath,
        calibrationDatasetIdentity = analysis.calibrationCapture.datasetIdentity?.let {
            "schema=${it.schemaVersion}, cámara=${it.cameraId}, " +
                "buffer=${it.bufferWidth}×${it.bufferHeight}, " +
                "crop=[${it.cropRect.left},${it.cropRect.top}–${it.cropRect.right},${it.cropRect.bottom}], " +
                "tablero=${it.internalColumns}×${it.internalRows}, " +
                "cuadro X=${it.horizontalSquareSizeMm} mm, Y=${it.verticalSquareSizeMm} mm"
        } ?: "no creada",
        calibrationJsonPreview = analysis.calibrationCapture.jsonPreview,
        arucoStatus = analysis.arucoDetection.status.name,
        arucoDictionary = analysis.arucoDetection.dictionaryName,
        arucoExpectedId = analysis.arucoDetection.expectedId.toString(),
        arucoFoundIds = analysis.arucoDetection.foundIds
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "[", postfix = "]")
            ?: "ninguno",
        arucoMarkerCount = analysis.arucoDetection.markerCount.toString(),
        arucoRejectedCandidates = analysis.arucoDetection.rejectedCandidateCount.toString(),
        arucoExpectedCorners = analysis.arucoDetection.expectedCorners
            .takeIf { it.size == 4 }
            ?.joinToString(prefix = "[", postfix = "]") {
                String.format(Locale.US, "(%.1f, %.1f)", it.x, it.y)
            } ?: "no disponibles",
        arucoAreaFraction = analysis.arucoDetection.expectedAreaFraction?.let {
            String.format(Locale.US, "%.4f", it)
        } ?: "no disponible",
        arucoDuration = analysis.arucoDetection.totalDurationNanos.asMilliseconds(),
        arucoAnalyzedFrames = analysis.arucoDetection.analyzedFrames.toString(),
        arucoSkippedFrames = analysis.arucoDetection.skippedByRateLimit.toString(),
        arucoReason = analysis.arucoDetection.error
            ?: analysis.arucoDetection.rejectionReason?.diagnostic,
        arucoPoseStatus = analysis.arucoPose.status.name,
        arucoPoseCalibrationStatus = analysis.arucoPose.calibration.status.name,
        arucoPosePhysicalSize = analysis.arucoPose.physicalMarkerSize?.let {
            String.format(Locale.US, "%.3f × %.3f mm", it.widthMillimeters, it.heightMillimeters)
        } ?: "no confirmado",
        arucoPoseIdentity = analysis.arucoPose.testIdentity?.let {
            "cámara=${it.cameraId}, buffer=${it.canonicalWidth}×${it.canonicalHeight}, " +
                "${it.markerDictionary} id=${it.markerId}, K=${it.calibrationSha256.take(12)}…"
        } ?: "no disponible",
        arucoPoseTranslation = analysis.arucoPose.result?.let {
            String.format(
                Locale.US,
                "tx=%.2f, ty=%.2f, tz=%.2f mm",
                it.txMillimeters,
                it.tyMillimeters,
                it.tzMillimeters,
            )
        } ?: "no disponible",
        arucoPoseDistance = analysis.arucoPose.result?.let {
            String.format(Locale.US, "%.2f mm", it.euclideanDistanceMillimeters)
        } ?: "no disponible",
        arucoPoseRotationVector = analysis.arucoPose.result?.rotationVector
            ?.asDiagnosticVector("rad") ?: "no disponible",
        arucoPoseEuler = analysis.arucoPose.result?.eulerDegreesXyz
            ?.asDiagnosticVector("°") ?: "no disponible",
        arucoPoseRotationMatrix = analysis.arucoPose.result?.rotationMatrix.asDiagnosticMatrix(),
        arucoPoseReprojectionRms = analysis.arucoPose.result?.let {
            String.format(Locale.US, "%.4f px", it.reprojectionRmsPixels)
        } ?: "no disponible",
        arucoPoseDuration = analysis.arucoPose.result?.calculationDurationNanos.asMilliseconds(),
        arucoPoseReason = analysis.arucoPose.error
            ?: analysis.arucoPose.rejectionReason?.diagnostic
            ?: analysis.arucoPose.calibration.cause,
        ocrStatus = analysis.textRecognition.status.name,
        ocrExpectedText = analysis.textRecognition.expectedText,
        ocrRecognizedText = analysis.textRecognition.result?.fullText
            ?.toVisibleDiagnosticText()
            ?: "no disponible",
        ocrNormalizedText = analysis.textRecognition.result?.normalizedText
            ?.toVisibleDiagnosticText()
            ?: "no disponible",
        ocrStructureCounts = analysis.textRecognition.result?.let {
            "bloques=${it.blockCount}, líneas=${it.lineCount}, elementos=${it.elementCount}"
        } ?: "bloques=0, líneas=0, elementos=0",
        ocrRequestInFlight = if (analysis.textRecognition.requestInFlight) "sí" else "no",
        ocrSkippedRequests = analysis.textRecognition.skippedWhileProcessing.toString(),
        ocrDuration = analysis.textRecognition.result?.processingDurationNanos.asMilliseconds(),
        ocrMatched = analysis.textRecognition.result?.expectedTextMatched?.let {
            if (it) "sí" else "no"
        } ?: "no evaluado",
        ocrReason = analysis.textRecognition.result?.error
            ?: analysis.textRecognition.result?.rejectionReason,
        referenceName = analysis.recognition.referenceName,
        referenceResolution = if (
            analysis.recognition.referenceWidth != null &&
            analysis.recognition.referenceHeight != null
        ) {
            "${analysis.recognition.referenceWidth} × ${analysis.recognition.referenceHeight}"
        } else {
            "no disponible"
        },
        referenceKeypoints = analysis.recognition.referenceKeypoints.toString(),
        frameKeypoints = analysis.recognition.frameKeypoints.toString(),
        knnMatches = analysis.recognition.knnMatches.toString(),
        ratioMatches = analysis.recognition.ratioAcceptedMatches.toString(),
        ransacInliers = analysis.recognition.ransacInliers.toString(),
        inlierRatio = String.format(Locale.US, "%.3f", analysis.recognition.inlierRatio),
        homographyStatus = analysis.recognition.homographyStatus,
        detectedCorners = analysis.recognition.corners.takeIf { it.size == 4 }?.joinToString(
            prefix = "[",
            postfix = "]",
        ) { point ->
            String.format(Locale.US, "(%.1f, %.1f)", point.x, point.y)
        } ?: "no disponibles",
        recognitionExtractionDuration =
            analysis.recognition.extractionDurationNanos.asMilliseconds(),
        recognitionMatchingDuration =
            analysis.recognition.matchingDurationNanos.asMilliseconds(),
        recognitionHomographyDuration =
            analysis.recognition.homographyDurationNanos.asMilliseconds(),
        recognitionTotalDuration = analysis.recognition.totalDurationNanos.asMilliseconds(),
        recognitionStatus = analysis.recognition.status.name,
        recognitionReason = analysis.recognition.error
            ?: analysis.recognition.rejectionReason?.diagnostic,
        arCore = when (capability.arCore) {
            ArCoreAvailability.CHECKING -> "comprobando"
            ArCoreAvailability.AVAILABLE -> "disponible"
            ArCoreAvailability.NOT_INSTALLED -> "no instalado"
            ArCoreAvailability.UNSUPPORTED -> "no compatible"
            ArCoreAvailability.UNKNOWN -> "no determinada"
        },
        mode = mode.name,
        previewError = error,
        analysisError = analysis.lastError,
        conversionError = analysis.lastConversionError,
        showPermissionAction = permission != CameraPermissionStatus.GRANTED,
        permissionExplanation = if (permission == CameraPermissionStatus.DENIED) {
            "La cámara es necesaria para mostrar la vista previa. " +
                "Puedes volver a solicitar el permiso."
        } else {
            null
        },
    )
}

private fun FrameResolution?.asDiagnosticResolution(): String =
    this?.let { "${it.width} × ${it.height}" } ?: "no disponible"

private fun Long?.asMilliseconds(): String = this?.let {
    String.format(Locale.US, "%.3f ms", it / 1_000_000.0)
} ?: "no disponible"

private fun List<Double>?.asDiagnosticMatrix(): String = this
    ?.chunked(3)
    ?.joinToString(prefix = "[", postfix = "]", separator = "; ") { row ->
        row.joinToString(prefix = "[", postfix = "]") {
            String.format(Locale.US, "%.4f", it)
        }
    } ?: "no disponible"

private fun List<Double>.asDiagnosticVector(unit: String): String = joinToString(
    prefix = "[",
    postfix = "] $unit",
) { String.format(Locale.US, "%.5f", it) }

private fun String.toVisibleDiagnosticText(maximumLength: Int = 180): String {
    val singleLine = replace(Regex("\\s+"), " ").trim()
    return if (singleLine.length <= maximumLength) singleLine else {
        singleLine.take(maximumLength - 1) + "…"
    }
}
