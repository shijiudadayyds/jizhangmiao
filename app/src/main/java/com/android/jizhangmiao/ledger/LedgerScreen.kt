@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.jizhangmiao.ledger

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.jizhangmiao.ledger.data.LedgerBudgetConfig
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.LedgerTemplate
import com.android.jizhangmiao.ledger.data.toAmountInput
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val IncomeTint = Color(0xFF2E8B57)
private val ExpenseTint = Color(0xFFC76B4B)
private val ChartPalette = listOf(
    Color(0xFFC76B4B),
    Color(0xFF2E8B57),
    Color(0xFF5B8DEF),
    Color(0xFFF2A93B),
    Color(0xFF8E6CCF)
)
private val HeroShape = RoundedCornerShape(32.dp)
private val SectionShape = RoundedCornerShape(28.dp)
private val EntryShape = RoundedCornerShape(24.dp)
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
    onCancelEditClick: () -> Unit,
    onDeleteClick: (LedgerEntry) -> Unit,
    onEditClick: (LedgerEntry) -> Unit,
    onSaveTemplateClick: () -> Unit,
    onApplyTemplateClick: (LedgerTemplate) -> Unit,
    onDeleteTemplateClick: (LedgerTemplate) -> Unit,
    onSaveBudgetClick: (String, String, String) -> Unit,
    onExportBackup: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    onScanReceipt: (Uri) -> Unit,
    onDismissStatusMessage: () -> Unit
) {
    var periodFilterName by rememberSaveable { mutableStateOf(LedgerPeriodFilter.THIS_MONTH.name) }
    var typeFilterName by rememberSaveable { mutableStateOf(LedgerEntryFilterType.ALL.name) }
    var categoryFilter by rememberSaveable { mutableStateOf("") }
    var budgetCategory by rememberSaveable { mutableStateOf(defaultCategoryFor(LedgerEntryType.EXPENSE)) }
    var trendGranularityName by rememberSaveable { mutableStateOf(LedgerTrendGranularity.MONTH.name) }
    var chartDetailCategory by rememberSaveable { mutableStateOf("") }

    var monthlyBudgetText by remember(uiState.budgetConfig.monthlyBudgetInCents) {
        mutableStateOf(uiState.budgetConfig.monthlyBudgetInCents?.toAmountInput().orEmpty())
    }
    var categoryBudgetText by remember(uiState.budgetConfig.categoryBudgets, budgetCategory) {
        mutableStateOf(uiState.budgetConfig.categoryBudgets[budgetCategory]?.toAmountInput().orEmpty())
    }

    val periodFilter = remember(periodFilterName) { LedgerPeriodFilter.valueOf(periodFilterName) }
    val typeFilter = remember(typeFilterName) { LedgerEntryFilterType.valueOf(typeFilterName) }
    val trendGranularity = remember(trendGranularityName) {
        LedgerTrendGranularity.valueOf(trendGranularityName)
    }
    val boards = remember { LedgerBoard.entries.toList() }
    val pagerState = rememberPagerState(pageCount = { boards.size })
    val coroutineScope = rememberCoroutineScope()

    val filteredEntries = remember(uiState.entries, periodFilter, typeFilter, categoryFilter) {
        filterEntries(
            entries = uiState.entries,
            periodFilter = periodFilter,
            typeFilter = typeFilter,
            category = categoryFilter.ifBlank { null }
        )
    }
    val filteredSummary = remember(filteredEntries) {
        LedgerSummaryCalculator.calculate(filteredEntries)
    }
    val dashboardSummary = remember(uiState.entries) {
        LedgerSummaryCalculator.calculate(uiState.entries)
    }
    val currentMonthEntries = remember(uiState.entries) {
        filterEntries(
            entries = uiState.entries,
            periodFilter = LedgerPeriodFilter.THIS_MONTH,
            typeFilter = LedgerEntryFilterType.ALL,
            category = null
        )
    }
    val currentMonthSummary = remember(currentMonthEntries) {
        LedgerSummaryCalculator.calculate(currentMonthEntries)
    }
    val currentMonthTopCategories = remember(currentMonthEntries) {
        buildCategoryBreakdown(currentMonthEntries)
    }
    val trendPoints = remember(filteredEntries, trendGranularity) {
        buildTrendPoints(filteredEntries, trendGranularity)
    }
    val availableCategories = remember(uiState.entries, uiState.templates) {
        buildAvailableCategories(uiState.entries, uiState.templates)
    }
    val recentEntries = remember(uiState.entries) {
        uiState.entries.take(6)
    }
    val budgetCategories = remember(availableCategories) {
        if (availableCategories.isEmpty()) {
            categorySuggestionsFor(LedgerEntryType.EXPENSE)
        } else {
            availableCategories
        }
    }
    val selectedStatsCategory = chartDetailCategory.takeIf { selectedCategory ->
        selectedCategory.isNotBlank() &&
            filteredEntries.any { entry -> entry.category == selectedCategory }
    }
    val statsDetailEntries = remember(filteredEntries, selectedStatsCategory) {
        if (selectedStatsCategory == null) {
            filteredEntries
        } else {
            filteredEntries.filter { entry -> entry.category == selectedStatsCategory }
        }
    }
    val currentBoard = boards[pagerState.currentPage.coerceIn(0, boards.lastIndex)]

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let(onExportBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(onImportBackup)
    }
    val receiptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let(onScanReceipt)
    }
    val openBoard: (LedgerBoard) -> Unit = { board ->
        coroutineScope.launch {
            pagerState.animateScrollToPage(board.ordinal)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFFF7EE),
                        Color(0xFFF4F7F1),
                        Color(0xFFFFFCF8)
                    )
                )
            )
    ) {
        DecorativeBackdrop()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "\u8bb0\u8d26\u55b5",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = currentBoard.subtitle(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(boards) { board ->
                            FilterChip(
                                selected = board.ordinal == pagerState.currentPage,
                                onClick = {
                                    openBoard(board)
                                },
                                label = {
                                    Text(board.displayName())
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding()
                    )
            ) {
                AnimatedVisibility(visible = uiState.statusMessage != null) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        StatusBanner(
                            message = uiState.statusMessage.orEmpty(),
                            onDismiss = onDismissStatusMessage
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (boards[page]) {
                        LedgerBoard.DASHBOARD -> DashboardBoard(
                            summary = dashboardSummary,
                            budgetConfig = uiState.budgetConfig,
                            currentMonthSummary = currentMonthSummary,
                            topCategories = currentMonthTopCategories,
                            totalRecordCount = uiState.entries.size,
                            recentEntries = recentEntries,
                            form = uiState.form,
                            isReceiptScanning = uiState.isReceiptScanning,
                            onTypeSelected = onTypeSelected,
                            onAmountChanged = onAmountChanged,
                            onCategoryChanged = onCategoryChanged,
                            onNoteChanged = onNoteChanged,
                            onSuggestedCategorySelected = onSuggestedCategorySelected,
                            onSaveClick = onSaveClick,
                            onCancelEditClick = onCancelEditClick,
                            onSaveTemplateClick = onSaveTemplateClick,
                            onScanReceiptClick = {
                                receiptLauncher.launch("image/*")
                            },
                            onEditClick = onEditClick,
                            onDeleteClick = onDeleteClick
                        )

                        LedgerBoard.STATS -> StatisticsBoard(
                            periodFilter = periodFilter,
                            typeFilter = typeFilter,
                            categoryFilter = categoryFilter.ifBlank { null },
                            categories = availableCategories,
                            filteredEntries = filteredEntries,
                            filteredSummary = filteredSummary,
                            trendPoints = trendPoints,
                            trendGranularity = trendGranularity,
                            selectedCategory = selectedStatsCategory,
                            detailEntries = statsDetailEntries,
                            onPeriodSelected = { selectedFilter ->
                                periodFilterName = selectedFilter.name
                            },
                            onTypeSelected = { selectedFilter ->
                                typeFilterName = selectedFilter.name
                            },
                            onCategorySelected = { selectedCategory ->
                                categoryFilter = selectedCategory.orEmpty()
                            },
                            onTrendGranularitySelected = { selectedGranularity ->
                                trendGranularityName = selectedGranularity.name
                            },
                            onChartCategorySelected = { selectedCategory ->
                                chartDetailCategory = if (chartDetailCategory == selectedCategory) {
                                    ""
                                } else {
                                    selectedCategory
                                }
                            },
                            onClearChartSelection = {
                                chartDetailCategory = ""
                            },
                            onEditClick = { entry ->
                                onEditClick(entry)
                                openBoard(LedgerBoard.DASHBOARD)
                            },
                            onDeleteClick = onDeleteClick
                        )

                        LedgerBoard.BUDGET -> BudgetBoard(
                            budgetConfig = uiState.budgetConfig,
                            currentMonthEntries = currentMonthEntries,
                            budgetCategories = budgetCategories,
                            selectedCategory = budgetCategory,
                            monthlyBudgetText = monthlyBudgetText,
                            categoryBudgetText = categoryBudgetText,
                            onMonthlyBudgetChanged = { value ->
                                monthlyBudgetText = value.filter { it.isDigit() || it == '.' }
                            },
                            onCategorySelected = { category ->
                                budgetCategory = category
                                categoryBudgetText = uiState.budgetConfig.categoryBudgets[category]?.toAmountInput().orEmpty()
                            },
                            onCategoryBudgetChanged = { value ->
                                categoryBudgetText = value.filter { it.isDigit() || it == '.' }
                            },
                            onSaveBudgetClick = {
                                onSaveBudgetClick(monthlyBudgetText, budgetCategory, categoryBudgetText)
                            }
                        )

                        LedgerBoard.TOOLS -> ToolsBoard(
                            templates = uiState.templates,
                            onApplyTemplateClick = { template ->
                                onApplyTemplateClick(template)
                                openBoard(LedgerBoard.DASHBOARD)
                            },
                            onDeleteTemplateClick = onDeleteTemplateClick,
                            onExportClick = {
                                exportLauncher.launch("jizhangmiao-backup-${LocalDate.now()}.json")
                            },
                            onImportClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain"))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardBoard(
    summary: LedgerSummary,
    budgetConfig: LedgerBudgetConfig,
    currentMonthSummary: LedgerSummary,
    topCategories: List<CategorySpend>,
    totalRecordCount: Int,
    recentEntries: List<LedgerEntry>,
    form: LedgerFormState,
    isReceiptScanning: Boolean,
    onTypeSelected: (LedgerEntryType) -> Unit,
    onAmountChanged: (String) -> Unit,
    onCategoryChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSuggestedCategorySelected: (String) -> Unit,
    onSaveClick: () -> Unit,
    onCancelEditClick: () -> Unit,
    onSaveTemplateClick: () -> Unit,
    onScanReceiptClick: () -> Unit,
    onEditClick: (LedgerEntry) -> Unit,
    onDeleteClick: (LedgerEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            LedgerHeroCard(
                summary = summary,
                budgetConfig = budgetConfig,
                currentMonthSummary = currentMonthSummary,
                recordCount = totalRecordCount,
                topCategories = topCategories
            )
        }

        item {
            EntryEditorSection(
                form = form,
                onTypeSelected = onTypeSelected,
                onAmountChanged = onAmountChanged,
                onCategoryChanged = onCategoryChanged,
                onNoteChanged = onNoteChanged,
                onSuggestedCategorySelected = onSuggestedCategorySelected,
                onSaveClick = onSaveClick,
                onCancelEditClick = onCancelEditClick,
                onSaveTemplateClick = onSaveTemplateClick,
                onScanReceiptClick = onScanReceiptClick,
                isReceiptScanning = isReceiptScanning
            )
        }

        item {
            SectionHeading(
                title = "\u6700\u8fd1\u8d26\u5355",
                subtitle = if (recentEntries.isEmpty()) {
                    "\u8fd8\u6ca1\u6709\u8bb0\u5f55\uff0c\u53ef\u4ee5\u5148\u5728\u4e0a\u65b9\u5feb\u901f\u8bb0\u4e00\u7b14"
                } else {
                    "\u6700\u8fd1 ${recentEntries.size} \u7b14\u8bb0\u5f55\uff0c\u53ef\u4ee5\u76f4\u63a5\u7f16\u8f91\u6216\u5220\u9664"
                }
            )
        }

        if (recentEntries.isEmpty()) {
            item {
                EmptyLedgerSection(
                    title = "\u9996\u9875\u8fd8\u6ca1\u6709\u7b14\u8bb0\u5f55",
                    subtitle = "\u5148\u4ece\u5feb\u901f\u8bb0\u8d26\u5f00\u59cb\uff0c\u4e4b\u540e\u8fd9\u91cc\u4f1a\u51fa\u73b0\u6700\u65b0\u8d26\u5355"
                )
            }
        } else {
            items(
                items = recentEntries,
                key = { entry -> entry.id }
            ) { entry ->
                LedgerEntryCard(
                    entry = entry,
                    onEditClick = { onEditClick(entry) },
                    onDeleteClick = { onDeleteClick(entry) }
                )
            }
        }
    }
}

@Composable
private fun StatisticsBoard(
    periodFilter: LedgerPeriodFilter,
    typeFilter: LedgerEntryFilterType,
    categoryFilter: String?,
    categories: List<String>,
    filteredEntries: List<LedgerEntry>,
    filteredSummary: LedgerSummary,
    trendPoints: List<TrendPoint>,
    trendGranularity: LedgerTrendGranularity,
    selectedCategory: String?,
    detailEntries: List<LedgerEntry>,
    onPeriodSelected: (LedgerPeriodFilter) -> Unit,
    onTypeSelected: (LedgerEntryFilterType) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onTrendGranularitySelected: (LedgerTrendGranularity) -> Unit,
    onChartCategorySelected: (String) -> Unit,
    onClearChartSelection: () -> Unit,
    onEditClick: (LedgerEntry) -> Unit,
    onDeleteClick: (LedgerEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            FilterSection(
                periodFilter = periodFilter,
                typeFilter = typeFilter,
                categoryFilter = categoryFilter,
                categories = categories,
                onPeriodSelected = onPeriodSelected,
                onTypeSelected = onTypeSelected,
                onCategorySelected = onCategorySelected
            )
        }

        item {
            StatsSection(
                summary = filteredSummary,
                filteredEntries = filteredEntries,
                trendPoints = trendPoints,
                trendGranularity = trendGranularity,
                selectedCategory = selectedCategory,
                onTrendGranularitySelected = onTrendGranularitySelected,
                onCategorySelected = onChartCategorySelected
            )
        }

        item {
            StatsDetailHeader(
                selectedCategory = selectedCategory,
                entryCount = detailEntries.size,
                onClearSelection = onClearChartSelection
            )
        }

        if (detailEntries.isEmpty()) {
            item {
                EmptyLedgerSection()
            }
        } else {
            items(
                items = detailEntries,
                key = { entry -> entry.id }
            ) { entry ->
                LedgerEntryCard(
                    entry = entry,
                    onEditClick = { onEditClick(entry) },
                    onDeleteClick = { onDeleteClick(entry) }
                )
            }
        }
    }
}

@Composable
private fun BudgetBoard(
    budgetConfig: LedgerBudgetConfig,
    currentMonthEntries: List<LedgerEntry>,
    budgetCategories: List<String>,
    selectedCategory: String,
    monthlyBudgetText: String,
    categoryBudgetText: String,
    onMonthlyBudgetChanged: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onCategoryBudgetChanged: (String) -> Unit,
    onSaveBudgetClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            BudgetSection(
                budgetConfig = budgetConfig,
                currentMonthEntries = currentMonthEntries,
                budgetCategories = budgetCategories,
                selectedCategory = selectedCategory,
                monthlyBudgetText = monthlyBudgetText,
                categoryBudgetText = categoryBudgetText,
                onMonthlyBudgetChanged = onMonthlyBudgetChanged,
                onCategorySelected = onCategorySelected,
                onCategoryBudgetChanged = onCategoryBudgetChanged,
                onSaveBudgetClick = onSaveBudgetClick
            )
        }
    }
}

@Composable
private fun ToolsBoard(
    templates: List<LedgerTemplate>,
    onApplyTemplateClick: (LedgerTemplate) -> Unit,
    onDeleteTemplateClick: (LedgerTemplate) -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            TemplateSection(
                templates = templates,
                onApplyTemplateClick = onApplyTemplateClick,
                onDeleteTemplateClick = onDeleteTemplateClick
            )
        }

        item {
            ToolSection(
                onExportClick = onExportClick,
                onImportClick = onImportClick
            )
        }
    }
}

