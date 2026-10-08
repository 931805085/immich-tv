import java.util.Base64

// CI 发布时用 -PversionName=1.1.0 注入版本号；本地开发默认 1.0.0。
// versionCode 由版本号推导（major*10000 + minor*100 + patch），保证每次发版单调递增
// ——自动更新走系统安装器，versionCode 不比已装版本高会被判“降级”装不上。
val injectedVersionName: String? = (findProperty("versionName") as String?)?.trim()?.takeIf { it.isNotEmpty() }

fun versionCodeOf(version: String): Int {
    val parts = version.trim().removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 10000 + minor * 100 + patch
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zch.immich.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zch.immich.tv"
        minSdk = 24
        targetSdk = 36
        // 电视桌面（小米 tvhome）按 versionCode 缓存应用图标：
        // 版本不变时即使 adb install -r 重装也不会刷新，界面上仍是旧图标。
        // 换了图标资源必须一起抬版本号，否则用户看不到效果。
        // CI 发布经 -PversionName 注入版本号并推导 versionCode，本地开发固定 1 / 1.0.0。
        versionCode = if (injectedVersionName != null) versionCodeOf(injectedVersionName) else 1
        versionName = injectedVersionName ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            // 正式签名：keystore 以 base64 形式经环境变量注入（GitHub Actions Secrets）。
            // 本地不设这些变量时该配置为空——assembleRelease 会报缺 storeFile，这是预期行为；
            // assembleDebug 不受影响。
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }
            if (keystoreBase64 != null) {
                val storePass = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
                val alias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
                // JDK 9+ 默认 PKCS12 keystore 只有一道口令：KEY_PASSWORD 可留空，默认与 KEYSTORE_PASSWORD 相同
                val keyPass = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePass
                val keystoreFile = layout.buildDirectory.file("keystore/release.jks").get().asFile
                keystoreFile.parentFile?.mkdirs()
                keystoreFile.writeBytes(Base64.getMimeDecoder().decode(keystoreBase64))
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
                println("release 签名：已从环境变量加载 keystore")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // 单测里 android.util.Log 返回默认值而不是抛异常；
        // 避免日志调用把真实异常掩盖掉（GitHubUpdater 失败路径会打 Log.w）
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Compose for TV (tv-material / tv-foundation)
    implementation(libs.tv.material)
    implementation(libs.tv.foundation)

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Immich HTTP API
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Video playback (Media3 ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // 磁盘缓存（SimpleCache / CacheDataSource：视频流式落盘 + 预读共享缓存）
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)

    // 生成二维码（电视把内置服务器地址转成二维码给手机扫）
    implementation(libs.zxing.core)

    // 单元测试（验证内置接收服务器的 HTTP 路由）
    testImplementation(libs.junit)
}
