package com.android.jizhangmiao.ledger.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.android.jizhangmiao.ledger.AutoImportedEntry
import com.android.jizhangmiao.ledger.data.room.AutoImportHistoryEntity
import com.android.jizhangmiao.ledger.data.room.DefaultLedgerMetadataEntity
import com.android.jizhangmiao.ledger.data.room.LedgerDatabase
import com.android.jizhangmiao.ledger.data.room.LedgerMetadataEntity
import com.android.jizhangmiao.ledger.data.room.toAutomationTrace
import com.android.jizhangmiao.ledger.data.room.toAutomationRules
import com.android.jizhangmiao.ledger.data.room.toBudgetConfig
import com.android.jizhangmiao.ledger.data.room.toEntity
import com.android.jizhangmiao.ledger.data.room.toModel
import com.android.jizhangmiao.ledger.data.room.toProfileConfig
import com.android.jizhangmiao.ledger.data.room.toSecurityConfig
import com.android.jizhangmiao.ledger.data.room.withAutomationTrace
import com.android.jizhangmiao.ledger.data.room.withAutomationRules
import com.android.jizhangmiao.ledger.data.room.withBudgetConfig
import com.android.jizhangmiao.ledger.data.room.withProfileConfig
import com.android.jizhangmiao.ledger.data.room.withSecurityConfig
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class LedgerStore internal constructor(
    private val database: LedgerDatabase,
    private val legacyPreferences: SharedPreferences? = null
) {
    private data class Snapshot(
        val entries: List<LedgerEntry>,
        val templates: List<LedgerTemplate>,
        val budgetConfig: LedgerBudgetConfig,
        val profileConfig: LedgerProfileConfig,
        val automationRules: List<LedgerAutomationRule>,
        val automationTrace: LedgerAutomationTrace,
        val pendingImports: List<PendingLedgerImport>,
        val securityConfig: LedgerSecurityConfig
    )

    private val dao = database.ledgerDao()
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialSnapshot = runBlocking(Dispatchers.IO) {
        initializeStore()
    }

    private val _entries = MutableStateFlow(initialSnapshot.entries)
    private val _templates = MutableStateFlow(initialSnapshot.templates)
    private val _budgetConfig = MutableStateFlow(initialSnapshot.budgetConfig)
    private val _profileConfig = MutableStateFlow(initialSnapshot.profileConfig)
    private val _automationRules = MutableStateFlow(initialSnapshot.automationRules)
    private val _automationTrace = MutableStateFlow(initialSnapshot.automationTrace)
    private val _pendingImports = MutableStateFlow(initialSnapshot.pendingImports)
    private val _securityConfig = MutableStateFlow(initialSnapshot.securityConfig)

    val entries: StateFlow<List<LedgerEntry>> = _entries.asStateFlow()
    val templates: StateFlow<List<LedgerTemplate>> = _templates.asStateFlow()
    val budgetConfig: StateFlow<LedgerBudgetConfig> = _budgetConfig.asStateFlow()
    val profileConfig: StateFlow<LedgerProfileConfig> = _profileConfig.asStateFlow()
    val automationRules: StateFlow<List<LedgerAutomationRule>> = _automationRules.asStateFlow()
    val automationTrace: StateFlow<LedgerAutomationTrace> = _automationTrace.asStateFlow()
    val pendingImports: StateFlow<List<PendingLedgerImport>> = _pendingImports.asStateFlow()
    val securityConfig: StateFlow<LedgerSecurityConfig> = _securityConfig.asStateFlow()

    init {
        observerScope.launch {
            dao.observeEntries().collect { entities ->
                _entries.value = normalizeEntries(entities.map { entity -> entity.toModel() })
            }
        }
        observerScope.launch {
            dao.observeTemplates().collect { entities ->
                _templates.value = normalizeTemplates(entities.map { entity -> entity.toModel() })
            }
        }
        observerScope.launch {
            dao.observePendingImports().collect { entities ->
                _pendingImports.value = normalizePendingImports(entities.map { entity -> entity.toModel() })
            }
        }
        observerScope.launch {
            dao.observeMetadata().collect { metadata ->
                val resolved = metadata ?: DefaultLedgerMetadataEntity
                _budgetConfig.value = resolved.toBudgetConfig()
                _profileConfig.value = resolved.toProfileConfig()
                _automationRules.value = resolved.toAutomationRules()
                _automationTrace.value = resolved.toAutomationTrace()
                _securityConfig.value = resolved.toSecurityConfig()
            }
        }
    }

    suspend fun addEntry(entry: LedgerEntry) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                dao.upsertEntry(
                    entry.copy(
                        id = entry.id.ifBlank { UUID.randomUUID().toString() },
                        account = entry.account.ifBlank { defaultLedgerAccount() },
                        updatedAt = System.currentTimeMillis()
                    ).toEntity()
                )
            }
        }
    }

    suspend fun updateEntry(entry: LedgerEntry) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                dao.upsertEntry(
                    entry.copy(
                        account = entry.account.ifBlank { defaultLedgerAccount() },
                        updatedAt = System.currentTimeMillis()
                    ).toEntity()
                )
            }
        }
    }

    suspend fun deleteEntry(entry: LedgerEntry) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                dao.deleteEntry(entry.id)
            }
        }
    }

    internal suspend fun importAutoEntry(entry: AutoImportedEntry): Boolean {
        return withContext(Dispatchers.IO) {
            withWriteTransaction {
                val rules = metadataOrDefault().toAutomationRules()
                val resolvedEntry = entry.applyAutomationRules(rules)
                val history = dao.getAutoImportHistory()
                val pendingImports = dao.getPendingImports().map { entity -> entity.toModel() }
                if (
                    history.any { savedHistory -> savedHistory.signature == entry.signature } ||
                    pendingImports.any { pendingImport -> pendingImport.signature == entry.signature }
                ) {
                    return@withWriteTransaction false
                }

                val hasLikelyPendingDuplicate = pendingImports.any { pendingImport ->
                    pendingImport.type == resolvedEntry.type &&
                        pendingImport.amountInCents == resolvedEntry.amountInCents &&
                        pendingImport.account == resolvedEntry.account &&
                        pendingImport.happenedAt in (resolvedEntry.happenedAt - AUTO_IMPORT_WINDOW_MILLIS)..(resolvedEntry.happenedAt + AUTO_IMPORT_WINDOW_MILLIS)
                }
                val hasLikelyDuplicate = dao.getEntries()
                    .asSequence()
                    .map { entity -> entity.toModel() }
                    .any { savedEntry ->
                        savedEntry.type == resolvedEntry.type &&
                            savedEntry.amountInCents == resolvedEntry.amountInCents &&
                            savedEntry.account == resolvedEntry.account &&
                            savedEntry.happenedAt in (resolvedEntry.happenedAt - AUTO_IMPORT_WINDOW_MILLIS)..(resolvedEntry.happenedAt + AUTO_IMPORT_WINDOW_MILLIS)
                    }

                dao.upsertAutoImportHistory(
                    AutoImportHistoryEntity(
                        signature = entry.signature,
                        createdAt = System.currentTimeMillis()
                    )
                )
                val overflowCount = history.size + 1 - MAX_AUTO_IMPORT_HISTORY
                if (overflowCount > 0) {
                    history.take(overflowCount).forEach { savedHistory ->
                        dao.deleteAutoImportSignature(savedHistory.signature)
                    }
                }

                if (hasLikelyPendingDuplicate || hasLikelyDuplicate) {
                    return@withWriteTransaction false
                }

                replacePendingImports(
                    normalizePendingImports(
                        pendingImports + PendingLedgerImport(
                            signature = resolvedEntry.signature,
                            type = resolvedEntry.type,
                            amountInCents = resolvedEntry.amountInCents,
                            account = resolvedEntry.account,
                            category = resolvedEntry.category,
                            note = resolvedEntry.note,
                            receiptText = resolvedEntry.receiptText,
                            happenedAt = resolvedEntry.happenedAt,
                            sourceLabel = resolvedEntry.note
                        )
                    )
                )
                true
            }
        }
    }

    suspend fun approvePendingImport(pendingImport: PendingLedgerImport) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                dao.upsertEntry(
                    LedgerEntry(
                        type = pendingImport.type,
                        amountInCents = pendingImport.amountInCents,
                        account = pendingImport.account.ifBlank { defaultLedgerAccount() },
                        category = pendingImport.category,
                        note = pendingImport.note,
                        receiptText = pendingImport.receiptText,
                        happenedAt = pendingImport.happenedAt,
                        updatedAt = System.currentTimeMillis()
                    ).toEntity()
                )
                dao.deletePendingImport(pendingImport.id)
            }
        }
    }

    suspend fun ignorePendingImport(pendingImport: PendingLedgerImport) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                dao.deletePendingImport(pendingImport.id)
            }
        }
    }

    suspend fun upsertTemplate(template: LedgerTemplate) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                dao.upsertTemplate(
                    template.copy(
                        id = template.id.ifBlank { UUID.randomUUID().toString() },
                        account = template.account.ifBlank { defaultLedgerAccount() },
                        nextDueAt = if (template.recurrence == LedgerTemplateRecurrence.NONE) {
                            null
                        } else {
                            template.nextDueAt
                        }
                    ).toEntity()
                )
            }
        }
    }

    suspend fun deleteTemplate(template: LedgerTemplate) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                dao.deleteTemplate(template.id)
            }
        }
    }

    suspend fun updateMonthlyBudget(amountInCents: Long?) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val metadata = metadataOrDefault()
                val updatedConfig = metadata.toBudgetConfig().copy(
                    monthlyBudgetInCents = amountInCents
                )
                dao.upsertMetadata(metadata.withBudgetConfig(updatedConfig))
            }
        }
    }

    suspend fun updateCategoryBudget(category: String, amountInCents: Long?) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val metadata = metadataOrDefault()
                val updatedBudgets = metadata.toBudgetConfig().categoryBudgets.toMutableMap()
                if (amountInCents == null) {
                    updatedBudgets.remove(category)
                } else {
                    updatedBudgets[category] = amountInCents
                }
                dao.upsertMetadata(
                    metadata.withBudgetConfig(
                        metadata.toBudgetConfig().copy(
                            categoryBudgets = updatedBudgets.toSortedMap()
                        )
                    )
                )
            }
        }
    }

    suspend fun addAccount(account: String) {
        val normalizedAccount = account.trim().take(12)
        if (normalizedAccount.isBlank()) {
            return
        }

        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val metadata = metadataOrDefault()
                val profileConfig = metadata.toProfileConfig()
                dao.upsertMetadata(
                    metadata.withProfileConfig(
                        profileConfig.copy(
                            customAccounts = (profileConfig.customAccounts + normalizedAccount)
                                .distinct()
                                .sorted()
                        )
                    )
                )
            }
        }
    }

    suspend fun addCategory(
        type: LedgerEntryType,
        category: String
    ) {
        val normalizedCategory = category.trim().take(12)
        if (normalizedCategory.isBlank()) {
            return
        }

        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val metadata = metadataOrDefault()
                val profileConfig = metadata.toProfileConfig()
                val updatedProfile = when (type) {
                    LedgerEntryType.EXPENSE -> profileConfig.copy(
                        customExpenseCategories = (profileConfig.customExpenseCategories + normalizedCategory)
                            .distinct()
                            .sorted()
                    )

                    LedgerEntryType.INCOME -> profileConfig.copy(
                        customIncomeCategories = (profileConfig.customIncomeCategories + normalizedCategory)
                            .distinct()
                            .sorted()
                    )
                }
                dao.upsertMetadata(metadata.withProfileConfig(updatedProfile))
            }
        }
    }

    suspend fun renameAccount(
        oldAccount: String,
        newAccount: String
    ) {
        val oldValue = oldAccount.trim()
        val newValue = newAccount.trim().take(12)
        if (oldValue.isBlank() || newValue.isBlank() || oldValue == newValue) {
            return
        }

        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val entries = dao.getEntries().map { entity -> entity.toModel() }
                val templates = dao.getTemplates().map { entity -> entity.toModel() }
                val pendingImports = dao.getPendingImports().map { entity -> entity.toModel() }
                val metadata = metadataOrDefault()
                val profileConfig = metadata.toProfileConfig()

                replaceEntries(
                    normalizeEntries(
                        entries.map { entry ->
                            if (entry.account == oldValue) entry.copy(account = newValue) else entry
                        }
                    )
                )
                replaceTemplates(
                    normalizeTemplates(
                        templates.map { template ->
                            if (template.account == oldValue) template.copy(account = newValue) else template
                        }
                    )
                )
                replacePendingImports(
                    normalizePendingImports(
                        pendingImports.map { pendingImport ->
                            if (pendingImport.account == oldValue) pendingImport.copy(account = newValue) else pendingImport
                        }
                    )
                )
                dao.upsertMetadata(
                    metadata.withProfileConfig(
                        profileConfig.copy(
                            customAccounts = (profileConfig.customAccounts
                                .map { account -> if (account == oldValue) newValue else account } + newValue)
                                .distinct()
                                .sorted()
                        )
                    )
                )
            }
        }
    }

    suspend fun renameCategory(
        type: LedgerEntryType,
        oldCategory: String,
        newCategory: String
    ) {
        val oldValue = oldCategory.trim()
        val newValue = newCategory.trim().take(12)
        if (oldValue.isBlank() || newValue.isBlank() || oldValue == newValue) {
            return
        }

        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val entries = dao.getEntries().map { entity -> entity.toModel() }
                val templates = dao.getTemplates().map { entity -> entity.toModel() }
                val pendingImports = dao.getPendingImports().map { entity -> entity.toModel() }
                val metadata = metadataOrDefault()
                val profileConfig = metadata.toProfileConfig()
                val budgetConfig = metadata.toBudgetConfig()

                replaceEntries(
                    normalizeEntries(
                        entries.map { entry ->
                            if (entry.type == type && entry.category == oldValue) {
                                entry.copy(category = newValue)
                            } else {
                                entry
                            }
                        }
                    )
                )
                replaceTemplates(
                    normalizeTemplates(
                        templates.map { template ->
                            if (template.type == type && template.category == oldValue) {
                                template.copy(category = newValue)
                            } else {
                                template
                            }
                        }
                    )
                )
                replacePendingImports(
                    normalizePendingImports(
                        pendingImports.map { pendingImport ->
                            if (pendingImport.type == type && pendingImport.category == oldValue) {
                                pendingImport.copy(category = newValue)
                            } else {
                                pendingImport
                            }
                        }
                    )
                )

                val updatedBudgetConfig = budgetConfig.copy(
                    categoryBudgets = budgetConfig.categoryBudgets
                        .mapKeys { (category, _) -> if (category == oldValue) newValue else category }
                        .toSortedMap()
                )
                val updatedProfile = when (type) {
                    LedgerEntryType.EXPENSE -> profileConfig.copy(
                        customExpenseCategories = (profileConfig.customExpenseCategories
                            .map { category -> if (category == oldValue) newValue else category } + newValue)
                            .distinct()
                            .sorted()
                    )

                    LedgerEntryType.INCOME -> profileConfig.copy(
                        customIncomeCategories = (profileConfig.customIncomeCategories
                            .map { category -> if (category == oldValue) newValue else category } + newValue)
                            .distinct()
                            .sorted()
                    )
                }

                dao.upsertMetadata(
                    metadata
                        .withBudgetConfig(updatedBudgetConfig)
                        .withProfileConfig(updatedProfile)
                )
            }
        }
    }

    suspend fun updateSecurityConfig(config: LedgerSecurityConfig) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val metadata = metadataOrDefault()
                dao.upsertMetadata(metadata.withSecurityConfig(config))
            }
        }
    }

    suspend fun recordAutomationTrace(trace: LedgerAutomationTrace) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val metadata = metadataOrDefault()
                dao.upsertMetadata(metadata.withAutomationTrace(trace))
            }
        }
    }

    suspend fun addAutomationRule(rule: LedgerAutomationRule) {
        val normalizedKeyword = rule.keyword.trim().take(20)
        val normalizedCategory = rule.category.trim().take(12)
        val normalizedAccount = rule.account.trim().take(12)
        if (normalizedKeyword.isBlank() || normalizedCategory.isBlank()) {
            return
        }

        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val metadata = metadataOrDefault()
                val existingRules = metadata.toAutomationRules()
                val updatedRules = normalizeAutomationRules(
                    existingRules.filterNot { existingRule ->
                        existingRule.type == rule.type &&
                            existingRule.keyword.trim().equals(normalizedKeyword, ignoreCase = true)
                    } + rule.copy(
                        keyword = normalizedKeyword,
                        category = normalizedCategory,
                        account = normalizedAccount
                    )
                )
                dao.upsertMetadata(metadata.withAutomationRules(updatedRules))
            }
        }
    }

    suspend fun deleteAutomationRule(ruleId: String) {
        withContext(Dispatchers.IO) {
            withWriteTransaction {
                val metadata = metadataOrDefault()
                val updatedRules = metadata.toAutomationRules()
                    .filterNot { rule -> rule.id == ruleId }
                dao.upsertMetadata(metadata.withAutomationRules(updatedRules))
            }
        }
    }

    fun exportBackupJson(): String {
        return LedgerJsonCodec.exportBackupJson(
            entries = _entries.value,
            templates = _templates.value,
            budgetConfig = _budgetConfig.value,
            profileConfig = _profileConfig.value,
            automationRules = _automationRules.value
        )
    }

    fun exportCsv(): String {
        val header = listOf(
            "id",
            "type",
            "amount",
            "account",
            "category",
            "note",
            "happenedAt",
            "updatedAt"
        ).joinToString(",")
        val rows = _entries.value.map { entry ->
            listOf(
                entry.id,
                entry.type.name,
                entry.amountInCents.toAmountInput(),
                entry.account,
                entry.category,
                entry.note,
                entry.happenedAt.toString(),
                entry.updatedAt.toString()
            ).joinToString(",") { value -> csvEscape(value) }
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    suspend fun syncRecurringTemplates(now: Long = System.currentTimeMillis()): Int {
        return withContext(Dispatchers.IO) {
            withWriteTransaction {
                val entries = dao.getEntries().map { entity -> entity.toModel() }
                val templates = dao.getTemplates().map { entity -> entity.toModel() }
                val result = syncRecurringTemplates(
                    entries = entries,
                    templates = templates,
                    now = now
                )
                if (result.entries != entries || result.templates != templates) {
                    replaceEntries(normalizeEntries(result.entries))
                    replaceTemplates(normalizeTemplates(result.templates))
                }
                result.generatedCount
            }
        }
    }

    fun previewBackupJson(json: String): BackupPreview? {
        return LedgerJsonCodec.previewBackupJson(json)
    }

    suspend fun importBackupJson(
        json: String,
        mode: LedgerImportMode = LedgerImportMode.REPLACE
    ): Boolean {
        val backupPayload = LedgerJsonCodec.decodeBackupJson(json) ?: return false
        return withContext(Dispatchers.IO) {
            withWriteTransaction {
                val entries = dao.getEntries().map { entity -> entity.toModel() }
                val templates = dao.getTemplates().map { entity -> entity.toModel() }
                val metadata = metadataOrDefault()
                val budgetConfig = metadata.toBudgetConfig()
                val profileConfig = metadata.toProfileConfig()
                val automationRules = metadata.toAutomationRules()

                val updatedEntries = when (mode) {
                    LedgerImportMode.REPLACE -> backupPayload.entries
                    LedgerImportMode.MERGE -> mergeEntries(entries, backupPayload.entries)
                }
                val updatedTemplates = when (mode) {
                    LedgerImportMode.REPLACE -> backupPayload.templates
                    LedgerImportMode.MERGE -> mergeTemplates(templates, backupPayload.templates)
                }
                val updatedBudgetConfig = when (mode) {
                    LedgerImportMode.REPLACE -> backupPayload.budgetConfig
                    LedgerImportMode.MERGE -> budgetConfig.copy(
                        monthlyBudgetInCents = backupPayload.budgetConfig.monthlyBudgetInCents
                            ?: budgetConfig.monthlyBudgetInCents,
                        categoryBudgets = (budgetConfig.categoryBudgets +
                            backupPayload.budgetConfig.categoryBudgets).toSortedMap()
                    )
                }
                val updatedProfileConfig = when (mode) {
                    LedgerImportMode.REPLACE -> backupPayload.profileConfig
                    LedgerImportMode.MERGE -> LedgerProfileConfig(
                        customAccounts = (profileConfig.customAccounts +
                            backupPayload.profileConfig.customAccounts).distinct().sorted(),
                        customExpenseCategories = (profileConfig.customExpenseCategories +
                            backupPayload.profileConfig.customExpenseCategories).distinct().sorted(),
                        customIncomeCategories = (profileConfig.customIncomeCategories +
                            backupPayload.profileConfig.customIncomeCategories).distinct().sorted()
                    )
                }
                val updatedAutomationRules = when (mode) {
                    LedgerImportMode.REPLACE -> normalizeAutomationRules(backupPayload.automationRules)
                    LedgerImportMode.MERGE -> mergeAutomationRules(automationRules, backupPayload.automationRules)
                }

                replaceEntries(updatedEntries)
                replaceTemplates(updatedTemplates)
                dao.upsertMetadata(
                    metadata
                        .withBudgetConfig(updatedBudgetConfig)
                        .withProfileConfig(updatedProfileConfig)
                        .withAutomationRules(updatedAutomationRules)
                )
                true
            }
        }
    }

    suspend fun importStatementEntries(
        entries: List<LedgerEntry>
    ): LedgerStatementImportResult {
        val normalizedImportedEntries = normalizeEntries(
            entries.map { entry ->
                entry.copy(
                    id = entry.id.ifBlank { UUID.randomUUID().toString() },
                    account = entry.account.ifBlank { defaultLedgerAccount() },
                    category = entry.category.ifBlank { "其他" }
                )
            }
        )
        if (normalizedImportedEntries.isEmpty()) {
            return LedgerStatementImportResult(
                totalCount = 0,
                importedCount = 0,
                skippedCount = 0
            )
        }

        return withContext(Dispatchers.IO) {
            withWriteTransaction {
                val existingEntries = dao.getEntries().map { entity -> entity.toModel() }
                val metadata = metadataOrDefault()
                val profileConfig = metadata.toProfileConfig()
                val automationRules = metadata.toAutomationRules()
                val ruledEntries = normalizeEntries(
                    normalizedImportedEntries.map { entry ->
                        entry.applyAutomationRules(automationRules)
                    }
                )
                val mergedEntries = mergeEntries(existingEntries, ruledEntries)
                val importedCount = (mergedEntries.size - existingEntries.size).coerceAtLeast(0)
                val updatedProfileConfig = profileConfig.copy(
                    customAccounts = (profileConfig.customAccounts +
                        ruledEntries.map { entry -> entry.account })
                        .filter { account -> account.isNotBlank() }
                        .distinct()
                        .sorted(),
                    customExpenseCategories = (profileConfig.customExpenseCategories +
                        ruledEntries.filter { entry -> entry.type == LedgerEntryType.EXPENSE }
                            .map { entry -> entry.category })
                        .filter { category -> category.isNotBlank() }
                        .distinct()
                        .sorted(),
                    customIncomeCategories = (profileConfig.customIncomeCategories +
                        ruledEntries.filter { entry -> entry.type == LedgerEntryType.INCOME }
                            .map { entry -> entry.category })
                        .filter { category -> category.isNotBlank() }
                        .distinct()
                        .sorted()
                )

                if (mergedEntries != existingEntries) {
                    replaceEntries(mergedEntries)
                }
                if (updatedProfileConfig != profileConfig) {
                    dao.upsertMetadata(metadata.withProfileConfig(updatedProfileConfig))
                }

                LedgerStatementImportResult(
                    totalCount = ruledEntries.size,
                    importedCount = importedCount,
                    skippedCount = ruledEntries.size - importedCount
                )
            }
        }
    }

    private suspend fun initializeStore(): Snapshot {
        if (legacyPreferences != null) {
            migrateLegacyPreferencesIfNeeded(legacyPreferences)
        } else {
            ensureMetadataRow()
        }
        return readSnapshot()
    }

    private suspend fun migrateLegacyPreferencesIfNeeded(preferences: SharedPreferences) {
        database.withTransaction {
            val metadata = dao.getMetadata() ?: DefaultLedgerMetadataEntity
            if (metadata.legacyMigrationCompletedAt > 0L) {
                if (dao.getMetadata() == null) {
                    dao.upsertMetadata(metadata)
                }
                return@withTransaction
            }

            val legacySnapshot = LegacyLedgerPreferences(preferences).readSnapshot()
            if (legacySnapshot.hasStoredPayload) {
                replaceEntries(legacySnapshot.entries)
                replaceTemplates(legacySnapshot.templates)
                replacePendingImports(legacySnapshot.pendingImports)
                replaceAutoImportHistory(legacySnapshot.autoImportHistory)
                dao.upsertMetadata(
                    metadata
                        .withBudgetConfig(legacySnapshot.budgetConfig)
                        .withProfileConfig(legacySnapshot.profileConfig)
                        .withAutomationTrace(legacySnapshot.automationTrace)
                        .withSecurityConfig(legacySnapshot.securityConfig)
                        .copy(legacyMigrationCompletedAt = System.currentTimeMillis())
                )
            } else {
                dao.upsertMetadata(
                    metadata.copy(legacyMigrationCompletedAt = System.currentTimeMillis())
                )
            }
        }
    }

    private suspend fun ensureMetadataRow() {
        if (dao.getMetadata() == null) {
            dao.upsertMetadata(DefaultLedgerMetadataEntity)
        }
    }

    private suspend fun readSnapshot(): Snapshot {
        val metadata = metadataOrDefault()
        return Snapshot(
            entries = normalizeEntries(dao.getEntries().map { entity -> entity.toModel() }),
            templates = normalizeTemplates(dao.getTemplates().map { entity -> entity.toModel() }),
            budgetConfig = metadata.toBudgetConfig(),
            profileConfig = metadata.toProfileConfig(),
            automationRules = metadata.toAutomationRules(),
            automationTrace = metadata.toAutomationTrace(),
            pendingImports = normalizePendingImports(dao.getPendingImports().map { entity -> entity.toModel() }),
            securityConfig = metadata.toSecurityConfig()
        )
    }

    private suspend fun <T> withWriteTransaction(
        block: suspend () -> T
    ): T {
        val result = database.withTransaction {
            block()
        }
        applySnapshot(readSnapshot())
        return result
    }

    private fun applySnapshot(snapshot: Snapshot) {
        _entries.value = snapshot.entries
        _templates.value = snapshot.templates
        _budgetConfig.value = snapshot.budgetConfig
        _profileConfig.value = snapshot.profileConfig
        _automationRules.value = snapshot.automationRules
        _automationTrace.value = snapshot.automationTrace
        _pendingImports.value = snapshot.pendingImports
        _securityConfig.value = snapshot.securityConfig
    }

    private suspend fun metadataOrDefault(): LedgerMetadataEntity {
        return dao.getMetadata() ?: DefaultLedgerMetadataEntity
    }

    private suspend fun replaceEntries(entries: List<LedgerEntry>) {
        dao.deleteAllEntries()
        val normalizedEntries = normalizeEntries(entries)
        if (normalizedEntries.isNotEmpty()) {
            dao.upsertEntries(normalizedEntries.map { entry -> entry.toEntity() })
        }
    }

    private suspend fun replaceTemplates(templates: List<LedgerTemplate>) {
        dao.deleteAllTemplates()
        val normalizedTemplates = normalizeTemplates(templates)
        if (normalizedTemplates.isNotEmpty()) {
            dao.upsertTemplates(normalizedTemplates.map { template -> template.toEntity() })
        }
    }

    private suspend fun replacePendingImports(pendingImports: List<PendingLedgerImport>) {
        dao.deleteAllPendingImports()
        val normalizedPendingImports = normalizePendingImports(pendingImports)
        if (normalizedPendingImports.isNotEmpty()) {
            dao.upsertPendingImports(normalizedPendingImports.map { pendingImport -> pendingImport.toEntity() })
        }
    }

    private suspend fun replaceAutoImportHistory(signatures: List<String>) {
        val normalizedSignatures = signatures
            .map { signature -> signature.trim() }
            .filter { signature -> signature.isNotBlank() }
            .distinct()
            .takeLast(MAX_AUTO_IMPORT_HISTORY)
        dao.deleteAllAutoImportHistory()
        if (normalizedSignatures.isNotEmpty()) {
            val baseTime = System.currentTimeMillis() - normalizedSignatures.size
            dao.upsertAutoImportHistory(
                normalizedSignatures.mapIndexed { index, signature ->
                    AutoImportHistoryEntity(
                        signature = signature,
                        createdAt = baseTime + index
                    )
                }
            )
        }
    }

    private fun mergeEntries(
        existingEntries: List<LedgerEntry>,
        importedEntries: List<LedgerEntry>
    ): List<LedgerEntry> {
        val existingIds = existingEntries.map { entry -> entry.id }.toSet()
        val merged = existingEntries + importedEntries.filterNot { entry ->
            entry.id in existingIds || existingEntries.any { savedEntry ->
                savedEntry.type == entry.type &&
                    savedEntry.amountInCents == entry.amountInCents &&
                    savedEntry.account == entry.account &&
                    savedEntry.category == entry.category &&
                    savedEntry.happenedAt == entry.happenedAt
            }
        }
        return normalizeEntries(merged)
    }

    private fun mergeTemplates(
        existingTemplates: List<LedgerTemplate>,
        importedTemplates: List<LedgerTemplate>
    ): List<LedgerTemplate> {
        val existingIds = existingTemplates.map { template -> template.id }.toSet()
        return normalizeTemplates(existingTemplates + importedTemplates.filterNot { template ->
            template.id in existingIds
        })
    }

    private fun mergeAutomationRules(
        existingRules: List<LedgerAutomationRule>,
        importedRules: List<LedgerAutomationRule>
    ): List<LedgerAutomationRule> {
        val existingIds = existingRules.map { rule -> rule.id }.toSet()
        val merged = existingRules + importedRules.filterNot { importedRule ->
            importedRule.id in existingIds || existingRules.any { existingRule ->
                existingRule.type == importedRule.type &&
                    existingRule.keyword.trim().equals(importedRule.keyword.trim(), ignoreCase = true) &&
                    existingRule.category == importedRule.category &&
                    existingRule.account == importedRule.account
            }
        }
        return normalizeAutomationRules(merged)
    }

    private fun normalizeAutomationRules(rules: List<LedgerAutomationRule>): List<LedgerAutomationRule> {
        return rules
            .filter { rule -> rule.keyword.trim().isNotBlank() && rule.category.trim().isNotBlank() }
            .distinctBy { rule ->
                "${rule.type.name}|${rule.keyword.trim().lowercase()}|${rule.category.trim()}|${rule.account.trim()}"
            }
            .sortedWith(
                compareByDescending<LedgerAutomationRule> { rule -> rule.keyword.trim().length }
                    .thenByDescending { rule -> rule.createdAt }
            )
    }

    private fun AutoImportedEntry.applyAutomationRules(
        rules: List<LedgerAutomationRule>
    ): AutoImportedEntry {
        val match = applyAutomationRule(
            rules = rules,
            type = type,
            currentCategory = category,
            currentAccount = account,
            matchText = listOf(note, receiptText, category, account).joinToString("\n")
        ) ?: return this

        return copy(
            category = match.category,
            account = match.account
        )
    }

    private fun LedgerEntry.applyAutomationRules(
        rules: List<LedgerAutomationRule>
    ): LedgerEntry {
        val match = applyAutomationRule(
            rules = rules,
            type = type,
            currentCategory = category,
            currentAccount = account,
            matchText = listOf(note, category, account).joinToString("\n")
        ) ?: return this

        return copy(
            category = match.category,
            account = match.account
        )
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { char -> char == ',' || char == '"' || char == '\n' || char == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    internal fun close() {
        observerScope.cancel()
    }

    companion object {
        private const val MAX_AUTO_IMPORT_HISTORY = 80
        private const val AUTO_IMPORT_WINDOW_MILLIS = 2 * 60 * 1000L

        @Volatile
        private var instance: LedgerStore? = null

        fun getInstance(context: Context): LedgerStore {
            return instance ?: synchronized(this) {
                instance ?: LedgerStore(
                    database = LedgerDatabase.getInstance(context.applicationContext),
                    legacyPreferences = context.applicationContext.getSharedPreferences(
                        LegacyLedgerPreferences.PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                ).also { createdStore ->
                    instance = createdStore
                }
            }
        }
    }
}
