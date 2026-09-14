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
        applicationId = "moe.chenxy.hyperpods.moondrop"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // 模块页「构建时间」一行：与参考实现 moondrop-pods 的 app/build.gradle.kts:30 同一写法
        // （配置期取一次当前时间，编进 BuildConfig.BUILD_TIMESTAMP）。删掉这一行即可回退。
        buildConfigField("long", "BUILD_TIMESTAMP", System.currentTimeMillis().toString())
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            // 模块靠字符串名反射 hook，混淆模块自身不影响反射目标，因此可以放心开启
            isMinifyEnabled = false
            isShrinkResources = false
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
            merges += "META-INF/xposed/*"
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
    // libxposed API：仅编译期，运行时由 LSPosed / Vector 注入
    compileOnly(libs.libxposedApi)
    // LSPosed 服务（应用进程侧）：绑定框架服务后可读框架版本与已勾选作用域，
    // 供模块页的「LSPosed 已激活 / 作用域齐全」状态卡使用。
    implementation(libs.libxposedService)

    implementation(libs.kotlinx.serialization.json)

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
