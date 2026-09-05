package dev.cabezudo.treasurehunt.recognition

import android.content.Context
import android.graphics.BitmapFactory
import dev.cabezudo.treasurehunt.R
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

data class LoadedReferenceImage(
    val name: String,
    val width: Int,
    val height: Int,
    val grayscale: Mat,
) : AutoCloseable {
    override fun close() = grayscale.release()
}

class ReferenceImageLoader(private val context: Context) {
    fun load(): LoadedReferenceImage {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = requireNotNull(
            BitmapFactory.decodeResource(
                context.resources,
                R.drawable.guardian_door_target,
                options,
            ),
        ) { "No se pudo decodificar la referencia ${ReferenceTarget.NAME}." }
        val rgba = Mat()
        val grayscale = Mat()
        try {
            check(bitmap.width == ReferenceTarget.PIXEL_WIDTH &&
                bitmap.height == ReferenceTarget.PIXEL_HEIGHT
            ) {
                "La referencia mide ${bitmap.width} × ${bitmap.height}; se esperaba " +
                    "${ReferenceTarget.PIXEL_WIDTH} × ${ReferenceTarget.PIXEL_HEIGHT}."
            }
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, grayscale, Imgproc.COLOR_RGBA2GRAY)
            return LoadedReferenceImage(
                name = ReferenceTarget.NAME,
                width = bitmap.width,
                height = bitmap.height,
                grayscale = grayscale,
            )
        } catch (error: Throwable) {
            grayscale.release()
            throw error
        } finally {
            rgba.release()
            bitmap.recycle()
        }
    }
}
