/*
 * MiuixMoondrop — 底部导航的两个页签
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 本应用已从 Xposed 模块收窄为普通 App，导航面只剩「应用自己」的两页：
 *   耳机（设备详情或设备选择）/ 设置（应用外观与入口）。
 * 原来的「模块」页签（模块状态卡 + 模块开关）随 hook 一起删除，不再有对应的页面。
 * 页签名与图标：耳机用纯 Compose 画的 AppIcons，设置沿用 Miuix 的 Settings 图标。
 */
package moe.huime.miuixmoondrop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import moe.huime.miuixmoondrop.R
import moe.huime.miuixmoondrop.ui.components.AppIcons
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings

internal enum class MainTab(val icon: ImageVector) {
    Earphones(AppIcons.Headphones),
    Settings(MiuixIcons.Settings),
}

@Composable
internal fun MainTab.title(): String = when (this) {
    MainTab.Earphones -> stringResource(R.string.tab_earphones)
    MainTab.Settings -> stringResource(R.string.settings_title)
}
