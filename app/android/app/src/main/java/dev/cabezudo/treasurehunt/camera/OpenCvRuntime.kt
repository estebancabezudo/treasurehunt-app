package dev.cabezudo.treasurehunt.camera

import org.opencv.android.OpenCVLoader

data class OpenCvInitialization(
    val status: OpenCvStatus,
    val error: String? = null,
)

object OpenCvRuntime {
    @Volatile
    private var initialization = OpenCvInitialization(OpenCvStatus.NOT_INITIALIZED)

    @Synchronized
    fun initialize(): OpenCvInitialization {
        if (initialization.status != OpenCvStatus.NOT_INITIALIZED) return initialization
        initialization = try {
            if (OpenCVLoader.initLocal()) {
                OpenCvInitialization(OpenCvStatus.READY)
            } else {
                OpenCvInitialization(OpenCvStatus.ERROR, "OpenCVLoader.initLocal() devolvió false.")
            }
        } catch (error: Throwable) {
            OpenCvInitialization(
                OpenCvStatus.ERROR,
                "No se pudo cargar OpenCV: ${rootCauseMessage(error)}",
            )
        }
        return initialization
    }

    fun current(): OpenCvInitialization = initialization

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
