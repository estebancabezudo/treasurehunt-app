package dev.cabezudo.treasurehunt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.cabezudo.treasurehunt.ocr.ExpectedTextMatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitTextRecognitionTest {
    @Test
    fun exactGuardianTargetContainsExpectedPhraseUsingRealMlKit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.guardian_door_target,
            BitmapFactory.Options().apply { inScaled = false },
        )
        try {
            val text = recognize(bitmap)
            assertTrue(
                "ML Kit did not read the expected phrase. Recognized: $text",
                ExpectedTextMatcher.evaluate(text).matched,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun guardianTargetWithNumber28IsRejectedUsingRealMlKit() {
        val bitmap = localTextImage("PUERTA DEL GUARDIÁN 28")
        try {
            val text = recognize(bitmap)
            assertTrue("ML Kit did not read the control text. Recognized: $text", text.isNotBlank())
            assertFalse(ExpectedTextMatcher.evaluate(text).matched)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun imageWithoutTextIsRejectedUsingRealMlKit() {
        val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        try {
            val text = recognize(bitmap)
            assertFalse(ExpectedTextMatcher.evaluate(text).matched)
        } finally {
            bitmap.recycle()
        }
    }

    private fun recognize(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<String>>()
        try {
            // These local images are already upright, matching the runtime InputImage contract.
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text -> result.set(Result.success(text.text)) }
                .addOnFailureListener { error -> result.set(Result.failure(error)) }
                .addOnCompleteListener { latch.countDown() }
            assertTrue("ML Kit did not complete in time", latch.await(20, TimeUnit.SECONDS))
            return checkNotNull(result.get()).getOrThrow()
        } finally {
            recognizer.close()
        }
    }

    private fun localTextImage(text: String): Bitmap =
        Bitmap.createBitmap(1600, 420, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 104f
                isFakeBoldText = true
            }
            canvas.drawText(text, 60f, 240f, paint)
        }
}
