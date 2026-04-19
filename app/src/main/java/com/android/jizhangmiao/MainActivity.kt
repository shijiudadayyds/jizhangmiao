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
        setContent {
            JizhangmiaoTheme {
                val viewModel: LedgerViewModel = viewModel(
                    factory = LedgerViewModel.factory(ledgerStore)
                )
                val uiState = viewModel.uiState.collectAsStateWithLifecycle()

                LedgerScreen(
                    uiState = uiState.value,
                    onTypeSelected = viewModel::onTypeSelected,
                    onAmountChanged = viewModel::onAmountChanged,
                    onCategoryChanged = viewModel::onCategoryChanged,
                    onNoteChanged = viewModel::onNoteChanged,
                    onSuggestedCategorySelected = viewModel::onSuggestedCategorySelected,
                    onSaveClick = viewModel::saveEntry,
                    onDeleteClick = viewModel::deleteEntry
                )
            }
        }
    }
}
