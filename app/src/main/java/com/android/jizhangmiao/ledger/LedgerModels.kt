package com.android.jizhangmiao.ledger

import com.android.jizhangmiao.ledger.data.LedgerBudgetConfig
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.LedgerTemplate

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

private val expenseCategorySuggestions = listOf(
    "\u9910\u996e",
    "\u4ea4\u901a",
    "\u65e5\u7528",
    "\u4f4f\u623f",
    "\u5a31\u4e50",
    "\u533b\u7597",
    "\u5176\u4ed6"
)
private val incomeCategorySuggestions = listOf(
    "\u5de5\u8d44",
    "\u5956\u91d1",
    "\u517c\u804c",
    "\u7406\u8d22",
    "\u9000\u6b3e",
    "\u7ea2\u5305",
    "\u5176\u4ed6"
)

fun categorySuggestionsFor(type: LedgerEntryType): List<String> {
    return when (type) {
        LedgerEntryType.EXPENSE -> expenseCategorySuggestions
        LedgerEntryType.INCOME -> incomeCategorySuggestions
    }
}

fun defaultCategoryFor(type: LedgerEntryType): String = categorySuggestionsFor(type).first()

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

data class LedgerFormState(
    val editingEntryId: String? = null,
    val type: LedgerEntryType = LedgerEntryType.EXPENSE,
    val amount: String = "",
    val category: String = defaultCategoryFor(LedgerEntryType.EXPENSE),
    val note: String = "",
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
    val form: LedgerFormState = LedgerFormState(),
    val statusMessage: String? = null,
    val isReceiptScanning: Boolean = false
)
