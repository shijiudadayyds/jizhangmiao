import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun certificateSha256Hex(
    storeFile: File?,
    storePassword: String?,
    keyAlias: String?,
    keyPassword: String?
): String {
    if (
        storeFile == null ||
        !storeFile.exists() ||
        storePassword.isNullOrBlank() ||
        keyAlias.isNullOrBlank()
    ) {
        return ""
    }

    return runCatching {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        FileInputStream(storeFile).use { input ->
            keyStore.load(input, storePassword.toCharArray())
        }
        val certificate = keyStore.getCertificate(keyAlias) ?: return ""
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02X".format(byte) }
    }.getOrDefault("")
}

fun xorObfuscateHex(
    value: String,
    key: Int = 0x5A
): String {
    return value.encodeToByteArray()
        .joinToString("") { byte -> "%02X".format((byte.toInt() xor key) and 0xFF) }
}

android {
    namespace = "com.android.jizhangmiao"
    compileSdk = 36
    val buildTime = ZonedDateTime.now(ZoneId.of("Asia/Hong_Kong"))
    val generatedVersionCode = buildTime.toEpochSecond().toInt()
    val generatedVersionName = buildTime.format(
        DateTimeFormatter.ofPattern("'1.0.'yyyyMMdd'.'HHmmss")
    )

    val releaseSigning = signingConfigs.getByName("debug")
    val releaseCertSha256Obfuscated = xorObfuscateHex(
        certificateSha256Hex(
            storeFile = releaseSigning.storeFile,
            storePassword = releaseSigning.storePassword,
            keyAlias = releaseSigning.keyAlias,
            keyPassword = releaseSigning.keyPassword
        )
    )

    defaultConfig {
        applicationId = "com.android.jizhangmiao"
        minSdk = 28
        targetSdk = 36
        versionCode = generatedVersionCode
        versionName = generatedVersionName
        resValue("bool", "security_checks_enabled", "false")
        resValue("string", "release_cert_sha256_obfuscated", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = releaseSigning
            resValue("bool", "security_checks_enabled", "true")
            resValue("string", "release_cert_sha256_obfuscated", "\"$releaseCertSha256Obfuscated\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = false
        resValues = true
    }

    androidResources {
        localeFilters += listOf("en", "zh-rCN", "zh-rHK", "zh-rTW")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.mlkit.text.recognition.chinese)

    testImplementation(libs.junit)
}
