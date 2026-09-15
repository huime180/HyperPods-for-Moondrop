/*
 * MiuixMoondrop — 应用根组件
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 与参考实现 _refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/ui/App.kt:8-21 一致：
 * 主题模式（0 跟随系统 / 1 浅色 / 2 深色）在这里映射成 Miuix ColorSchemeMode，
 * 页面骨架交给 MainUI（NavDisplay 单栈导航）。
 *
 * 快速弹窗（ui/PopupActivity.kt）不走这里：它是独立的半透明 Activity。
 */
package moe.huime.miuixmoondrop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@Composable
fun App(
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
) {
    val colorSchemeMode = when (themeMode.value) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    AppTheme(colorSchemeMode = colorSchemeMode) {
        MainUI(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
    }
}