@Composable
private fun DecorativeBackdrop() {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset(x = 240.dp, y = (-48).dp)
                .size(220.dp)
                .clip(CircleShape)
                .background(Color(0x332E8B57))
        )
        Box(
            modifier = Modifier
                .offset(x = (-56).dp, y = 320.dp)
                .size(180.dp)
                .clip(CircleShape)
                .background(Color(0x22C76B4B))
        )
    }
}

@Composable
private fun StatusBanner(
    message: String,
    onDismiss: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            TextButton(onClick = onDismiss) {
                Text("\u77e5\u9053\u4e86")
            }
        }
    }
}

@Composable
private fun LedgerHeroCard(
    summary: LedgerSummary,
    budgetConfig: LedgerBudgetConfig,
    currentMonthSummary: LedgerSummary,
    recordCount: Int,
    topCategories: List<CategorySpend>
) {
    val budgetRatio by animateFloatAsState(
        targetValue = budgetRatio(
            spentInCents = currentMonthSummary.expenseInCents,
            budgetInCents = budgetConfig.monthlyBudgetInCents
        ),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 260f),
        label = "budgetRatio"
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroShape,
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(HeroShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1F4037),
                            Color(0xFF2C5A4B),
                            Color(0xFF875B43)
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Surface(
                    color = Color.White.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = "\u8d26\u672c\u4eea\u8868\u76d8",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }

                Text(
                    text = formatCurrency(summary.balanceInCents),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White
                )

                Text(
                    text = "\u5f53\u524d\u7b5b\u9009\u4e0b\u5171 $recordCount \u7b14\u8bb0\u5f55\uff0c\u6536\u5165 ${formatCurrency(summary.incomeInCents)}\uff0c\u652f\u51fa ${formatCurrency(summary.expenseInCents)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        title = "\u672c\u6708\u9884\u7b97",
                        value = budgetConfig.monthlyBudgetInCents?.let(::formatCurrency) ?: "\u672a\u8bbe\u7f6e"
                    )
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        title = "\u672c\u6708\u5df2\u82b1",
                        value = formatCurrency(currentMonthSummary.expenseInCents)
                    )
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        title = "\u6700\u70ed\u5206\u7c7b",
                        value = topCategories.firstOrNull()?.category ?: "\u6682\u65e0"
                    )
                }

                Surface(
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "\u672c\u6708\u9884\u7b97\u4f7f\u7528 ${(budgetRatio * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        LinearProgressIndicator(
                            progress = { budgetRatio.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = Color(0xFFFFD7B7),
                            trackColor = Color.White.copy(alpha = 0.16f)
                        )
                        Text(
                            text = budgetInsightText(budgetConfig, currentMonthSummary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.86f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetric(
    modifier: Modifier = Modifier,
    title: String,
    value: String
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = Color.White
            )
        }
    }
}

