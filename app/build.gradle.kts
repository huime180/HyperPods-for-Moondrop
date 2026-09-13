plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.parcelize)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

android {
    namespace = "moe.chenxy.hyperpods"
    compileSdk = 36

    defaultConfig {
        applicationId = "moe.chenxy.hyperpods.moondrop"
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
            // 模块靠字符串名反射 hook，混淆模块自身不影响反射目标，因此可以放心开启
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        buildConfig = true
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

dependencies {
    implementation(libs.coreKtx)
    // libxposed API：仅编译期，运行时由 LSPosed / Vector 注入
    compileOnly(libs.libxposedApi)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.yukonga.miuix)
    implementation(libs.androidx.activity.compose)
    implementation(libs.haze)

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.components.resources)
    implementation(compose.preview)
    debugImplementation(compose.uiTooling)

    testImplementation(libs.junit)
}
