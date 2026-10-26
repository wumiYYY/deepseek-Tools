import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ---- 版本号：只改 versionName，versionCode 自动派生（规则见下），避免手改漏改 ----
// versionCode = major*10000 + minor*100 + patch，例如 1.2.0 → 10200。**只增不减**，
// 覆盖安装（小米/HyperOS 等）要求 versionCode >= 已装版本。
val versionNameValue = "1.3.2"
val versionCodeValue = versionNameValue.split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    major * 10000 + minor * 100 + patch
}

/**
 * 签名口令从 local.properties（已被 .gitignore 忽略）或环境变量读，
 * **不要把密码写回这个文件** —— 它会进 git。
 * 找不到口令时 release 不配置签名（会产出 unsigned APK，装不上，属于显式失败）。
 */
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.jingyuniang.ledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jingyuniang.vault"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionNameValue
        vectorDrawables { useSupportLibrary = true }
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
        // ML Kit 中文 OCR 的模型打在 .so 里，默认会为 4 个 ABI 各打一份（x86/x86_64 共 22MB，
        // 手机根本用不到）。只留 arm64-v8a：APK 从 ~56MB 降到 ~34MB。
        // ⚠️ 副作用：Android 模拟器（x86_64）上跑不了 OCR；要在模拟器调试就临时加回 "x86_64"。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/jingyuniang-release.jks")
            val storePwd = signingValue("WHALE_STORE_PASSWORD", "WHALE_STORE_PASSWORD")
            val keyPwd = signingValue("WHALE_KEY_PASSWORD", "WHALE_KEY_PASSWORD")
            val alias = signingValue("WHALE_KEY_ALIAS", "WHALE_KEY_ALIAS")
            if (ks.exists() && storePwd != null && keyPwd != null && alias != null) {
                storeFile = ks
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
            } else {
                logger.warn(
                    "[鲸鱼娘] 缺少签名配置（keystore 或 local.properties 里的 WHALE_* 口令），" +
                        "release 将产出 unsigned APK"
                )
            }
        }
    }

    buildTypes {
        release {
            // 关闭 R8：体积可控优先保证运行稳定（无设备可回归的情况下更安全）
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (rootProject.file("keystore/jingyuniang-release.jks").exists() &&
                signingValue("WHALE_STORE_PASSWORD", "WHALE_STORE_PASSWORD") != null
            ) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // JVM 单测里 android.jar 的 org.json 是空实现，需要真实实现才能测规则 JSON
    testImplementation("org.json:json:20231013")
}
