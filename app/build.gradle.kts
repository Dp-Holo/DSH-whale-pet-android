plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsha.whalepet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsha.whalepet"
        minSdk = 26
        targetSdk = 35
        // CI（GitHub Actions）里取 run number 保证每次发版 versionCode 递增；本地构建回退 1
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionName = "1.1.50"
    }

    // 正式签名：keystore 由 CI 从 Actions secrets 注入（KEYSTORE_PATH 等环境变量）；
    // 本地/无凭据构建不设置签名（产出 unsigned release，不影响 assembleDebug）。
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "whale"
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // AIDL：Shizuku UserService 接口
        aidl = true
    }
}

// 消除 kotlinOptions 弃用警告（Kotlin 2.x 推荐写法）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Shizuku：免 root 自动授予悬浮窗等系统权限
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 插件脚本引擎：Android 版 Rhino（纯 Java，无需 NDK/JNI）
    implementation("com.faendir.rhino:rhino-android:1.6.0")
    // 强制升级 Rhino 运行时：rhino-android:1.6.0 传递引入的 rhino-runtime:1.7.13
    // 命中 CVE-2025-66453（攻击者控制的浮点数传给 toFixed 会触发天文数字次幂运算，
    // 单次 Java 调用内完成，JS 指令上限拦不住 → 插件线程被永久占死）。
    // 1.7.15.1 起修复；这里显式覆盖传递依赖的版本。
    implementation("org.mozilla:rhino-runtime:1.7.15.1")
    // 余额查询走系统 HttpURLConnection（零三方依赖）

    // 单元测试（JVM）：Android 内置的 org.json 在测试环境不存在，需单独引入实现
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
