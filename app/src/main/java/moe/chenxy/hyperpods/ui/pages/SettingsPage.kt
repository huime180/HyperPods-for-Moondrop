/*
 * HyperPods for Moondrop — 设置页内容（底部导航第三个页签）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式与分组对齐参考实现 moondrop-pods 的 ui/pages/SettingsPage.kt:104-204：
 *   · androidx LazyColumn + 左右 12dp 留白，块与块之间靠 Card(modifier = padding(top = 12.dp)) 分隔
 *     （参考实现这一页不用 SmallTitle 分组标题，本项目跟随）;
 *   · 开关行用 preference.SwitchPreference，下拉行用 preference.OverlayDropdownPreference，
 *     跳转行用 preference.ArrowPreference；
 *   · 第一张卡是外观（主题），中间是模块级开关，最后一张卡是应用级入口。
 *
 * 模块级开关（启用 / 通知栏显示 / 强提示 / 超级岛 / 型号 / 调试）本轮从模块页搬到这里，
 * 与参考实现一致：模块页只显示状态，模块的设置都在设置页。
 * 读写仍然只走 [ModuleSettingsState]（键来自 utils/data/HyperPodsPrefsKey.kt），没有新造状态存储。
 *
 * 这里同时保留本项目的应用级入口：手势操作（能力位门控）、重启作用域、关于。
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
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.ui.ModuleSettingsState
import moe.chenxy.hyperpods.ui.rememberModuleSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 卡片之间的统一间距。 */
private val SECTION_GAP = 12.dp

/**
 * 设置页。
 *
 * @param settings 模块级设置（启用 / 通知显示 / 强提示 / 超级岛 / 型号 / 调试日志）
 * @param themeMode 0 跟随系统 / 1 浅色 / 2 深色（由 MainActivity 持久化）
 * @param hasGestures 耳机上报了 feature 22（TOUCHV2）时为真 —— 手势入口行只有此时才出现
 *                    （与设备页的「手势操作」行同一套能力门控）
 * @param onOpenGestures 「手势操作」行 → 导航到手势页（由 MainUI 的返回栈负责）
 * @param onRequestRestartScope 「重启作用域」行 → 打开作用域勾选确认框（状态由 MainUI 持有）
 */
@Composable
fun SettingsPage(
    settings: ModuleSettingsState = rememberModuleSettings(),
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
    val models = MoondropModels.MODELS
    val modelNames = remember(models) { models.map { it.nameZh } }
    val modelSelectedIndex = models.indexOfFirst { it.id == settings.modelId }
        .takeIf { it >= 0 }
        ?: 0

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
        // 外观：主题（对应参考实现第一张卡「主题 / Theme」的位置）
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

        // 模块开关：总开关 + 通知类三个开关（对应参考实现第二张卡的一组开关）
        item {
            Card(modifier = Modifier.padding(top = SECTION_GAP)) {
                SwitchPreference(
                    title = stringResource(R.string.module_enable_title),
                    summary = stringResource(R.string.module_enable_summary),
                    checked = settings.enabled,
                    onCheckedChange = { settings.setEnabled(it) },
                )
                // 「通知栏显示」：默认开；值落到 hyperpods_moondrop_settings 组的
                // HyperPodsPrefsKey.SHOW_NOTIFICATION，hook 侧经 getRemotePreferences(同组名) 读取。
                SwitchPreference(
                    title = stringResource(R.string.notification_display_title),
                    summary = stringResource(R.string.notification_display_summary),
                    checked = settings.showNotification,
                    onCheckedChange = { settings.setShowNotification(it) },
                    enabled = settings.enabled,
                )
                SwitchPreference(
                    title = stringResource(R.string.show_strong_toast_title),
                    summary = stringResource(R.string.show_strong_toast_summary),
                    checked = settings.showStrongToast,
                    onCheckedChange = { settings.setShowStrongToast(it) },
                    enabled = settings.enabled,
                )
                SwitchPreference(
                    title = stringResource(R.string.show_focus_island_title),
                    summary = stringResource(R.string.show_focus_island_summary),
                    checked = settings.showFocusIsland,
                    onCheckedChange = { settings.setShowFocusIsland(it) },
                    enabled = settings.enabled,
                )
            }
        }

        // 型号识别 + 调试日志
        item {
            Card(modifier = Modifier.padding(top = SECTION_GAP)) {
                SwitchPreference(
                    title = stringResource(R.string.model_auto_title),
                    summary = stringResource(R.string.model_auto_summary),
                    checked = settings.modelAuto,
                    onCheckedChange = { settings.setModelAuto(it) },
                    enabled = settings.enabled,
                )
                if (modelNames.isNotEmpty()) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.model_manual_title),
                        items = modelNames,
                        selectedIndex = modelSelectedIndex,
                        onSelectedIndexChange = { index ->
                            models.getOrNull(index)?.let { settings.setModelId(it.id) }
                        },
                        enabled = settings.enabled && !settings.modelAuto,
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.debug_log_title),
                    summary = stringResource(R.string.debug_log_summary),
                    checked = settings.debugLog,
                    onCheckedChange = { settings.setDebugLog(it) },
                    enabled = settings.enabled,
                )
            }
        }

        // 应用级入口：手势（能力位门控）/ 重启作用域 / 关于
        item {
            Card(modifier = Modifier.padding(top = SECTION_GAP)) {
                if (hasGestures) {
                    ArrowPreference(
                        title = stringResource(R.string.gesture_title),
                        summary = stringResource(R.string.gesture_summary),
                        onClick = onOpenGestures,
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.restart_scope),
                    summary = stringResource(R.string.restart_scope_summary),
                    onClick = onRequestRestartScope,
                )
                ArrowPreference(
                    title = stringResource(R.string.about),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
