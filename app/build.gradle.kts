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
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
