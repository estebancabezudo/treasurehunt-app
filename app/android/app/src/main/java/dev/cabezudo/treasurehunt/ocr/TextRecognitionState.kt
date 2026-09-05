package dev.cabezudo.treasurehunt.ocr

data class RecognizedTextElement(val text: String)

data class RecognizedTextLine(
    val text: String,
    val elements: List<RecognizedTextElement>,
)

data class RecognizedTextBlock(
    val text: String,
    val lines: List<RecognizedTextLine>,
)

data class RecognizedTextDocument(
    val text: String,
    val blocks: List<RecognizedTextBlock>,
) {
    val lineCount: Int get() = blocks.sumOf { it.lines.size }
    val elementCount: Int get() = blocks.sumOf { block ->
        block.lines.sumOf { it.elements.size }
    }
}

enum class TextRecognitionStatus {
    NOT_INITIALIZED,
    READY,
    PROCESSING,
    TEXT_NOT_FOUND,
    TEXT_FOUND,
    EXPECTED_TEXT_MATCHED,
    REJECTED,
    ERROR,
    CLOSED,
}

data class TextRecognitionResult(
    val fullText: String,
    val blocks: List<RecognizedTextBlock>,
    val normalizedText: String,
    val normalizedExpectedText: String,
    val expectedTextMatched: Boolean,
    val processingDurationNanos: Long,
    val completedAtNanos: Long,
    val rejectionReason: String? = null,
    val error: String? = null,
) {
    val blockCount: Int get() = blocks.size
    val lineCount: Int get() = blocks.sumOf { it.lines.size }
    val elementCount: Int get() = blocks.sumOf { block ->
        block.lines.sumOf { it.elements.size }
    }
}

data class TextRecognitionState(
    val status: TextRecognitionStatus = TextRecognitionStatus.NOT_INITIALIZED,
    val expectedText: String = ExpectedText.RAW,
    val normalizedExpectedText: String = ExpectedText.NORMALIZED,
    val result: TextRecognitionResult? = null,
    val requestInFlight: Boolean = false,
    val skippedWhileProcessing: Long = 0,
    val completedRequests: Long = 0,
)