@Composable
private fun FilterSection(
    periodFilter: LedgerPeriodFilter,
    typeFilter: LedgerEntryFilterType,
    categoryFilter: String?,
    categories: List<String>,
    onPeriodSelected: (LedgerPeriodFilter) -> Unit,
    onTypeSelected: (LedgerEntryFilterType) -> Unit,
    onCategorySelected: (String?) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeading(
                title = "\u7b5b\u9009\u548c\u89c2\u5bdf",
                subtitle = "\u6309\u65f6\u95f4\u3001\u6536\u652f\u7c7b\u578b\u548c\u5206\u7c7b\u770b\u4f60\u7684\u8d26\u672c"
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionEyebrow("\u65f6\u95f4")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LedgerPeriodFilter.entries.toList()) { filter ->
                        FilterChip(
                            selected = filter == periodFilter,
                            onClick = { onPeriodSelected(filter) },
                            label = {
                                Text(filter.displayName())
                            }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionEyebrow("\u7c7b\u578b")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LedgerEntryFilterType.entries.toList()) { filter ->
                        FilterChip(
                            selected = filter == typeFilter,
                            onClick = { onTypeSelected(filter) },
                            label = {
                                Text(filter.displayName())
                            }
                        )
                    }
                }
            }

            if (categories.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionEyebrow("\u5206\u7c7b")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = categoryFilter == null,
                                onClick = { onCategorySelected(null) },
                                label = {
                                    Text("\u5168\u90e8")
                                }
                            )
                        }
                        items(categories) { category ->
                            FilterChip(
                                selected = category == categoryFilter,
                                onClick = { onCategorySelected(category) },
                                label = {
                                    Text(category)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSection(
    summary: LedgerSummary,
    filteredEntries: List<LedgerEntry>,
    trendPoints: List<TrendPoint>,
    trendGranularity: LedgerTrendGranularity,
    selectedCategory: String?,
    onTrendGranularitySelected: (LedgerTrendGranularity) -> Unit,
    onCategorySelected: (String) -> Unit
) {
    val topCategories = remember(filteredEntries) {
        buildCategoryBreakdown(filteredEntries)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SectionHeading(
                title = "\u7edf\u8ba1\u89c6\u56fe",
                subtitle = "\u7528\u56fe\u8868\u5bf9\u6bd4\u8d26\u76ee\u91cd\u5fc3\uff0c\u8fd8\u53ef\u4ee5\u70b9\u51fb\u805a\u7126\u660e\u7ec6"
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    title = "\u7ed3\u4f59",
                    value = formatCurrency(summary.balanceInCents),
                    accentColor = MaterialTheme.colorScheme.primary
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    title = "\u8bb0\u5f55",
                    value = filteredEntries.size.toString(),
                    accentColor = MaterialTheme.colorScheme.secondary
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "\u70b9\u51fb\u4e0b\u65b9\u7684\u67f1\u72b6\u56fe\u3001\u997c\u56fe\u56fe\u4f8b\u6216\u6392\u540d\u6761\uff0c\u660e\u7ec6\u5217\u8868\u4f1a\u81ea\u52a8\u805a\u7126\u5230\u5bf9\u5e94\u5206\u7c7b\u3002",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionEyebrow("\u5206\u7c7b\u56fe\u8868")
                if (topCategories.isEmpty()) {
                    Text(
                        text = "\u5f53\u524d\u7b5b\u9009\u4e0b\u8fd8\u6ca1\u6709\u8db3\u591f\u7684\u652f\u51fa\u6570\u636e",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    CategoryPieChart(
                        topCategories = topCategories,
                        selectedCategory = selectedCategory,
                        onCategorySelected = onCategorySelected
                    )
                    CategoryBarChart(
                        topCategories = topCategories,
                        selectedCategory = selectedCategory,
                        onCategorySelected = onCategorySelected
                    )
                    topCategories.forEach { categorySpend ->
                        CategoryBreakdownRow(
                            categorySpend = categorySpend,
                            topAmount = topCategories.first().amountInCents,
                            selected = categorySpend.category == selectedCategory,
                            onClick = {
                                onCategorySelected(categorySpend.category)
                            }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionEyebrow("\u6536\u652f\u8d8b\u52bf")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LedgerTrendGranularity.entries.toList()) { granularity ->
                        FilterChip(
                            selected = granularity == trendGranularity,
                            onClick = {
                                onTrendGranularitySelected(granularity)
                            },
                            label = {
                                Text(granularity.displayName())
                            }
                        )
                    }
                }
                trendPoints.forEach { point ->
                    TrendRow(point = point)
                }
            }
        }
    }
}

@Composable
private fun CategoryPieChart(
    topCategories: List<CategorySpend>,
    selectedCategory: String?,
    onCategorySelected: (String) -> Unit
) {
    val totalAmount = remember(topCategories) {
        topCategories.sumOf { categorySpend -> categorySpend.amountInCents }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(220.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = size.minDimension * 0.18f
                    val diameter = size.minDimension - strokeWidth
                    val topLeft = Offset(
                        x = (size.width - diameter) / 2f,
                        y = (size.height - diameter) / 2f
                    )
                    val arcSize = Size(diameter, diameter)
                    var startAngle = -90f

                    topCategories.forEachIndexed { index, categorySpend ->
                        val sweepAngle = if (totalAmount <= 0L) {
                            0f
                        } else {
                            categorySpend.amountInCents.toFloat() / totalAmount.toFloat() * 360f
                        }

                        drawArc(
                            color = ChartPalette[index % ChartPalette.size],
                            startAngle = startAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth)
                        )
                        startAngle += sweepAngle
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "\u652f\u51fa\u603b\u989d",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatCurrency(totalAmount),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        color = ExpenseTint
                    )
                    Text(
                        text = "\u524d ${topCategories.size} \u4e2a\u5206\u7c7b",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(topCategories) { categorySpend ->
                val percent = if (totalAmount <= 0L) {
                    0
                } else {
                    (categorySpend.amountInCents * 100 / totalAmount).toInt()
                }
                FilterChip(
                    selected = categorySpend.category == selectedCategory,
                    onClick = {
                        onCategorySelected(categorySpend.category)
                    },
                    label = {
                        Text("${categorySpend.category} $percent%")
                    }
                )
            }
        }
    }
}

@Composable
private fun CategoryBarChart(
    topCategories: List<CategorySpend>,
    selectedCategory: String?,
    onCategorySelected: (String) -> Unit
) {
    val topAmount = remember(topCategories) {
        topCategories.maxOfOrNull { categorySpend -> categorySpend.amountInCents } ?: 0L
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            topCategories.forEachIndexed { index, categorySpend ->
                val isSelected = categorySpend.category == selectedCategory
                val ratio by animateFloatAsState(
                    targetValue = if (topAmount <= 0L) {
                        0f
                    } else {
                        categorySpend.amountInCents.toFloat() / topAmount.toFloat()
                    },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 280f),
                    label = "categoryBar${categorySpend.category}"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onCategorySelected(categorySpend.category)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatCurrency(categorySpend.amountInCents),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = ExpenseTint,
                        textAlign = TextAlign.Center
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((128.dp * ratio.coerceAtLeast(0.16f)).coerceAtLeast(24.dp))
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 8.dp, bottomEnd = 8.dp))
                            .background(
                                ChartPalette[index % ChartPalette.size].copy(
                                    alpha = if (isSelected) 1f else 0.72f
                                )
                            )
                    )
                    Text(
                        text = categorySpend.category,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) {
                            ExpenseTint
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accentColor: Color
) {
    Surface(
        modifier = modifier,
        color = accentColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = accentColor
            )
        }
    }
}

@Composable
private fun StatsDetailHeader(
    selectedCategory: String?,
    entryCount: Int,
    onClearSelection: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionHeading(
            title = if (selectedCategory == null) {
                "\u7b5b\u9009\u660e\u7ec6"
            } else {
                "$selectedCategory \u660e\u7ec6"
            },
            subtitle = if (selectedCategory == null) {
                "\u5f53\u524d\u7b5b\u9009\u4e0b\u5171 $entryCount \u7b14\uff0c\u70b9\u51fb\u56fe\u8868\u540e\u53ef\u6309\u5206\u7c7b\u805a\u7126"
            } else {
                "\u5f53\u524d\u805a\u7126\u5206\u7c7b\u4e0b\u5171 $entryCount \u7b14\uff0c\u53ef\u4ee5\u70b9\u51fb\u53f3\u4fa7\u6062\u590d\u5168\u90e8"
            }
        )
        if (selectedCategory != null) {
            TextButton(onClick = onClearSelection) {
                Text("\u67e5\u770b\u5168\u90e8")
            }
        }
    }
}

@Composable
private fun CategoryBreakdownRow(
    categorySpend: CategorySpend,
    topAmount: Long,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) {
            ExpenseTint.copy(alpha = 0.08f)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = categorySpend.category,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = formatCurrency(categorySpend.amountInCents),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    color = ExpenseTint
                )
            }
            LinearProgressIndicator(
                progress = {
                    if (topAmount <= 0L) 0f else categorySpend.amountInCents.toFloat() / topAmount.toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = ExpenseTint,
                trackColor = ExpenseTint.copy(alpha = 0.12f)
            )
        }
    }
}

