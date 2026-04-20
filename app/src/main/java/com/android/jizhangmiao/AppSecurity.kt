package com.android.jizhangmiao

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Debug
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal data class SecurityVerdict(
    val isTrusted: Boolean,
    val reason: String? = null
)

internal fun containsSuspiciousMarker(
    content: String,
    markers: List<String>
): Boolean {
    val normalized = content.lowercase(Locale.US)
    return markers.any { marker -> normalized.contains(marker) }
}

internal fun decodeObfuscatedHex(
    hex: String,
    key: Int = OBFUSCATION_KEY
): String {
    if (hex.isBlank()) {
        return ""
    }

    val bytes = hex.chunked(2)
        .map { chunk -> ((chunk.toInt(16) xor key) and 0xFF).toByte() }
        .toByteArray()

    return bytes.toString(Charsets.UTF_8)
}

internal object AppSecurity {
    fun evaluate(context: Context): SecurityVerdict {
        if (!context.resources.getBoolean(R.bool.security_checks_enabled)) {
            return SecurityVerdict(isTrusted = true)
        }

        if (isDebuggable(context) || Debug.isDebuggerConnected() || Debug.waitingForDebugger() || tracerPid() > 0) {
            return SecurityVerdict(
                isTrusted = false,
                reason = "\u68c0\u6d4b\u5230\u8c03\u8bd5\u6216\u9644\u52a0\u73af\u5883"
            )
        }

        if (hasHookingFrameworkClasses() || hasSuspiciousRuntimeArtifacts()) {
            return SecurityVerdict(
                isTrusted = false,
                reason = "\u68c0\u6d4b\u5230 Hook \u6216\u6ce8\u5165\u75d5\u8ff9"
            )
        }

        val expectedDigest = decodeObfuscatedHex(
            context.getString(R.string.release_cert_sha256_obfuscated)
        )
            .filter { char -> char.isLetterOrDigit() }
            .uppercase(Locale.US)

        if (expectedDigest.isNotBlank()) {
            val actualDigests = signingCertificateDigests(context)
            if (expectedDigest !in actualDigests) {
                return SecurityVerdict(
                    isTrusted = false,
                    reason = "\u68c0\u6d4b\u5230\u5b89\u88c5\u5305\u7b7e\u540d\u5f02\u5e38"
                )
            }
        }

        return SecurityVerdict(isTrusted = true)
    }

    private fun isDebuggable(context: Context): Boolean {
        return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(context: Context): Set<String> {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signatures = packageInfo.signingInfo?.let { signingInfo ->
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        }.orEmpty()

        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
        }.toSet()
    }

    private fun tracerPid(): Int {
        return runCatching {
            File("/proc/self/status")
                .useLines { lines ->
                    lines.firstOrNull { line -> line.startsWith("TracerPid:") }
                }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: 0
        }.getOrDefault(0)
    }

    private fun hasHookingFrameworkClasses(): Boolean {
        return suspiciousHookClassNames().any { className ->
            runCatching {
                Class.forName(className)
            }.isSuccess
        }
    }

    private fun hasSuspiciousRuntimeArtifacts(): Boolean {
        val mapsContent = runCatching {
            File("/proc/self/maps").readText()
        }.getOrDefault("")

        if (containsSuspiciousMarker(mapsContent, suspiciousRuntimeMarkers())) {
            return true
        }

        return suspiciousFridaPaths().any { path ->
            runCatching {
                File(path).exists()
            }.getOrDefault(false)
        }
    }
}

private const val OBFUSCATION_KEY = 0x5A

private fun suspiciousHookClassNames(): List<String> = listOf(
    "3E3F742835382C743B343E2835333E74222A35293F3E74022A35293F3E1828333E3D3F",
    "39353774293B2F28333174292F38292E283B2E3F741709",
    "39353774292D333C2E74293B343E3235353174093B343E12353531"
).map(::decodeObfuscatedHex)

private fun suspiciousRuntimeMarkers(): List<String> = listOf(
    "3C28333E3B",
    "3633383C28333E3B",
    "3C28333E3B773B3D3F342E",
    "222A35293F3E",
    "36292A35293F3E",
    "3F3E222A",
    "292F38292E283B2E3F",
    "293B343E32353531"
).map(::decodeObfuscatedHex)

private fun suspiciousFridaPaths(): List<String> = listOf(
    "753E3B2E3B753635393B36752E372A753C28333E3B77293F282C3F28",
    "753E3B2E3B753635393B36752E372A75283F743C28333E3B74293F282C3F28",
    "753E3B2E3B753635393B36752E372A753C28333E3B773B3D3F342E",
    "753E3B2E3B753635393B36752E372A753C296C6E",
    "753E3B2E3B753635393B36752E372A753C296968"
).map(::decodeObfuscatedHex)

@Composable
internal fun SecurityBlockedScreen(reason: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "\u5b89\u5168\u6821\u9a8c\u672a\u901a\u8fc7",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "\u8bf7\u4f7f\u7528\u672a\u88ab\u4fee\u6539\u7684 release \u5b89\u88c5\u5305\u91cd\u65b0\u5b89\u88c5\u3002",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
