package com.android.jizhangmiao.ledger

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.defaultLedgerAccount
import com.android.jizhangmiao.ledger.data.toAmountInCents
import com.android.jizhangmiao.ledger.data.toAmountInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReceiptRecognitionResult(
    val rawText: String,
    val amountInput: String?,
    val account: String?,
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

    val amountCandidates = lines.flatMap { line ->
        amountRegex.findAll(line)
            .mapNotNull { matchResult -> parseAmountToken(matchResult.value) }
            .toList()
            .map { amountInCents -> line to amountInCents }
    }

    val prioritizedAmount = amountCandidates
        .filter { (line, _) -> receiptAmountPriorityKeywords.any { keyword -> line.contains(keyword) } }
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
        account = guessAccount(text),
        category = category,
        suggestedNote = suggestedNote,
        type = type
    )
}

private fun guessType(text: String): LedgerEntryType? {
    return when {
        receiptIncomeKeywords.any { keyword -> text.contains(keyword) } -> LedgerEntryType.INCOME
        receiptExpenseKeywords.any { keyword -> text.contains(keyword) } -> LedgerEntryType.EXPENSE
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

private fun guessAccount(text: String): String {
    return accountKeywordMap.entries.firstOrNull { (_, keywords) ->
        keywords.any(text::contains)
    }?.key ?: defaultLedgerAccount()
}

private fun parseAmountToken(token: String): Long? {
    val normalized = token.replace(",", "").replace("\u00a5", "")
    return normalized.toAmountInCents()
}

private val amountRegex = Regex("""(?<!\d)(?:\d{1,5}(?:[.,]\d{1,2})?)(?!\d)""")
private val receiptAmountPriorityKeywords = listOf(
    "\u5408\u8ba1",
    "\u603b\u8ba1",
    "\u5b9e\u4ed8",
    "\u652f\u4ed8",
    "\u5e94\u4ed8",
    "\u91d1\u989d"
)
private val accountKeywordMap = mapOf(
    "\u652f\u4ed8\u5b9d" to listOf("\u652f\u4ed8\u5b9d", "\u4f59\u989d\u5b9d"),
    "\u5fae\u4fe1" to listOf("\u5fae\u4fe1", "\u5fae\u4fe1\u652f\u4ed8"),
    "\u94f6\u884c\u5361" to listOf("\u94f6\u884c\u5361", "\u50a8\u84c4\u5361", "\u4fe1\u7528\u5361"),
    "\u73b0\u91d1" to listOf("\u73b0\u91d1")
)
