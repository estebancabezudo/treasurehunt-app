package dev.cabezudo.treasurehunt.ocr

interface OcrFrameImage : AutoCloseable

fun interface TextRecognitionCallback {
    fun complete(result: Result<RecognizedTextDocument>)
}

interface TextRecognitionEngine : AutoCloseable {
    fun process(image: OcrFrameImage, callback: TextRecognitionCallback)
}

class CameraTextRecognizer(
    private val engine: TextRecognitionEngine,
    private val onStateChanged: (TextRecognitionState) -> Unit,
    private val monotonicNanos: () -> Long,
    analysesPerSecond: Double = 2.0,
) : AutoCloseable {
    private val minimumIntervalNanos = (1_000_000_000.0 / analysesPerSecond).toLong()
    private var state = TextRecognitionState()
    private var initialized = false
    private var closed = false
    private var inFlight = false
    private var lastRequestNanos: Long? = null
    private var activeRequestId = 0L

    init {
        require(analysesPerSecond > 0.0) { "La frecuencia OCR debe ser positiva." }
    }

    @Synchronized
    fun initialize() {
        if (closed || initialized) return
        initialized = true
        state = state.copy(status = TextRecognitionStatus.READY)
        onStateChanged(state)
    }

    fun tryRecognize(nowNanos: Long, imageFactory: () -> OcrFrameImage): Boolean {
        val requestId: Long
        val processingState: TextRecognitionState
        synchronized(this) {
            if (closed || !initialized) return false
            if (inFlight) {
                state = state.copy(skippedWhileProcessing = state.skippedWhileProcessing + 1)
                return false
            }
            val previous = lastRequestNanos
            if (previous != null && nowNanos - previous < minimumIntervalNanos) return false
            inFlight = true
            lastRequestNanos = nowNanos
            requestId = ++activeRequestId
            state = state.copy(
                status = TextRecognitionStatus.PROCESSING,
                requestInFlight = true,
            )
            processingState = state
        }
        onStateChanged(processingState)

        val image = try {
            imageFactory()
        } catch (error: Throwable) {
            completeRequest(requestId, null, Result.failure(error))
            return false
        }
        try {
            engine.process(image) { result -> completeRequest(requestId, image, result) }
        } catch (error: Throwable) {
            completeRequest(requestId, image, Result.failure(error))
        }
        return true
    }

    @Synchronized
    fun currentState(): TextRecognitionState = state

    private fun completeRequest(
        requestId: Long,
        image: OcrFrameImage?,
        engineResult: Result<RecognizedTextDocument>,
    ) {
        image?.close()
        val statesToPublish = mutableListOf<TextRecognitionState>()
        synchronized(this) {
            if (requestId != activeRequestId || !inFlight) return
            inFlight = false
            if (closed) return
            val completedAt = monotonicNanos()
            val document = engineResult.getOrNull()
            val error = engineResult.exceptionOrNull()
            if (error != null) {
                val result = TextRecognitionResult(
                    fullText = "",
                    blocks = emptyList(),
                    normalizedText = "",
                    normalizedExpectedText = ExpectedText.NORMALIZED,
                    expectedTextMatched = false,
                    processingDurationNanos = elapsedSinceLastRequest(completedAt),
                    completedAtNanos = completedAt,
                    error = rootCauseMessage(error),
                )
                state = state.copy(
                    status = TextRecognitionStatus.ERROR,
                    result = result,
                    requestInFlight = false,
                    completedRequests = state.completedRequests + 1,
                )
                statesToPublish += state
            } else {
                checkNotNull(document)
                val decision = ExpectedTextMatcher.evaluate(document.text)
                val result = TextRecognitionResult(
                    fullText = document.text,
                    blocks = document.blocks,
                    normalizedText = decision.normalizedText,
                    normalizedExpectedText = ExpectedText.NORMALIZED,
                    expectedTextMatched = decision.matched,
                    processingDurationNanos = elapsedSinceLastRequest(completedAt),
                    completedAtNanos = completedAt,
                    rejectionReason = decision.rejectionReason,
                )
                val completedRequests = state.completedRequests + 1
                if (decision.normalizedText.isEmpty()) {
                    state = state.copy(
                        status = TextRecognitionStatus.TEXT_NOT_FOUND,
                        result = result,
                        requestInFlight = false,
                        completedRequests = completedRequests,
                    )
                    statesToPublish += state
                } else {
                    state = state.copy(
                        status = TextRecognitionStatus.TEXT_FOUND,
                        result = result,
                        requestInFlight = false,
                        completedRequests = completedRequests,
                    )
                    statesToPublish += state
                    state = state.copy(
                        status = if (decision.matched) {
                            TextRecognitionStatus.EXPECTED_TEXT_MATCHED
                        } else {
                            TextRecognitionStatus.REJECTED
                        },
                    )
                    statesToPublish += state
                }
            }
        }
        statesToPublish.forEach(onStateChanged)
    }

    override fun close() {
        val closedState: TextRecognitionState
        synchronized(this) {
            if (closed) return
            closed = true
            activeRequestId++
            state = state.copy(
                status = TextRecognitionStatus.CLOSED,
                requestInFlight = false,
            )
            closedState = state
        }
        engine.close()
        onStateChanged(closedState)
    }

    private fun elapsedSinceLastRequest(completedAt: Long): Long =
        (completedAt - (lastRequestNanos ?: completedAt)).coerceAtLeast(0L)

    private fun rootCauseMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
