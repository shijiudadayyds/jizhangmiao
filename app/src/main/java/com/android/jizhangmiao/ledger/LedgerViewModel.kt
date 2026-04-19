package com.android.jizhangmiao.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.LedgerStore
import java.math.RoundingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LedgerViewModel(
    private val ledgerStore: LedgerStore
) : ViewModel() {
    private val formState = MutableStateFlow(LedgerFormState())

    val uiState: StateFlow<LedgerUiState> = combine(
        ledgerStore.entries,
        formState
    ) { entries, form ->
        LedgerUiState(
            summary = LedgerSummaryCalculator.calculate(entries),
            form = form,
            entries = entries
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

    fun onNoteChanged(value: String) {
        formState.update { current ->
            current.copy(
                note = value.take(40),
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

    fun saveEntry() {
        val snapshot = formState.value
        val amountInCents = snapshot.amount.toAmountInCents()
        val category = snapshot.category.trim()

        when {
            amountInCents == null -> {
                showError("\u8bf7\u8f93\u5165\u5927\u4e8e 0 \u7684\u91d1\u989d")
                return
            }

            category.isBlank() -> {
                showError("\u8bf7\u8f93\u5165\u5206\u7c7b")
                return
            }
        }

        viewModelScope.launch {
            ledgerStore.addEntry(
                LedgerEntry(
                    type = snapshot.type,
                    amountInCents = amountInCents,
                    category = category,
                    note = snapshot.note.trim()
                )
            )

            formState.update { current ->
                current.copy(
                    amount = "",
                    category = category,
                    note = "",
                    errorMessage = null
                )
            }
        }
    }

    fun deleteEntry(entry: LedgerEntry) {
        viewModelScope.launch {
            ledgerStore.deleteEntry(entry)
        }
    }

    private fun showError(message: String) {
        formState.update { current ->
            current.copy(errorMessage = message)
        }
    }

    companion object {
        fun factory(ledgerStore: LedgerStore): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LedgerViewModel(ledgerStore)
            }
        }
    }
}

private fun sanitizeAmountInput(value: String): String {
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

private fun String.toAmountInCents(): Long? {
    if (isBlank()) {
        return null
    }

    return runCatching {
        toBigDecimal()
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()?.takeIf { it > 0L }
}
