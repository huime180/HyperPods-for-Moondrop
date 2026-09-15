/*
 * MiuixMoondrop — 设置页内容（底部导航第二个页签）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式与分组对齐参考实现 moondrop-pods 的 ui/pages/SettingsPage.kt:104-204：
 *   · androidx LazyColumn + 左右 12dp 留白，块与块之间靠 Card(modifier = padding(top = 12.dp)) 分隔
 *     （参考实现这一页不用 SmallTitle 分组标题，本项目跟随）；
 *   · 下拉行用 preference.OverlayDropdownPreference，开关行用 preference.SwitchPreference，
 *     跳转行用 preference.ArrowPreference；
 *   · 第一张卡是外观（主题），第二张卡是三个应用自己的开关（通知栏显示 / 连接时自动唤出弹窗 /
 *     超级岛）+ 一条「后台弹出弹窗权限」跳转行，最后一张卡是应用级入口。
 *
 * 只有这几件事留在这一页（其余偏好都只对 hook 侧有意义，随模块一起删除）：
 *   · 「通知栏显示」—— 控制**应用自己**发的那条耳机状态通知（pods/PodNotification.kt）。
 *     本应用已不是 Xposed 模块，这条通知不需要任何 hook，因此这个开关仍然有意义；
 *     键是 HyperPodsPrefsKey.SHOW_NOTIFICATION，读写仍然只走 [ModuleSettingsState]。
 *   · 「连接时自动唤出弹窗」—— 控制 pods/ControlBridge.kt 在耳机连上后自动弹出的
 *     ui/PopupActivity（与点通知唤出的是**同一个**弹窗：电量 / 降噪 / 快捷控制，
 *     与状态栏通知同时刷新）；键 HyperPodsPrefsKey.AUTO_POPUP_ON_CONNECT，
 *     同样只走 [ModuleSettingsState]。
 *   · 应用级入口：关于。
 *
 * 「手势操作」入口原本也在这张卡里（由 hasGestures 门控），现已删除：手势是耳机相关功能，
 * 本仓库的约定是耳机相关功能铺在耳机页上（设备页保留了「手势操作」行），设置页只留应用级入口。
 */
package moe.huime.miuixmoondrop.ui.pages

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
import moe.huime.miuixmoondrop.R
import moe.huime.miuixmoondrop.pods.PodNotification
import moe.huime.miuixmoondrop.ui.ModuleSettingsState
import moe.huime.miuixmoondrop.ui.rememberModuleSettings
import moe.huime.miuixmoondrop.ui.canStartActivityFromBackground
import moe.huime.miuixmoondrop.ui.openBackgroundPopupPermissionSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
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
 */
@Composable
fun SettingsPage(
    settings: ModuleSettingsState = rememberModuleSettings(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    themeMode: MutableState<Int> = remember { mutableStateOf(0) },
    onThemeModeChange: (Int) -> Unit = {},
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

        // 通知：应用自己发的状态通知 + 超级岛 + 连接弹窗，统一收在「通知」小标题下
        item { SmallTitle(text = stringResource(R.string.notification_section)) }

        item {
            Card {
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

        // 应用级入口：关于（耳机相关功能都在耳机页，见文件头）
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
