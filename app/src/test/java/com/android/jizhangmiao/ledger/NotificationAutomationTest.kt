package com.android.jizhangmiao.ledger

import com.android.jizhangmiao.ledger.data.LedgerEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAutomationTest {
    @Test
    fun parseAutoImportedEntry_detectsExpenseFromNearbyTransactionLine() {
        val entry = parseAutoImportedEntry(
            packageName = WeChatPackageName,
            mergedText = """
                服务提醒 ¥0.01
                支付成功
                ¥18.80
                便利店
            """.trimIndent(),
            dedupeSeed = "wx-nearby-amount",
            happenedAt = 1_710_000_000_000L,
            sourceLabel = "通知",
            title = "微信零钱"
        )

        assertNotNull(entry)
        assertEquals(LedgerEntryType.EXPENSE, entry?.type)
        assertEquals(1_880L, entry?.amountInCents)
        assertEquals("微信", entry?.account)
        assertEquals("日用", entry?.category)
        assertTrue(entry?.note?.contains("自动记账：微信支付 / 通知 / 微信零钱") == true)
    }

    @Test
    fun parseAutoImportedEntry_detectsAlipayRefundIncome() {
        val entry = parseAutoImportedEntry(
            packageName = AlipayPackageName,
            mergedText = """
                支付宝到账提醒
                退款到账
                ￥25.00
                淘宝订单退款
            """.trimIndent(),
            dedupeSeed = "alipay-refund",
            happenedAt = 1_710_000_123_000L,
            sourceLabel = "通知",
            title = "支付宝消息"
        )

        assertNotNull(entry)
        assertEquals(LedgerEntryType.INCOME, entry?.type)
        assertEquals(2_500L, entry?.amountInCents)
        assertEquals("支付宝", entry?.account)
        assertEquals("退款", entry?.category)
        assertEquals(1_710_000_123_000L, entry?.happenedAt)
    }

    @Test
    fun analyzeAutoImportedEntry_ignoresUnsupportedPackage() {
        val analysis = analyzeAutoImportedEntry(
            packageName = "com.example.other",
            mergedText = "支付成功 ￥18.80",
            dedupeSeed = "unsupported",
            happenedAt = 123L,
            sourceLabel = "通知"
        )

        assertNull(analysis.candidate)
        assertEquals("忽略了非微信/支付宝事件", analysis.statusSummary)
        assertEquals("", analysis.mergedText)
    }

    @Test
    fun analyzeAutoImportedEntry_reportsMissingAmount() {
        val analysis = analyzeAutoImportedEntry(
            packageName = WeChatPackageName,
            mergedText = """
                微信支付
                支付成功
                便利店
            """.trimIndent(),
            dedupeSeed = "missing-amount",
            happenedAt = 456L,
            sourceLabel = "页面识别"
        )

        assertNull(analysis.candidate)
        assertTrue(analysis.statusSummary.contains("没识别到金额"))
        assertTrue(analysis.mergedText.contains("支付成功"))
    }

    @Test
    fun normalizeCollectedText_filtersNumericOnlyBlankAndDuplicates() {
        val normalized = normalizeCollectedText(
            listOf(
                "  ",
                "123456",
                " 微信支付 ",
                "微信支付",
                " 支付成功 ",
                "￥18.80"
            )
        )

        assertEquals(
            """
                微信支付
                支付成功
                ￥18.80
            """.trimIndent(),
            normalized
        )
    }
}
