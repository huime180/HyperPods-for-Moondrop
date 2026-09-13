// Top-level build file. 仅声明插件版本，模块各自 apply。
plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.jetbrains.compose) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
