/*
 * HyperPods for Moondrop — 模块 UI 入口
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 由广播 chen.action.hyperpods.moondrop.show_ui 启动（见 AndroidManifest 的 intent-filter）。
 * 主题模式的读取/持久化与参考实现 _refs/OppoPods/.../MainActivity.kt:20-48 同一写法。
 */
package moe.chenxy.hyperpods

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import moe.chenxy.hyperpods.ui.App
import moe.chenxy.hyperpods.ui.loadThemeMode
import moe.chenxy.hyperpods.ui.saveThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val themeMode = remember { mutableStateOf(loadThemeMode(context)) }
            val darkMode = when (themeMode.value) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false // Xiaomi moment, this code must be here

                onDispose {}
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.value = it
                    saveThemeMode(context, it)
                },
            )
        }
    }
}
