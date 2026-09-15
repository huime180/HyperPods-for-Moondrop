plugins {
    alias(libs.plugins.agp.app)
    // AGP 9 自带 Kotlin（KGP 版本由根 build.gradle.kts 提升），因此不再 apply kotlin-android
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "moe.chenxy.hyperpods"
    // Miuix 0.9.3 的 AAR metadata 声明 minCompileSdk=37，低于 37 会在
    // CheckAarMetadata 阶段直接失败，所以必须跟着提到 37。
    compileSdk = 37

    defaultConfig {
        // 应用身份（包名）：与 namespace 解耦 —— namespace 仍是 moe.chenxy.hyperpods
        // （R 类与资源包路径由它决定，改它会牵动全仓库 import，收益与风险不成比例）。
        applicationId = "moe.huime.miuixmoondrop"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            // 与 HyperPods（dev）的构建配置一致：开启 R8 与资源压缩。
            // 关闭时 release 产物 25 MB —— 其中 23.5 MB 是未混淆的 dex（Compose + Miuix
            // 全量符号、无死代码消除）；开启后同类工程落在 4~8 MB 量级。
            // keep 规则见 proguard-rules.pro（协议层 core.** / pods.** 全保留）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
            )
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coreKtx)

    implementation(libs.kotlinx.serialization.json)

    // HyperOS 焦点通知 / 超级岛（miui.focus.*）的模板库，与 dev 分支同库同版本
    implementation(libs.focus.api)

    // Compose：统一用 androidx compose（BOM 管理版本），与 miuix 0.9.3 的 -android 产物一致
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    // Miuix 0.9.3：拆分产物（miuix-ui / preference / icons，navigation3 集成）
    implementation(libs.miuix)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    // 背景动效用的 RuntimeShader 封装与混合模式（ui/effect 目录，0.9.3 与其它 Miuix 产物同版本）
    implementation(libs.miuix.blur)
    implementation(libs.miuix.navigation3.ui)

    // Navigation 3：MainUI 的 NavDisplay + rememberDecoratedNavEntries
    implementation(libs.navigation3.runtime)

    testImplementation(libs.junit)
}
