package com.android.jizhangmiao

import com.android.jizhangmiao.ledger.LedgerSummaryCalculator
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun summaryCalculator_sumsIncomeExpenseAndBalance() {
        val summary = LedgerSummaryCalculator.calculate(
            listOf(
                LedgerEntry(
                    type = LedgerEntryType.INCOME,
                    amountInCents = 15_000L,
                    category = "工资"
                ),
                LedgerEntry(
                    type = LedgerEntryType.EXPENSE,
                    amountInCents = 2_599L,
                    category = "餐饮"
                ),
                LedgerEntry(
                    type = LedgerEntryType.EXPENSE,
                    amountInCents = 1_201L,
                    category = "交通"
                )
            )
        )

        assertEquals(15_000L, summary.incomeInCents)
        assertEquals(3_800L, summary.expenseInCents)
        assertEquals(11_200L, summary.balanceInCents)
    }

    @Test
    fun summaryCalculator_returnsZeroForEmptyLedger() {
        val summary = LedgerSummaryCalculator.calculate(emptyList())

        assertEquals(0L, summary.incomeInCents)
        assertEquals(0L, summary.expenseInCents)
        assertEquals(0L, summary.balanceInCents)
    }
}
