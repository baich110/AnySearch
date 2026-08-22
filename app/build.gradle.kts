// AnySearch APP - v0.3.0 美化升级
// AGP 9.3.0 / Gradle 9.6.1 / Kotlin 2.4.10 / compileSdk 37

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.anysearch.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    signingConfigs {
        create("release") {
            // 签名信息从环境变量读取（本地/CI 注入），避免密码硬编码泄露
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "../release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "anysearch"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    defaultConfig {
        applicationId = "com.anysearch.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            // 开启代码混淆 + 资源压缩（R8）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Compose（BOM 统管版本）-> 2026.08.00，核心模块 1.12（Mesh渐变/广色域HDR）
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // 网络 - OkHttp 5.4.0（官方协程扩展模块 executeAsync/awaitResult）
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-coroutines:5.4.0")

    // JSON序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Markdown渲染
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.43.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.43.0")

    // 图片加载 - Coil3（3.5.0 已是最新）
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    // 导航
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // WebView
    implementation("androidx.webkit:webkit:1.17.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Material 动态配色
    implementation("com.google.android.material:material:1.14.0")
}