@Composable
private fun TrendRow(point: TrendPoint) {
    val accentColor = if (point.netInCents >= 0L) IncomeTint else ExpenseTint
    val barRatio = remember(point.maxAbsoluteValue, point.netInCents) {
        if (point.maxAbsoluteValue <= 0L) {
            0f
        } else {
            point.netInCents.absoluteValue.toFloat() / point.maxAbsoluteValue.toFloat()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = point.label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (point.netInCents >= 0L) "+${formatCurrency(point.netInCents)}" else "-${formatCurrency(point.netInCents.absoluteValue)}",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = accentColor
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accentColor.copy(alpha = 0.1f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(barRatio)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accentColor)
            )
        }
    }
}

@Composable
private fun BudgetSection(
    budgetConfig: LedgerBudgetConfig,
    currentMonthEntries: List<LedgerEntry>,
    budgetCategories: List<String>,
    selectedCategory: String,
    monthlyBudgetText: String,
    categoryBudgetText: String,
    onMonthlyBudgetChanged: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onCategoryBudgetChanged: (String) -> Unit,
    onSaveBudgetClick: () -> Unit
) {
    val monthExpense = currentMonthEntries
        .filter { entry -> entry.type == LedgerEntryType.EXPENSE }
        .sumOf { entry -> entry.amountInCents }
    val categoryExpense = currentMonthEntries
        .filter { entry -> entry.type == LedgerEntryType.EXPENSE && entry.category == selectedCategory }
        .sumOf { entry -> entry.amountInCents }
    val monthlyBudget = budgetConfig.monthlyBudgetInCents
    val categoryBudget = budgetConfig.categoryBudgets[selectedCategory]

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SectionHeading(
                title = "\u9884\u7b97\u7ba1\u7406",
                subtitle = "\u53ef\u4ee5\u540c\u65f6\u8bbe\u7f6e\u6708\u603b\u9884\u7b97\u548c\u5206\u7c7b\u9884\u7b97"
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BudgetProgressCard(
                    modifier = Modifier.weight(1f),
                    title = "\u672c\u6708\u603b\u9884\u7b97",
                    spentInCents = monthExpense,
                    budgetInCents = monthlyBudget,
                    accentColor = ExpenseTint
                )
                BudgetProgressCard(
                    modifier = Modifier.weight(1f),
                    title = "$selectedCategory \u9884\u7b97",
                    spentInCents = categoryExpense,
                    budgetInCents = categoryBudget,
                    accentColor = MaterialTheme.colorScheme.primary
                )
            }

            OutlinedTextField(
                value = monthlyBudgetText,
                onValueChange = onMonthlyBudgetChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("\u6708\u5ea6\u603b\u9884\u7b97")
                },
                prefix = {
                    Text("\u00a5")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(20.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionEyebrow("\u5206\u7c7b\u9884\u7b97")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(budgetCategories) { category ->
                        FilterChip(
                            selected = category == selectedCategory,
                            onClick = { onCategorySelected(category) },
                            label = {
                                Text(category)
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = categoryBudgetText,
                onValueChange = onCategoryBudgetChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("$selectedCategory \u9884\u7b97")
                },
                prefix = {
                    Text("\u00a5")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(20.dp)
            )

            Button(
                onClick = onSaveBudgetClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("\u4fdd\u5b58\u9884\u7b97")
            }
        }
    }
}

@Composable
private fun BudgetProgressCard(
    modifier: Modifier = Modifier,
    title: String,
    spentInCents: Long,
    budgetInCents: Long?,
    accentColor: Color
) {
    val ratio = budgetRatio(spentInCents, budgetInCents)

    Surface(
        modifier = modifier,
        color = accentColor.copy(alpha = 0.08f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = budgetInCents?.let(::formatCurrency) ?: "\u672a\u8bbe\u7f6e",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = accentColor
            )
            LinearProgressIndicator(
                progress = { ratio.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.12f)
            )
            Text(
                text = if (budgetInCents == null) {
                    "\u5df2\u82b1 ${formatCurrency(spentInCents)}"
                } else {
                    "\u5df2\u82b1 ${formatCurrency(spentInCents)} / \u5269\u4f59 ${formatCurrency((budgetInCents - spentInCents).coerceAtLeast(0L))}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TemplateSection(
    templates: List<LedgerTemplate>,
    onApplyTemplateClick: (LedgerTemplate) -> Unit,
    onDeleteTemplateClick: (LedgerTemplate) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeading(
                title = "\u5feb\u6377\u6a21\u677f",
                subtitle = if (templates.isEmpty()) {
                    "\u5148\u4ece\u4e0b\u9762\u7684\u5f55\u5165\u533a\u4fdd\u5b58\u4e00\u4e2a\u5e38\u7528\u6a21\u677f"
                } else {
                    "\u4e00\u952e\u5957\u7528\u5e38\u7528\u8d26\u5355\uff0c\u9002\u5408\u65e9\u9910\u3001\u901a\u52e4\u3001\u5de5\u8d44\u7b49\u56fa\u5b9a\u573a\u666f"
                }
            )

            if (templates.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Text(
                        text = "\u6ca1\u6709\u6a21\u677f\u65f6\uff0c\u4f60\u6bcf\u6b21\u90fd\u9700\u8981\u624b\u52a8\u8f93\u5165\uff1b\u6709\u4e86\u6a21\u677f\uff0c\u5e38\u7528\u6d88\u8d39\u53ef\u4ee5\u4e00\u952e\u590d\u7528\u3002",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(templates, key = { template -> template.id }) { template ->
                        TemplateCard(
                            template = template,
                            onApplyClick = { onApplyTemplateClick(template) },
                            onDeleteClick = { onDeleteTemplateClick(template) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: LedgerTemplate,
    onApplyClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val accentColor = if (template.type == LedgerEntryType.INCOME) IncomeTint else ExpenseTint

    Surface(
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(24.dp),
        color = accentColor.copy(alpha = 0.08f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = template.title,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "${template.category} ${formatCurrency(template.amountInCents)}",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = accentColor
            )
            if (template.note.isNotBlank()) {
                Text(
                    text = template.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onApplyClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("\u5957\u7528")
                }
                OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("\u5220\u9664")
                }
            }
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
    onSaveClick: () -> Unit,
    onCancelEditClick: () -> Unit,
    onSaveTemplateClick: () -> Unit,
    onScanReceiptClick: () -> Unit,
    isReceiptScanning: Boolean
) {
    val accentColor = if (form.type == LedgerEntryType.INCOME) IncomeTint else ExpenseTint

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (form.isEditing) "\u7f16\u8f91\u8bb0\u5f55" else "\u5feb\u901f\u8bb0\u4e00\u7b14",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        text = if (form.isEditing) {
                            "\u4f60\u53ef\u4ee5\u76f4\u63a5\u4fee\u6539\u91d1\u989d\u3001\u5206\u7c7b\u3001\u5907\u6ce8\u548c\u7c7b\u578b"
                        } else {
                            "\u652f\u6301\u624b\u52a8\u5f55\u5165\u3001\u5957\u7528\u6a21\u677f\uff0c\u8fd8\u53ef\u4ee5\u8bc6\u522b\u5c0f\u7968"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = accentColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = form.type.displayName(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = accentColor
                    )
                }
            }

            AnimatedVisibility(visible = form.isEditing) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = "\u5f53\u524d\u662f\u7f16\u8f91\u6a21\u5f0f\uff0c\u4fdd\u5b58\u540e\u4f1a\u76f4\u63a5\u66f4\u65b0\u8fd9\u7b14\u8d26",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

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
                    Text(
                        text = "\u00a5",
                        color = accentColor
                    )
                },
                textStyle = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(20.dp)
            )

            OutlinedTextField(
                value = form.category,
                onValueChange = onCategoryChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("\u5206\u7c7b")
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionEyebrow("\u5feb\u6377\u5206\u7c7b")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categorySuggestionsFor(form.type)) { suggestion ->
                        FilterChip(
                            selected = suggestion == form.category,
                            onClick = {
                                onSuggestedCategorySelected(suggestion)
                            },
                            label = {
                                Text(suggestion)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accentColor.copy(alpha = 0.15f),
                                selectedLabelColor = accentColor
                            )
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
                maxLines = 3,
                shape = RoundedCornerShape(20.dp)
            )

            if (form.receiptText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "\u6700\u8fd1\u4e00\u6b21\u5c0f\u7968\u8bc6\u522b",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = form.receiptText.take(120),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "\u8f93\u5165\u4fe1\u606f\u540e\u53ef\u4ee5\u76f4\u63a5\u4fdd\u5b58\uff0c\u4e5f\u53ef\u4ee5\u987a\u624b\u5b58\u6210\u5feb\u6377\u6a21\u677f\u3002",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = form.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = form.errorMessage.orEmpty(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSaveClick,
                    modifier = Modifier.weight(1.2f),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text(if (form.isEditing) "\u4fdd\u5b58\u4fee\u6539" else "\u4fdd\u5b58\u8bb0\u5f55")
                }
                OutlinedButton(
                    onClick = onSaveTemplateClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("\u5b58\u4e3a\u6a21\u677f")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onScanReceiptClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        if (isReceiptScanning) "\u8bc6\u522b\u4e2d..." else "\u8bc6\u522b\u5c0f\u7968"
                    )
                }
                if (form.isEditing) {
                    OutlinedButton(
                        onClick = onCancelEditClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("\u53d6\u6d88\u7f16\u8f91")
                    }
                }
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
    val accentColor = if (type == LedgerEntryType.INCOME) IncomeTint else ExpenseTint

    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
        ) {
            Text(type.displayName())
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(type.displayName())
        }
    }
}

@Composable
private fun ToolSection(
    onExportClick: () -> Unit,
    onImportClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeading(
                title = "\u5907\u4efd\u548c\u8fc1\u79fb",
                subtitle = "\u53ef\u4ee5\u5bfc\u51fa JSON \u5907\u4efd\uff0c\u4e5f\u53ef\u4ee5\u518d\u5bfc\u5165\u5230\u540c\u4e00\u8bbe\u5907\u6216\u5176\u4ed6\u8bbe\u5907"
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onExportClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("\u5bfc\u51fa\u5907\u4efd")
                }
                OutlinedButton(
                    onClick = onImportClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("\u5bfc\u5165\u5907\u4efd")
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyLedgerSection(
    title: String = "\u8fd8\u6ca1\u6709\u7b26\u5408\u7b5b\u9009\u6761\u4ef6\u7684\u8bb0\u5f55",
    subtitle: String = "\u4f60\u53ef\u4ee5\u653e\u5bbd\u7b5b\u9009\uff0c\u6216\u8005\u5148\u65b0\u589e\u4e00\u7b14\u6536\u652f\u3002"
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = EntryShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = CircleShape
            ) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u00a5",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LedgerEntryCard(
    entry: LedgerEntry,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val accentColor = if (entry.type == LedgerEntryType.INCOME) IncomeTint else ExpenseTint

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = EntryShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accentColor.copy(alpha = 0.88f))
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = entry.category,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Surface(
                            color = accentColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = entry.type.displayName(),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = accentColor
                            )
                        }
                    }
                    Surface(
                        color = accentColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            text = formatSignedCurrency(entry),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = accentColor
                        )
                    }
                }

                if (entry.note.isNotBlank()) {
                    Text(
                        text = entry.note,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatEntryTime(entry.happenedAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onEditClick,
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(999.dp)
                                )
                        ) {
                            Text("\u7f16\u8f91")
                        }
                        TextButton(
                            onClick = onDeleteClick,
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(999.dp)
                                )
                        ) {
                            Text("\u5220\u9664")
                        }
                    }
                }
            }
        }
    }
}

