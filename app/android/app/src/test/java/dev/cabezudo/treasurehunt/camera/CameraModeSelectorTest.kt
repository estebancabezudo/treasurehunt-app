package dev.cabezudo.treasurehunt.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeSelectorTest {
    @Test
    fun arCoreCompatibleStillUsesStandardCameraUntilArRouteExists() {
        val capability = CameraCapability(ArCoreAvailability.AVAILABLE)

        assertEquals(CameraMode.STANDARD_CAMERA, selectCameraMode(capability))
    }

    @Test
    fun arCoreIncompatibleUsesStandardCamera() {
        val state = CameraRuntimeState(
            capability = CameraCapability(ArCoreAvailability.UNSUPPORTED),
            mode = selectCameraMode(CameraCapability(ArCoreAvailability.UNSUPPORTED)),
        )

        assertEquals(CameraMode.STANDARD_CAMERA, state.mode)
        assertEquals("no compatible", state.toDiagnosticUiState().arCore)
    }

    @Test
    fun arCoreAbsentUsesStandardCameraWithoutFatalError() {
        val capability = CameraCapability(ArCoreAvailability.NOT_INSTALLED)
        val state = CameraRuntimeState(
            capability = capability,
            mode = selectCameraMode(capability),
        )

        assertEquals(CameraMode.STANDARD_CAMERA, state.mode)
        assertEquals(null, state.error)
        assertEquals("no instalado", state.toDiagnosticUiState().arCore)
    }

    @Test
    fun pendingPermissionOffersCameraRequest() {
        val diagnostic = CameraRuntimeState(
            permission = CameraPermissionStatus.PENDING,
        ).toDiagnosticUiState()

        assertEquals("Permiso de cámara requerido", diagnostic.headline)
        assertEquals("pendiente", diagnostic.permission)
        assertTrue(diagnostic.showPermissionAction)
    }

    @Test
    fun deniedPermissionExplainsAndAllowsRetry() {
        val diagnostic = CameraRuntimeState(
            permission = CameraPermissionStatus.DENIED,
        ).toDiagnosticUiState()

        assertEquals("rechazado", diagnostic.permission)
        assertTrue(diagnostic.showPermissionAction)
        assertTrue(diagnostic.permissionExplanation!!.contains("volver a solicitar"))
    }

    @Test
    fun startedCameraXReportsStandardCameraAndFrameDetails() {
        val diagnostic = CameraRuntimeState(
            permission = CameraPermissionStatus.GRANTED,
            cameraX = CameraXStatus.STARTED,
            selectedCamera = SelectedCamera.BACK,
            frameResolution = FrameResolution(1920, 1080),
            rotationDegrees = 90,
            capability = CameraCapability(ArCoreAvailability.UNSUPPORTED),
            mode = CameraMode.STANDARD_CAMERA,
        ).toDiagnosticUiState()

        assertEquals("Cámara activa", diagnostic.headline)
        assertEquals("iniciado", diagnostic.previewStatus)
        assertEquals("trasera", diagnostic.selectedCamera)
        assertEquals("1920 × 1080", diagnostic.previewResolution)
        assertEquals("90°", diagnostic.previewRotation)
        assertEquals("STANDARD_CAMERA", diagnostic.mode)
    }

    @Test
    fun cameraXErrorKeepsConcreteCause() {
        val diagnostic = CameraRuntimeState(
            permission = CameraPermissionStatus.GRANTED,
            cameraX = CameraXStatus.ERROR,
            error = "La cámara está siendo usada por otra aplicación.",
        ).toDiagnosticUiState()

        assertEquals(
            "Error: La cámara está siendo usada por otra aplicación.",
            diagnostic.headline,
        )
        assertEquals("error", diagnostic.previewStatus)
        assertEquals("La cámara está siendo usada por otra aplicación.", diagnostic.previewError)
    }
}
