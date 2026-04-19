package com.android.jizhangmiao.ledger

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.toAmountInCents
import com.android.jizhangmiao.ledger.data.toAmountInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReceiptRecognitionResult(
    val rawText: String,
    val amountInput: String?,
    val category: String?,
    val suggestedNote: String,
    val type: LedgerEntryType?
)

suspend fun recognizeReceipt(
    context: Context,
    uri: Uri
): ReceiptRecognitionResult? = withContext(Dispatchers.IO) {
    val image = InputImage.fromFilePath(context, uri)
    val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    try {
        val result = Tasks.await(recognizer.process(image))
        parseReceiptText(result.text)
    } finally {
        recognizer.close()
    }
}

internal fun parseReceiptText(text: String): ReceiptRecognitionResult? {
    val lines = text.lines()
        .map { line -> line.trim() }
        .filter { line -> line.isNotBlank() }

    if (lines.isEmpty()) {
        return null
    }

    val priorityKeywords = listOf(
        "\u5408\u8ba1",
        "\u603b\u8ba1",
        "\u5b9e\u4ed8",
        "\u652f\u4ed8",
        "\u5e94\u4ed8",
        "\u91d1\u989d"
    )

    val amountCandidates = lines.flatMap { line ->
        amountRegex.findAll(line)
            .mapNotNull { matchResult -> parseAmountToken(matchResult.value) }
            .toList()
            .map { amountInCents -> line to amountInCents }
    }

    val prioritizedAmount = amountCandidates
        .filter { (line, _) -> priorityKeywords.any { keyword -> line.contains(keyword) } }
        .maxByOrNull { (_, amountInCents) -> amountInCents }
        ?.second

    val fallbackAmount = amountCandidates
        .map { (_, amountInCents) -> amountInCents }
        .filter { amountInCents -> amountInCents in 50..2_000_000 }
        .maxOrNull()

    val finalAmount = prioritizedAmount ?: fallbackAmount
    val type = guessType(text)
    val category = guessCategory(text, type)
    val suggestedNote = lines.take(3).joinToString(" ")

    return ReceiptRecognitionResult(
        rawText = text,
        amountInput = finalAmount?.toAmountInput(),
        category = category,
        suggestedNote = suggestedNote,
        type = type
    )
}

private fun guessType(text: String): LedgerEntryType? {
    return when {
        incomeKeywords.any { keyword -> text.contains(keyword) } -> LedgerEntryType.INCOME
        expenseKeywords.any { keyword -> text.contains(keyword) } -> LedgerEntryType.EXPENSE
        else -> LedgerEntryType.EXPENSE
    }
}

private fun guessCategory(
    text: String,
    type: LedgerEntryType?
): String? {
    if (type == LedgerEntryType.INCOME) {
        return incomeCategorySuggestions.firstOrNull { category ->
            incomeCategoryKeywordMap[category].orEmpty().any { keyword -> text.contains(keyword) }
        } ?: defaultCategoryFor(LedgerEntryType.INCOME)
    }

    return expenseCategorySuggestions.firstOrNull { category ->
        expenseCategoryKeywordMap[category].orEmpty().any { keyword -> text.contains(keyword) }
    } ?: defaultCategoryFor(LedgerEntryType.EXPENSE)
}

private fun parseAmountToken(token: String): Long? {
    val normalized = token.replace(",", "").replace("\u00a5", "")
    return normalized.toAmountInCents()
}

private val amountRegex = Regex("""(?<!\d)(?:\d{1,5}(?:[.,]\d{1,2})?)(?!\d)""")

private val incomeKeywords = listOf(
    "\u5de5\u8d44",
    "\u6536\u5165",
    "\u5230\u8d26",
    "\u9000\u6b3e",
    "\u5956\u91d1",
    "\u6536\u6b3e"
)

private val expenseKeywords = listOf(
    "\u652f\u4ed8",
    "\u5c0f\u7968",
    "\u8ba2\u5355",
    "\u6d88\u8d39",
    "\u4ed8\u6b3e",
    "\u5b9e\u4ed8"
)

private val expenseCategorySuggestions = categorySuggestionsFor(LedgerEntryType.EXPENSE)
private val incomeCategorySuggestions = categorySuggestionsFor(LedgerEntryType.INCOME)

private val expenseCategoryKeywordMap = mapOf(
    "\u9910\u996e" to listOf("\u5496\u5561", "\u9910", "\u996d", "\u5976\u8336", "\u5916\u5356", "\u9152\u697c"),
    "\u4ea4\u901a" to listOf("\u5730\u94c1", "\u516c\u4ea4", "\u6253\u8f66", "\u9ad8\u94c1", "\u505c\u8f66", "\u8fc7\u8def\u8d39"),
    "\u65e5\u7528" to listOf("\u4fbf\u5229\u5e97", "\u8d85\u5e02", "\u6dd8\u5b9d", "\u65e5\u7528", "\u5546\u5e97"),
    "\u4f4f\u623f" to listOf("\u623f\u79df", "\u6c34\u8d39", "\u7535\u8d39", "\u7269\u4e1a", "\u71c3\u6c14"),
    "\u5a31\u4e50" to listOf("\u7535\u5f71", "\u6e38\u620f", "\u6f14\u51fa", "\u5f71\u9662", "KTV"),
    "\u533b\u7597" to listOf("\u533b\u9662", "\u836f", "\u95e8\u8bca", "\u836f\u623f", "\u68c0\u67e5"),
    "\u5176\u4ed6" to emptyList()
)

private val incomeCategoryKeywordMap = mapOf(
    "\u5de5\u8d44" to listOf("\u5de5\u8d44", "\u85aa\u8d44"),
    "\u5956\u91d1" to listOf("\u5956\u91d1", "\u5956\u52b1"),
    "\u517c\u804c" to listOf("\u517c\u804c", "\u5916\u5feb", "\u5916\u5305"),
    "\u7406\u8d22" to listOf("\u5229\u606f", "\u7406\u8d22", "\u5206\u7ea2"),
    "\u9000\u6b3e" to listOf("\u9000\u6b3e", "\u9000\u8d27"),
    "\u7ea2\u5305" to listOf("\u7ea2\u5305", "\u8f6c\u8d60"),
    "\u5176\u4ed6" to emptyList()
)
