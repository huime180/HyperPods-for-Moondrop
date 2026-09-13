// Top-level build file. 仅声明插件版本，模块各自 apply。
buildscript {
    dependencies {
        // AGP 9 自带 Kotlin 编译支持，其内置 KGP 版本较旧；这里提升到 catalog 里的
        // Kotlin 版本（与 miuix 0.9.3 的编译版本一致），否则消费 miuix 产物会报
        // 「compiled with a newer Kotlin compiler」。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
