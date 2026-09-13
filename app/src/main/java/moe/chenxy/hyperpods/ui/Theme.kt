/*
 * HyperPods for Moondrop — Miuix 主题
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * @param colorMode 0 = 跟随系统，1 = 浅色，2 = 深色
 */
@Composable
fun AppTheme(
    colorMode: Int = 0,
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    return MiuixTheme(
        colors = when (colorMode) {
            1 -> lightColorScheme()
            2 -> darkColorScheme()
            else -> if (darkTheme) darkColorScheme() else lightColorScheme()
        },
        content = content
    )
}
