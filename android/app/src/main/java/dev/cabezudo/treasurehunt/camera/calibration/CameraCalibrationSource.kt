package dev.cabezudo.treasurehunt.camera.calibration

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

class CameraCalibrationSource {
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun read(cameraInfo: CameraInfo): CameraCalibrationState = try {
        val camera2 = Camera2CameraInfo.from(cameraInfo)
        val intrinsics = camera2.getCameraCharacteristic(
            CameraCharacteristics.LENS_INTRINSIC_CALIBRATION,
        )?.takeIf { it.size >= 5 }?.let {
            IntrinsicParameters(
                fx = it[0].toDouble(),
                fy = it[1].toDouble(),
                cx = it[2].toDouble(),
                cy = it[3].toDouble(),
                skew = it[4].toDouble(),
            )
        }
        val distortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            readDistortion(camera2)
        } else {
            null
        }
        val active = camera2.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
        )?.let { CoordinateRect(it.left, it.top, it.right, it.bottom) }
        val preCorrection = camera2.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
        )?.let { CoordinateRect(it.left, it.top, it.right, it.bottom) }
        val physicalSize = camera2.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
        )
        val focalLengths = camera2.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
        )?.map(Float::toDouble).orEmpty()
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            readDistortionModes(camera2)
        } else {
            emptyList()
        }
        val missing = buildList {
            if (intrinsics == null) add("LENS_INTRINSIC_CALIBRATION")
            if (active == null) add("SENSOR_INFO_ACTIVE_ARRAY_SIZE")
            if (preCorrection == null) add("SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE")
        }
        val raw = RawCameraCalibration(
            cameraId = camera2.cameraId,
            intrinsics = intrinsics,
            lensDistortion = distortion,
            activeArray = active,
            preCorrectionActiveArray = preCorrection,
            availableFocalLengthsMm = focalLengths,
            sensorPhysicalWidthMm = physicalSize?.width?.toDouble(),
            sensorPhysicalHeightMm = physicalSize?.height?.toDouble(),
            availableDistortionCorrectionModes = modes,
            sensorOrientationDegrees = camera2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_ORIENTATION,
            ),
            distortionState = when {
                distortion == null -> AndroidDistortionState.NOT_REPORTED
                distortion.all { kotlin.math.abs(it) <= 1e-12 } -> {
                    AndroidDistortionState.REPORTED_ZERO
                }
                else -> AndroidDistortionState.REPORTED_NON_ZERO
            },
            unavailableCause = missing.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "Camera2 no expone: ",
            ),
        )
        CameraCalibrationState(
            status = if (missing.isEmpty()) {
                CameraCalibrationStatus.RAW_AVAILABLE
            } else {
                CameraCalibrationStatus.NOT_AVAILABLE
            },
            raw = raw,
            cause = raw.unavailableCause,
        )
    } catch (error: Exception) {
        CameraCalibrationState(
            status = CameraCalibrationStatus.ERROR,
            cause = "No se pudieron consultar las características Camera2: ${error.message}",
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun readDistortion(camera2: Camera2CameraInfo): List<Double>? =
        camera2.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION)
            ?.map(Float::toDouble)

    @RequiresApi(Build.VERSION_CODES.P)
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun readDistortionModes(camera2: Camera2CameraInfo): List<Int> =
        camera2.getCameraCharacteristic(
            CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
        )?.toList().orEmpty()
}
