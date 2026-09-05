package dev.cabezudo.treasurehunt.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.cabezudo.treasurehunt.camera.CameraRuntimeState
import dev.cabezudo.treasurehunt.camera.CameraDiagnosticUiState
import dev.cabezudo.treasurehunt.camera.MonochromeFrameSample
import dev.cabezudo.treasurehunt.camera.toDiagnosticUiState
import dev.cabezudo.treasurehunt.camera.DetectionOverlayKind
import dev.cabezudo.treasurehunt.camera.DiagnosticProcessingMode
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationStatus
import java.util.Locale

@Composable
fun TreasureHuntCameraApp(
    runtimeState: CameraRuntimeState,
    onPreviewReady: (PreviewView) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onDiagnosticModeChanged: (DiagnosticProcessingMode) -> Unit,
    onConfirmPhysicalSquareMeasurement: (Double, Double) -> Unit,
    onCaptureCalibrationSample: () -> Unit,
    onCancelCalibrationSample: () -> Unit,
    onResetCalibrationDataset: () -> Unit,
    onShowCalibrationDatasetJson: () -> Unit,
    onCalculateManualCalibration: () -> Unit,
    onShowManualCalibrationJson: () -> Unit,
    onConfirmPhysicalMarkerSize: (Double, Double) -> Unit,
) {
    val diagnostic = runtimeState.toDiagnosticUiState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var horizontalMeasurementText by rememberSaveable { mutableStateOf("") }
    var verticalMeasurementText by rememberSaveable { mutableStateOf("") }
    var resetConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    var markerWidthText by rememberSaveable { mutableStateOf("") }
    var markerHeightText by rememberSaveable { mutableStateOf("") }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { context -> PreviewView(context).also(onPreviewReady) },
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.76f))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
                    .semantics {
                        contentDescription = "Estado de cámara: ${diagnostic.headline}"
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Treasure Hunt",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = diagnostic.headline,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )

                diagnostic.permissionExplanation?.let { explanation ->
                    Text(
                        text = explanation,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (diagnostic.showPermissionAction) {
                    Button(onClick = onRequestCameraPermission) {
                        Text(
                            if (diagnostic.permission == "rechazado") {
                                "Volver a solicitar permiso"
                            } else {
                                "Solicitar permiso de cámara"
                            },
                        )
                    }
                }
                Button(
                    onClick = {
                        onDiagnosticModeChanged(
                            if (runtimeState.diagnosticMode == DiagnosticProcessingMode.RECOGNITION) {
                                DiagnosticProcessingMode.CAMERA_CALIBRATION
                            } else {
                                DiagnosticProcessingMode.RECOGNITION
                            },
                        )
                    },
                ) {
                    Text(
                        if (runtimeState.diagnosticMode == DiagnosticProcessingMode.RECOGNITION) {
                            "Activar calibración de cámara"
                        } else {
                            "Volver a reconocimiento"
                        },
                    )
                }
                if (runtimeState.diagnosticMode == DiagnosticProcessingMode.RECOGNITION) {
                    OutlinedTextField(
                        value = markerWidthText,
                        onValueChange = { markerWidthText = it },
                        label = { Text("Marcador negro — ancho exterior real (mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = calibrationMeasurementFieldColors(),
                    )
                    OutlinedTextField(
                        value = markerHeightText,
                        onValueChange = { markerHeightText = it },
                        label = { Text("Marcador negro — alto exterior real (mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = calibrationMeasurementFieldColors(),
                    )
                    Button(
                        onClick = {
                            onConfirmPhysicalMarkerSize(
                                markerWidthText.toDouble(),
                                markerHeightText.toDouble(),
                            )
                        },
                        enabled = markerWidthText.toDoubleOrNull()?.let { it > 0.0 } == true &&
                            markerHeightText.toDoubleOrNull()?.let { it > 0.0 } == true,
                    ) { Text("Confirmar tamaño físico del marcador") }
                }
                if (runtimeState.diagnosticMode == DiagnosticProcessingMode.CAMERA_CALIBRATION) {
                    OutlinedTextField(
                        value = horizontalMeasurementText,
                        onValueChange = { horizontalMeasurementText = it },
                        label = { Text("Paso horizontal real (mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = calibrationMeasurementFieldColors(),
                    )
                    OutlinedTextField(
                        value = verticalMeasurementText,
                        onValueChange = { verticalMeasurementText = it },
                        label = { Text("Paso vertical real (mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = calibrationMeasurementFieldColors(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                val horizontal = horizontalMeasurementText.toDoubleOrNull()
                                val vertical = verticalMeasurementText.toDoubleOrNull()
                                if (horizontal != null && vertical != null) {
                                    onConfirmPhysicalSquareMeasurement(horizontal, vertical)
                                }
                            },
                            enabled = horizontalMeasurementText.toDoubleOrNull()?.let { it > 0.0 } == true &&
                                verticalMeasurementText.toDoubleOrNull()?.let { it > 0.0 } == true,
                        ) { Text("Confirmar medidas") }
                        Button(
                            onClick = onCaptureCalibrationSample,
                            enabled = runtimeState.analysis.calibrationCapture.pendingRequest == null,
                        ) { Text("Capturar muestra") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = onCancelCalibrationSample,
                            enabled = runtimeState.analysis.calibrationCapture.pendingRequest != null,
                        ) { Text("Cancelar captura") }
                        Button(onClick = onShowCalibrationDatasetJson) { Text("Mostrar JSON") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = onCalculateManualCalibration,
                            enabled = runtimeState.analysis.calibrationCapture.coverage.ready &&
                                runtimeState.manualCalibration.status !=
                                ManualCalibrationStatus.CALCULATING,
                        ) { Text("Calcular calibración") }
                        Button(
                            onClick = onShowManualCalibrationJson,
                            enabled = runtimeState.manualCalibration.result != null,
                        ) { Text("Mostrar resultado JSON") }
                    }
                    if (resetConfirmationVisible) {
                        Text(
                            "Esta acción elimina todas las muestras válidas del dataset.",
                            color = Color(0xFFFFD18B),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = {
                                onResetCalibrationDataset()
                                resetConfirmationVisible = false
                            }) { Text("Confirmar reinicio") }
                            Button(onClick = { resetConfirmationVisible = false }) {
                                Text("Conservar dataset")
                            }
                        }
                    } else {
                        Button(onClick = { resetConfirmationVisible = true }) {
                            Text("Reiniciar dataset")
                        }
                    }
                }
                ManualCalibrationMetrics(runtimeState)
                DiagnosticMetrics(diagnostic, isLandscape)

                diagnostic.previewError?.let { error ->
                    Text(
                        text = "Error de vista previa: $error",
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.analysisError?.let { error ->
                    Text(
                        text = "Error de análisis: $error",
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.conversionError?.takeIf { it != diagnostic.analysisError }?.let { error ->
                    Text(
                        text = "Error de conversión: $error",
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.recognitionReason?.let { reason ->
                    Text(
                        text = "Reconocimiento: $reason",
                        color = if (diagnostic.recognitionStatus == "ERROR") {
                            Color(0xFFFFB4AB)
                        } else {
                            Color(0xFFFFD18B)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.ocrReason?.let { reason ->
                    Text(
                        text = "OCR: $reason",
                        color = if (diagnostic.ocrStatus == "ERROR") {
                            Color(0xFFFFB4AB)
                        } else {
                            Color(0xFFFFD18B)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.arucoReason?.let { reason ->
                    Text(
                        text = "ArUco: $reason",
                        color = if (diagnostic.arucoStatus == "ERROR") {
                            Color(0xFFFFB4AB)
                        } else {
                            Color(0xFFFFD18B)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.calibrationCause?.let { reason ->
                    Text(
                        text = "Calibración: $reason",
                        color = Color(0xFFFFD18B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.boardReason?.let { reason ->
                    Text(
                        text = "Tablero: $reason",
                        color = if (diagnostic.boardStatus == "ERROR") {
                            Color(0xFFFFB4AB)
                        } else {
                            Color(0xFFFFD18B)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.lastCalibrationRejection?.let { reason ->
                    Text(
                        text = "Captura: $reason",
                        color = Color(0xFFFFD18B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                diagnostic.calibrationJsonPreview?.let { json ->
                    Text(
                        text = json.take(2_000),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                runtimeState.manualCalibration.jsonPreview?.let { json ->
                    Text(
                        text = json.take(4_000),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = "ARCore es una capacidad opcional; la cámara estándar funciona sin él.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            runtimeState.monochromeSample?.let { sample ->
                MonochromeSample(
                    sample = sample,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ManualCalibrationMetrics(runtimeState: CameraRuntimeState) {
    val state = runtimeState.manualCalibration
    val result = state.result
    val active = result?.comparativeWithoutOutliers ?: result?.primary
    val worst = active?.sampleErrors?.maxByOrNull { it.rmsPixels }
    val values = listOf(
        "Calibración manual — estado" to state.status.name,
        "Calibración manual — muestras usadas" to
            (active?.usedSampleIndices?.joinToString() ?: "no disponible"),
        "Calibración manual — muestras excluidas" to
            (active?.excludedSampleIndices?.joinToString()?.ifEmpty { "ninguna" }
                ?: "no disponible"),
        "Calibración manual — fx/fy" to active?.let {
            String.format(Locale.US, "%.6f / %.6f px", it.fx, it.fy)
        }.orEmpty().ifEmpty { "no disponible" },
        "Calibración manual — cx/cy" to active?.let {
            String.format(Locale.US, "%.6f / %.6f px", it.cx, it.cy)
        }.orEmpty().ifEmpty { "no disponible" },
        "Calibración manual — k1,k2,p1,p2,k3" to
            (active?.distortionCoefficients?.joinToString(prefix = "[", postfix = "]") {
                String.format(Locale.US, "%.9g", it)
            } ?: "no disponibles"),
        "Calibración manual — RMS global" to active?.let {
            String.format(Locale.US, "%.6f px", it.globalRmsPixels)
        }.orEmpty().ifEmpty { "no disponible" },
        "Calibración manual — peor muestra" to worst?.let {
            String.format(Locale.US, "#%d: RMS %.6f px", it.sampleIndex, it.rmsPixels)
        }.orEmpty().ifEmpty { "no disponible" },
        "Calibración manual — archivo" to state.resultFilePath,
        "Calibración manual — causa" to (state.cause ?: "ninguna"),
    )
    values.forEach { DiagnosticLine(it.first, it.second) }
}

@Composable
private fun calibrationMeasurementFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedContainerColor = Color(0xFF202124),
    unfocusedContainerColor = Color(0xFF202124),
    cursorColor = Color.White,
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color.White,
    focusedBorderColor = Color(0xFF8EC5FF),
    unfocusedBorderColor = Color(0xFFBDBDBD),
)

@Composable
private fun DiagnosticMetrics(diagnostic: CameraDiagnosticUiState, splitColumns: Boolean) {
    val lines = listOf(
        "Modo diagnóstico" to diagnostic.diagnosticMode,
        "Tablero — estado" to diagnostic.boardStatus,
        "Tablero — esquinas" to diagnostic.boardCornerCount,
        "Tablero — coordenadas" to diagnostic.boardCorners,
        "Tablero — resolución" to diagnostic.boardFrameResolution,
        "Tablero — área relativa" to diagnostic.boardAreaFraction,
        "Tablero — centro" to diagnostic.boardCenter,
        "Tablero — tamaño aproximado" to diagnostic.boardApproximateSize,
        "Tablero — duración" to diagnostic.boardDuration,
        "Tablero — análisis" to diagnostic.boardAnalyzedFrames,
        "Tablero — omitidos" to diagnostic.boardSkippedFrames,
        "Captura — solicitud pendiente" to diagnostic.capturePending,
        "Captura — medida confirmada" to diagnostic.confirmedSquareMeasurement,
        "Dataset — muestras aceptadas" to diagnostic.acceptedCalibrationSamples,
        "Dataset — muestras rechazadas" to diagnostic.rejectedCalibrationSamples,
        "Dataset — posiciones 3 × 3" to diagnostic.coveredGridPositions,
        "Dataset — escalas" to diagnostic.coveredScales,
        "Dataset — inclinación horizontal" to diagnostic.coveredHorizontalTilts,
        "Dataset — inclinación vertical" to diagnostic.coveredVerticalTilts,
        "Dataset — orientaciones" to diagnostic.coveredOrientations,
        "Dataset — cantidad recomendada" to diagnostic.recommendedCalibrationSamples,
        "Dataset — estado" to diagnostic.calibrationDatasetReady,
        "Dataset — recomendaciones" to diagnostic.calibrationRecommendations,
        "Dataset — archivo interno" to diagnostic.calibrationDatasetFile,
        "Dataset — identidad" to diagnostic.calibrationDatasetIdentity,
        "Calibración — ID de cámara" to diagnostic.calibrationCameraId,
        "Calibración — estado" to diagnostic.calibrationStatus,
        "Calibración — orientación del sensor" to diagnostic.calibrationSensorOrientation,
        "Calibración — arreglo activo" to diagnostic.calibrationSensorResolution,
        "Calibración — arreglo previo a corrección" to
            diagnostic.calibrationPreCorrectionResolution,
        "Calibración — resolución ImageAnalysis" to diagnostic.calibrationAnalysisResolution,
        "Calibración — crop del cuadro" to diagnostic.calibrationCrop,
        "Calibración — rotación aplicada" to diagnostic.calibrationRotation,
        "Calibración — matriz sensor a buffer" to diagnostic.calibrationSensorToBufferMatrix,
        "Calibración — intrínsecos crudos" to diagnostic.calibrationRawIntrinsics,
        "Calibración — intrínsecos transformados" to diagnostic.calibrationPreparedIntrinsics,
        "Calibración — focales disponibles" to diagnostic.calibrationFocalLengths,
        "Calibración — tamaño físico del sensor" to diagnostic.calibrationSensorPhysicalSize,
        "Calibración — distorsión" to diagnostic.calibrationDistortion,
        "Calibración — matriz 3 × 3" to diagnostic.calibrationMatrix,
        "Calibración — causa" to (diagnostic.calibrationCause ?: "ninguna"),
        "ArUco — estado" to diagnostic.arucoStatus,
        "ArUco — diccionario" to diagnostic.arucoDictionary,
        "ArUco — ID esperado" to diagnostic.arucoExpectedId,
        "ArUco — IDs encontrados" to diagnostic.arucoFoundIds,
        "ArUco — marcadores" to diagnostic.arucoMarkerCount,
        "ArUco — candidatos rechazados" to diagnostic.arucoRejectedCandidates,
        "ArUco — esquinas del esperado" to diagnostic.arucoExpectedCorners,
        "ArUco — área relativa" to diagnostic.arucoAreaFraction,
        "ArUco — duración" to diagnostic.arucoDuration,
        "ArUco — análisis" to diagnostic.arucoAnalyzedFrames,
        "ArUco — omitidos por frecuencia" to diagnostic.arucoSkippedFrames,
        "Pose ArUco — estado" to diagnostic.arucoPoseStatus,
        "Pose ArUco — calibración" to diagnostic.arucoPoseCalibrationStatus,
        "Pose ArUco — tamaño físico" to diagnostic.arucoPosePhysicalSize,
        "Pose ArUco — identidad" to diagnostic.arucoPoseIdentity,
        "Pose ArUco — tx/ty/tz" to diagnostic.arucoPoseTranslation,
        "Pose ArUco — distancia euclidiana" to diagnostic.arucoPoseDistance,
        "Pose ArUco — rvec" to diagnostic.arucoPoseRotationVector,
        "Pose ArUco — Euler XYZ" to diagnostic.arucoPoseEuler,
        "Pose ArUco — matriz R" to diagnostic.arucoPoseRotationMatrix,
        "Pose ArUco — RMS reproyección" to diagnostic.arucoPoseReprojectionRms,
        "Pose ArUco — duración" to diagnostic.arucoPoseDuration,
        "Pose ArUco — causa" to (diagnostic.arucoPoseReason ?: "ninguna"),
        "OCR — estado" to diagnostic.ocrStatus,
        "OCR — texto esperado" to diagnostic.ocrExpectedText,
        "OCR — texto reconocido" to diagnostic.ocrRecognizedText,
        "OCR — texto normalizado" to diagnostic.ocrNormalizedText,
        "OCR — estructura" to diagnostic.ocrStructureCounts,
        "OCR — solicitud en curso" to diagnostic.ocrRequestInFlight,
        "OCR — omitidos por concurrencia" to diagnostic.ocrSkippedRequests,
        "OCR — duración" to diagnostic.ocrDuration,
        "OCR — coincidencia exacta" to diagnostic.ocrMatched,
        "Estado de reconocimiento" to diagnostic.recognitionStatus,
        "Referencia" to diagnostic.referenceName,
        "Dimensiones de referencia" to diagnostic.referenceResolution,
        "Puntos ORB de referencia" to diagnostic.referenceKeypoints,
        "Puntos ORB del cuadro" to diagnostic.frameKeypoints,
        "Comparaciones KNN" to diagnostic.knnMatches,
        "Coincidencias por razón" to diagnostic.ratioMatches,
        "Inliers RANSAC" to diagnostic.ransacInliers,
        "Proporción de inliers" to diagnostic.inlierRatio,
        "Estado de homografía" to diagnostic.homographyStatus,
        "Esquinas" to diagnostic.detectedCorners,
        "Extracción ORB" to diagnostic.recognitionExtractionDuration,
        "Comparación" to diagnostic.recognitionMatchingDuration,
        "Homografía" to diagnostic.recognitionHomographyDuration,
        "Reconocimiento total" to diagnostic.recognitionTotalDuration,
        "Permiso de cámara" to diagnostic.permission,
        "Estado de vista previa" to diagnostic.previewStatus,
        "Estado del análisis" to diagnostic.analysisStatus,
        "Cámara seleccionada" to diagnostic.selectedCamera,
        "Resolución de Preview" to diagnostic.previewResolution,
        "Rotación de Preview" to diagnostic.previewRotation,
        "Resolución de ImageAnalysis" to diagnostic.analysisResolution,
        "Rotación del análisis" to diagnostic.analysisRotation,
        "Formato del análisis" to diagnostic.analysisFormat,
        "Planos del último cuadro" to diagnostic.analysisPlaneCount,
        "Cuadros totales" to diagnostic.totalFrames,
        "Cuadros preparados" to diagnostic.preparedFrames,
        "Cuadros del último segundo" to diagnostic.framesLastSecond,
        "FPS aproximados" to diagnostic.approximateFps,
        "Antigüedad del último cuadro" to diagnostic.lastFrameAge,
        "Estado de OpenCV" to diagnostic.openCvStatus,
        "Dimensiones antes de rotar" to diagnostic.sourceResolution,
        "Dimensiones después de rotar" to diagnostic.preparedResolution,
        "Strides del plano Y" to diagnostic.luminanceStrides,
        "Duración de copia" to diagnostic.copyDuration,
        "Duración de rotación" to diagnostic.rotationDuration,
        "Preparación total" to diagnostic.preparationDuration,
        "Luminancia media" to diagnostic.meanLuminance,
        "Disponibilidad de ARCore" to diagnostic.arCore,
        "Modo activo" to diagnostic.mode,
    )
    if (splitColumns) {
        val midpoint = (lines.size + 1) / 2
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                lines.take(midpoint).forEach { DiagnosticLine(it.first, it.second) }
            }
            Column(modifier = Modifier.weight(1f)) {
                lines.drop(midpoint).forEach { DiagnosticLine(it.first, it.second) }
            }
        }
    } else {
        lines.forEach { DiagnosticLine(it.first, it.second) }
    }
}

@Composable
private fun MonochromeSample(sample: MonochromeFrameSample, modifier: Modifier = Modifier) {
    val image = remember(sample) {
        createDiagnosticBitmap(sample).asImageBitmap()
    }
    Image(
        bitmap = image,
        contentDescription = "Muestra monocromática preparada",
        modifier = modifier
            .width(144.dp)
            .aspectRatio(sample.width.toFloat() / sample.height),
        contentScale = ContentScale.Fit,
    )
}

@SuppressLint("UseKtx")
internal fun createDiagnosticBitmap(sample: MonochromeFrameSample): Bitmap {
    val colors = IntArray(sample.pixels.size) { index ->
        val luminance = sample.pixels[index].toInt() and 0xFF
        0xFF000000.toInt() or (luminance shl 16) or (luminance shl 8) or luminance
    }
    return Bitmap.createBitmap(sample.width, sample.height, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.setPixels(colors, 0, sample.width, 0, 0, sample.width, sample.height)
        drawDetectionOverlay(bitmap, sample)
    }
}

private fun drawDetectionOverlay(bitmap: Bitmap, sample: MonochromeFrameSample) {
    if (sample.detectionOverlays.isEmpty()) return
    val canvas = Canvas(bitmap)
    val cornerColors = intArrayOf(
        android.graphics.Color.RED,
        android.graphics.Color.YELLOW,
        android.graphics.Color.CYAN,
        android.graphics.Color.MAGENTA,
    )
    sample.detectionOverlays.forEachIndexed { overlayIndex, overlay ->
        if (overlay.kind == DetectionOverlayKind.CALIBRATION_BOARD) {
            drawCalibrationBoardOverlay(canvas, overlay, overlayIndex)
            return@forEachIndexed
        }
        if (overlay.kind == DetectionOverlayKind.ARUCO_POSE_AXES) {
            drawPoseAxesOverlay(canvas, overlay, overlayIndex)
            return@forEachIndexed
        }
        if (overlay.corners.size != 4) return@forEachIndexed
        val overlayColor = when (overlay.kind) {
            DetectionOverlayKind.ORB -> android.graphics.Color.GREEN
            DetectionOverlayKind.ARUCO_EXPECTED -> android.graphics.Color.CYAN
            DetectionOverlayKind.ARUCO_UNEXPECTED -> android.graphics.Color.RED
            DetectionOverlayKind.ARUCO_POSE_AXES -> android.graphics.Color.WHITE
            DetectionOverlayKind.CALIBRATION_BOARD -> android.graphics.Color.BLUE
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlayColor
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        overlay.corners.indices.forEach { index ->
            val start = overlay.corners[index]
            val end = overlay.corners[(index + 1) % overlay.corners.size]
            canvas.drawLine(
                start.x.toFloat(),
                start.y.toFloat(),
                end.x.toFloat(),
                end.y.toFloat(),
                linePaint,
            )
        }
        val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        overlay.corners.forEachIndexed { index, corner ->
            cornerPaint.color = cornerColors[index]
            canvas.drawCircle(corner.x.toFloat(), corner.y.toFloat(), 5f, cornerPaint)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlayColor
            textSize = 15f
            isFakeBoldText = true
        }
        canvas.drawText(overlay.label, 5f, 18f + overlayIndex * 17f, textPaint)
    }
}

private fun drawPoseAxesOverlay(
    canvas: Canvas,
    overlay: dev.cabezudo.treasurehunt.camera.DetectionSampleOverlay,
    overlayIndex: Int,
) {
    if (overlay.corners.size != 4) return
    val origin = overlay.corners[0]
    val colors = intArrayOf(
        android.graphics.Color.RED,
        android.graphics.Color.GREEN,
        android.graphics.Color.BLUE,
    )
    val labels = arrayOf("X", "Y", "Z")
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        isFakeBoldText = true
    }
    for (index in 0..2) {
        val endpoint = overlay.corners[index + 1]
        paint.color = colors[index]
        textPaint.color = colors[index]
        canvas.drawLine(
            origin.x.toFloat(), origin.y.toFloat(),
            endpoint.x.toFloat(), endpoint.y.toFloat(), paint,
        )
        canvas.drawText(labels[index], endpoint.x.toFloat(), endpoint.y.toFloat(), textPaint)
    }
    textPaint.color = android.graphics.Color.WHITE
    canvas.drawText(overlay.label, 5f, 18f + overlayIndex * 17f, textPaint)
}

private fun drawCalibrationBoardOverlay(
    canvas: Canvas,
    overlay: dev.cabezudo.treasurehunt.camera.DetectionSampleOverlay,
    overlayIndex: Int,
) {
    val columns = overlay.gridColumns ?: return
    val rows = overlay.gridRows ?: return
    if (overlay.corners.size != columns * rows) return
    val rowColors = intArrayOf(
        android.graphics.Color.RED,
        android.graphics.Color.YELLOW,
        android.graphics.Color.GREEN,
        android.graphics.Color.CYAN,
        android.graphics.Color.BLUE,
        android.graphics.Color.MAGENTA,
    )
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    for (row in 0 until rows) {
        linePaint.color = rowColors[row % rowColors.size]
        for (column in 0 until columns - 1) {
            val start = overlay.corners[row * columns + column]
            val end = overlay.corners[row * columns + column + 1]
            canvas.drawLine(start.x.toFloat(), start.y.toFloat(), end.x.toFloat(), end.y.toFloat(), linePaint)
        }
    }
    linePaint.color = android.graphics.Color.WHITE
    for (column in 0 until columns) {
        for (row in 0 until rows - 1) {
            val start = overlay.corners[row * columns + column]
            val end = overlay.corners[(row + 1) * columns + column]
            canvas.drawLine(start.x.toFloat(), start.y.toFloat(), end.x.toFloat(), end.y.toFloat(), linePaint)
        }
    }
    val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    overlay.corners.forEachIndexed { index, point ->
        pointPaint.color = rowColors[(index / columns) % rowColors.size]
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 3.5f, pointPaint)
    }
    val outline = listOf(
        overlay.corners.first(),
        overlay.corners[columns - 1],
        overlay.corners.last(),
        overlay.corners[(rows - 1) * columns],
    )
    linePaint.color = android.graphics.Color.GREEN
    linePaint.strokeWidth = 3f
    outline.indices.forEach { index ->
        val start = outline[index]
        val end = outline[(index + 1) % outline.size]
        canvas.drawLine(start.x.toFloat(), start.y.toFloat(), end.x.toFloat(), end.y.toFloat(), linePaint)
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.GREEN
        textSize = 15f
        isFakeBoldText = true
    }
    canvas.drawText(overlay.label, 5f, 18f + overlayIndex * 17f, textPaint)
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        color = Color.White,
        style = MaterialTheme.typography.bodySmall,
    )
}
