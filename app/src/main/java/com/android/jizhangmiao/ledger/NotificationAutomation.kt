package com.android.jizhangmiao.ledger

import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.sanitizeAmountInput
import com.android.jizhangmiao.ledger.data.toAmountInCents
import java.security.MessageDigest
import java.util.Locale

internal const val WeChatPackageName = "com.tencent.mm"
internal const val AlipayPackageName = "com.eg.android.AlipayGphone"

internal data class NotificationAutomationStatus(
    val notificationAccessEnabled: Boolean,
    val isWeChatInstalled: Boolean,
    val isAlipayInstalled: Boolean
)

internal data class AutoImportedEntry(
    val signature: String,
    val type: LedgerEntryType,
    val amountInCents: Long,
    val account: String,
    val category: String,
    val note: String,
    val receiptText: String,
    val happenedAt: Long
)

private val incomeKeywords = listOf(
    "收款成功",
    "成功收款",
    "收款到账",
    "到账提醒",
    "到账通知",
    "二维码收款",
    "支付宝到账",
    "微信支付收款",
    "已收款"
)

private val expenseKeywords = listOf(
    "支付成功",
    "付款成功",
    "支付了",
    "付款了",
    "已支付",
    "向商家付款",
    "消费支出",
    "微信支付",
    "支付宝支付",
    "付款码"
)

private val amountPatterns = listOf(
    Regex("""[￥¥]\s*([0-9]+(?:\.[0-9]{1,2})?)"""),
    Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元""")
)

internal fun queryNotificationAutomationStatus(context: Context): NotificationAutomationStatus {
    return NotificationAutomationStatus(
        notificationAccessEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName),
        isWeChatInstalled = canLaunchPackage(context, WeChatPackageName),
        isAlipayInstalled = canLaunchPackage(context, AlipayPackageName)
    )
}

internal fun openNotificationAutomationSettings(context: Context) {
    val detailIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, PaymentNotificationListenerService::class.java).flattenToString()
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else {
        null
    }

    val fallbackIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching {
        if (detailIntent != null) {
            context.startActivity(detailIntent)
        } else {
            context.startActivity(fallbackIntent)
        }
    }.recoverCatching {
        context.startActivity(fallbackIntent)
    }.recoverCatching {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.getOrElse { throwable ->
        if (throwable !is ActivityNotFoundException) {
            throw throwable
        }
    }
}

internal fun launchExternalPaymentApp(
    context: Context,
    packageName: String
): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
    }.isSuccess
}

internal fun parseAutoImportedEntry(sbn: StatusBarNotification): AutoImportedEntry? {
    val packageName = sbn.packageName
    if (packageName != WeChatPackageName && packageName != AlipayPackageName) {
        return null
    }

    val notification = sbn.notification ?: return null
    val mergedText = collectNotificationText(notification)
    if (mergedText.isBlank()) {
        return null
    }

    val type = detectEntryType(mergedText) ?: return null
    val amountInCents = extractAmountInCents(mergedText) ?: return null
    val sourceName = if (packageName == WeChatPackageName) "微信支付" else "支付宝"
    val account = if (packageName == WeChatPackageName) "微信" else "支付宝"
    val happenedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
    val normalizedText = mergedText
        .replace(Regex("""\s+"""), " ")
        .trim()
    val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)
        ?.toString()
        ?.trim()
        .orEmpty()

    return AutoImportedEntry(
        signature = sha256Hex(
            listOf(
                packageName,
                sbn.key,
                type.name,
                amountInCents.toString(),
                normalizedText.lowercase(Locale.ROOT)
            ).joinToString("|")
        ),
        type = type,
        amountInCents = amountInCents,
        account = account,
        category = detectCategory(type, normalizedText),
        note = buildString {
            append("自动记账：")
            append(sourceName)
            if (title.isNotBlank()) {
                append(" / ")
                append(title.take(24))
            }
        },
        receiptText = normalizedText.take(400),
        happenedAt = happenedAt
    )
}

private fun canLaunchPackage(
    context: Context,
    packageName: String
): Boolean {
    return context.packageManager.getLaunchIntentForPackage(packageName) != null
}

private fun collectNotificationText(notification: Notification): String {
    val extras = notification.extras
    val textLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        ?.joinToString("\n") { item -> item.toString() }
        .orEmpty()

    return listOfNotNull(
        extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        notification.tickerText?.toString(),
        textLines.takeIf { it.isNotBlank() }
    ).map { item ->
        item.replace(Regex("""\s+"""), " ").trim()
    }.filter { item ->
        item.isNotBlank()
    }.distinct().joinToString("\n")
}

private fun detectEntryType(content: String): LedgerEntryType? {
    return when {
        incomeKeywords.any(content::contains) -> LedgerEntryType.INCOME
        expenseKeywords.any(content::contains) -> LedgerEntryType.EXPENSE
        else -> null
    }
}

private fun detectCategory(
    type: LedgerEntryType,
    content: String
): String {
    val keywordMap = when (type) {
        LedgerEntryType.EXPENSE -> expenseCategoryKeywordMap
        LedgerEntryType.INCOME -> incomeCategoryKeywordMap
    }

    return keywordMap.entries.firstOrNull { (_, keywords) ->
        keywords.any(content::contains)
    }?.key ?: defaultCategoryFor(type)
}

private fun extractAmountInCents(content: String): Long? {
    return amountPatterns
        .flatMap { pattern ->
            pattern.findAll(content).mapNotNull { match ->
                match.groupValues
                    .drop(1)
                    .firstOrNull { value -> value.isNotBlank() }
                    ?.let(::sanitizeAmountInput)
                    ?.toAmountInCents()
            }.toList()
        }
        .maxOrNull()
}

private fun sha256Hex(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte ->
            "%02x".format(byte)
        }
}
