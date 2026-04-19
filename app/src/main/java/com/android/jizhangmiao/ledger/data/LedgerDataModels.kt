package com.android.jizhangmiao.ledger.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

enum class LedgerEntryType {
    EXPENSE,
    INCOME
}

data class LedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: LedgerEntryType,
    val amountInCents: Long,
    val category: String,
    val note: String = "",
    val receiptText: String = "",
    val happenedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class LedgerTemplate(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val type: LedgerEntryType,
    val amountInCents: Long,
    val category: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class LedgerBudgetConfig(
    val monthlyBudgetInCents: Long? = null,
    val categoryBudgets: Map<String, Long> = emptyMap()
)

fun sanitizeAmountInput(value: String): String {
    val filtered = value.filter { it.isDigit() || it == '.' }
    val dotIndex = filtered.indexOf('.')

    if (dotIndex == -1) {
        return filtered
    }

    val integerPart = filtered.substring(0, dotIndex)
    val decimalPart = filtered.substring(dotIndex + 1).replace(".", "").take(2)
    return buildString {
        append(integerPart)
        append('.')
        append(decimalPart)
    }
}

fun String.toAmountInCents(): Long? {
    if (isBlank()) {
        return null
    }

    return runCatching {
        trim().toBigDecimal()
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()?.takeIf { it > 0L }
}

fun Long.toAmountInput(): String {
    return BigDecimal.valueOf(this, 2)
        .stripTrailingZeros()
        .toPlainString()
}
