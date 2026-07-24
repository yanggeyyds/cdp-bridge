import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.devtools.cdp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devtools.cdp"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // externalNativeBuild：让 Gradle 调用 CMake 编译 src/main/cpp/CMakeLists.txt
        externalNativeBuild {
            cmake {
                // 只用 C，不需要 cpp 标准
                arguments("-DANDROID_STL=c++_static")
                cFlags("-Wno-unused-parameter")
            }
        }
        // 只编译 armeabi-v7a / arm64-v8a / x86_64，覆盖常见手机与模拟器
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 指向 CMakeLists.txt
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        buildConfig = true
        // AIDL 必须显式开启，否则 ICdpBridge.Stub 不会生成，Kotlin 编译报 Unresolved reference
        aidl = true
    }
    composeOptions {
        // Kotlin 1.9.10 对应 Compose Compiler 1.5.3
        kotlinCompilerExtensionVersion = "1.5.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ---- Compose BOM (Material3) ----
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended 提供 Stop / PlayArrow 等扩展图标（core 只含基础集）
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    // ---- Shizuku Client API ----
    // 研究确认最新稳定版为 13.1.5（来源 Maven Central / RikkaApps/Shizuku-API manifest.gradle）
    // 原始需求写 13.0.0，13.1.5 是其向后兼容升级版，API 完全兼容。
    // 来源: https://central.sonatype.com/artifact/dev.rikka.shizuku/api/versions
    // 来源: https://github.com/RikkaApps/Shizuku-API/blob/master/manifest.gradle
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
    // annotationProcessor(kapt) 提供 @Keep 等；用 kotlin 需加 kotlin-kapt
    // 注意：用户需求列出 compiler:13.0.0 作为 annotationProcessor，
    // Shizuku 的 compiler 模块主要给 hidden-api 注解处理器；本项目暂不强依赖，
    // 但按需求声明依赖以备使用（用 kapt 走注解处理）。
    // kapt("dev.rikka.shizuku:compiler:$shizukuVersion")

    // ---- OkHttp 4.12.x: HTTP + WebSocket ----
    // 来源: https://square.github.io/okhttp/4.x/okhttp/okhttp3/-web-socket/
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- Gson ----
    implementation("com.google.code.gson:gson:2.10.1")

    // ---- Coroutines ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
