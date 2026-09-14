/*
 * MiuixMoondrop — 底部导航栏
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 形态对齐参考实现 moondrop-pods 的 ui/MainBottomNavigation.kt:49-64（非浮动分支）：
 * 用 Miuix 的 NavigationBar + 每页签一个 NavigationBarItem（icon + label）。
 * 参考实现里的浮动 / 毛玻璃分支需要 miuix-blur 依赖，本项目没有该依赖，因此不引入。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem

@Composable
internal fun MainBottomNavigation(
    tabs: List<MainTab>,
    selectedTab: MainTab,
    onTabClick: (MainTab) -> Unit,
) {
    NavigationBar(
        // 与参考实现 moondrop-pods 的 MainBottomNavigation.kt:50-54 一致：底栏上方不画分割线
        // （页面内容自己带留白，多一条线反而割裂）。symbol 见 miuix 0.9.3 源码基本组件 NavigationBar。
        showDivider = false,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onTabClick(tab) },
                icon = tab.icon,
                label = tab.title(),
            )
        }
    }
}
