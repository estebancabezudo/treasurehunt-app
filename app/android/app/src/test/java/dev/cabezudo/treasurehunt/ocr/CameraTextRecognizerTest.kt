package dev.cabezudo.treasurehunt.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTextRecognizerTest {
    @Test
    fun allowsOnlyOneRequestAndRecoversAfterSuccess() {
        val fixture = Fixture()
        fixture.recognizer.initialize()

        assertTrue(fixture.start(0L))
        assertFalse(fixture.start(600_000_000L))
        assertEquals(1, fixture.createdImages)
        assertEquals(1, fixture.recognizer.currentState().skippedWhileProcessing)

        fixture.engine.succeed(document(ExpectedText.RAW))
        assertEquals(TextRecognitionStatus.EXPECTED_TEXT_MATCHED, fixture.last.status)
        assertTrue(fixture.last.result!!.expectedTextMatched)
        assertTrue(fixture.engine.lastImage!!.closed)

        assertTrue(fixture.start(600_000_000L))
        assertEquals(2, fixture.createdImages)
    }

    @Test
    fun limitsFrequencyWithoutCreatingAnImage() {
        val fixture = Fixture()
        fixture.recognizer.initialize()
        assertTrue(fixture.start(0L))
        fixture.engine.succeed(document("texto distinto"))

        assertFalse(fixture.start(499_999_999L))
        assertEquals(1, fixture.createdImages)
        assertTrue(fixture.start(500_000_000L))
    }

    @Test
    fun publishesTextNotFoundAndRejectedSeparately() {
        val fixture = Fixture()
        fixture.recognizer.initialize()
        fixture.start(0L)
        fixture.engine.succeed(document(""))
        assertEquals(TextRecognitionStatus.TEXT_NOT_FOUND, fixture.last.status)

        fixture.start(500_000_000L)
        fixture.engine.succeed(document("PUERTA DEL GUARDIÁN 28"))
        assertEquals(TextRecognitionStatus.REJECTED, fixture.last.status)
        assertFalse(fixture.last.result!!.expectedTextMatched)
        assertTrue(fixture.states.any { it.status == TextRecognitionStatus.TEXT_FOUND })
    }

    @Test
    fun recoversAfterEngineError() {
        val fixture = Fixture()
        fixture.recognizer.initialize()
        fixture.start(0L)
        fixture.engine.fail(IllegalStateException("modelo temporalmente ocupado"))
        assertEquals(TextRecognitionStatus.ERROR, fixture.last.status)
        assertEquals("modelo temporalmente ocupado", fixture.last.result!!.error)

        assertTrue(fixture.start(500_000_000L))
        fixture.engine.succeed(document(ExpectedText.RAW))
        assertEquals(TextRecognitionStatus.EXPECTED_TEXT_MATCHED, fixture.last.status)
    }

    @Test
    fun ignoresCallbackAfterCloseAndCloseIsIdempotent() {
        val fixture = Fixture()
        fixture.recognizer.initialize()
        fixture.start(0L)

        fixture.recognizer.close()
        fixture.recognizer.close()
        val statesBeforeCallback = fixture.states.size
        fixture.engine.succeed(document(ExpectedText.RAW))

        assertEquals(1, fixture.engine.closeCalls)
        assertEquals(statesBeforeCallback, fixture.states.size)
        assertEquals(TextRecognitionStatus.CLOSED, fixture.last.status)
        assertTrue(fixture.engine.lastImage!!.closed)
        assertFalse(fixture.start(1_000_000_000L))
    }

    @Test
    fun imageFactoryFailureDoesNotLeaveRequestInFlight() {
        val states = mutableListOf<TextRecognitionState>()
        val recognizer = CameraTextRecognizer(FakeEngine(), states::add, { 10L })
        recognizer.initialize()

        assertFalse(recognizer.tryRecognize(0L) { error("falló bitmap") })

        assertEquals(TextRecognitionStatus.ERROR, recognizer.currentState().status)
        assertFalse(recognizer.currentState().requestInFlight)
    }

    private class Fixture {
        val engine = FakeEngine()
        val states = mutableListOf<TextRecognitionState>()
        var now = 0L
        var createdImages = 0
        val recognizer = CameraTextRecognizer(engine, states::add, { now })
        val last: TextRecognitionState get() = states.last()

        fun start(atNanos: Long): Boolean {
            now = atNanos
            return recognizer.tryRecognize(atNanos) {
                createdImages++
                FakeImage()
            }
        }
    }

    private class FakeImage : OcrFrameImage {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeEngine : TextRecognitionEngine {
        var callback: TextRecognitionCallback? = null
        var lastImage: FakeImage? = null
        var closeCalls = 0

        override fun process(image: OcrFrameImage, callback: TextRecognitionCallback) {
            lastImage = image as FakeImage
            this.callback = callback
        }

        fun succeed(document: RecognizedTextDocument) {
            callback!!.complete(Result.success(document))
            callback = null
        }

        fun fail(error: Throwable) {
            callback!!.complete(Result.failure(error))
            callback = null
        }

        override fun close() {
            closeCalls++
        }
    }

    private fun document(text: String) = RecognizedTextDocument(
        text = text,
        blocks = if (text.isEmpty()) emptyList() else listOf(
            RecognizedTextBlock(
                text = text,
                lines = listOf(
                    RecognizedTextLine(
                        text = text,
                        elements = text.split(' ').map(::RecognizedTextElement),
                    ),
                ),
            ),
        ),
    )
}
