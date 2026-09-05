package dev.cabezudo.treasurehunt

import dev.cabezudo.treasurehunt.camera.DiagnosticProcessingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticModePersistenceTest {
    @Test
    fun calibrationModeRestoresAfterActivityRecreation() {
        assertEquals(
            DiagnosticProcessingMode.CAMERA_CALIBRATION,
            diagnosticModeFromSavedValue("CAMERA_CALIBRATION"),
        )
    }

    @Test
    fun recognitionModeRestoresAfterActivityRecreation() {
        assertEquals(
            DiagnosticProcessingMode.RECOGNITION,
            diagnosticModeFromSavedValue("RECOGNITION"),
        )
    }

    @Test
    fun unknownSavedModeIsIgnoredSafely() {
        assertNull(diagnosticModeFromSavedValue("FUTURE_MODE"))
        assertNull(diagnosticModeFromSavedValue(null))
    }
}
