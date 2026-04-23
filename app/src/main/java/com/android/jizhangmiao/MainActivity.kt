package com.android.jizhangmiao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.jizhangmiao.ledger.LedgerScreen
import com.android.jizhangmiao.ledger.LedgerViewModel
import com.android.jizhangmiao.ledger.data.LedgerStore
import com.android.jizhangmiao.ui.theme.JizhangmiaoTheme

class MainActivity : ComponentActivity() {
    private val ledgerStore by lazy {
        LedgerStore.getInstance(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val securityVerdict = AppSecurity.evaluate(applicationContext)
        if (!securityVerdict.isTrusted) {
            setContent {
                JizhangmiaoTheme {
                    SecurityBlockedScreen(
                        reason = securityVerdict.reason ?: "\u5f53\u524d\u73af\u5883\u4e0d\u53d7\u4fe1\u4efb"
                    )
                }
            }
            return
        }
        setContent {
            JizhangmiaoTheme {
                val viewModel: LedgerViewModel = viewModel(
                    factory = LedgerViewModel.factory(applicationContext, ledgerStore)
                )
                val uiState = viewModel.uiState.collectAsStateWithLifecycle()

                LedgerScreen(
                    uiState = uiState.value,
                    onTypeSelected = viewModel::onTypeSelected,
                    onAmountChanged = viewModel::onAmountChanged,
                    onAccountChanged = viewModel::onAccountChanged,
                    onCategoryChanged = viewModel::onCategoryChanged,
                    onNoteChanged = viewModel::onNoteChanged,
                    onSuggestedAccountSelected = viewModel::onSuggestedAccountSelected,
                    onSuggestedCategorySelected = viewModel::onSuggestedCategorySelected,
                    onTemplateRecurrenceSelected = viewModel::onTemplateRecurrenceSelected,
                    onSaveClick = viewModel::saveEntry,
                    onCancelEditClick = viewModel::cancelEditing,
                    onDeleteClick = viewModel::deleteEntry,
                    onEditClick = viewModel::startEditing,
                    onSaveTemplateClick = viewModel::saveCurrentAsTemplate,
                    onApplyTemplateClick = viewModel::applyTemplate,
                    onDeleteTemplateClick = viewModel::deleteTemplate,
                    onSaveBudgetClick = viewModel::saveBudget,
                    onExportBackup = viewModel::exportBackup,
                    onImportBackup = viewModel::importBackup,
                    onScanReceipt = viewModel::scanReceipt,
                    onDismissStatusMessage = viewModel::dismissStatusMessage
                )
            }
        }
    }
}
