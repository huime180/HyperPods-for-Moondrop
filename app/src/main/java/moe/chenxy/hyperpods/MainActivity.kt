/*
 * HyperPods for Moondrop — 模块 UI 入口
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 由广播 chen.action.hyperpods.moondrop.show_ui 启动（见 AndroidManifest 的 intent-filter）。
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
import moe.chenxy.hyperpods.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val darkMode = isSystemInDarkTheme()

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false // Xiaomi moment, this code must be here

                onDispose {}
            }

            App()
        }
    }
}
