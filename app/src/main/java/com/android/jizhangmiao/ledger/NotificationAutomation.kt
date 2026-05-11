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
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.android.jizhangmiao.ledger.data.LedgerEntryType
import com.android.jizhangmiao.ledger.data.sanitizeAmountInput
import com.android.jizhangmiao.ledger.data.toAmountInCents
import com.android.jizhangmiao.ledger.data.toAmountInput
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

internal const val WeChatPackageName = "com.tencent.mm"
internal const val AlipayPackageName = "com.eg.android.AlipayGphone"

internal data class NotificationAutomationStatus(
    val notificationAccessEnabled: Boolean,
    val accessibilityAccessEnabled: Boolean,
    val isWeChatInstalled: Boolean,
    val isAlipayInstalled: Boolean
)

internal data class AutoImportAnalysis(
    val candidate: AutoImportedEntry?,
    val mergedText: String,
    val statusSummary: String
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

private data class AmountCandidate(
    val amountInCents: Long,
    val score: Int
)

private val incomeKeywords = listOf(
    "\u6536\u6b3e\u6210\u529f",
    "\u6210\u529f\u6536\u6b3e",
    "\u6536\u6b3e\u5230\u8d26",
    "\u6536\u94b1\u5230\u8d26",
    "\u5df2\u6536\u94b1",
    "\u6536\u6b3e\u5df2\u5165\u8d26",
    "\u5165\u8d26\u6210\u529f",
    "\u5df2\u5165\u8d26",
    "\u5230\u8d26\u901a\u77e5",
    "\u5230\u8d26\u63d0\u9192",
    "\u4e8c\u7ef4\u7801\u6536\u6b3e",
    "\u652f\u4ed8\u5b9d\u5230\u8d26",
    "\u5fae\u4fe1\u652f\u4ed8\u6536\u6b3e",
    "\u5df2\u6536\u6b3e",
    "\u9000\u6b3e\u6210\u529f",
    "\u9000\u6b3e\u5230\u8d26",
    "\u7ea2\u5305\u6536\u5165",
    "moneyreceived",
    "paymentreceived"
)

private val expenseKeywords = listOf(
    "\u652f\u4ed8\u6210\u529f",
    "\u4ed8\u6b3e\u6210\u529f",
    "\u4ea4\u6613\u6210\u529f",
    "\u6263\u6b3e\u6210\u529f",
    "\u6d88\u8d39\u6210\u529f",
    "\u5df2\u652f\u4ed8",
    "\u5df2\u4ed8\u6b3e",
    "\u5411\u5546\u5bb6\u4ed8\u6b3e",
    "\u6d88\u8d39\u652f\u51fa",
    "\u4ed8\u6b3e\u7801",
    "\u4ed8\u6b3e\u65b9\u5f0f",
    "\u652f\u4ed8\u65b9\u5f0f",
    "\u4ed8\u6b3e\u91d1\u989d",
    "\u8ba2\u5355\u91d1\u989d",
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
    "\u4ea4\u6613",
    "\u8ba2\u5355",
    "\u91d1\u989d",
    "\u5b9e\u4ed8",
    "\u6263\u6b3e",
    "payment",
    "received"
)).distinct()

