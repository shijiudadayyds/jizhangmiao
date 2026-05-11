package com.android.jizhangmiao.ledger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.android.jizhangmiao.ledger.data.LedgerAutomationTrace
import com.android.jizhangmiao.ledger.data.LedgerStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaymentAccessibilityService : AccessibilityService() {
    private data class TextSnapshot(
        val text: String,
        val rootCount: Int,
        val windowCount: Int
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastImportedSignature: String? = null
    private var lastImportedAt: Long = 0L
    private var lastDelayedScanKey: String = ""
    private var lastDelayedScanAt: Long = 0L
    private var lastScreenshotScanAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        resolveTargetPackageName(rootInActiveWindow?.packageName?.toString().orEmpty())?.let { packageName ->
            scanScreenIfMatched(
                packageName = packageName,
                sourceLabel = SOURCE_ACCESSIBILITY,
                event = null,
                allowScreenshotFallback = true
            )
            scheduleDelayedRootScan(packageName)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType !in SupportedEventTypes) {
            return
        }

        val packageName = resolveTargetPackageName(event.packageName?.toString().orEmpty()) ?: return
        scanScreenIfMatched(
            packageName = packageName,
            sourceLabel = SOURCE_ACCESSIBILITY,
            event = event,
            allowScreenshotFallback = false
        )
        scheduleDelayedRootScan(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun scheduleDelayedRootScan(packageName: String) {
        val now = System.currentTimeMillis()
        val scanKey = "$packageName:${now / DELAYED_SCAN_BUCKET_MILLIS}"
        if (scanKey == lastDelayedScanKey && now - lastDelayedScanAt < DELAYED_SCAN_BUCKET_MILLIS) {
            return
        }
        lastDelayedScanKey = scanKey
        lastDelayedScanAt = now

        DELAYED_SCAN_MILLIS.forEach { delayMillis ->
            mainHandler.postDelayed(
                {
                    val resolvedPackageName = resolveTargetPackageName(packageName) ?: return@postDelayed
                    scanScreenIfMatched(
                        packageName = resolvedPackageName,
                        sourceLabel = SOURCE_ACCESSIBILITY,
                        event = null,
                        allowScreenshotFallback = true
                    )
                },
                delayMillis
            )
        }
    }

    private fun scanScreenIfMatched(
        packageName: String,
        sourceLabel: String,
        event: AccessibilityEvent?,
        allowScreenshotFallback: Boolean
    ) {
        val happenedAt = System.currentTimeMillis()
        val snapshot = collectActiveWindowText(event)
        if (snapshot.text.isBlank()) {
            recordTrace(
                sourceLabel = sourceLabel,
                summary = "\u5df2\u6536\u5230\u9875\u9762\u4e8b\u4ef6\uff0c\u4f46\u5f53\u524d\u6ca1\u8bfb\u5230\u53ef\u7528\u6587\u5b57\uff08root=${snapshot.rootCount}, window=${snapshot.windowCount}\uff09",
                rawText = "",
                happenedAt = happenedAt
            )
            if (allowScreenshotFallback) {
                tryImportFromScreenshot(packageName, happenedAt)
            }
            return
        }

        val analysis = analyzeAutoImportedEntry(
            packageName = packageName,
            mergedText = snapshot.text,
            dedupeSeed = stableSeedFor(packageName, snapshot.text),
            happenedAt = happenedAt,
            sourceLabel = sourceLabel
        )
        val candidate = analysis.candidate
        if (candidate == null) {
            recordTrace(
                sourceLabel = sourceLabel,
                summary = "${analysis.statusSummary}\uff08root=${snapshot.rootCount}, window=${snapshot.windowCount}\uff09",
                rawText = analysis.mergedText,
                happenedAt = happenedAt
            )
            if (allowScreenshotFallback) {
                tryImportFromScreenshot(packageName, happenedAt)
            }
            return
        }

        importCandidate(
            analysis = analysis,
            sourceLabel = sourceLabel,
            happenedAt = happenedAt
        )
    }

    private fun importCandidate(
        analysis: AutoImportAnalysis,
        sourceLabel: String,
        happenedAt: Long
    ) {
        val candidate = analysis.candidate ?: return
        if (
            candidate.signature == lastImportedSignature &&
            happenedAt - lastImportedAt < DEDUPE_BUCKET_MILLIS
        ) {
            return
        }

        lastImportedSignature = candidate.signature
        lastImportedAt = happenedAt
        serviceScope.launch {
            val store = LedgerStore.getInstance(applicationContext)
            val imported = store.importAutoEntry(candidate)
            store.recordAutomationTrace(
                LedgerAutomationTrace(
                    sourceLabel = sourceLabel,
                    summary = if (imported) {
                        "${analysis.statusSummary} / \u5df2\u8fdb\u5165\u5ba1\u6838\u7bb1"
                    } else {
                        "${analysis.statusSummary} / \u91cd\u590d\u6216\u65e0\u9700\u5ba1\u6838"
                    },
                    rawText = analysis.mergedText,
                    happenedAt = happenedAt
                )
            )
        }
    }

    private fun collectActiveWindowText(event: AccessibilityEvent?): TextSnapshot {
        val rawTexts = mutableListOf<String>()
        var rootCount = 0
        var windowCount = 0

        event?.text
            ?.map(CharSequence::toString)
            ?.forEach(rawTexts::add)
        event?.contentDescription
            ?.toString()
            ?.let(rawTexts::add)

        traverseNodeTree(event?.source, rawTexts)?.let { rootCount += it }
        traverseNodeTree(rootInActiveWindow, rawTexts)?.let { rootCount += it }

        runCatching { windows }
            .getOrDefault(emptyList())
            .sortedByDescending { window -> window.layer }
            .forEach { window ->
                val root = window.root ?: return@forEach
                if (resolveTargetPackageName(root.packageName?.toString().orEmpty()) == null) {
                    return@forEach
                }
                windowCount += 1
                traverseNodeTree(root, rawTexts)?.let { rootCount += it }
            }

        return TextSnapshot(
            text = normalizeCollectedText(rawTexts),
            rootCount = rootCount,
            windowCount = windowCount
        )
    }

    private fun traverseNodeTree(
        rootNode: AccessibilityNodeInfo?,
        rawTexts: MutableList<String>
    ): Int? {
        if (rootNode == null) {
            return null
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(rootNode)
        var visitedCount = 0

        while (queue.isNotEmpty() && visitedCount < MAX_NODE_COUNT) {
            val node = queue.removeFirst()
            collectNodeText(node, rawTexts)

            repeat(node.childCount) { index ->
                node.getChild(index)?.let(queue::addLast)
            }
            visitedCount += 1
        }
        return visitedCount
    }

    private fun collectNodeText(
        node: AccessibilityNodeInfo,
        rawTexts: MutableList<String>
    ) {
        node.text?.toString()?.let(rawTexts::add)
        node.contentDescription?.toString()?.let(rawTexts::add)

        node.hintText?.toString()?.let(rawTexts::add)
        node.paneTitle?.toString()?.let(rawTexts::add)
        node.tooltipText?.toString()?.let(rawTexts::add)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.let(rawTexts::add)
        }
    }

    private fun resolveTargetPackageName(packageName: String): String? {
        if (packageName == WeChatPackageName || packageName == AlipayPackageName) {
            return packageName
        }

        val rootPackageName = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (rootPackageName == WeChatPackageName || rootPackageName == AlipayPackageName) {
            return rootPackageName
        }

        return runCatching {
            windows
                .asSequence()
                .mapNotNull { window -> window.root?.packageName?.toString() }
                .firstOrNull { candidate ->
                    candidate == WeChatPackageName || candidate == AlipayPackageName
                }
        }.getOrNull()
    }

    private fun tryImportFromScreenshot(
        packageName: String,
        happenedAt: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastScreenshotScanAt < SCREENSHOT_SCAN_INTERVAL_MILLIS) {
            return
        }
        lastScreenshotScanAt = now

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    handleScreenshot(packageName, happenedAt, screenshot)
                }

                override fun onFailure(errorCode: Int) {
                    recordTrace(
                        sourceLabel = SOURCE_SCREENSHOT,
                        summary = "\u9875\u9762\u6587\u672c\u8bc6\u522b\u5931\u8d25\uff0c\u622a\u56fe OCR \u4e5f\u672a\u80fd\u542f\u52a8\uff08error=$errorCode\uff09",
                        rawText = "",
                        happenedAt = System.currentTimeMillis()
                    )
                }
            }
        )
    }

    @SuppressLint("NewApi")
    private fun handleScreenshot(
        packageName: String,
        happenedAt: Long,
        screenshot: ScreenshotResult
    ) {
        val bitmap = Bitmap.wrapHardwareBuffer(
            screenshot.hardwareBuffer,
            screenshot.colorSpace
        )?.copy(Bitmap.Config.ARGB_8888, false)
        screenshot.hardwareBuffer.close()

        if (bitmap == null) {
            recordTrace(
                sourceLabel = SOURCE_SCREENSHOT,
                summary = "\u9875\u9762\u6587\u672c\u8bc6\u522b\u5931\u8d25\uff0c\u622a\u56fe OCR \u6ca1\u6709\u62ff\u5230\u56fe\u50cf",
                rawText = "",
                happenedAt = System.currentTimeMillis()
            )
            return
        }

        serviceScope.launch {
            val recognizedText = runCatching {
                recognizeScreenshotText(bitmap)
            }.getOrDefault("")
            bitmap.recycle()

            if (recognizedText.isBlank()) {
                recordTrace(
                    sourceLabel = SOURCE_SCREENSHOT,
                    summary = "\u9875\u9762\u6587\u672c\u8bc6\u522b\u5931\u8d25\uff0c\u622a\u56fe OCR \u6ca1\u8bfb\u5230\u53ef\u7528\u6587\u5b57",
                    rawText = "",
                    happenedAt = System.currentTimeMillis()
                )
                return@launch
            }

            val analysis = analyzeAutoImportedEntry(
                packageName = packageName,
                mergedText = recognizedText,
                dedupeSeed = stableSeedFor(packageName, recognizedText),
                happenedAt = happenedAt,
                sourceLabel = SOURCE_SCREENSHOT
            )
            if (analysis.candidate == null) {
                recordTrace(
                    sourceLabel = SOURCE_SCREENSHOT,
                    summary = analysis.statusSummary,
                    rawText = analysis.mergedText,
                    happenedAt = System.currentTimeMillis()
                )
                return@launch
            }

            importCandidate(
                analysis = analysis,
                sourceLabel = SOURCE_SCREENSHOT,
                happenedAt = happenedAt
            )
        }
    }

    private suspend fun recognizeScreenshotText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )

        try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
        } finally {
            recognizer.close()
        }
    }

    private fun recordTrace(
        sourceLabel: String,
        summary: String,
        rawText: String,
        happenedAt: Long
    ) {
        serviceScope.launch {
            LedgerStore.getInstance(applicationContext).recordAutomationTrace(
                LedgerAutomationTrace(
                    sourceLabel = sourceLabel,
                    summary = summary,
                    rawText = rawText,
                    happenedAt = happenedAt
                )
            )
        }
    }

    private fun stableSeedFor(
        packageName: String,
        text: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)
        return "a11y:$packageName:$digest"
    }

    private companion object {
        const val SOURCE_ACCESSIBILITY = "\u9875\u9762\u8bc6\u522b"
        const val SOURCE_SCREENSHOT = "\u9875\u9762\u622a\u56fe OCR"
        val SupportedEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        )
        val DELAYED_SCAN_MILLIS = listOf(250L, 900L)

        const val MAX_NODE_COUNT = 600
        const val DEDUPE_BUCKET_MILLIS = 15_000L
        const val DELAYED_SCAN_BUCKET_MILLIS = 1_500L
        const val SCREENSHOT_SCAN_INTERVAL_MILLIS = 5_000L
    }
}
