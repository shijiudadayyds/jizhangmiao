package com.android.jizhangmiao.ledger.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.android.jizhangmiao.ledger.data.LedgerAutomationTrace
import com.android.jizhangmiao.ledger.data.LedgerBudgetConfig
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.LedgerJsonCodec
import com.android.jizhangmiao.ledger.data.LedgerProfileConfig
import com.android.jizhangmiao.ledger.data.LedgerSecurityConfig
import com.android.jizhangmiao.ledger.data.LedgerTemplate
import com.android.jizhangmiao.ledger.data.LedgerTemplateRecurrence
import com.android.jizhangmiao.ledger.data.PendingLedgerImport
import com.android.jizhangmiao.ledger.data.defaultLedgerAccount

@Entity(tableName = "ledger_entries")
internal data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val type: LedgerEntryType,
    val amountInCents: Long,
    val account: String,
    val category: String,
    val note: String,
    val receiptText: String,
    val happenedAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "ledger_templates")
internal data class LedgerTemplateEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: LedgerEntryType,
    val amountInCents: Long,
    val account: String,
    val category: String,
    val recurrence: LedgerTemplateRecurrence,
    val nextDueAt: Long?,
    val note: String,
    val createdAt: Long
)

@Entity(tableName = "ledger_pending_imports")
internal data class PendingLedgerImportEntity(
    @PrimaryKey val id: String,
    val signature: String,
    val type: LedgerEntryType,
    val amountInCents: Long,
    val account: String,
    val category: String,
    val note: String,
    val receiptText: String,
    val happenedAt: Long,
    val sourceLabel: String,
    val createdAt: Long
)

@Entity(tableName = "ledger_metadata")
internal data class LedgerMetadataEntity(
    @PrimaryKey val singletonId: Int = 1,
    val monthlyBudgetInCents: Long? = null,
    val categoryBudgetsJson: String = "{}",
    val customAccountsJson: String = "[]",
    val customExpenseCategoriesJson: String = "[]",
    val customIncomeCategoriesJson: String = "[]",
    val automationSourceLabel: String = "",
    val automationSummary: String = "",
    val automationRawText: String = "",
    val automationHappenedAt: Long = 0L,
    val pinHash: String = "",
    val pinSalt: String = "",
    val legacyMigrationCompletedAt: Long = 0L
)

@Entity(tableName = "ledger_auto_import_history")
internal data class AutoImportHistoryEntity(
    @PrimaryKey val signature: String,
    val createdAt: Long
)

internal val DefaultLedgerMetadataEntity = LedgerMetadataEntity()

internal fun LedgerEntryEntity.toModel(): LedgerEntry {
    return LedgerEntry(
        id = id,
        type = type,
        amountInCents = amountInCents,
        account = account.ifBlank { defaultLedgerAccount() },
        category = category,
        note = note,
        receiptText = receiptText,
        happenedAt = happenedAt,
        updatedAt = updatedAt
    )
}

internal fun LedgerEntry.toEntity(): LedgerEntryEntity {
    return LedgerEntryEntity(
        id = id,
        type = type,
        amountInCents = amountInCents,
        account = account.ifBlank { defaultLedgerAccount() },
        category = category,
        note = note,
        receiptText = receiptText,
        happenedAt = happenedAt,
        updatedAt = updatedAt
    )
}

internal fun LedgerTemplateEntity.toModel(): LedgerTemplate {
    return LedgerTemplate(
        id = id,
        title = title,
        type = type,
        amountInCents = amountInCents,
        account = account.ifBlank { defaultLedgerAccount() },
        category = category,
        recurrence = recurrence,
        nextDueAt = nextDueAt,
        note = note,
        createdAt = createdAt
    )
}

internal fun LedgerTemplate.toEntity(): LedgerTemplateEntity {
    return LedgerTemplateEntity(
        id = id,
        title = title,
        type = type,
        amountInCents = amountInCents,
        account = account.ifBlank { defaultLedgerAccount() },
        category = category,
        recurrence = recurrence,
        nextDueAt = nextDueAt,
        note = note,
        createdAt = createdAt
    )
}

internal fun PendingLedgerImportEntity.toModel(): PendingLedgerImport {
    return PendingLedgerImport(
        id = id,
        signature = signature,
        type = type,
        amountInCents = amountInCents,
        account = account.ifBlank { defaultLedgerAccount() },
        category = category,
        note = note,
        receiptText = receiptText,
        happenedAt = happenedAt,
        sourceLabel = sourceLabel,
        createdAt = createdAt
    )
}

internal fun PendingLedgerImport.toEntity(): PendingLedgerImportEntity {
    return PendingLedgerImportEntity(
        id = id,
        signature = signature,
        type = type,
        amountInCents = amountInCents,
        account = account.ifBlank { defaultLedgerAccount() },
        category = category,
        note = note,
        receiptText = receiptText,
        happenedAt = happenedAt,
        sourceLabel = sourceLabel,
        createdAt = createdAt
    )
}

internal fun LedgerMetadataEntity.toBudgetConfig(): LedgerBudgetConfig {
    return LedgerBudgetConfig(
        monthlyBudgetInCents = monthlyBudgetInCents,
        categoryBudgets = LedgerJsonCodec.decodeCategoryBudgets(categoryBudgetsJson)
    )
}

internal fun LedgerMetadataEntity.toProfileConfig(): LedgerProfileConfig {
    return LedgerProfileConfig(
        customAccounts = LedgerJsonCodec.decodeStringList(customAccountsJson),
        customExpenseCategories = LedgerJsonCodec.decodeStringList(customExpenseCategoriesJson),
        customIncomeCategories = LedgerJsonCodec.decodeStringList(customIncomeCategoriesJson)
    )
}

internal fun LedgerMetadataEntity.toAutomationTrace(): LedgerAutomationTrace {
    return LedgerAutomationTrace(
        sourceLabel = automationSourceLabel,
        summary = automationSummary,
        rawText = automationRawText,
        happenedAt = automationHappenedAt
    )
}

internal fun LedgerMetadataEntity.toSecurityConfig(): LedgerSecurityConfig {
    return LedgerSecurityConfig(
        pinHash = pinHash,
        pinSalt = pinSalt
    )
}

internal fun LedgerMetadataEntity.withBudgetConfig(config: LedgerBudgetConfig): LedgerMetadataEntity {
    return copy(
        monthlyBudgetInCents = config.monthlyBudgetInCents,
        categoryBudgetsJson = LedgerJsonCodec.encodeCategoryBudgets(config.categoryBudgets)
    )
}

internal fun LedgerMetadataEntity.withProfileConfig(config: LedgerProfileConfig): LedgerMetadataEntity {
    return copy(
        customAccountsJson = LedgerJsonCodec.encodeStringList(config.customAccounts),
        customExpenseCategoriesJson = LedgerJsonCodec.encodeStringList(config.customExpenseCategories),
        customIncomeCategoriesJson = LedgerJsonCodec.encodeStringList(config.customIncomeCategories)
    )
}

internal fun LedgerMetadataEntity.withAutomationTrace(trace: LedgerAutomationTrace): LedgerMetadataEntity {
    return copy(
        automationSourceLabel = trace.sourceLabel,
        automationSummary = trace.summary,
        automationRawText = trace.rawText,
        automationHappenedAt = trace.happenedAt
    )
}

internal fun LedgerMetadataEntity.withSecurityConfig(config: LedgerSecurityConfig): LedgerMetadataEntity {
    return copy(
        pinHash = config.pinHash,
        pinSalt = config.pinSalt
    )
}
