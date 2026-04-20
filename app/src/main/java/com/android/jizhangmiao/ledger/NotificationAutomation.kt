package com.android.jizhangmiao.ledger

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
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
    val accessibilityAccessEnabled: Boolean,
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
    "\u6536\u6b3e\u6210\u529f",
    "\u6210\u529f\u6536\u6b3e",
    "\u6536\u6b3e\u5230\u8d26",
    "\u5230\u8d26\u901a\u77e5",
    "\u5230\u8d26\u63d0\u9192",
    "\u4e8c\u7ef4\u7801\u6536\u6b3e",
    "\u652f\u4ed8\u5b9d\u5230\u8d26",
    "\u5fae\u4fe1\u652f\u4ed8\u6536\u6b3e",
    "\u5df2\u6536\u6b3e",
    "moneyreceived",
    "paymentreceived"
)

private val expenseKeywords = listOf(
    "\u652f\u4ed8\u6210\u529f",
    "\u4ed8\u6b3e\u6210\u529f",
    "\u5df2\u652f\u4ed8",
    "\u5df2\u4ed8\u6b3e",
    "\u5411\u5546\u5bb6\u4ed8\u6b3e",
    "\u6d88\u8d39\u652f\u51fa",
    "\u4ed8\u6b3e\u7801",
    "\u5b9e\u4ed8",
    "\u5fae\u4fe1\u652f\u4ed8",
    "\u652f\u4ed8\u5b9d\u652f\u4ed8",
    "paymentsuccessful",
    "paidsuccessfully"
)

private val transactionKeywords = (incomeKeywords + expenseKeywords + listOf(
    "\u652f\u4ed8",
    "\u4ed8\u6b3e",
    "\u6536\u6b3e",
    "\u5230\u8d26",
    "\u6d88\u8d39",
    "payment",
    "received"
)).distinct()