private val explicitAmountPatterns = listOf(
    Regex(
        """(?:[\uFFE5\u00A5]|RMB|CNY)\s*([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    ),
    Regex("""([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*(?:\u5143|\u5706)""")
)

private val standaloneAmountPattern = Regex(
    """(?<![\d.,])([0-9]{1,6}(?:,[0-9]{3})*(?:\.[0-9]{1,2}))(?![\d.,])"""
)

private val amountContextKeywords = listOf(
    "\u91d1\u989d",
    "\u5b9e\u4ed8",
    "\u652f\u4ed8",
    "\u4ed8\u6b3e",
    "\u6536\u6b3e",
    "\u6536\u94b1",
    "\u5230\u8d26",
    "\u9000\u6b3e",
    "\u5408\u8ba1",
    "\u603b\u8ba1",
    "\u4ea4\u6613",
    "\u8ba2\u5355",
    "amount",
    "total"
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

    return analyzeAutoImportedEntry(
        packageName = sbn.packageName,
        mergedText = collectNotificationText(notification),
        dedupeSeed = sbn.key,
        happenedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
        sourceLabel = "\u901a\u77e5",
        title = title
    ).candidate
}

internal fun parseAutoImportedEntry(
    packageName: String,
    mergedText: String,
    dedupeSeed: String,
    happenedAt: Long,
    sourceLabel: String,
    title: String = ""
): AutoImportedEntry? {
    return analyzeAutoImportedEntry(
        packageName = packageName,
        mergedText = mergedText,
        dedupeSeed = dedupeSeed,
        happenedAt = happenedAt,
        sourceLabel = sourceLabel,
        title = title
    ).candidate
}

internal fun analyzeAutoImportedEntry(
    packageName: String,
    mergedText: String,
    dedupeSeed: String,
    happenedAt: Long,
    sourceLabel: String,
    title: String = ""
): AutoImportAnalysis {
    if (packageName != WeChatPackageName && packageName != AlipayPackageName) {
        return AutoImportAnalysis(
            candidate = null,
            mergedText = "",
            statusSummary = "\u5ffd\u7565\u4e86\u975e\u5fae\u4fe1/\u652f\u4ed8\u5b9d\u4e8b\u4ef6"
        )
    }

    val normalizedReceiptText = normalizeCollectedText(
        mergedText.lineSequence().toList()
    )
    if (normalizedReceiptText.isBlank()) {
        return AutoImportAnalysis(
            candidate = null,
            mergedText = "",
            statusSummary = buildTraceSummary(sourceNameFor(packageName), sourceLabel, "\u5df2\u6355\u83b7\u5230\u4e8b\u4ef6\uff0c\u4f46\u6ca1\u8bfb\u5230\u53ef\u7528\u6587\u5b57")
        )
    }

    val normalizedContent = normalizeForMatching(normalizedReceiptText)
    val sourceName = sourceNameFor(packageName)
    val account = if (packageName == WeChatPackageName) {
        "\u5fae\u4fe1"
    } else {
        "\u652f\u4ed8\u5b9d"
    }
    val type = detectEntryType(normalizedContent)
    if (type == null) {
        return AutoImportAnalysis(
            candidate = null,
            mergedText = normalizedReceiptText.take(400),
            statusSummary = buildTraceSummary(sourceName, sourceLabel, "\u5df2\u8bfb\u5230\u9875\u9762/\u901a\u77e5\uff0c\u4f46\u6ca1\u5224\u65ad\u51fa\u6536\u5165\u6216\u652f\u51fa")
        )
    }
    val amountInCents = extractAmountInCents(normalizedReceiptText)
    if (amountInCents == null) {
        return AutoImportAnalysis(
            candidate = null,
            mergedText = normalizedReceiptText.take(400),
            statusSummary = buildTraceSummary(sourceName, sourceLabel, "\u5df2\u5224\u65ad\u51fa${type.displayName()}\uff0c\u4f46\u6ca1\u8bc6\u522b\u5230\u91d1\u989d")
        )
    }

    val candidate = AutoImportedEntry(
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

    return AutoImportAnalysis(
        candidate = candidate,
        mergedText = normalizedReceiptText.take(400),
        statusSummary = buildTraceSummary(
            sourceName,
            sourceLabel,
            "\u5df2\u8bc6\u522b\u4e3a${type.displayName()} ${amountInCents.toAmountInput()}"
        )
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

internal fun collectNotificationText(notification: Notification): String {
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

private fun sourceNameFor(packageName: String): String {
    return if (packageName == WeChatPackageName) {
        "\u5fae\u4fe1\u652f\u4ed8"
    } else {
        "\u652f\u4ed8\u5b9d"
    }
}

internal fun normalizeCollectedText(rawTexts: Iterable<String>): String {
    return rawTexts
        .map { text -> text.replace(Regex("""\s+"""), " ").trim() }
        .filter { text -> text.isNotBlank() && !isDigitsOnly(text) }
        .distinct()
        .joinToString("\n")
}

private fun isDigitsOnly(text: CharSequence): Boolean {
    return text.isNotEmpty() && text.all(Char::isDigit)
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
        (normalizedContent.contains("\u6536\u6b3e") && !normalizedContent.contains("\u6536\u6b3e\u65b9")) ||
            normalizedContent.contains("\u5230\u8d26") ||
            normalizedContent.contains("\u5df2\u5165\u8d26") -> {
            LedgerEntryType.INCOME
        }
        normalizedContent.contains("\u652f\u4ed8") ||
            normalizedContent.contains("\u4ed8\u6b3e") ||
            normalizedContent.contains("\u6d88\u8d39") ||
            normalizedContent.contains("\u652f\u51fa") ||
            normalizedContent.contains("\u6263\u6b3e") ||
            normalizedContent.contains("\u4ea4\u6613\u6210\u529f") -> {
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
    val lines = mergeSplitCurrencyLines(
        content
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
    )
    val prioritizedLineIndexes = lines.indices.filter { index ->
        val normalizedLine = normalizeForMatching(lines[index])
        transactionKeywords.any(normalizedLine::contains)
    }

    val contextualAmounts = prioritizedLineIndexes
        .asSequence()
        .flatMap { center ->
            contextualLineIndexes(center, lines.indices)
                .asSequence()
                .flatMap { index ->
                    extractAmountsAroundLine(lines, index)
                        .asSequence()
                        .map { amountInCents ->
                            AmountCandidate(
                                amountInCents = amountInCents,
                                score = scoreAmountCandidate(
                                    lines = lines,
                                    index = index,
                                    distance = abs(index - center),
                                    amountInCents = amountInCents
                                )
                            )
                        }
                }
        }
        .toList()

    if (contextualAmounts.isNotEmpty()) {
        return contextualAmounts
            .maxWithOrNull(
                compareBy<AmountCandidate> { candidate -> candidate.score }
                    .thenBy { candidate -> candidate.amountInCents }
            )
            ?.amountInCents
    }

    return lines
        .asSequence()
        .flatMap { line -> extractAmountsFromLine(line, allowStandalone = false).asSequence() }
        .firstOrNull()
}

private fun contextualLineIndexes(
    center: Int,
    validRange: IntRange
): List<Int> {
    return listOf(center, center + 1, center - 1, center + 2, center - 2, center + 3, center - 3)
        .filter { index -> index in validRange }
        .distinct()
}

private fun mergeSplitCurrencyLines(lines: List<String>): List<String> {
    val mergedLines = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val nextLine = lines.getOrNull(index + 1)
        if (
            nextLine != null &&
            isCurrencyOnlyLine(line) &&
            standaloneAmountPattern.find(nextLine) != null
        ) {
            mergedLines += "$line$nextLine"
            index += 2
        } else {
            mergedLines += line
            index += 1
        }
    }
    return mergedLines
}

private fun extractAmountsAroundLine(
    lines: List<String>,
    index: Int
): List<Long> {
    val line = lines[index]
    val candidates = mutableListOf<Long>()
    candidates += extractAmountsFromLine(line, allowStandalone = hasAmountContext(line))

    if (index + 1 in lines.indices) {
        candidates += extractAmountsFromLine(
            "${line}${lines[index + 1]}",
            allowStandalone = false
        )
    }

    if (index - 1 in lines.indices) {
        candidates += extractAmountsFromLine(
            "${lines[index - 1]}${line}",
            allowStandalone = false
        )
    }

    if (candidates.isNotEmpty()) {
        return candidates
    }

    val neighborHasAmountContext = (index - 1 in lines.indices && hasAmountContext(lines[index - 1])) ||
        (index + 1 in lines.indices && hasAmountContext(lines[index + 1])) ||
        (index - 1 in lines.indices && hasCurrencyMarker(lines[index - 1])) ||
        (index + 1 in lines.indices && hasCurrencyMarker(lines[index + 1]))
    return extractAmountsFromLine(line, allowStandalone = neighborHasAmountContext)
}

private fun extractAmountsFromLine(
    line: String,
    allowStandalone: Boolean
): List<Long> {
    val explicitAmounts = explicitAmountPatterns.flatMap { pattern ->
        pattern.findAll(line).mapNotNull { match ->
            match.groupValues
                .drop(1)
                .firstOrNull { value -> value.isNotBlank() }
                ?.replace(",", "")
                ?.let(::sanitizeAmountInput)
                ?.toAmountInCents()
                ?.takeIf(::isLikelyPaymentAmount)
        }.toList()
    }

    if (explicitAmounts.isNotEmpty() || !allowStandalone) {
        return explicitAmounts
    }

    return standaloneAmountPattern.findAll(line)
        .mapNotNull { match ->
            match.groupValues
                .getOrNull(1)
                ?.replace(",", "")
                ?.let(::sanitizeAmountInput)
                ?.toAmountInCents()
                ?.takeIf(::isLikelyPaymentAmount)
        }
        .toList()
}

private fun hasAmountContext(line: String): Boolean {
    val normalizedLine = normalizeForMatching(line)
    return amountContextKeywords.any { keyword ->
        normalizedLine.contains(normalizeForMatching(keyword))
    }
}

private fun hasCurrencyMarker(line: String): Boolean {
    return line.contains('\uFFE5') ||
        line.contains('\u00A5') ||
        line.contains("RMB", ignoreCase = true) ||
        line.contains("CNY", ignoreCase = true) ||
        line.contains("\u5143") ||
        line.contains("\u5706")
}

private fun isLikelyPaymentAmount(amountInCents: Long): Boolean {
    return amountInCents in 1..20_000_000
}

private fun scoreAmountCandidate(
    lines: List<String>,
    index: Int,
    distance: Int,
    amountInCents: Long
): Int {
    val line = lines[index]
    var score = 100 - distance * 20
    if (hasAmountContext(line)) {
        score += 40
    }
    if (hasCurrencyMarker(line)) {
        score += 30
    }
    if (index - 1 in lines.indices && hasAmountContext(lines[index - 1])) {
        score += 25
    }
    if (index + 1 in lines.indices && hasAmountContext(lines[index + 1])) {
        score += 25
    }
    if (index - 1 in lines.indices && isCurrencyOnlyLine(lines[index - 1])) {
        score += 80
    }
    if (index + 1 in lines.indices && isCurrencyOnlyLine(lines[index + 1])) {
        score += 80
    }
    if (amountInCents >= 100L) {
        score += 10
    }
    return score
}

private fun isCurrencyOnlyLine(line: String): Boolean {
    val normalizedLine = line.trim()
    return normalizedLine == "\uFFE5" ||
        normalizedLine == "\u00A5" ||
        normalizedLine.equals("RMB", ignoreCase = true) ||
        normalizedLine.equals("CNY", ignoreCase = true)
}

private fun sha256Hex(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte ->
            "%02x".format(byte)
        }
}

private fun buildTraceSummary(
    sourceName: String,
    sourceLabel: String,
    message: String
): String {
    return "$sourceName / $sourceLabel / $message"
}
