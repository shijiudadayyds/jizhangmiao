package com.android.jizhangmiao

import com.android.jizhangmiao.ledger.LedgerSummaryCalculator
import com.android.jizhangmiao.ledger.parseReceiptText
import com.android.jizhangmiao.ledger.data.LedgerEntry
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun summaryCalculator_sumsIncomeExpenseAndBalance() {
        val summary = LedgerSummaryCalculator.calculate(
            listOf(
                LedgerEntry(
                    type = LedgerEntryType.INCOME,
                    amountInCents = 15_000L,
                    category = "\u5de5\u8d44"
                ),
                LedgerEntry(
                    type = LedgerEntryType.EXPENSE,
                    amountInCents = 2_599L,
                    category = "\u9910\u996e"
                ),
                LedgerEntry(
                    type = LedgerEntryType.EXPENSE,
                    amountInCents = 1_201L,
                    category = "\u4ea4\u901a"
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

    @Test
    fun parseReceiptText_extractsAmountCategoryAndType() {
        val result = parseReceiptText(
            "\u4fbf\u5229\u5e97\n" +
                "\u5546\u54c1A 12.50\n" +
                "\u5408\u8ba1 18.80\n" +
                "\u5fae\u4fe1\u652f\u4ed8"
        )

        assertNotNull(result)
        assertEquals("18.8", result?.amountInput)
        assertEquals("\u65e5\u7528", result?.category)
        assertEquals(LedgerEntryType.EXPENSE, result?.type)
    }
}
