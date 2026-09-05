package dev.cabezudo.treasurehunt.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager

class CameraPermissionController(
    private val activity: Activity,
    private val launchPermissionRequest: () -> Unit,
    private val onStatusChanged: (CameraPermissionStatus) -> Unit,
) {
    private var status = CameraPermissionStatus.PENDING

    fun initialize() {
        refresh()
    }

    fun refresh() {
        val refreshed = when {
            hasPermission() -> CameraPermissionStatus.GRANTED
            activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                CameraPermissionStatus.DENIED
            }
            status == CameraPermissionStatus.DENIED -> CameraPermissionStatus.DENIED
            else -> CameraPermissionStatus.PENDING
        }
        update(refreshed)
    }

    fun requestPermission() {
        if (hasPermission()) {
            update(CameraPermissionStatus.GRANTED)
        } else {
            launchPermissionRequest()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        update(
            if (granted) CameraPermissionStatus.GRANTED else CameraPermissionStatus.DENIED,
        )
    }

    private fun hasPermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun update(newStatus: CameraPermissionStatus) {
        status = newStatus
        onStatusChanged(newStatus)
    }
}
