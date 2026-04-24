package com.android.jizhangmiao.ledger.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class LegacyLedgerSnapshot(
    val entries: List<LedgerEntry> = emptyList(),
    val templates: List<LedgerTemplate> = emptyList(),
    val budgetConfig: LedgerBudgetConfig = LedgerBudgetConfig(),
    val profileConfig: LedgerProfileConfig = LedgerProfileConfig(),
    val automationTrace: LedgerAutomationTrace = LedgerAutomationTrace(),
    val pendingImports: List<PendingLedgerImport> = emptyList(),
    val securityConfig: LedgerSecurityConfig = LedgerSecurityConfig(),
    val autoImportHistory: List<String> = emptyList(),
    val hasStoredPayload: Boolean = false
)

internal class LegacyLedgerPreferences(
    private val preferences: SharedPreferences
) {
    fun readSnapshot(): LegacyLedgerSnapshot {
        val entriesRaw = preferences.getString(KEY_ENTRIES, null).orEmpty()
        val templatesRaw = preferences.getString(KEY_TEMPLATES, null).orEmpty()
        val budgetConfigRaw = preferences.getString(KEY_BUDGET_CONFIG, null).orEmpty()
        val profileConfigRaw = preferences.getString(KEY_PROFILE_CONFIG, null).orEmpty()
        val automationTraceRaw = preferences.getString(KEY_AUTOMATION_TRACE, null).orEmpty()
        val pendingImportsRaw = preferences.getString(KEY_PENDING_IMPORTS, null).orEmpty()
        val securityConfigRaw = preferences.getString(KEY_SECURITY_CONFIG, null).orEmpty()
        val autoImportHistoryRaw = preferences.getString(KEY_AUTO_IMPORT_HISTORY, null).orEmpty()

        return LegacyLedgerSnapshot(
            entries = runCatching {
                if (entriesRaw.isBlank()) emptyList() else LedgerJsonCodec.decodeEntries(JSONArray(entriesRaw))
            }.getOrDefault(emptyList()),
            templates = runCatching {
                if (templatesRaw.isBlank()) emptyList() else LedgerJsonCodec.decodeTemplates(JSONArray(templatesRaw))
            }.getOrDefault(emptyList()),
            budgetConfig = runCatching {
                if (budgetConfigRaw.isBlank()) LedgerBudgetConfig() else LedgerJsonCodec.decodeBudgetConfig(JSONObject(budgetConfigRaw))
            }.getOrDefault(LedgerBudgetConfig()),
            profileConfig = runCatching {
                if (profileConfigRaw.isBlank()) LedgerProfileConfig() else LedgerJsonCodec.decodeProfileConfig(JSONObject(profileConfigRaw))
            }.getOrDefault(LedgerProfileConfig()),
            automationTrace = runCatching {
                if (automationTraceRaw.isBlank()) LedgerAutomationTrace() else LedgerJsonCodec.decodeAutomationTrace(JSONObject(automationTraceRaw))
            }.getOrDefault(LedgerAutomationTrace()),
            pendingImports = runCatching {
                if (pendingImportsRaw.isBlank()) emptyList() else LedgerJsonCodec.decodePendingImports(JSONArray(pendingImportsRaw))
            }.getOrDefault(emptyList()),
            securityConfig = runCatching {
                if (securityConfigRaw.isBlank()) LedgerSecurityConfig() else LedgerJsonCodec.decodeSecurityConfig(JSONObject(securityConfigRaw))
            }.getOrDefault(LedgerSecurityConfig()),
            autoImportHistory = runCatching {
                if (autoImportHistoryRaw.isBlank()) {
                    emptyList()
                } else {
                    LedgerJsonCodec.decodeStringList(JSONArray(autoImportHistoryRaw))
                }
            }.getOrDefault(emptyList()),
            hasStoredPayload = listOf(
                entriesRaw,
                templatesRaw,
                budgetConfigRaw,
                profileConfigRaw,
                automationTraceRaw,
                pendingImportsRaw,
                securityConfigRaw,
                autoImportHistoryRaw
            ).any { value -> value.isNotBlank() }
        )
    }

    companion object {
        const val PREFS_NAME = "ledger_store"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_TEMPLATES = "templates"
        private const val KEY_BUDGET_CONFIG = "budget_config"
        private const val KEY_PROFILE_CONFIG = "profile_config"
        private const val KEY_AUTOMATION_TRACE = "automation_trace"
        private const val KEY_PENDING_IMPORTS = "pending_imports"
        private const val KEY_SECURITY_CONFIG = "security_config"
        private const val KEY_AUTO_IMPORT_HISTORY = "auto_import_history"
    }
}