private data class CategorySpend(
    val category: String,
    val amountInCents: Long
)

private data class TrendPoint(
    val label: String,
    val netInCents: Long,
    val maxAbsoluteValue: Long
)

private enum class LedgerBoard {
    DASHBOARD,
    STATS,
    BUDGET,
    TOOLS
}

private enum class LedgerTrendGranularity {
    WEEK,
    MONTH
}

private fun LedgerBoard.displayName(): String {
    return when (this) {
        LedgerBoard.DASHBOARD -> "\u9996\u9875"
        LedgerBoard.STATS -> "\u7edf\u8ba1"
        LedgerBoard.BUDGET -> "\u9884\u7b97"
        LedgerBoard.TOOLS -> "\u5de5\u5177"
    }
}

private fun LedgerBoard.subtitle(): String {
    return when (this) {
        LedgerBoard.DASHBOARD -> "\u4eea\u8868\u76d8\u3001\u5feb\u901f\u8bb0\u8d26\u548c\u6700\u8fd1\u8d26\u5355"
        LedgerBoard.STATS -> "\u7b5b\u9009\u3001\u56fe\u8868\u4e0e\u660e\u7ec6\u8054\u52a8\u90fd\u5728\u8fd9\u4e00\u9875"
        LedgerBoard.BUDGET -> "\u5355\u72ec\u7ba1\u7406\u6708\u9884\u7b97\u548c\u5206\u7c7b\u9884\u7b97"
        LedgerBoard.TOOLS -> "\u5feb\u6377\u6a21\u677f\u3001\u5907\u4efd\u5bfc\u5165\u5bfc\u51fa\u5168\u90e8\u5728\u8fd9\u91cc"
    }
}

