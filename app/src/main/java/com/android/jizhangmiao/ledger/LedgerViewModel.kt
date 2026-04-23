package com.android.jizhangmiao.ledger

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.LedgerStore
import com.android.jizhangmiao.ledger.data.LedgerTemplate
import com.android.jizhangmiao.ledger.data.LedgerTemplateRecurrence
import com.android.jizhangmiao.ledger.data.defaultLedgerAccount
import com.android.jizhangmiao.ledger.data.initialTemplateNextDueAt
import com.android.jizhangmiao.ledger.data.sanitizeAmountInput
import com.android.jizhangmiao.ledger.data.toAmountInCents
import com.android.jizhangmiao.ledger.data.toAmountInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LedgerViewModel(
    private val appContext: Context,
    private val ledgerStore: LedgerStore
) : ViewModel() {
    private data class UiSeed(
        val entries: List<LedgerEntry>,
        val templates: List<LedgerTemplate>,
        val budgetConfig: com.android.jizhangmiao.ledger.data.LedgerBudgetConfig,
        val automationTrace: com.android.jizhangmiao.ledger.data.LedgerAutomationTrace
    )

    private val formState = MutableStateFlow(LedgerFormState())
    private val statusMessage = MutableStateFlow<String?>(null)
    private val scanningState = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val generatedCount = ledgerStore.syncRecurringTemplates()
            if (generatedCount > 0) {
                statusMessage.value = "\u5df2\u81ea\u52a8\u8865\u5165 $generatedCount \u7b14\u5230\u671f\u7684\u5468\u671f\u8d26\u5355"
            }
        }
    }

    val uiState: StateFlow<LedgerUiState> = combine(
        combine(
            ledgerStore.entries,
            ledgerStore.templates,
            ledgerStore.budgetConfig,
            ledgerStore.automationTrace
        ) { entries, templates, budgetConfig, automationTrace ->
            UiSeed(
                entries = entries,
                templates = templates,
                budgetConfig = budgetConfig,
                automationTrace = automationTrace
            )
        },
        formState,
        statusMessage,
        scanningState
    ) { seed, form, message, isScanning ->
        LedgerUiState(
            entries = seed.entries,
            templates = seed.templates,
            budgetConfig = seed.budgetConfig,
            automationTrace = seed.automationTrace,
            form = form,
            statusMessage = message,
            isReceiptScanning = isScanning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LedgerUiState()
    )

    fun onTypeSelected(type: LedgerEntryType) {
        formState.update { current ->
            val shouldReplaceCategory = current.category.isBlank() ||
                current.category in categorySuggestionsFor(current.type)

            current.copy(
                type = type,
                category = if (shouldReplaceCategory) defaultCategoryFor(type) else current.category,
                errorMessage = null
            )
        }
    }

    fun onAmountChanged(value: String) {
        formState.update { current ->
            current.copy(
                amount = sanitizeAmountInput(value),
                errorMessage = null
            )
        }
    }

    fun onCategoryChanged(value: String) {
        formState.update { current ->
            current.copy(
                category = value.take(12),
                errorMessage = null
            )
        }
    }

    fun onAccountChanged(value: String) {
        formState.update { current ->
            current.copy(
                account = value.take(12),
                errorMessage = null
            )
        }
    }

    fun onNoteChanged(value: String) {
        formState.update { current ->
            current.copy(
                note = value.take(80),
                errorMessage = null
            )
        }
    }

    fun onSuggestedCategorySelected(category: String) {
        formState.update { current ->
            current.copy(
                category = category,
                errorMessage = null
            )
        }
    }

    fun onSuggestedAccountSelected(account: String) {
        formState.update { current ->
            current.copy(
                account = account,
                errorMessage = null
            )
        }
    }

    fun onTemplateRecurrenceSelected(recurrence: LedgerTemplateRecurrence) {
        formState.update { current ->
            current.copy(
                templateRecurrence = recurrence,
                errorMessage = null
            )
        }
    }

    fun startEditing(entry: LedgerEntry) {
        formState.value = LedgerFormState(
            editingEntryId = entry.id,
            type = entry.type,
            amount = entry.amountInCents.toAmountInput(),
            account = entry.account,
            category = entry.category,
            note = entry.note,
            receiptText = entry.receiptText
        )
        statusMessage.value = "\u5df2\u8f7d\u5165\u8fd9\u7b14\u8bb0\u5f55\uff0c\u53ef\u4ee5\u76f4\u63a5\u4fee\u6539"
    }

    fun cancelEditing() {
        resetForm()
    }

    fun saveEntry() {
        val snapshot = formState.value
        val amountInCents = snapshot.amount.toAmountInCents()
        val account = snapshot.account.trim()
        val category = snapshot.category.trim()

        when {
            amountInCents == null -> {
                showFormError("\u8bf7\u8f93\u5165\u5927\u4e8e 0 \u7684\u91d1\u989d")
                return
            }

            account.isBlank() -> {
                showFormError("\u8bf7\u8f93\u5165\u8d26\u6237")
                return
            }

            category.isBlank() -> {
                showFormError("\u8bf7\u8f93\u5165\u5206\u7c7b")
                return
            }
        }

        viewModelScope.launch {
            val existingEntry = snapshot.editingEntryId?.let { editingId ->
                ledgerStore.entries.value.firstOrNull { entry -> entry.id == editingId }
            }

            if (existingEntry == null) {
                ledgerStore.addEntry(
                    LedgerEntry(
                        type = snapshot.type,
                        amountInCents = amountInCents,
                        account = account,
                        category = category,
                        note = snapshot.note.trim(),
                        receiptText = snapshot.receiptText.trim()
                    )
                )
                statusMessage.value = "\u5df2\u4fdd\u5b58\u8fd9\u7b14\u8bb0\u5f55"
            } else {
                ledgerStore.updateEntry(
                    existingEntry.copy(
                        type = snapshot.type,
                        amountInCents = amountInCents,
                        account = account,
                        category = category,
                        note = snapshot.note.trim(),
                        receiptText = snapshot.receiptText.trim()
                    )
                )
                statusMessage.value = "\u8bb0\u5f55\u5df2\u66f4\u65b0"
            }

            resetForm(type = snapshot.type)
        }
    }

    fun deleteEntry(entry: LedgerEntry) {
        viewModelScope.launch {
            ledgerStore.deleteEntry(entry)
            if (formState.value.editingEntryId == entry.id) {
                resetForm()
            }
            statusMessage.value = "\u8fd9\u7b14\u8bb0\u5f55\u5df2\u5220\u9664"
        }
    }

    fun saveCurrentAsTemplate() {
        val snapshot = formState.value
        val amountInCents = snapshot.amount.toAmountInCents()
        val account = snapshot.account.trim()
        val category = snapshot.category.trim()

        when {
            amountInCents == null -> {
                showFormError("\u5148\u586b\u597d\u91d1\u989d\uff0c\u518d\u4fdd\u5b58\u4e3a\u6a21\u677f")
                return
            }

            account.isBlank() -> {
                showFormError("\u5148\u9009\u597d\u8d26\u6237\uff0c\u518d\u4fdd\u5b58\u4e3a\u6a21\u677f")
                return
            }

            category.isBlank() -> {
                showFormError("\u5148\u586b\u597d\u5206\u7c7b\uff0c\u518d\u4fdd\u5b58\u4e3a\u6a21\u677f")
                return
            }
        }

        viewModelScope.launch {
            val title = snapshot.note.trim().ifBlank { category }
            ledgerStore.upsertTemplate(
                LedgerTemplate(
                    title = title,
                    type = snapshot.type,
                    amountInCents = amountInCents,
                    account = account,
                    category = category,
                    recurrence = snapshot.templateRecurrence,
                    nextDueAt = initialTemplateNextDueAt(
                        fromTimeMillis = System.currentTimeMillis(),
                        recurrence = snapshot.templateRecurrence
                    ),
                    note = snapshot.note.trim()
                )
            )
            statusMessage.value = if (snapshot.templateRecurrence == LedgerTemplateRecurrence.NONE) {
                "\u5df2\u4fdd\u5b58\u4e3a\u5feb\u6377\u6a21\u677f"
            } else {
                "\u5df2\u4fdd\u5b58\u4e3a${snapshot.templateRecurrence.displayName()}\u5468\u671f\u6a21\u677f"
            }
        }
    }

    fun applyTemplate(template: LedgerTemplate) {
        formState.value = LedgerFormState(
            type = template.type,
            amount = template.amountInCents.toAmountInput(),
            account = template.account,
            category = template.category,
            note = template.note,
            templateRecurrence = template.recurrence
        )
        statusMessage.value = "\u5df2\u5e94\u7528\u6a21\u677f\uff0c\u53ef\u4ee5\u76f4\u63a5\u4fdd\u5b58\u6216\u518d\u5fae\u8c03"
    }

    fun deleteTemplate(template: LedgerTemplate) {
        viewModelScope.launch {
            ledgerStore.deleteTemplate(template)
            statusMessage.value = "\u5df2\u5220\u9664\u8fd9\u4e2a\u6a21\u677f"
        }
    }

    fun saveBudget(
        monthlyBudgetInput: String,
        category: String,
        categoryBudgetInput: String
    ) {
        val normalizedMonthlyBudget = monthlyBudgetInput.trim()
        val normalizedCategoryBudget = categoryBudgetInput.trim()

        if (normalizedMonthlyBudget.isNotBlank() && normalizedMonthlyBudget.toAmountInCents() == null) {
            statusMessage.value = "\u6708\u5ea6\u9884\u7b97\u683c\u5f0f\u4e0d\u5bf9"
            return
        }

        if (normalizedCategoryBudget.isNotBlank() && normalizedCategoryBudget.toAmountInCents() == null) {
            statusMessage.value = "\u5206\u7c7b\u9884\u7b97\u683c\u5f0f\u4e0d\u5bf9"
            return
        }

        viewModelScope.launch {
            ledgerStore.updateMonthlyBudget(normalizedMonthlyBudget.toAmountInCents())
            ledgerStore.updateCategoryBudget(category, normalizedCategoryBudget.toAmountInCents())
            statusMessage.value = "\u9884\u7b97\u5df2\u4fdd\u5b58"
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                appContext.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(ledgerStore.exportBackupJson())
                } ?: error("Unable to open output stream")
            }.onSuccess {
                statusMessage.value = "\u5907\u4efd\u5df2\u5bfc\u51fa"
            }.onFailure {
                statusMessage.value = "\u5bfc\u51fa\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5"
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val backupJson = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readText()
                } ?: error("Unable to open input stream")

                ledgerStore.importBackupJson(backupJson)
            }.onSuccess { imported ->
                if (imported) {
                    val generatedCount = ledgerStore.syncRecurringTemplates()
                    resetForm()
                    statusMessage.value = if (generatedCount > 0) {
                        "\u5907\u4efd\u5df2\u5bfc\u5165\uff0c\u5e76\u8865\u5165 $generatedCount \u7b14\u5468\u671f\u8d26\u5355"
                    } else {
                        "\u5907\u4efd\u5df2\u5bfc\u5165"
                    }
                } else {
                    statusMessage.value = "\u5907\u4efd\u683c\u5f0f\u4e0d\u6b63\u786e"
                }
            }.onFailure {
                statusMessage.value = "\u5bfc\u5165\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u6587\u4ef6"
            }
        }
    }

    fun scanReceipt(uri: Uri) {
        viewModelScope.launch {
            scanningState.value = true
            runCatching {
                recognizeReceipt(appContext, uri)
            }.onSuccess { result ->
                if (result == null || result.rawText.isBlank()) {
                    statusMessage.value = "\u672a\u8bc6\u522b\u5230\u53ef\u7528\u6587\u5b57"
                } else {
                    formState.update { current ->
                        current.copy(
                            editingEntryId = null,
                            type = result.type ?: current.type,
                            amount = result.amountInput ?: current.amount,
                            account = result.account ?: current.account.ifBlank { defaultLedgerAccount() },
                            category = result.category ?: current.category,
                            note = if (current.note.isBlank()) result.suggestedNote.take(80) else current.note,
                            receiptText = result.rawText,
                            errorMessage = null
                        )
                    }
                    statusMessage.value = "\u5df2\u4ece\u5c0f\u7968\u63d0\u53d6\u5185\u5bb9\uff0c\u8bf7\u518d\u6838\u5bf9\u91d1\u989d"
                }
            }.onFailure {
                statusMessage.value = "\u5c0f\u7968\u8bc6\u522b\u5931\u8d25\uff0c\u53ef\u4ee5\u6539\u4e3a\u624b\u52a8\u5f55\u5165"
            }
            scanningState.value = false
        }
    }

    fun dismissStatusMessage() {
        statusMessage.value = null
    }

    private fun showFormError(message: String) {
        formState.update { current ->
            current.copy(errorMessage = message)
        }
    }

    private fun resetForm(type: LedgerEntryType = LedgerEntryType.EXPENSE) {
        formState.value = LedgerFormState(
            type = type,
            account = defaultLedgerAccount(),
            category = defaultCategoryFor(type)
        )
    }

    companion object {
        fun factory(
            appContext: Context,
            ledgerStore: LedgerStore
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LedgerViewModel(appContext.applicationContext, ledgerStore)
            }
        }
    }
}
