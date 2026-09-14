/*
 * MiuixMoondrop — 设置页内容（底部导航第二个页签）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式与分组对齐参考实现 moondrop-pods 的 ui/pages/SettingsPage.kt:104-204：
 *   · androidx LazyColumn + 左右 12dp 留白，块与块之间靠 Card(modifier = padding(top = 12.dp)) 分隔
 *     （参考实现这一页不用 SmallTitle 分组标题，本项目跟随）；
 *   · 下拉行用 preference.OverlayDropdownPreference，开关行用 preference.SwitchPreference，
 *     跳转行用 preference.ArrowPreference；
 *   · 第一张卡是外观（主题），第二张卡是两个应用自己的开关（通知栏显示 / 连接时自动唤出弹窗），
 *     最后一张卡是应用级入口。
 *
 * 只有两件事留在这一页（其余偏好都只对 hook 侧有意义，随模块一起删除）：
 *   · 「通知栏显示」—— 控制**应用自己**发的那条耳机状态通知（pods/PodNotification.kt）。
 *     本应用已不是 Xposed 模块，这条通知不需要任何 hook，因此这个开关仍然有意义；
 *     键是 HyperPodsPrefsKey.SHOW_NOTIFICATION，读写仍然只走 [ModuleSettingsState]。
 *   · 「连接时自动唤出连接弹窗」—— 控制 pods/ControlBridge.kt 在耳机连上后自动弹出的
 *     ui/ConnectionPopupActivity（三路电量，与状态栏通知同时刷新）；键
 *     HyperPodsPrefsKey.AUTO_POPUP_ON_CONNECT，同样只走 [ModuleSettingsState]。
 *   · 应用级入口：手势操作（能力位门控）、关于。
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.PodNotification
import moe.chenxy.hyperpods.ui.ModuleSettingsState
import moe.chenxy.hyperpods.ui.rememberModuleSettings
import moe.chenxy.hyperpods.ui.canStartActivityFromBackground
import moe.chenxy.hyperpods.ui.openBackgroundPopupPermissionSettings
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
 * @param settings 应用侧设置（通知栏显示 / 连接时自动唤出连接弹窗）
 * @param themeMode 0 跟随系统 / 1 浅色 / 2 深色（由 MainActivity 持久化）
 * @param hasGestures 耳机上报了 feature 22（TOUCHV2）时为真 —— 手势入口行只有此时才出现
 *                    （与设备页的「手势操作」行同一套能力门控）
 * @param onOpenGestures 「手势操作」行 → 导航到手势页（由 MainUI 的返回栈负责）
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
    onOpenAbout: () -> Unit = {},
) {
    // 通知开关改完要立刻生效（应用自己发的那条通知由 pods/PodNotification.kt 落地）：
    // 不必等下一次电量轮询（最长 30s）才把已经发出去的那条撤掉。
    val context = LocalContext.current
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
        // 外观：主题
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

        // 通知：「通知栏显示」——控制应用自己发的耳机电量状态通知
        item {
            Card(modifier = Modifier.padding(top = SECTION_GAP)) {
                SwitchPreference(
                    title = stringResource(R.string.notification_display_title),
                    summary = stringResource(R.string.notification_display_summary),
                    checked = settings.showNotification,
                    onCheckedChange = {
                        settings.setShowNotification(it)
                        PodNotification.refreshFromSnapshot(context)
                    },
                )
                // 连接弹窗开关：只影响「连上后自动弹」这一条路径，手动拉起（通知点击 /
                // 按 action 隐式启动）不受影响 —— 关掉开关不等于让弹窗彻底不可用。
                SwitchPreference(
                    title = stringResource(R.string.connect_popup_title),
                    summary = stringResource(R.string.connect_popup_summary),
                    checked = settings.autoPopupOnConnect,
                    onCheckedChange = { checked ->
                        settings.setAutoPopupOnConnect(checked)
                        // 打开开关时若还缺「后台弹出」权限就直接带去授权页：否则用户把开关
                        // 打开了却什么都不会发生（后台启动被系统静默拦掉，最难排查的一种）。
                        if (checked && !canStartActivityFromBackground(context)) {
                            openBackgroundPopupPermissionSettings(context)
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.bg_popup_permission_title),
                    summary = stringResource(R.string.bg_popup_permission_summary),
                    onClick = { openBackgroundPopupPermissionSettings(context) },
                )
            }
        }

        // 应用级入口：手势（能力位门控）/ 关于
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
                    title = stringResource(R.string.about),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
