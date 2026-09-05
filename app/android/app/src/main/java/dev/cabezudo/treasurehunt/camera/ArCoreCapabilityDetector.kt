package dev.cabezudo.treasurehunt.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ar.core.ArCoreApk

class ArCoreCapabilityDetector(
    context: Context,
    private val onCapabilityChanged: (CameraCapability) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var checkInFlight = false
    private var closed = false

    fun detect() {
        if (closed || checkInFlight) return
        checkInFlight = true
        onCapabilityChanged(CameraCapability(ArCoreAvailability.CHECKING))
        ArCoreApk.getInstance().checkAvailabilityAsync(applicationContext) { availability ->
            mainHandler.post {
                if (closed) return@post
                checkInFlight = false
                onCapabilityChanged(
                    CameraCapability(
                        arCore = when (availability) {
                            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                                ArCoreAvailability.AVAILABLE
                            }
                            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
                            -> ArCoreAvailability.NOT_INSTALLED
                            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                                ArCoreAvailability.UNSUPPORTED
                            }
                            ArCoreApk.Availability.UNKNOWN_CHECKING,
                            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
                            ArCoreApk.Availability.UNKNOWN_ERROR,
                            -> ArCoreAvailability.UNKNOWN
                        },
                    ),
                )
            }
        }
    }

    fun close() {
        closed = true
    }
}
