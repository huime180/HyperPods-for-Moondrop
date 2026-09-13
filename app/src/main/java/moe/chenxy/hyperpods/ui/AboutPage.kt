/*
 * HyperPods for Moondrop — 设置页 + 关于页
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式对齐参考实现 _refs/OppoPods/.../ui/AboutPage.kt：
 *   · SettingsPage（:102-226）：卡内用 preference.SwitchPreference / OverlayDropdownPreference /
 *     ArrowPreference 分行，卡间 12dp，区块标题用 basic.SmallTitle（:808, :850 的用法）；
 *   · AboutContent（:503-885）：同样的 SmallTitle + Card 结构，底部是只读信息行。
 * 两个页面都由 MainUI 的 entry 提供 Scaffold + SmallTopAppBar。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.core.MoondropModels
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 卡片/区块之间的统一间距。 */
private val SECTION_GAP = 12.dp

/**
 * 设置页：主题 + 模块总开关 + 通知/超级岛 + 调试 + 型号识别。
 *
 * @param themeMode 0 跟随系统 / 1 浅色 / 2 深色（由 MainActivity 持久化）
 * @param settings  模块首选项读写封装（见 ui/ModuleSettings.kt），键来自 HyperPodsPrefsKey
 * @param hasGestures 耳机上报了 feature 22（TOUCHV2）时为真 —— 手势入口行只有此时才出现
 *                    （与设备页的「手势操作」行同一套能力门控）
 * @param onOpenGestures 「手势操作」行 → 导航到手势页（由 MainUI 的返回栈负责）
 */
@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    themeMode: MutableState<Int> = remember { mutableStateOf(0) },
    onThemeModeChange: (Int) -> Unit = {},
    settings: ModuleSettingsState = rememberModuleSettings(),
    onOpenAbout: () -> Unit = {},
    hasGestures: Boolean = false,
    onOpenGestures: () -> Unit = {},
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

        item { SmallTitle(text = stringResource(R.string.settings_section_module)) }

        item {
            Card {
                SwitchPreference(
                    title = stringResource(R.string.module_enable_title),
                    summary = stringResource(R.string.module_enable_summary),
                    checked = settings.enabled,
                    onCheckedChange = { settings.setEnabled(it) },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.settings_section_notification)) }

        item {
            Card {
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

        item { SmallTitle(text = stringResource(R.string.settings_section_model)) }

        item {
            Card {
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
            }
        }

        item { SmallTitle(text = stringResource(R.string.settings_section_debug)) }

        item {
            Card {
                SwitchPreference(
                    title = stringResource(R.string.debug_log_title),
                    summary = stringResource(R.string.debug_log_summary),
                    checked = settings.debugLog,
                    onCheckedChange = { settings.setDebugLog(it) },
                    enabled = settings.enabled,
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

/** 关于页：图标 + 版本 + 致谢/许可（只读行，所以用 BasicComponent 而不是 ArrowPreference）。 */
@Composable
fun AboutContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + SECTION_GAP,
            bottom = contentPadding.calculateBottomPadding() + SECTION_GAP,
            start = 12.dp,
            end = 12.dp,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        overscrollEffect = null,
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SECTION_GAP),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(72.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.padding(top = 8.dp),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                )
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    modifier = Modifier.padding(top = 2.dp),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 13.sp,
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.about)) }

        item {
            Card {
                BasicComponent(
                    title = stringResource(R.string.about_credits_title),
                    summary = stringResource(R.string.about_credits_summary),
                )
                BasicComponent(
                    title = stringResource(R.string.about_license_title),
                    summary = stringResource(R.string.about_license_value),
                )
            }
        }
    }
}
