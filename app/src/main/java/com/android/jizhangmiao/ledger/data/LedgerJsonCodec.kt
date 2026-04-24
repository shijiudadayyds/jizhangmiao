package com.android.jizhangmiao.ledger.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class LedgerBackupPayload(
    val entries: List<LedgerEntry>,
    val templates: List<LedgerTemplate>,
    val budgetConfig: LedgerBudgetConfig,
    val profileConfig: LedgerProfileConfig
)

internal object LedgerJsonCodec {
    fun exportBackupJson(
        entries: List<LedgerEntry>,
        templates: List<LedgerTemplate>,
        budgetConfig: LedgerBudgetConfig,
        profileConfig: LedgerProfileConfig
    ): String {
        return JSONObject().apply {
            put("version", 3)
            put("exportedAt", System.currentTimeMillis())
            put("entries", encodeEntries(entries))
            put("templates", encodeTemplates(templates))
            put("budgetConfig", encodeBudgetConfig(budgetConfig))
            put("profileConfig", encodeProfileConfig(profileConfig))
        }.toString(2)
    }

    fun previewBackupJson(json: String): BackupPreview? {
        return runCatching {
            val backup = JSONObject(json)
            BackupPreview(
                entriesCount = backup.optJSONArray("entries")?.length() ?: 0,
                templatesCount = backup.optJSONArray("templates")?.length() ?: 0,
                categoryBudgetCount = backup
                    .optJSONObject("budgetConfig")
                    ?.optJSONObject("categoryBudgets")
                    ?.length()
                    ?: 0
            )
        }.getOrNull()
    }

    fun decodeBackupJson(json: String): LedgerBackupPayload? {
        return runCatching {
            val backup = JSONObject(json)
            LedgerBackupPayload(
                entries = decodeEntries(backup.optJSONArray("entries")),
                templates = decodeTemplates(backup.optJSONArray("templates")),
                budgetConfig = decodeBudgetConfig(backup.optJSONObject("budgetConfig")),
                profileConfig = decodeProfileConfig(backup.optJSONObject("profileConfig"))
            )
        }.getOrNull()
    }

    fun encodeCategoryBudgets(value: Map<String, Long>): String {
        return encodeBudgetConfig(LedgerBudgetConfig(categoryBudgets = value)).toString()
    }

    fun decodeCategoryBudgets(value: String): Map<String, Long> {
        return runCatching {
            decodeBudgetConfig(JSONObject(value)).categoryBudgets
        }.getOrDefault(emptyMap())
    }

    fun encodeStringList(values: List<String>): String {
        return encodeStringListArray(values).toString()
    }

    fun decodeStringList(value: String): List<String> {
        return runCatching {
            decodeStringList(JSONArray(value))
        }.getOrDefault(emptyList())
    }

    fun encodeEntries(entries: List<LedgerEntry>): JSONArray {
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

    fun encodeTemplates(templates: List<LedgerTemplate>): JSONArray {
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

    fun encodeBudgetConfig(config: LedgerBudgetConfig): JSONObject {
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

    fun encodeProfileConfig(config: LedgerProfileConfig): JSONObject {
        return JSONObject().apply {
            put("customAccounts", encodeStringListArray(config.customAccounts))
            put("customExpenseCategories", encodeStringListArray(config.customExpenseCategories))
            put("customIncomeCategories", encodeStringListArray(config.customIncomeCategories))
        }
    }

    fun encodeAutomationTrace(trace: LedgerAutomationTrace): JSONObject {
        return JSONObject().apply {
            put("sourceLabel", trace.sourceLabel)
            put("summary", trace.summary)
            put("rawText", trace.rawText)
            put("happenedAt", trace.happenedAt)
        }
    }

    fun encodePendingImports(pendingImports: List<PendingLedgerImport>): JSONArray {
        return JSONArray().apply {
            pendingImports.forEach { pendingImport ->
                put(
                    JSONObject().apply {
                        put("id", pendingImport.id)
                        put("signature", pendingImport.signature)
                        put("type", pendingImport.type.name)
                        put("amountInCents", pendingImport.amountInCents)
                        put("account", pendingImport.account)
                        put("category", pendingImport.category)
                        put("note", pendingImport.note)
                        put("receiptText", pendingImport.receiptText)
                        put("happenedAt", pendingImport.happenedAt)
                        put("sourceLabel", pendingImport.sourceLabel)
                        put("createdAt", pendingImport.createdAt)
                    }
                )
            }
        }
    }

    fun encodeSecurityConfig(config: LedgerSecurityConfig): JSONObject {
        return JSONObject().apply {
            put("pinHash", config.pinHash)
            put("pinSalt", config.pinSalt)
        }
    }

    fun decodeEntries(jsonArray: JSONArray?): List<LedgerEntry> {
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

    fun decodeTemplates(jsonArray: JSONArray?): List<LedgerTemplate> {
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

    fun decodeBudgetConfig(jsonObject: JSONObject?): LedgerBudgetConfig {
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

    fun decodeProfileConfig(jsonObject: JSONObject?): LedgerProfileConfig {
        if (jsonObject == null) {
            return LedgerProfileConfig()
        }

        return LedgerProfileConfig(
            customAccounts = decodeStringList(jsonObject.optJSONArray("customAccounts")),
            customExpenseCategories = decodeStringList(jsonObject.optJSONArray("customExpenseCategories")),
            customIncomeCategories = decodeStringList(jsonObject.optJSONArray("customIncomeCategories"))
        )
    }

    fun decodeAutomationTrace(jsonObject: JSONObject?): LedgerAutomationTrace {
        if (jsonObject == null) {
            return LedgerAutomationTrace()
        }

        return LedgerAutomationTrace(
            sourceLabel = jsonObject.optString("sourceLabel"),
            summary = jsonObject.optString("summary"),
            rawText = jsonObject.optString("rawText"),
            happenedAt = jsonObject.optLong("happenedAt", 0L)
        )
    }

    fun decodePendingImports(jsonArray: JSONArray?): List<PendingLedgerImport> {
        if (jsonArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(index)
                add(
                    PendingLedgerImport(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        signature = item.optString("signature"),
                        type = LedgerEntryType.valueOf(item.getString("type")),
                        amountInCents = item.getLong("amountInCents"),
                        account = item.optString("account").ifBlank { defaultLedgerAccount() },
                        category = item.getString("category"),
                        note = item.optString("note"),
                        receiptText = item.optString("receiptText"),
                        happenedAt = item.optLong("happenedAt", System.currentTimeMillis()),
                        sourceLabel = item.optString("sourceLabel"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }.let(::normalizePendingImports)
    }

    fun decodeSecurityConfig(jsonObject: JSONObject?): LedgerSecurityConfig {
        if (jsonObject == null) {
            return LedgerSecurityConfig()
        }

        return LedgerSecurityConfig(
            pinHash = jsonObject.optString("pinHash"),
            pinSalt = jsonObject.optString("pinSalt")
        )
    }

    fun decodeStringList(jsonArray: JSONArray?): List<String> {
        if (jsonArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until jsonArray.length()) {
                val value = jsonArray.optString(index).trim()
                if (value.isNotBlank()) {
                    add(value)
                }
            }
        }.distinct().sorted()
    }

    private fun encodeStringListArray(values: List<String>): JSONArray {
        return JSONArray().apply {
            values
                .filter { value -> value.isNotBlank() }
                .distinct()
                .forEach(::put)
        }
    }
}