private val amountPatterns = listOf(
    Regex(
        """(?:[\uFFE5\u00A5]|RMB|CNY)\s*([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    ),
    Regex("""([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*(?:\u5143|\u5706)""")
)

internal fun queryNotificationAutomationStatus(context: Context): NotificationAutomationStatus {
    return NotificationAutomationStatus(
        notificationAccessEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName),
        accessibilityAccessEnabled = isAccessibilityAutomationEnabled(context),
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

internal fun openAccessibilityAutomationSettings(context: Context) {
    val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching {
        context.startActivity(accessibilityIntent)
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
    val notification = sbn.notification ?: return null
    val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)
        ?.toString()
        ?.trim()
        .orEmpty()

    return parseAutoImportedEntry(
        packageName = sbn.packageName,
        mergedText = collectNotificationText(notification),
        dedupeSeed = sbn.key,
        happenedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
        sourceLabel = "\u901a\u77e5",
        title = title
    )
}

internal fun parseAutoImportedEntry(
    packageName: String,
    mergedText: String,
    dedupeSeed: String,
    happenedAt: Long,
    sourceLabel: String,
    title: String = ""
): AutoImportedEntry? {
    if (packageName != WeChatPackageName && packageName != AlipayPackageName) {
        return null
    }

    val normalizedReceiptText = normalizeCollectedText(
        mergedText.lineSequence().toList()
    )
    if (normalizedReceiptText.isBlank()) {
        return null
    }

    val normalizedContent = normalizeForMatching(normalizedReceiptText)
    val type = detectEntryType(normalizedContent) ?: return null
    val amountInCents = extractAmountInCents(normalizedReceiptText) ?: return null
    val sourceName = if (packageName == WeChatPackageName) {
        "\u5fae\u4fe1\u652f\u4ed8"
    } else {
        "\u652f\u4ed8\u5b9d"
    }
    val account = if (packageName == WeChatPackageName) {
        "\u5fae\u4fe1"
    } else {
        "\u652f\u4ed8\u5b9d"
    }

    return AutoImportedEntry(
        signature = sha256Hex(
            listOf(
                packageName,
                dedupeSeed,
                type.name,
                amountInCents.toString(),
                normalizedContent
            ).joinToString("|")
        ),
        type = type,
        amountInCents = amountInCents,
        account = account,
        category = detectCategory(type, normalizedContent),
        note = buildString {
            append("\u81ea\u52a8\u8bb0\u8d26\uff1a")
            append(sourceName)
            append(" / ")
            append(sourceLabel)
            if (title.isNotBlank()) {
                append(" / ")
                append(title.take(24))
            }
        },
        receiptText = normalizedReceiptText.take(400),
        happenedAt = happenedAt
    )
}

private fun canLaunchPackage(
    context: Context,
    packageName: String
): Boolean {
    return context.packageManager.getLaunchIntentForPackage(packageName) != null
}

private fun isAccessibilityAutomationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    val expectedComponent = ComponentName(context, PaymentAccessibilityService::class.java)

    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { serviceInfo ->
            val service = serviceInfo.resolveInfo?.serviceInfo ?: return@any false
            val serviceClassName = if (service.name.startsWith(".")) {
                service.packageName + service.name
            } else {
                service.name
            }
            ComponentName(service.packageName, serviceClassName) == expectedComponent
        }
}

private fun collectNotificationText(notification: Notification): String {
    val extras = notification.extras
    val rawTexts = mutableListOf<String>()

    listOfNotNull(
        extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        notification.tickerText?.toString()
    ).forEach(rawTexts::add)

    extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        ?.map(CharSequence::toString)
        ?.forEach(rawTexts::add)

    extras?.keySet()?.forEach { key ->
        extras.getCharSequence(key)?.let { value ->
            rawTexts += value.toString()
        }
        extras.getCharSequenceArray(key)
            ?.map(CharSequence::toString)
            ?.forEach(rawTexts::add)
    }

    return normalizeCollectedText(rawTexts)
}

internal fun normalizeCollectedText(rawTexts: Iterable<String>): String {
    return rawTexts
        .map { text -> text.replace(Regex("""\s+"""), " ").trim() }
        .filter { text -> text.isNotBlank() && !TextUtils.isDigitsOnly(text) }
        .distinct()
        .joinToString("\n")
}

private fun normalizeForMatching(content: String): String {
    return content.lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), "")
        .replace(":", "")
        .replace("\uff1a", "")
}

private fun detectEntryType(normalizedContent: String): LedgerEntryType? {
    return when {
        incomeKeywords.any(normalizedContent::contains) -> LedgerEntryType.INCOME
        expenseKeywords.any(normalizedContent::contains) -> LedgerEntryType.EXPENSE
        normalizedContent.contains("\u6536\u6b3e") || normalizedContent.contains("\u5230\u8d26") -> {
            LedgerEntryType.INCOME
        }
        normalizedContent.contains("\u652f\u4ed8") ||
            normalizedContent.contains("\u4ed8\u6b3e") ||
            normalizedContent.contains("\u6d88\u8d39") ||
            normalizedContent.contains("\u652f\u51fa") -> {
            LedgerEntryType.EXPENSE
        }
        else -> null
    }
}

private fun detectCategory(
    type: LedgerEntryType,
    normalizedContent: String
): String {
    val keywordMap = when (type) {
        LedgerEntryType.EXPENSE -> expenseCategoryKeywordMap
        LedgerEntryType.INCOME -> incomeCategoryKeywordMap
    }

    return keywordMap.entries.firstOrNull { (_, keywords) ->
        keywords.map(::normalizeForMatching).any(normalizedContent::contains)
    }?.key ?: defaultCategoryFor(type)
}

private fun extractAmountInCents(content: String): Long? {
    val lines = content
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
    val prioritizedLines = lines.filter { line ->
        val normalizedLine = normalizeForMatching(line)
        transactionKeywords.any(normalizedLine::contains)
    }

    return (prioritizedLines + lines)
        .asSequence()
        .flatMap { line -> extractAmountsFromLine(line).asSequence() }
        .firstOrNull()
}

private fun extractAmountsFromLine(line: String): List<Long> {
    return amountPatterns.flatMap { pattern ->
        pattern.findAll(line).mapNotNull { match ->
            match.groupValues
                .drop(1)
                .firstOrNull { value -> value.isNotBlank() }
                ?.replace(",", "")
                ?.let(::sanitizeAmountInput)
                ?.toAmountInCents()
        }.toList()
    }
}

private fun sha256Hex(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte ->
            "%02x".format(byte)
        }
}
