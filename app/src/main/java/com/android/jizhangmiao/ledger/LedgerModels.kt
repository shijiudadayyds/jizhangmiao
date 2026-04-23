package com.android.jizhangmiao.ledger

import com.android.jizhangmiao.ledger.data.LedgerBudgetConfig
import com.android.jizhangmiao.ledger.data.LedgerAutomationTrace
import com.android.jizhangmiao.ledger.data.LedgerTemplateRecurrence
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.LedgerTemplate
import com.android.jizhangmiao.ledger.data.defaultLedgerAccount
import com.android.jizhangmiao.ledger.data.ledgerAccountSuggestions

enum class LedgerPeriodFilter {
    THIS_MONTH,
    LAST_90_DAYS,
    ALL
}

enum class LedgerEntryFilterType {
    ALL,
    EXPENSE,
    INCOME
}

internal val expenseCategorySuggestions = listOf(
    "\u9910\u996e",
    "\u4ea4\u901a",
    "\u65e5\u7528",
    "\u4f4f\u623f",
    "\u5a31\u4e50",
    "\u533b\u7597",
    "\u5176\u4ed6"
)
internal val incomeCategorySuggestions = listOf(
    "\u5de5\u8d44",
    "\u5956\u91d1",
    "\u517c\u804c",
    "\u7406\u8d22",
    "\u9000\u6b3e",
    "\u7ea2\u5305",
    "\u5176\u4ed6"
)
internal val receiptIncomeKeywords = listOf(
    "\u5de5\u8d44",
    "\u6536\u5165",
    "\u5230\u8d26",
    "\u9000\u6b3e",
    "\u5956\u91d1",
    "\u6536\u6b3e"
)
internal val receiptExpenseKeywords = listOf(
    "\u652f\u4ed8",
    "\u5c0f\u7968",
    "\u8ba2\u5355",
    "\u6d88\u8d39",
    "\u4ed8\u6b3e",
    "\u5b9e\u4ed8"
)
internal val expenseCategoryKeywordMap = mapOf(
    "\u9910\u996e" to listOf("\u5496\u5561", "\u9910", "\u996d", "\u5976\u8336", "\u5916\u5356", "\u9152\u697c"),
    "\u4ea4\u901a" to listOf("\u5730\u94c1", "\u516c\u4ea4", "\u6253\u8f66", "\u9ad8\u94c1", "\u505c\u8f66", "\u8fc7\u8def\u8d39"),
    "\u65e5\u7528" to listOf("\u4fbf\u5229\u5e97", "\u8d85\u5e02", "\u6dd8\u5b9d", "\u65e5\u7528", "\u5546\u5e97"),
    "\u4f4f\u623f" to listOf("\u623f\u79df", "\u6c34\u8d39", "\u7535\u8d39", "\u7269\u4e1a", "\u71c3\u6c14"),
    "\u5a31\u4e50" to listOf("\u7535\u5f71", "\u6e38\u620f", "\u6f14\u51fa", "\u5f71\u9662", "KTV"),
    "\u533b\u7597" to listOf("\u533b\u9662", "\u836f", "\u95e8\u8bca", "\u836f\u623f", "\u68c0\u67e5"),
    "\u5176\u4ed6" to emptyList()
)
internal val incomeCategoryKeywordMap = mapOf(
    "\u5de5\u8d44" to listOf("\u5de5\u8d44", "\u85aa\u8d44"),
    "\u5956\u91d1" to listOf("\u5956\u91d1", "\u5956\u52b1"),
    "\u517c\u804c" to listOf("\u517c\u804c", "\u5916\u5feb", "\u5916\u5305"),
    "\u7406\u8d22" to listOf("\u5229\u606f", "\u7406\u8d22", "\u5206\u7ea2"),
    "\u9000\u6b3e" to listOf("\u9000\u6b3e", "\u9000\u8d27"),
    "\u7ea2\u5305" to listOf("\u7ea2\u5305", "\u8f6c\u8d60"),
    "\u5176\u4ed6" to emptyList()
)

fun categorySuggestionsFor(type: LedgerEntryType): List<String> {
    return when (type) {
        LedgerEntryType.EXPENSE -> expenseCategorySuggestions
        LedgerEntryType.INCOME -> incomeCategorySuggestions
    }
}

fun defaultCategoryFor(type: LedgerEntryType): String = categorySuggestionsFor(type).first()

fun accountSuggestions(): List<String> = ledgerAccountSuggestions

fun LedgerEntryType.displayName(): String {
    return when (this) {
        LedgerEntryType.EXPENSE -> "\u652f\u51fa"
        LedgerEntryType.INCOME -> "\u6536\u5165"
    }
}

fun LedgerPeriodFilter.displayName(): String {
    return when (this) {
        LedgerPeriodFilter.THIS_MONTH -> "\u672c\u6708"
        LedgerPeriodFilter.LAST_90_DAYS -> "90\u5929"
        LedgerPeriodFilter.ALL -> "\u5168\u90e8"
    }
}

fun LedgerEntryFilterType.displayName(): String {
    return when (this) {
        LedgerEntryFilterType.ALL -> "\u5168\u90e8"
        LedgerEntryFilterType.EXPENSE -> "\u652f\u51fa"
        LedgerEntryFilterType.INCOME -> "\u6536\u5165"
    }
}

fun LedgerTemplateRecurrence.displayName(): String {
    return when (this) {
        LedgerTemplateRecurrence.NONE -> "\u4e0d\u5faa\u73af"
        LedgerTemplateRecurrence.WEEKLY -> "\u6bcf\u5468"
        LedgerTemplateRecurrence.MONTHLY -> "\u6bcf\u6708"
    }
}

data class LedgerFormState(
    val editingEntryId: String? = null,
    val type: LedgerEntryType = LedgerEntryType.EXPENSE,
    val amount: String = "",
    val account: String = defaultLedgerAccount(),
    val category: String = defaultCategoryFor(LedgerEntryType.EXPENSE),
    val note: String = "",
    val templateRecurrence: LedgerTemplateRecurrence = LedgerTemplateRecurrence.NONE,
    val receiptText: String = "",
    val errorMessage: String? = null
) {
    val isEditing: Boolean
        get() = editingEntryId != null
}

data class LedgerSummary(
    val incomeInCents: Long = 0,
    val expenseInCents: Long = 0
) {
    val balanceInCents: Long
        get() = incomeInCents - expenseInCents
}

object LedgerSummaryCalculator {
    fun calculate(entries: List<LedgerEntry>): LedgerSummary {
        var income = 0L
        var expense = 0L

        entries.forEach { entry ->
            when (entry.type) {
                LedgerEntryType.EXPENSE -> expense += entry.amountInCents
                LedgerEntryType.INCOME -> income += entry.amountInCents
            }
        }

        return LedgerSummary(
            incomeInCents = income,
            expenseInCents = expense
        )
    }
}

data class LedgerUiState(
    val entries: List<LedgerEntry> = emptyList(),
    val templates: List<LedgerTemplate> = emptyList(),
    val budgetConfig: LedgerBudgetConfig = LedgerBudgetConfig(),
    val automationTrace: LedgerAutomationTrace = LedgerAutomationTrace(),
    val form: LedgerFormState = LedgerFormState(),
    val statusMessage: String? = null,
    val isReceiptScanning: Boolean = false
)
