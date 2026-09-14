/*
 * HyperPods for Moondrop — 底部导航的三个页签
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 结构对齐参考实现 moondrop-pods 的 ui/MainTab.kt:11-22：
 *   模块（模块状态与模块级设置）/ 耳机（设备详情或设备选择）/ 设置（应用外观与入口）。
 * 页签名与图标：模块与耳机用纯 Compose 画的 AppIcons，设置沿用 Miuix 的 Settings 图标。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.components.AppIcons
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings

internal enum class MainTab(val icon: ImageVector) {
    Module(AppIcons.Home),
    Earphones(AppIcons.Headphones),
    Settings(MiuixIcons.Settings),
}

@Composable
internal fun MainTab.title(): String = when (this) {
    MainTab.Module -> stringResource(R.string.tab_module)
    MainTab.Earphones -> stringResource(R.string.tab_earphones)
    MainTab.Settings -> stringResource(R.string.settings_title)
}
