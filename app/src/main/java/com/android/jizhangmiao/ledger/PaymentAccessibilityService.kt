package com.android.jizhangmiao.ledger

import android.accessibilityservice.AccessibilityService
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.android.jizhangmiao.ledger.data.LedgerStore
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PaymentAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastImportedSignature: String? = null
    private var lastImportedAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        importScreenIfMatched(
            packageName = rootInActiveWindow?.packageName?.toString().orEmpty(),
            sourceLabel = "\u9875\u9762\u8bc6\u522b",
            dedupeSeed = "connected:${System.currentTimeMillis() / DEDUPE_BUCKET_MILLIS}"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType !in SupportedEventTypes) {
            return
        }

        val packageName = event.packageName?.toString().orEmpty()
        if (packageName != WeChatPackageName && packageName != AlipayPackageName) {
            return
        }

        importScreenIfMatched(
            packageName = packageName,
            sourceLabel = "\u9875\u9762\u8bc6\u522b",
            dedupeSeed = buildString {
                append(event.className?.toString().orEmpty())
                append(':')
                append(System.currentTimeMillis() / DEDUPE_BUCKET_MILLIS)
            },
            event = event
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun importScreenIfMatched(
        packageName: String,
        sourceLabel: String,
        dedupeSeed: String,
        event: AccessibilityEvent? = null
    ) {
        if (packageName != WeChatPackageName && packageName != AlipayPackageName) {
            return
        }

        val mergedText = collectActiveWindowText(event) ?: return
        val happenedAt = System.currentTimeMillis()
        val candidate = parseAutoImportedEntry(
            packageName = packageName,
            mergedText = mergedText,
            dedupeSeed = dedupeSeed,
            happenedAt = happenedAt,
            sourceLabel = sourceLabel
        ) ?: return

        if (
            candidate.signature == lastImportedSignature &&
            happenedAt - lastImportedAt < DEDUPE_BUCKET_MILLIS
        ) {
            return
        }

        lastImportedSignature = candidate.signature
        lastImportedAt = happenedAt
        serviceScope.launch {
            LedgerStore.getInstance(applicationContext).importAutoEntry(candidate)
        }
    }

    private fun collectActiveWindowText(event: AccessibilityEvent?): String? {
        val rawTexts = mutableListOf<String>()
        event?.text
            ?.map(CharSequence::toString)
            ?.forEach(rawTexts::add)
        event?.contentDescription
            ?.toString()
            ?.let(rawTexts::add)

        val rootNode = rootInActiveWindow ?: return normalizeCollectedText(rawTexts).takeIf(String::isNotBlank)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(rootNode)
        var visitedCount = 0

        while (queue.isNotEmpty() && visitedCount < MAX_NODE_COUNT) {
            val node = queue.removeFirst()
            node.text?.toString()?.let(rawTexts::add)
            node.contentDescription?.toString()?.let(rawTexts::add)

            repeat(node.childCount) { index ->
                node.getChild(index)?.let(queue::addLast)
            }
            visitedCount += 1
        }

        return normalizeCollectedText(rawTexts).takeIf { merged ->
            merged.isNotBlank() && !TextUtils.isDigitsOnly(merged)
        }
    }

    private companion object {
        val SupportedEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        )

        const val MAX_NODE_COUNT = 240
        const val DEDUPE_BUCKET_MILLIS = 15_000L
    }
}
