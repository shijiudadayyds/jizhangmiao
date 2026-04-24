package com.android.jizhangmiao.ledger.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class LedgerEntryType {
    EXPENSE,
    INCOME
}

enum class LedgerTemplateRecurrence {
    NONE,
    WEEKLY,
    MONTHLY
}

val ledgerAccountSuggestions = listOf(
    "\u652f\u4ed8\u5b9d",
    "\u5fae\u4fe1",
    "\u94f6\u884c\u5361",
    "\u73b0\u91d1"
)

fun defaultLedgerAccount(): String = ledgerAccountSuggestions.first()

data class LedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: LedgerEntryType,
    val amountInCents: Long,
    val account: String = defaultLedgerAccount(),
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
    val account: String = defaultLedgerAccount(),
    val category: String,
    val recurrence: LedgerTemplateRecurrence = LedgerTemplateRecurrence.NONE,
    val nextDueAt: Long? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class LedgerBudgetConfig(
    val monthlyBudgetInCents: Long? = null,
    val categoryBudgets: Map<String, Long> = emptyMap()
)

data class LedgerProfileConfig(
    val customAccounts: List<String> = emptyList(),
    val customExpenseCategories: List<String> = emptyList(),
    val customIncomeCategories: List<String> = emptyList()
)

data class LedgerAutomationTrace(
    val sourceLabel: String = "",
    val summary: String = "",
    val rawText: String = "",
    val happenedAt: Long = 0L
) {
    val isAvailable: Boolean
        get() = happenedAt > 0L
}

data class PendingLedgerImport(
    val id: String = UUID.randomUUID().toString(),
    val signature: String,
    val type: LedgerEntryType,
    val amountInCents: Long,
    val account: String = defaultLedgerAccount(),
    val category: String,
    val note: String = "",
    val receiptText: String = "",
    val happenedAt: Long,
    val sourceLabel: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class LedgerSecurityConfig(
    val pinHash: String = "",
    val pinSalt: String = ""
) {
    val isPinEnabled: Boolean
        get() = pinHash.isNotBlank() && pinSalt.isNotBlank()
}

data class BackupPreview(
    val entriesCount: Int,
    val templatesCount: Int,
    val categoryBudgetCount: Int
)

enum class LedgerImportMode {
    REPLACE,
    MERGE
}

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

data class RecurringSyncResult(
    val entries: List<LedgerEntry>,
    val templates: List<LedgerTemplate>,
    val generatedCount: Int
)

fun nextRecurringDueAt(
    baseTimeMillis: Long,
    recurrence: LedgerTemplateRecurrence
): Long? {
    val zoneId = ZoneId.systemDefault()
    val baseDateTime = Instant.ofEpochMilli(baseTimeMillis).atZone(zoneId)
    return when (recurrence) {
        LedgerTemplateRecurrence.NONE -> null
        LedgerTemplateRecurrence.WEEKLY -> baseDateTime.plusWeeks(1).toInstant().toEpochMilli()
        LedgerTemplateRecurrence.MONTHLY -> baseDateTime.plusMonths(1).toInstant().toEpochMilli()
    }
}

fun initialTemplateNextDueAt(
    fromTimeMillis: Long,
    recurrence: LedgerTemplateRecurrence
): Long? = nextRecurringDueAt(fromTimeMillis, recurrence)

fun syncRecurringTemplates(
    entries: List<LedgerEntry>,
    templates: List<LedgerTemplate>,
    now: Long = System.currentTimeMillis()
): RecurringSyncResult {
    val generatedEntries = mutableListOf<LedgerEntry>()
    val updatedTemplates = templates.map { template ->
        if (template.recurrence == LedgerTemplateRecurrence.NONE) {
            template.copy(nextDueAt = null)
        } else {
            var nextDueAt = template.nextDueAt ?: initialTemplateNextDueAt(now, template.recurrence)
            while (nextDueAt != null && nextDueAt <= now) {
                generatedEntries += LedgerEntry(
                    type = template.type,
                    amountInCents = template.amountInCents,
                    account = template.account.ifBlank { defaultLedgerAccount() },
                    category = template.category,
                    note = template.note,
                    happenedAt = nextDueAt,
                    updatedAt = nextDueAt
                )
                nextDueAt = nextRecurringDueAt(nextDueAt, template.recurrence)
            }
            template.copy(
                account = template.account.ifBlank { defaultLedgerAccount() },
                nextDueAt = nextDueAt
            )
        }
    }

    return RecurringSyncResult(
        entries = entries + generatedEntries,
        templates = updatedTemplates,
        generatedCount = generatedEntries.size
    )
}

fun normalizeEntries(entries: List<LedgerEntry>): List<LedgerEntry> {
    return entries.sortedWith(
        compareByDescending<LedgerEntry> { it.happenedAt }
            .thenByDescending { it.updatedAt }
            .thenByDescending { it.id }
    )
}

fun normalizeTemplates(templates: List<LedgerTemplate>): List<LedgerTemplate> {
    return templates.sortedWith(
        compareByDescending<LedgerTemplate> { it.createdAt }
            .thenByDescending { it.id }
    )
}

fun normalizePendingImports(pendingImports: List<PendingLedgerImport>): List<PendingLedgerImport> {
    return pendingImports.sortedWith(
        compareByDescending<PendingLedgerImport> { it.createdAt }
            .thenByDescending { it.happenedAt }
            .thenByDescending { it.id }
    )
}
