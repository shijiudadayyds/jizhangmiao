@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.jizhangmiao.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IncomeTint = Color(0xFF1C8A53)
private val ExpenseTint = Color(0xFFC65A3A)
private val EntryCardShape = RoundedCornerShape(24.dp)
private val EntryTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M\u6708d\u65e5 HH:mm", Locale.CHINA)

@Composable
fun LedgerScreen(
    uiState: LedgerUiState,
    onTypeSelected: (LedgerEntryType) -> Unit,
    onAmountChanged: (String) -> Unit,
    onCategoryChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSuggestedCategorySelected: (String) -> Unit,
    onSaveClick: () -> Unit,
    onDeleteClick: (LedgerEntry) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("\u8bb0\u8d26\u55b5")
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SummarySection(summary = uiState.summary)
            }
            item {
                EntryEditorSection(
                    form = uiState.form,
                    onTypeSelected = onTypeSelected,
                    onAmountChanged = onAmountChanged,
                    onCategoryChanged = onCategoryChanged,
                    onNoteChanged = onNoteChanged,
                    onSuggestedCategorySelected = onSuggestedCategorySelected,
                    onSaveClick = onSaveClick
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\u8d26\u5355\u660e\u7ec6",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${uiState.entries.size} \u7b14",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.entries.isEmpty()) {
                item {
                    EmptyLedgerSection()
                }
            } else {
                items(
                    items = uiState.entries,
                    key = { entry -> entry.id }
                ) { entry ->
                    LedgerEntryCard(
                        entry = entry,
                        onDeleteClick = { onDeleteClick(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummarySection(summary: LedgerSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = EntryCardShape,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "\u5f53\u524d\u7ed3\u4f59",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = formatCurrency(summary.balanceInCents),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "\u6536\u5165 - \u652f\u51fa = \u5f53\u524d\u53ef\u652f\u914d\u91d1\u989d",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryStatCard(
                modifier = Modifier.weight(1f),
                title = "\u6536\u5165",
                amountInCents = summary.incomeInCents,
                accentColor = IncomeTint
            )
            SummaryStatCard(
                modifier = Modifier.weight(1f),
                title = "\u652f\u51fa",
                amountInCents = summary.expenseInCents,
                accentColor = ExpenseTint
            )
        }
    }
}

@Composable
private fun SummaryStatCard(
    modifier: Modifier = Modifier,
    title: String,
    amountInCents: Long,
    accentColor: Color
) {
    ElevatedCard(
        modifier = modifier,
        shape = EntryCardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatCurrency(amountInCents),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
    }
}

@Composable
private fun EntryEditorSection(
    form: LedgerFormState,
    onTypeSelected: (LedgerEntryType) -> Unit,
    onAmountChanged: (String) -> Unit,
    onCategoryChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSuggestedCategorySelected: (String) -> Unit,
    onSaveClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = EntryCardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "\u65b0\u589e\u8bb0\u5f55",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EntryTypeButton(
                    modifier = Modifier.weight(1f),
                    type = LedgerEntryType.EXPENSE,
                    selected = form.type == LedgerEntryType.EXPENSE,
                    onClick = { onTypeSelected(LedgerEntryType.EXPENSE) }
                )
                EntryTypeButton(
                    modifier = Modifier.weight(1f),
                    type = LedgerEntryType.INCOME,
                    selected = form.type == LedgerEntryType.INCOME,
                    onClick = { onTypeSelected(LedgerEntryType.INCOME) }
                )
            }

            OutlinedTextField(
                value = form.amount,
                onValueChange = onAmountChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("\u91d1\u989d")
                },
                prefix = {
                    Text("\u00a5")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            OutlinedTextField(
                value = form.category,
                onValueChange = onCategoryChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("\u5206\u7c7b")
                },
                singleLine = true
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "\u5feb\u6377\u5206\u7c7b",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categorySuggestionsFor(form.type)) { suggestion ->
                        FilterChip(
                            selected = suggestion == form.category,
                            onClick = {
                                onSuggestedCategorySelected(suggestion)
                            },
                            label = {
                                Text(suggestion)
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = form.note,
                onValueChange = onNoteChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("\u5907\u6ce8")
                },
                minLines = 2,
                maxLines = 3
            )

            Text(
                text = "\u4fdd\u5b58\u65f6\u4f1a\u81ea\u52a8\u8bb0\u5f55\u5f53\u524d\u65f6\u95f4\uff0c\u9002\u5408\u65e5\u5e38\u5feb\u901f\u8bb0\u8d26\u3002",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            form.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = onSaveClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("\u4fdd\u5b58\u8fd9\u7b14\u8bb0\u5f55")
            }
        }
    }
}

@Composable
private fun EntryTypeButton(
    modifier: Modifier = Modifier,
    type: LedgerEntryType,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier
        ) {
            Text(type.displayName())
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
        ) {
            Text(type.displayName())
        }
    }
}

@Composable
private fun EmptyLedgerSection() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = EntryCardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "\u8fd8\u6ca1\u6709\u8d26\u5355\u8bb0\u5f55",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "\u5148\u5f55\u5165\u4e00\u7b14\u652f\u51fa\u6216\u6536\u5165\uff0c\u4e0b\u9762\u7684\u7edf\u8ba1\u4f1a\u81ea\u52a8\u5237\u65b0\u3002",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LedgerEntryCard(
    entry: LedgerEntry,
    onDeleteClick: () -> Unit
) {
    val accentColor = when (entry.type) {
        LedgerEntryType.EXPENSE -> ExpenseTint
        LedgerEntryType.INCOME -> IncomeTint
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = EntryCardShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accentColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = entry.type.displayName(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor
                        )
                    }
                    Text(
                        text = entry.category,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (entry.note.isNotBlank()) {
                    Text(
                        text = entry.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = formatEntryTime(entry.happenedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = formatSignedCurrency(entry),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                TextButton(onClick = onDeleteClick) {
                    Text("\u5220\u9664")
                }
            }
        }
    }
}

private fun formatCurrency(amountInCents: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.CHINA)
    return formatter.format(amountInCents / 100.0)
}

private fun formatSignedCurrency(entry: LedgerEntry): String {
    val prefix = if (entry.type == LedgerEntryType.INCOME) "+" else "-"
    return prefix + formatCurrency(entry.amountInCents)
}

private fun formatEntryTime(timestamp: Long): String {
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(EntryTimeFormatter)
}