private fun LedgerTrendGranularity.displayName(): String {
    return when (this) {
        LedgerTrendGranularity.WEEK -> "\u6309\u5468"
        LedgerTrendGranularity.MONTH -> "\u6309\u6708"
    }
}

private fun buildAvailableCategories(
    entries: List<LedgerEntry>,
    templates: List<LedgerTemplate>
): List<String> {
    return (entries.map { entry -> entry.category } + templates.map { template -> template.category })
        .filter { category -> category.isNotBlank() }
        .distinct()
        .sorted()
}

private fun buildCategoryBreakdown(entries: List<LedgerEntry>): List<CategorySpend> {
    return entries
        .filter { entry -> entry.type == LedgerEntryType.EXPENSE }
        .groupBy { entry -> entry.category }
        .map { (category, categoryEntries) ->
            CategorySpend(
                category = category,
                amountInCents = categoryEntries.sumOf { entry -> entry.amountInCents }
            )
        }
        .sortedByDescending { categorySpend -> categorySpend.amountInCents }
        .take(4)
}

private fun buildTrendPoints(
    entries: List<LedgerEntry>,
    granularity: LedgerTrendGranularity
): List<TrendPoint> {
    val zoneId = ZoneId.systemDefault()

    val points = when (granularity) {
        LedgerTrendGranularity.MONTH -> {
            val months = (0..5).map { offset ->
                YearMonth.now(zoneId).minusMonths(offset.toLong())
            }.reversed()

            months.map { month ->
                val monthEntries = entries.filter { entry ->
                    val entryMonth = Instant.ofEpochMilli(entry.happenedAt)
                        .atZone(zoneId)
                        .toLocalDate()
                        .let(YearMonth::from)
                    entryMonth == month
                }
                val summary = LedgerSummaryCalculator.calculate(monthEntries)
                TrendPoint(
                    label = "${month.monthValue}\u6708",
                    netInCents = summary.balanceInCents,
                    maxAbsoluteValue = 0L
                )
            }
        }

        LedgerTrendGranularity.WEEK -> {
            val today = LocalDate.now(zoneId)
            val currentWeekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val weeks = (0..7).map { offset ->
                currentWeekStart.minusWeeks(offset.toLong())
            }.reversed()

            weeks.map { weekStart ->
                val weekEnd = weekStart.plusDays(6)
                val weekEntries = entries.filter { entry ->
                    val entryDate = Instant.ofEpochMilli(entry.happenedAt)
                        .atZone(zoneId)
                        .toLocalDate()
                    !entryDate.isBefore(weekStart) && !entryDate.isAfter(weekEnd)
                }
                val summary = LedgerSummaryCalculator.calculate(weekEntries)
                TrendPoint(
                    label = "${weekStart.monthValue}/${weekStart.dayOfMonth}",
                    netInCents = summary.balanceInCents,
                    maxAbsoluteValue = 0L
                )
            }
        }
    }

    val maxAbsolute = points.maxOfOrNull { point -> point.netInCents.absoluteValue } ?: 0L
    return points.map { point ->
        point.copy(maxAbsoluteValue = maxAbsolute)
    }
}

