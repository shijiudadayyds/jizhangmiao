package com.android.jizhangmiao.ledger.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LedgerStore private constructor(
    private val preferences: SharedPreferences
) {
    private val writeLock = Any()

    private val _entries = MutableStateFlow(loadEntries())
    private val _templates = MutableStateFlow(loadTemplates())
    private val _budgetConfig = MutableStateFlow(loadBudgetConfig())

    val entries: StateFlow<List<LedgerEntry>> = _entries.asStateFlow()
    val templates: StateFlow<List<LedgerTemplate>> = _templates.asStateFlow()
    val budgetConfig: StateFlow<LedgerBudgetConfig> = _budgetConfig.asStateFlow()

    suspend fun addEntry(entry: LedgerEntry) {
        updateEntries { current ->
            current + entry.copy(
                id = entry.id.ifBlank { UUID.randomUUID().toString() },
                account = entry.account.ifBlank { defaultLedgerAccount() },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun updateEntry(entry: LedgerEntry) {
        updateEntries { current ->
            current.map { savedEntry ->
                if (savedEntry.id == entry.id) {
                    entry.copy(
                        account = entry.account.ifBlank { defaultLedgerAccount() },
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    savedEntry
                }
            }
        }
    }

    suspend fun deleteEntry(entry: LedgerEntry) {
        updateEntries { current ->
            current.filterNot { savedEntry -> savedEntry.id == entry.id }
        }
    }

    suspend fun upsertTemplate(template: LedgerTemplate) {
        updateTemplates { current ->
            current.filterNot { savedTemplate -> savedTemplate.id == template.id } +
                template.copy(
                    id = template.id.ifBlank { UUID.randomUUID().toString() },
                    account = template.account.ifBlank { defaultLedgerAccount() },
                    nextDueAt = if (template.recurrence == LedgerTemplateRecurrence.NONE) {
                        null
                    } else {
                        template.nextDueAt
                    }
                )
        }
    }

    suspend fun deleteTemplate(template: LedgerTemplate) {
        updateTemplates { current ->
            current.filterNot { savedTemplate -> savedTemplate.id == template.id }
        }
    }

    suspend fun updateMonthlyBudget(amountInCents: Long?) {
        updateBudgetConfig { current ->
            current.copy(monthlyBudgetInCents = amountInCents)
        }
    }

    suspend fun updateCategoryBudget(category: String, amountInCents: Long?) {
        updateBudgetConfig { current ->
            val updatedBudgets = current.categoryBudgets.toMutableMap()
            if (amountInCents == null) {
                updatedBudgets.remove(category)
            } else {
                updatedBudgets[category] = amountInCents
            }
            current.copy(categoryBudgets = updatedBudgets.toSortedMap())
        }
    }

    fun exportBackupJson(): String {
        return JSONObject().apply {
            put("version", 2)
            put("exportedAt", System.currentTimeMillis())
            put("entries", encodeEntries(_entries.value))
            put("templates", encodeTemplates(_templates.value))
            put("budgetConfig", encodeBudgetConfig(_budgetConfig.value))
        }.toString(2)
    }

    suspend fun syncRecurringTemplates(now: Long = System.currentTimeMillis()): Int {
        return withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                val result = syncRecurringTemplates(
                    entries = _entries.value,
                    templates = _templates.value,
                    now = now
                )
                val entriesChanged = result.entries != _entries.value
                val templatesChanged = result.templates != _templates.value
                if (entriesChanged || templatesChanged) {
                    val updatedEntries = normalizeEntries(result.entries)
                    val updatedTemplates = normalizeTemplates(result.templates)
                    persistEntries(updatedEntries)
                    persistTemplates(updatedTemplates)
                    _entries.value = updatedEntries
                    _templates.value = updatedTemplates
                }
                result.generatedCount
            }
        }
    }

    suspend fun importBackupJson(json: String): Boolean {
        return withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                runCatching {
                    val backup = JSONObject(json)
                    val importedEntries = decodeEntries(backup.optJSONArray("entries"))
                    val importedTemplates = decodeTemplates(backup.optJSONArray("templates"))
                    val importedBudgetConfig = decodeBudgetConfig(backup.optJSONObject("budgetConfig"))

                    persistEntries(importedEntries)
                    persistTemplates(importedTemplates)
                    persistBudgetConfig(importedBudgetConfig)

                    _entries.value = importedEntries
                    _templates.value = importedTemplates
                    _budgetConfig.value = importedBudgetConfig
                }.isSuccess
            }
        }
    }

    private suspend fun updateEntries(
        transform: (List<LedgerEntry>) -> List<LedgerEntry>
    ) {
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                val updatedEntries = normalizeEntries(transform(_entries.value))
                persistEntries(updatedEntries)
                _entries.value = updatedEntries
            }
        }
    }

    private suspend fun updateTemplates(
        transform: (List<LedgerTemplate>) -> List<LedgerTemplate>
    ) {
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                val updatedTemplates = normalizeTemplates(transform(_templates.value))
                persistTemplates(updatedTemplates)
                _templates.value = updatedTemplates
            }
        }
    }

    private suspend fun updateBudgetConfig(
        transform: (LedgerBudgetConfig) -> LedgerBudgetConfig
    ) {
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                val updatedConfig = transform(_budgetConfig.value)
                persistBudgetConfig(updatedConfig)
                _budgetConfig.value = updatedConfig
            }
        }
    }

    private fun loadEntries(): List<LedgerEntry> {
        val rawValue = preferences.getString(KEY_ENTRIES, null).orEmpty()
        if (rawValue.isBlank()) {
            return emptyList()
        }

        return runCatching {
            decodeEntries(JSONArray(rawValue))
        }.getOrElse {
            emptyList()
        }
    }

    private fun loadTemplates(): List<LedgerTemplate> {
        val rawValue = preferences.getString(KEY_TEMPLATES, null).orEmpty()
        if (rawValue.isBlank()) {
            return emptyList()
        }

        return runCatching {
            decodeTemplates(JSONArray(rawValue))
        }.getOrElse {
            emptyList()
        }
    }

    private fun loadBudgetConfig(): LedgerBudgetConfig {
        val rawValue = preferences.getString(KEY_BUDGET_CONFIG, null).orEmpty()
        if (rawValue.isBlank()) {
            return LedgerBudgetConfig()
        }

        return runCatching {
            decodeBudgetConfig(JSONObject(rawValue))
        }.getOrElse {
            LedgerBudgetConfig()
        }
    }

    private fun normalizeEntries(entries: List<LedgerEntry>): List<LedgerEntry> {
        return entries.sortedWith(
            compareByDescending<LedgerEntry> { it.happenedAt }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.id }
        )
    }

    private fun normalizeTemplates(templates: List<LedgerTemplate>): List<LedgerTemplate> {
        return templates.sortedWith(
            compareByDescending<LedgerTemplate> { it.createdAt }
                .thenByDescending { it.id }
        )
    }

    private fun persistEntries(entries: List<LedgerEntry>) {
        preferences.edit()
            .putString(KEY_ENTRIES, encodeEntries(entries).toString())
            .apply()
    }

    private fun persistTemplates(templates: List<LedgerTemplate>) {
        preferences.edit()
            .putString(KEY_TEMPLATES, encodeTemplates(templates).toString())
            .apply()
    }

    private fun persistBudgetConfig(config: LedgerBudgetConfig) {
        preferences.edit()
            .putString(KEY_BUDGET_CONFIG, encodeBudgetConfig(config).toString())
            .apply()
    }

    private fun encodeEntries(entries: List<LedgerEntry>): JSONArray {
        return JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("type", entry.type.name)
                        put("amountInCents", entry.amountInCents)
                        put("account", entry.account)
                        put("category", entry.category)
                        put("note", entry.note)
                        put("receiptText", entry.receiptText)
                        put("happenedAt", entry.happenedAt)
                        put("updatedAt", entry.updatedAt)
                    }
                )
            }
        }
    }

    private fun encodeTemplates(templates: List<LedgerTemplate>): JSONArray {
        return JSONArray().apply {
            templates.forEach { template ->
                put(
                    JSONObject().apply {
                        put("id", template.id)
                        put("title", template.title)
                        put("type", template.type.name)
                        put("amountInCents", template.amountInCents)
                        put("account", template.account)
                        put("category", template.category)
                        put("recurrence", template.recurrence.name)
                        template.nextDueAt?.let { nextDueAt ->
                            put("nextDueAt", nextDueAt)
                        }
                        put("note", template.note)
                        put("createdAt", template.createdAt)
                    }
                )
            }
        }
    }

    private fun encodeBudgetConfig(config: LedgerBudgetConfig): JSONObject {
        return JSONObject().apply {
            config.monthlyBudgetInCents?.let { amount ->
                put("monthlyBudgetInCents", amount)
            }
            put(
                "categoryBudgets",
                JSONObject().apply {
                    config.categoryBudgets.forEach { (category, amount) ->
                        put(category, amount)
                    }
                }
            )
        }
    }

    private fun decodeEntries(jsonArray: JSONArray?): List<LedgerEntry> {
        if (jsonArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(index)
                add(
                    LedgerEntry(
                        id = item.optString("id"),
                        type = LedgerEntryType.valueOf(item.getString("type")),
                        amountInCents = item.getLong("amountInCents"),
                        account = item.optString("account").ifBlank { defaultLedgerAccount() },
                        category = item.getString("category"),
                        note = item.optString("note"),
                        receiptText = item.optString("receiptText"),
                        happenedAt = item.optLong("happenedAt", System.currentTimeMillis()),
                        updatedAt = item.optLong("updatedAt", item.optLong("happenedAt", System.currentTimeMillis()))
                    )
                )
            }
        }.let(::normalizeEntries)
    }

    private fun decodeTemplates(jsonArray: JSONArray?): List<LedgerTemplate> {
        if (jsonArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(index)
                add(
                    LedgerTemplate(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        type = LedgerEntryType.valueOf(item.getString("type")),
                        amountInCents = item.getLong("amountInCents"),
                        account = item.optString("account").ifBlank { defaultLedgerAccount() },
                        category = item.getString("category"),
                        recurrence = item.optString("recurrence")
                            .takeIf { value -> value.isNotBlank() }
                            ?.let(LedgerTemplateRecurrence::valueOf)
                            ?: LedgerTemplateRecurrence.NONE,
                        nextDueAt = item.optLong("nextDueAt").takeIf {
                            item.has("nextDueAt")
                        },
                        note = item.optString("note"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }.let(::normalizeTemplates)
    }

    private fun decodeBudgetConfig(jsonObject: JSONObject?): LedgerBudgetConfig {
        if (jsonObject == null) {
            return LedgerBudgetConfig()
        }

        val categoryBudgetsObject = jsonObject.optJSONObject("categoryBudgets") ?: JSONObject()
        val categoryBudgets = buildMap {
            val keys = categoryBudgetsObject.keys()
            while (keys.hasNext()) {
                val category = keys.next()
                put(category, categoryBudgetsObject.getLong(category))
            }
        }.toSortedMap()

        return LedgerBudgetConfig(
            monthlyBudgetInCents = jsonObject.optLong("monthlyBudgetInCents").takeIf {
                jsonObject.has("monthlyBudgetInCents")
            },
            categoryBudgets = categoryBudgets
        )
    }

    companion object {
        private const val PREFS_NAME = "ledger_store"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_TEMPLATES = "templates"
        private const val KEY_BUDGET_CONFIG = "budget_config"

        @Volatile
        private var instance: LedgerStore? = null

        fun getInstance(context: Context): LedgerStore {
            return instance ?: synchronized(this) {
                instance ?: LedgerStore(
                    preferences = context.applicationContext.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                ).also { createdStore ->
                    instance = createdStore
                }
            }
        }
    }
}
