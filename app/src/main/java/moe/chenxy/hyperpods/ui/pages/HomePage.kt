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
 * 参考实现那张「LSPosed 已激活 / 作用域是否齐全」的状态卡已经搬过来（本轮补齐）：
 * 本项目原先只有 compileOnly 的 io.github.libxposed:api，应用进程绑定不到框架服务，
 * 所以迟迟没做；现在依赖了 io.github.libxposed:service（同 102.0.0），由
 * ui/XposedServiceState.kt 负责订阅并把「框架版本 + 已勾选作用域」交给这里渲染。
 * 状态口径严格按事实：服务没连上就显示「未激活 / 等待连接」，
 * 连上了但 5 个作用域没勾齐就显示缺失清单，绝不乐观地写「已激活」。
 *
 * 视觉上与参考实现的差异：参考实现是一张 112dp 高、带耳机图标与固定浅色底
 * （#FFE5E3 / #DFFAE4）的大卡片；那两档写死的浅色在深色主题下会刺眼，
 * 所以这里用本项目统一的 miuix Card + BasicComponent 只读行，
 * 信息（激活状态 + 缺失作用域 + 框架版本）与参考实现一致。
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
import moe.chenxy.hyperpods.ui.LsposedScopeState
import moe.chenxy.hyperpods.ui.lsposedScopeState
import moe.chenxy.hyperpods.ui.lsposedVersionText
import moe.chenxy.hyperpods.ui.rememberXposedService
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

    // LSPosed 服务：null = 框架服务还没连上（没装框架 / 模块没激活 / 绑定中）
    val xposedService = rememberXposedService()
    val lsposedState = remember(xposedService) { lsposedScopeState(xposedService) }

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
        item { LsposedStatusCard(state = lsposedState) }

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
                BasicComponent(
                    title = stringResource(R.string.info_lsposed_version),
                    summary = lsposedVersionText(xposedService)
                        .ifBlank { stringResource(R.string.unknown_value) },
                )
            }
        }
    }
}

/**
 * 「LSPosed 已激活 / 作用域是否齐全」状态卡（对应参考实现 HomePage.kt:133-193 的 StatusCard）。
 *
 * 三档文案全部由事实推出：
 *   ① 服务已连上且 5 个作用域齐全 → 已激活 / 作用域齐全；
 *   ② 服务没连上           → 未激活 / 等待 LSPosed 服务连接；
 *   ③ 服务连上了但缺作用域 → 作用域不齐全 / 列出缺的那几个。
 */
@Composable
private fun LsposedStatusCard(state: LsposedScopeState) {
    val title = when {
        state.active -> stringResource(R.string.lsposed_status_active)
        state.serviceConnected -> stringResource(R.string.lsposed_status_scope_incomplete)
        else -> stringResource(R.string.lsposed_status_inactive)
    }
    val summary = when {
        state.active -> stringResource(R.string.lsposed_status_scopes_ok)
        !state.serviceConnected -> stringResource(R.string.lsposed_status_waiting)
        else -> {
            // map 是 inline 函数，因此这里可以在 lambda 里调用 @Composable 的 stringResource
            val names = state.missingScopes.map { stringResource(it) }.joinToString(" · ")
            stringResource(R.string.lsposed_status_scopes_missing, names)
        }
    }

    Card {
        BasicComponent(title = title, summary = summary)
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
