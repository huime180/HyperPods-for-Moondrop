/*
 * HyperPods for Moondrop — 应用根组件（详情页）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 快速弹窗（ui/PopupActivity.kt）不走这里：它是独立的半透明 Activity。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.runtime.Composable

@Composable
fun App() {
    AppTheme {
        DetailScreen()
    }
}