private fun filterEntries(
    entries: List<LedgerEntry>,
    periodFilter: LedgerPeriodFilter,
    typeFilter: LedgerEntryFilterType,
    category: String?
): List<LedgerEntry> {
    val zoneId = ZoneId.systemDefault()
    val now = Instant.now().atZone(zoneId)
    val periodStart = when (periodFilter) {
        LedgerPeriodFilter.THIS_MONTH -> YearMonth.from(now).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        LedgerPeriodFilter.LAST_90_DAYS -> now.minusDays(89).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
        LedgerPeriodFilter.ALL -> null
    }

    return entries.filter { entry ->
        val matchesPeriod = periodStart == null || entry.happenedAt >= periodStart
        val matchesType = when (typeFilter) {
            LedgerEntryFilterType.ALL -> true
            LedgerEntryFilterType.EXPENSE -> entry.type == LedgerEntryType.EXPENSE
            LedgerEntryFilterType.INCOME -> entry.type == LedgerEntryType.INCOME
        }
        val matchesCategory = category == null || entry.category == category

        matchesPeriod && matchesType && matchesCategory
    }
}

private fun budgetRatio(
    spentInCents: Long,
    budgetInCents: Long?
): Float {
    if (budgetInCents == null || budgetInCents <= 0L) {
        return 0f
    }
    return (spentInCents.toFloat() / budgetInCents.toFloat()).coerceAtLeast(0f)
}

