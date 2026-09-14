/*
 * HyperPods for Moondrop — 设置页内容（底部导航第三个页签）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式对齐参考实现 moondrop-pods 的 ui/pages/SettingsPage.kt:104-204：
 *   · androidx LazyColumn + 左右 12dp / 块间 12dp；
 *   · 每块内容装在一张 miuix Card 里，「跳转行」用 preference.ArrowPreference，
 *     「下拉行」用 preference.OverlayDropdownPreference；
 *   · 页面顶部第一张卡是外观（主题），底部是「关于」入口。
 *
 * 这里只放应用级/导航级的东西：主题、手势入口（能力位门控）、重启作用域入口、关于。
 * 模块级开关（模块总开关 / 通知显示 / 型号识别 / 调试）在模块页（ui/pages/HomePage.kt）。
 */
package moe.chenxy.hyperpods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 卡片/区块之间的统一间距。 */
private val SECTION_GAP = 12.dp

/**
 * 设置页。
 *
 * @param themeMode 0 跟随系统 / 1 浅色 / 2 深色（由 MainActivity 持久化）
 * @param hasGestures 耳机上报了 feature 22（TOUCHV2）时为真 —— 手势入口行只有此时才出现
 *                    （与设备页的「手势操作」行同一套能力门控）
 * @param onOpenGestures 「手势操作」行 → 导航到手势页（由 MainUI 的返回栈负责）
 * @param onRequestRestartScope 「重启作用域」行 → 打开作用域勾选确认框（状态由 MainUI 持有）
 */
@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    themeMode: MutableState<Int> = remember { mutableStateOf(0) },
    onThemeModeChange: (Int) -> Unit = {},
    hasGestures: Boolean = false,
    onOpenGestures: () -> Unit = {},
    onRequestRestartScope: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val themeOptions = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + SECTION_GAP,
            bottom = contentPadding.calculateBottomPadding() + SECTION_GAP,
            start = 12.dp,
            end = 12.dp,
        ),
        overscrollEffect = null,
    ) {
        item {
            Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.theme_title),
                    items = themeOptions,
                    selectedIndex = themeMode.value.coerceIn(0, themeOptions.lastIndex),
                    onSelectedIndexChange = { onThemeModeChange(it) },
                )
            }
        }

        // 手势入口（第二个入口；设备页那行在 PodDetailPage）——只有能力位为真时出现
        if (hasGestures) {
            item { SmallTitle(text = stringResource(R.string.settings_section_gesture)) }

            item {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.gesture_title),
                        summary = stringResource(R.string.gesture_summary),
                        onClick = onOpenGestures,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.padding(top = SECTION_GAP)) {
                ArrowPreference(
                    title = stringResource(R.string.restart_scope),
                    summary = stringResource(R.string.restart_scope_summary),
                    onClick = onRequestRestartScope,
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = SECTION_GAP)) {
                ArrowPreference(
                    title = stringResource(R.string.about),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
