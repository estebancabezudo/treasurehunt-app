package dev.cabezudo.treasurehunt.aruco.pose

import android.content.res.AssetManager
import dev.cabezudo.treasurehunt.camera.calibration.CoordinateRect
import dev.cabezudo.treasurehunt.manualcalibration.CANONICAL_BUFFER_COORDINATE_SYSTEM
import dev.cabezudo.treasurehunt.manualcalibration.ManualCalibrationResultJson
import dev.cabezudo.treasurehunt.manualcalibration.PHYSICAL_CALIBRATION_DATASET_SHA256
import dev.cabezudo.treasurehunt.manualcalibration.PHYSICAL_CALIBRATION_RESULT_SHA256
import java.security.MessageDigest

class PoseCalibrationLoader(
    private val assets: AssetManager,
    private val codec: ManualCalibrationResultJson = ManualCalibrationResultJson(),
) {
    fun load(): PoseCalibrationState = try {
        val bytes = assets.open(ASSET_NAME).use { it.readBytes() }
        val sha256 = bytes.sha256()
        require(sha256 == PHYSICAL_CALIBRATION_RESULT_SHA256) {
            "SHA-256 de calibración inesperado: $sha256."
        }
        val result = codec.decode(bytes.toString(Charsets.UTF_8))
        require(result.datasetSha256 == PHYSICAL_CALIBRATION_DATASET_SHA256) {
            "La calibración no pertenece al dataset físico autorizado."
        }
        require(result.identity.cameraId == "0") { "La calibración no pertenece a la cámara 0." }
        require(result.identity.bufferWidth == 640 && result.identity.bufferHeight == 480) {
            "La calibración no corresponde al buffer canónico 640 × 480."
        }
        require(result.identity.cropRect == CoordinateRect(0, 0, 640, 480)) {
            "La calibración no usa crop completo."
        }
        require(result.identity.coordinateSystem == CANONICAL_BUFFER_COORDINATE_SYSTEM) {
            "El sistema de coordenadas de la calibración es incompatible."
        }
        PoseCalibrationState(
            status = PoseCalibrationStatus.READY,
            result = result,
            resultSha256 = sha256,
            cause = null,
        )
    } catch (error: java.io.FileNotFoundException) {
        PoseCalibrationState(
            status = PoseCalibrationStatus.NOT_AVAILABLE,
            cause = "No está empaquetado $ASSET_NAME.",
        )
    } catch (error: Exception) {
        PoseCalibrationState(
            status = PoseCalibrationStatus.ERROR,
            cause = rootCauseMessage(error),
        )
    }

    companion object {
        const val ASSET_NAME = "25078RA3EL_camera0_calibration_v1.json"

        fun isCompatibleWithFrame(
            calibration: PoseCalibrationState,
            cameraId: String?,
            bufferWidth: Int,
            bufferHeight: Int,
            cropRect: CoordinateRect,
        ): PoseCalibrationState {
            if (calibration.status != PoseCalibrationStatus.READY || calibration.result == null) {
                return calibration
            }
            val identity = calibration.result.identity
            return if (
                cameraId == identity.cameraId &&
                bufferWidth == identity.bufferWidth &&
                bufferHeight == identity.bufferHeight &&
                cropRect == identity.cropRect
            ) {
                calibration
            } else {
                PoseCalibrationState(
                    status = PoseCalibrationStatus.INCOMPATIBLE,
                    resultSha256 = calibration.resultSha256,
                    cause = "Cuadro cámara=${cameraId ?: "no disponible"}, " +
                        "buffer=${bufferWidth}×$bufferHeight, crop=$cropRect; " +
                        "se requiere cámara=${identity.cameraId}, " +
                        "buffer=${identity.bufferWidth}×${identity.bufferHeight}, " +
                        "crop=${identity.cropRect}.",
                )
            }
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private fun rootCauseMessage(error: Throwable): String {
    var cause = error
    while (cause.cause != null) cause = cause.cause!!
    return cause.message ?: cause.javaClass.simpleName
}