private fun budgetInsightText(
    budgetConfig: LedgerBudgetConfig,
    currentMonthSummary: LedgerSummary
): String {
    val monthlyBudget = budgetConfig.monthlyBudgetInCents
    if (monthlyBudget == null) {
        return "\u4f60\u8fd8\u6ca1\u8bbe\u7f6e\u672c\u6708\u603b\u9884\u7b97\uff0c\u53ef\u4ee5\u5728\u4e0b\u65b9\u5148\u8bbe\u5b9a\u4e00\u4e2a\u76ee\u6807\u989d\u5ea6\u3002"
    }

    val remaining = monthlyBudget - currentMonthSummary.expenseInCents
    return if (remaining >= 0L) {
        "\u8ddd\u79bb\u672c\u6708\u9884\u7b97\u8fd8\u6709 ${formatCurrency(remaining)} \u4f59\u91cf\uff0c\u53ef\u4ee5\u7ee7\u7eed\u7a33\u4f4f\u8282\u594f\u3002"
    } else {
        "\u672c\u6708\u5df2\u8d85\u51fa\u9884\u7b97 ${formatCurrency(remaining.absoluteValue)}\uff0c\u53ef\u4ee5\u4f18\u5148\u68c0\u67e5\u6392\u540d\u6700\u9ad8\u7684\u652f\u51fa\u5206\u7c7b\u3002"
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

private val Long.absoluteValue: Long
    get() = if (this < 0) -this else this
