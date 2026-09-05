package dev.cabezudo.treasurehunt.ocr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.core.CvType
import org.opencv.core.Mat

class AndroidOcrFrameImage internal constructor(
    internal val bitmap: Bitmap,
) : OcrFrameImage {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        bitmap.recycle()
    }
}

object OpenCvOcrImageFactory {
    @SuppressLint("UseKtx")
    fun create(monochrome: Mat): AndroidOcrFrameImage {
        require(monochrome.type() == CvType.CV_8UC1) {
            "OCR requiere un Mat monocromático CV_8UC1."
        }
        val width = monochrome.cols()
        val height = monochrome.rows()
        require(width > 0 && height > 0) { "El Mat OCR debe tener dimensiones positivas." }
        val luminance = ByteArray(width * height)
        val read = monochrome.get(0, 0, luminance)
        check(read == luminance.size) {
            "OpenCV leyó $read de ${luminance.size} bytes para OCR."
        }
        val colors = IntArray(luminance.size) { index ->
            val value = luminance[index].toInt() and 0xFF
            0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(colors, 0, width, 0, 0, width, height)
        return AndroidOcrFrameImage(bitmap)
    }
}

class MlKitTextRecognitionEngine(
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS,
    ),
) : TextRecognitionEngine {
    override fun process(image: OcrFrameImage, callback: TextRecognitionCallback) {
        require(image is AndroidOcrFrameImage) { "ML Kit requiere una imagen Android administrada." }
        // OpenCV ya aplicó imageInfo.rotationDegrees: volver a rotar aquí sería incorrecto.
        val input = InputImage.fromBitmap(image.bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { text -> callback.complete(Result.success(text.toDocument())) }
            .addOnFailureListener { error -> callback.complete(Result.failure(error)) }
    }

    override fun close() {
        recognizer.close()
    }
}

private fun Text.toDocument(): RecognizedTextDocument = RecognizedTextDocument(
    text = text,
    blocks = textBlocks.map { block ->
        RecognizedTextBlock(
            text = block.text,
            lines = block.lines.map { line ->
                RecognizedTextLine(
                    text = line.text,
                    elements = line.elements.map { element ->
                        RecognizedTextElement(element.text)
                    },
                )
            },
        )
    },
)
