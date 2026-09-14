/*
 * HyperPods for Moondrop — 模块页内容（底部导航第一个页签）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式对齐参考实现 moondrop-pods 的 ui/pages/HomePage.kt:44-264：
 *   · 顶层是 androidx 的 LazyColumn，12dp 左右的左右留白，块间距 12dp；
 *   · 底部一张只读的「系统信息」卡（参考实现叫 InfoCard），条目用 basic.BasicComponent；
 *   · 每块内容装在 miuix 的 Card 里，区块标题用 basic.SmallTitle
 *     （本项目原设置页就是这套词汇，见 ui/pages/SettingsPage.kt 的注释）。
 *
 * 参考实现那张「LSPosed 已激活 / 蓝牙作用域是否齐全」的状态卡没有搬：
 * 它依赖 XposedService（io.github.libxposed.service），而本项目的 libxposed api 是
 * compileOnly，应用进程拿不到该服务；凭空显示一个「已激活」反而会误导用户。
 * 这里只显示本项目确实能读到的模块级设置与构建信息。
 */
package moe.chenxy.hyperpods.ui.pages

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.ui.ModuleSettingsState
import moe.chenxy.hyperpods.ui.rememberModuleSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 卡片/区块之间的统一间距（与本项目其它页面一致）。 */
private val SECTION_GAP = 12.dp

/**
 * 模块页：模块总开关、通知显示、型号识别、调试日志，以及只读的系统信息。
 *
 * 这些开关就是原先设置页里的「模块」一组，只是按页签重新分组；读写仍走
 * [ModuleSettingsState]（键来自 utils/data/HyperPodsPrefsKey.kt）。
 */
@Composable
fun HomePage(
    settings: ModuleSettingsState = rememberModuleSettings(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val models = MoondropModels.MODELS
    val modelNames = remember(models) { models.map { it.nameZh } }
    val modelSelectedIndex = models.indexOfFirst { it.id == settings.modelId }
        .takeIf { it >= 0 }
        ?: 0
    val systemInfo = remember(context) { readSystemInfo(context) }

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

        item { SmallTitle(text = stringResource(R.string.settings_section_system)) }

        item {
            Card {
                BasicComponent(
                    title = stringResource(R.string.info_app_version),
                    summary = systemInfo.appVersion,
                )
                BasicComponent(
                    title = stringResource(R.string.info_android_version),
                    summary = systemInfo.androidVersion,
                )
                BasicComponent(
                    title = stringResource(R.string.info_system_version),
                    summary = systemInfo.systemVersion,
                )
                BasicComponent(
                    title = stringResource(R.string.info_device_model),
                    summary = systemInfo.deviceModel,
                )
            }
        }
    }
}

/** 只读的构建/系统信息（同参考实现 HomePage.kt:266-285 的 HomeSystemInfo）。 */
private data class SystemInfo(
    val appVersion: String,
    val androidVersion: String,
    val systemVersion: String,
    val deviceModel: String,
)

private fun readSystemInfo(context: Context): SystemInfo {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = packageInfo?.versionName ?: BuildConfig.VERSION_NAME
    val versionCode = packageInfo?.longVersionCode ?: BuildConfig.VERSION_CODE.toLong()
    val model = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    return SystemInfo(
        appVersion = "$versionName ($versionCode)",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        systemVersion = Build.DISPLAY,
        // 只读展示行：厂商/型号都读不到时留一个中性占位，不谎报机型
        deviceModel = model.ifBlank { "-" },
    )
}
