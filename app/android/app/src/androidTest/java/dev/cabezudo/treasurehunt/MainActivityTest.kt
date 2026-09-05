package dev.cabezudo.treasurehunt

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cabezudo.treasurehunt.camera.ArCoreAvailability
import dev.cabezudo.treasurehunt.camera.CameraMode
import dev.cabezudo.treasurehunt.camera.CameraRuntimeState
import dev.cabezudo.treasurehunt.camera.CameraXStatus
import dev.cabezudo.treasurehunt.camera.FrameAnalysisStatus
import dev.cabezudo.treasurehunt.camera.OpenCvRuntime
import dev.cabezudo.treasurehunt.camera.OpenCvStatus
import dev.cabezudo.treasurehunt.recognition.ImageRecognitionStatus
import dev.cabezudo.treasurehunt.ocr.TextRecognitionStatus
import dev.cabezudo.treasurehunt.camera.DetectionSampleOverlay
import dev.cabezudo.treasurehunt.camera.DetectionOverlayKind
import dev.cabezudo.treasurehunt.aruco.ArucoDetectionStatus
import dev.cabezudo.treasurehunt.aruco.pose.PoseCalibrationStatus
import dev.cabezudo.treasurehunt.camera.MonochromeFrameSample
import dev.cabezudo.treasurehunt.camera.calibration.CameraCalibrationStatus
import dev.cabezudo.treasurehunt.camera.DiagnosticProcessingMode
import dev.cabezudo.treasurehunt.recognition.RecognitionPoint
import dev.cabezudo.treasurehunt.ui.createDiagnosticBitmap
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationStatus
import dev.cabezudo.treasurehunt.manualcalibration.PHYSICAL_CALIBRATION_DATASET_SHA256
import dev.cabezudo.treasurehunt.manualcalibration.Sha256
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test
    fun standardCameraStartsWithoutRequiringArCore() {
        val validStates = setOf(
            "Permiso de cámara requerido",
            "Iniciando CameraX",
            "Cámara activa",
            "Cámara y análisis activos",
            "Cámara pausada",
            "Buscando objetivo",
            "Objetivo detectado",
        )
        var launchedActivity: MainActivity? = null
        val resumed = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ensurePhysicalCalibrationDataset(instrumentation)
        val application = instrumentation.targetContext.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    launchedActivity = activity
                    currentActivity = activity
                    resumed.countDown()
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -n dev.cabezudo.treasurehunt/.MainActivity",
                ),
            ).bufferedReader().use { it.readText() }
            assertTrue("MainActivity launch failed: $launchOutput", "Status: ok" in launchOutput)
            assertTrue("MainActivity did not reach RESUMED", resumed.await(10, TimeUnit.SECONDS))
            grantCameraPermissionThroughAccessibility(instrumentation)
            val state = waitForCameraState(instrumentation) { current ->
                val preparedResolution = current.analysis.preparedResolution
                current.mode == CameraMode.STANDARD_CAMERA &&
                    current.diagnosticMode == DiagnosticProcessingMode.RECOGNITION &&
                    current.capability.arCore != ArCoreAvailability.CHECKING &&
                    current.cameraX == CameraXStatus.STARTED &&
                    current.analysis.status == FrameAnalysisStatus.RUNNING &&
                    current.analysis.openCvStatus == OpenCvStatus.READY &&
                    current.analysis.preparedFrames > 0 &&
                    current.analysis.recognition.detectorInitialized &&
                    current.analysis.recognition.referenceLoaded &&
                    current.analysis.recognition.referenceKeypoints > 0 &&
                    current.analysis.recognition.processedFrames > 0 &&
                    current.analysis.recognition.status != ImageRecognitionStatus.ERROR &&
                    current.analysis.arucoDetection.detectorInitialized &&
                    current.analysis.arucoDetection.analyzedFrames > 0 &&
                    current.analysis.arucoDetection.status != ArucoDetectionStatus.ERROR &&
                    current.analysis.arucoPose.calibration.status == PoseCalibrationStatus.READY &&
                    current.analysis.calibration.status in setOf(
                        CameraCalibrationStatus.TRANSFORMED,
                        CameraCalibrationStatus.NOT_AVAILABLE,
                    ) &&
                    (
                        current.analysis.calibration.status != CameraCalibrationStatus.TRANSFORMED ||
                            current.analysis.calibration.prepared?.let { calibration ->
                                calibration.width == preparedResolution?.width &&
                                    calibration.height == preparedResolution.height &&
                                    calibration.intrinsicMatrix.values.all(Double::isFinite) &&
                                    calibration.fx > 0.0 && calibration.fy > 0.0
                            } == true
                    ) &&
                    current.analysis.textRecognition.completedRequests > 0 &&
                    current.analysis.textRecognition.status !in setOf(
                        TextRecognitionStatus.NOT_INITIALIZED,
                        TextRecognitionStatus.PROCESSING,
                        TextRecognitionStatus.ERROR,
                        TextRecognitionStatus.CLOSED,
                    ) &&
                    preparedResolution != null &&
                    preparedResolution.width > 0 &&
                    preparedResolution.height > 0 &&
                    current.analysis.lastConversionError == null
            }
            val headline = AtomicReference("")
            instrumentation.runOnMainSync {
                headline.set(launchedActivity!!.currentDiagnosticHeadline())
            }
            assertTrue(
                "No valid camera diagnostic state was displayed: ${headline.get()}",
                headline.get() in validStates || headline.get().startsWith("Error:"),
            )
            assertEquals(CameraMode.STANDARD_CAMERA, state.mode)
            assertEquals(DiagnosticProcessingMode.RECOGNITION, state.diagnosticMode)
            assertEquals(CameraXStatus.STARTED, state.cameraX)
            assertEquals(FrameAnalysisStatus.RUNNING, state.analysis.status)
            assertTrue("ImageAnalysis did not receive a frame", state.analysis.totalFrames > 0)
            assertEquals(OpenCvStatus.READY, state.analysis.openCvStatus)
            assertTrue("OpenCV did not prepare a frame", state.analysis.preparedFrames > 0)
            val preparedResolution = requireNotNull(state.analysis.preparedResolution)
            assertTrue(preparedResolution.width > 0)
            assertTrue(preparedResolution.height > 0)
            assertEquals(null, state.analysis.lastConversionError)
            assertTrue(state.analysis.recognition.detectorInitialized)
            assertTrue(state.analysis.recognition.referenceLoaded)
            assertTrue(state.analysis.recognition.referenceKeypoints > 0)
            assertTrue(state.analysis.recognition.processedFrames > 0)
            assertTrue(state.analysis.recognition.error == null)
            assertTrue(state.analysis.arucoDetection.detectorInitialized)
            assertTrue(state.analysis.arucoDetection.analyzedFrames > 0)
            assertTrue(state.analysis.arucoDetection.error == null)
            assertEquals(PoseCalibrationStatus.READY, state.analysis.arucoPose.calibration.status)
            assertTrue(
                state.analysis.calibration.status in setOf(
                    CameraCalibrationStatus.TRANSFORMED,
                    CameraCalibrationStatus.NOT_AVAILABLE,
                ),
            )
            state.analysis.calibration.prepared?.let { calibration ->
                assertEquals(preparedResolution.width, calibration.width)
                assertEquals(preparedResolution.height, calibration.height)
                assertTrue(calibration.fx > 0.0)
                assertTrue(calibration.fy > 0.0)
            }
            assertTrue(state.analysis.textRecognition.completedRequests > 0)
            assertTrue(state.analysis.textRecognition.result?.error == null)

            instrumentation.runOnMainSync {
                launchedActivity!!.setDiagnosticModeForTest(
                    DiagnosticProcessingMode.CAMERA_CALIBRATION,
                )
            }
            waitForCameraState(instrumentation) { current ->
                current.diagnosticMode == DiagnosticProcessingMode.CAMERA_CALIBRATION &&
                    current.cameraX == CameraXStatus.STARTED &&
                    current.analysis.calibrationBoard.analyzedFrames > 0
            }
            SystemClock.sleep(700)
            val calibrationStable = currentState(instrumentation)
            val orbFrames = calibrationStable.analysis.recognition.processedFrames
            val arucoFrames = calibrationStable.analysis.arucoDetection.analyzedFrames
            val ocrRequests = calibrationStable.analysis.textRecognition.completedRequests
            val cameraFrames = calibrationStable.analysis.totalFrames
            val calibrationSamplesBeforeRecreation =
                calibrationStable.analysis.calibrationCapture.acceptedSamples
            val calibrationDatasetPath =
                calibrationStable.analysis.calibrationCapture.datasetFilePath
            assertTrue(calibrationDatasetPath.endsWith("calibration_dataset_v1.json"))
            instrumentation.runOnMainSync {
                launchedActivity!!.calculateManualCalibrationForTest()
            }
            val calculated = waitForCameraState(instrumentation) { current ->
                current.manualCalibration.status == ManualCalibrationStatus.CALIBRATED
            }
            assertEquals(20, calculated.manualCalibration.result?.primary?.usedSampleIndices?.size)
            SystemClock.sleep(700)
            val stillCalibrating = currentState(instrumentation)
            assertEquals(orbFrames, stillCalibrating.analysis.recognition.processedFrames)
            assertEquals(arucoFrames, stillCalibrating.analysis.arucoDetection.analyzedFrames)
            assertEquals(ocrRequests, stillCalibrating.analysis.textRecognition.completedRequests)
            assertTrue(stillCalibrating.analysis.totalFrames > cameraFrames)
            assertTrue(stillCalibrating.analysis.calibrationBoard.analyzedFrames > 0)

            instrumentation.runOnMainSync { launchedActivity!!.recreate() }
            val recreatedCalibration = waitForCameraState(instrumentation) { current ->
                current.diagnosticMode == DiagnosticProcessingMode.CAMERA_CALIBRATION &&
                    current.cameraX == CameraXStatus.STARTED &&
                    current.analysis.calibrationBoard.analyzedFrames > 0 &&
                    current.analysis.calibrationCapture.datasetFilePath == calibrationDatasetPath
            }
            assertEquals(
                calibrationSamplesBeforeRecreation,
                recreatedCalibration.analysis.calibrationCapture.acceptedSamples,
            )
            assertEquals(
                ManualCalibrationStatus.CALIBRATED,
                recreatedCalibration.manualCalibration.status,
            )

            instrumentation.runOnMainSync {
                launchedActivity!!.setDiagnosticModeForTest(DiagnosticProcessingMode.RECOGNITION)
            }
            waitForCameraState(instrumentation) { current ->
                current.diagnosticMode == DiagnosticProcessingMode.RECOGNITION &&
                    current.analysis.recognition.processedFrames > orbFrames &&
                    current.analysis.arucoDetection.analyzedFrames > arucoFrames
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            instrumentation.runOnMainSync {
                launchedActivity?.finish()
                currentActivity = null
            }
        }
    }

    @Test
    fun officialOpenCvRuntimeInitializes() {
        assertEquals(OpenCvStatus.READY, OpenCvRuntime.initialize().status)
    }

    @Test
    fun detectedOverlayIsDrawnOnMutableDiagnosticBitmap() {
        val sample = MonochromeFrameSample(
            width = 32,
            height = 24,
            pixels = ByteArray(32 * 24) { 96 },
            detectionOverlays = listOf(DetectionSampleOverlay(
                corners = listOf(
                    RecognitionPoint(2.0, 2.0),
                    RecognitionPoint(29.0, 3.0),
                    RecognitionPoint(28.0, 21.0),
                    RecognitionPoint(3.0, 20.0),
                ),
                label = "DETECTED",
                kind = DetectionOverlayKind.ORB,
            )),
        )

        val bitmap = createDiagnosticBitmap(sample)

        assertTrue(bitmap.isMutable)
        assertTrue(bitmap.getPixel(2, 2) != bitmap.getPixel(16, 12))
        bitmap.recycle()
    }

    private fun grantCameraPermissionThroughAccessibility(
        instrumentation: android.app.Instrumentation,
    ) {
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        clickNodeContainingAnyText(instrumentation, listOf("Solicitar permiso de cámara"))
        clickNodeContainingAnyText(
            instrumentation,
            listOf("Mientras la app está en uso", "While using the app"),
        )

        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Camera permission was not granted through the Android dialog")
    }

    private fun ensurePhysicalCalibrationDataset(instrumentation: android.app.Instrumentation) {
        val directory = File(instrumentation.targetContext.filesDir, "calibration").apply { mkdirs() }
        val target = File(directory, "calibration_dataset_v1.json")
        instrumentation.context.assets.open("25078RA3EL_camera0_dataset_v1.json").use { input ->
            target.outputStream().use(input::copyTo)
        }
        assertEquals(PHYSICAL_CALIBRATION_DATASET_SHA256, Sha256.of(target))
    }

    private fun clickNodeContainingAnyText(
        instrumentation: android.app.Instrumentation,
        expectedTexts: List<String>,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val matchingNode = root?.let { findNodeContainingAnyText(it, expectedTexts) }
            val clickableNode = matchingNode?.let(::findClickableAncestor)
            if (clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Accessibility node was not actionable: $expectedTexts")
    }

    private fun findNodeContainingAnyText(
        node: AccessibilityNodeInfo,
        expectedTexts: List<String>,
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        if (expectedTexts.any { text.contains(it, ignoreCase = true) }) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNodeContainingAnyText(child, expectedTexts)?.let { return it }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (candidate.isClickable) return candidate
            candidate = candidate.parent
        }
        return null
    }

    private fun waitForCameraState(
        instrumentation: android.app.Instrumentation,
        predicate: (CameraRuntimeState) -> Boolean,
    ): CameraRuntimeState {
        val state = AtomicReference(CameraRuntimeState())
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                val activity = currentActivity
                if (activity != null) state.set(activity.currentCameraRuntimeState())
            }
            if (predicate(state.get())) return state.get()
            SystemClock.sleep(100)
        }
        throw AssertionError("CameraX did not reach a valid state: ${state.get()}")
    }

    private fun currentState(
        instrumentation: android.app.Instrumentation,
    ): CameraRuntimeState {
        val state = AtomicReference(CameraRuntimeState())
        instrumentation.runOnMainSync {
            state.set(requireNotNull(currentActivity).currentCameraRuntimeState())
        }
        return state.get()
    }

    private var currentActivity: MainActivity? = null
}
