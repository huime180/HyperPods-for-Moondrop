/*
 * MiuixMoondrop — Miuix 主题
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 逐条对齐参考实现 _refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/ui/Theme.kt:12-37：
 * 用 Miuix 的 ThemeController 承载 ColorSchemeMode（跟随系统 / 浅色 / 深色）；
 * 浅色/深色时用 LocalConfiguration 覆写 uiMode，保证系统栏与 Compose 内侧同一套明暗。
 */
package moe.chenxy.hyperpods.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun AppTheme(
    colorSchemeMode: ColorSchemeMode = ColorSchemeMode.System,
    content: @Composable () -> Unit,
) {
    val controller = remember(colorSchemeMode) { ThemeController(colorSchemeMode) }

    when (colorSchemeMode) {
        ColorSchemeMode.Light, ColorSchemeMode.Dark -> {
            val nightMode = if (colorSchemeMode == ColorSchemeMode.Dark) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            val currentConfig = LocalConfiguration.current
            val overrideConfig = remember(currentConfig, nightMode) {
                Configuration(currentConfig).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
            }
            CompositionLocalProvider(LocalConfiguration provides overrideConfig) {
                MiuixTheme(controller = controller, content = content)
            }
        }

        else -> MiuixTheme(controller = controller, content = content)
    }
}
