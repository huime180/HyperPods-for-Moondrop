/*
 * HyperPods for Moondrop — 模块页内容（底部导航第一个页签，纯状态页）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 本文件逐块复刻参考实现 moondrop-pods 的 ui/pages/HomePage.kt，只把数据线接到本项目：
 *   · StatusGrid（参考 :92-130）——左边一张 StatusCard（LSPosed 激活状态 + 蓝牙进程是否在响应），
 *     右边两张可点的 StatCard（蓝牙状态 / 配对蓝牙）。版式按参考原样分两档：
 *     窄屏是「StatusCard 1:1 + 右侧一列两张 1:1」，宽屏（>= 600dp）是三张等宽 112dp 的横排。
 *   · InfoCard（参考 :236-264）——只读六行：系统版本 / 应用版本 / Android 版本 /
 *     LSPosed 版本 / 构建时间 / 设备型号。字号取参考同款 MiuixTheme.textStyles
 *     （headline1 = 17sp 标题、body2 = 14sp 内容）。
 *
 * 模块级开关（启用 / 通知栏显示 / 强提示 / 超级岛 / 型号 / 调试）本轮已全部搬到设置页
 * （ui/pages/SettingsPage.kt），本页只负责「看状态」，与参考实现的模块页职责一致。
 *
 * 数据来源（全部是真实读数，没有任何写死的假值）：
 *   · LSPosed 激活状态 + 缺失作用域 —— ui/XposedServiceState.kt（同一份订阅 + 缺失清单）；
 *   · 蓝牙开关 / 已配对设备数 —— BluetoothAdapter（权限门控，同参考 readBluetoothState）；
 *   · 蓝牙进程是否在响应 —— ui/BluetoothStatus.kt（真实广播 + 75s 窗口，见该文件 KDoc 里的差异说明）；
 *   · 应用版本 —— BuildConfig.VERSION_NAME/VERSION_CODE；
 *     Android 版本 —— Build.VERSION.RELEASE + SDK_INT；
 *     系统版本 —— Build.DISPLAY；
 *     构建时间 —— BuildConfig.BUILD_TIMESTAMP（app/build.gradle.kts 配置期生成，同参考 :30）；
 *     设备型号 —— Build.MANUFACTURER + Build.MODEL。
 */
package moe.chenxy.hyperpods.ui.pages

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.libxposed.service.XposedService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.components.AppIcons
import moe.chenxy.hyperpods.ui.lsposedScopeState
import moe.chenxy.hyperpods.ui.lsposedVersionText
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 模块页（纯状态）。
 *
 * @param xposedService LSPosed 框架服务；null = 没连上（没装框架 / 模块没激活 / 正在绑定）
 * @param bluetoothServiceResponsive 蓝牙进程里的模块最近是否回过话（ui/BluetoothStatus.kt）
 * @param bluetoothEnabled 蓝牙是否已开启（读 BluetoothAdapter）
 * @param bondedDeviceCount 已配对设备数（读 BluetoothAdapter）
 * @param onBluetoothStatusClick 「蓝牙状态」卡片 → 打开系统蓝牙设置（蓝牙关着时先请求打开）
 * @param onPairedBluetoothClick 「配对蓝牙」卡片 → 已配对设备选择页
 */
@Composable
fun HomePage(
    xposedService: XposedService?,
    bluetoothServiceResponsive: Boolean,
    bluetoothEnabled: Boolean,
    bondedDeviceCount: Int,
    onBluetoothStatusClick: () -> Unit,
    onPairedBluetoothClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
) {
    val context = LocalContext.current
    val systemInfo = remember(context) { homeSystemInfo(context) }
    val lsposedState = remember(xposedService) { lsposedScopeState(xposedService) }
    val active = lsposedState.active
    val inactiveSummary = if (xposedService == null) {
        stringResource(R.string.lsposed_status_waiting)
    } else {
        // 服务连上了但作用域没勾齐：把缺的那几个列出来（map 是 inline，可以在 lambda 里调 stringResource）
        val names = lsposedState.missingScopes.map { stringResource(it) }.joinToString(" · ")
        stringResource(R.string.lsposed_status_scopes_missing, names)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 12.dp,
            bottom = bottomContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusGrid(
                serviceConnected = lsposedState.serviceConnected,
                active = active,
                inactiveSummary = inactiveSummary,
                bluetoothServiceResponsive = bluetoothServiceResponsive,
                bluetoothEnabled = bluetoothEnabled,
                bondedDeviceCount = bondedDeviceCount,
                onBluetoothStatusClick = onBluetoothStatusClick,
                onPairedBluetoothClick = onPairedBluetoothClick,
            )
        }
        item {
            InfoCard(systemInfo = systemInfo, xposedService = xposedService)
        }
    }
}

/** 状态卡 + 两张统计卡；宽屏横排三张，窄屏左侧一张 1:1、右侧一列两张 1:1（同参考 :102-130）。 */
@Composable
private fun StatusGrid(
    serviceConnected: Boolean,
    active: Boolean,
    inactiveSummary: String,
    bluetoothServiceResponsive: Boolean,
    bluetoothEnabled: Boolean,
    bondedDeviceCount: Int,
    onBluetoothStatusClick: () -> Unit,
    onPairedBluetoothClick: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusCard(
                    serviceConnected = serviceConnected,
                    active = active,
                    inactiveSummary = inactiveSummary,
                    bluetoothServiceResponsive = bluetoothServiceResponsive,
                    modifier = Modifier.weight(1f).height(112.dp),
                )
                StatCard(
                    title = stringResource(R.string.home_stat_bluetooth_status),
                    value = if (bluetoothEnabled) {
                        stringResource(R.string.home_stat_bluetooth_on)
                    } else {
                        stringResource(R.string.home_stat_bluetooth_off)
                    },
                    modifier = Modifier.weight(1f).height(112.dp),
                    onClick = onBluetoothStatusClick,
                )
                StatCard(
                    title = stringResource(R.string.home_stat_paired_bluetooth),
                    value = bondedDeviceCount.toString(),
                    modifier = Modifier.weight(1f).height(112.dp),
                    onClick = onPairedBluetoothClick,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusCard(
                    serviceConnected = serviceConnected,
                    active = active,
                    inactiveSummary = inactiveSummary,
                    bluetoothServiceResponsive = bluetoothServiceResponsive,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                )
                Column(
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        title = stringResource(R.string.home_stat_bluetooth_status),
                        value = if (bluetoothEnabled) {
                            stringResource(R.string.home_stat_bluetooth_on)
                        } else {
                            stringResource(R.string.home_stat_bluetooth_off)
                        },
                        modifier = Modifier.weight(1f),
                        onClick = onBluetoothStatusClick,
                    )
                    StatCard(
                        title = stringResource(R.string.home_stat_paired_bluetooth),
                        value = bondedDeviceCount.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = onPairedBluetoothClick,
                    )
                }
            }
        }
    }
}

/**
 * 模块状态卡（同参考 :133-193 的 StatusCard，配色与图标位置原样照搬）：
 *   ① 服务没连上 -> 红「LSPosed 未激活」；
 *   ② 服务连上但作用域没勾齐 -> 红「作用域不齐全」+ 缺哪几个；
 *   ③ 作用域齐全但蓝牙进程 75s 没响应 -> 橙「模块服务超时」；
 *   ④ 都正常 -> 绿「LSPosed 已激活」。
 */
@Composable
private fun StatusCard(
    serviceConnected: Boolean,
    active: Boolean,
    inactiveSummary: String,
    bluetoothServiceResponsive: Boolean,
    modifier: Modifier = Modifier,
) {
    val serviceTimeout = active && !bluetoothServiceResponsive
    val statusColor = when {
        !active -> Color(0xFFFF5A52)
        serviceTimeout -> Color(0xFFFF9F0A)
        else -> Color(0xFF36D167)
    }
    val statusBackground = when {
        !active -> Color(0xFFFFE5E3)
        serviceTimeout -> Color(0xFFFFF0D7)
        else -> Color(0xFFDFFAE4)
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = statusBackground),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().offset(34.dp, 38.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(136.dp),
                    imageVector = AppIcons.Headphones,
                    contentDescription = null,
                    tint = statusColor.copy(alpha = 0.78f),
                )
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = when {
                        !serviceConnected -> stringResource(R.string.lsposed_status_inactive)
                        !active -> stringResource(R.string.lsposed_status_scope_incomplete)
                        serviceTimeout -> stringResource(R.string.lsposed_status_service_timeout)
                        else -> stringResource(R.string.lsposed_status_active)
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF101010),
                )
                Text(
                    text = when {
                        !active -> inactiveSummary
                        serviceTimeout -> stringResource(R.string.lsposed_status_service_no_response)
                        else -> stringResource(R.string.lsposed_status_scopes_ok)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        !active -> Color(0xFFFF5A52)
                        serviceTimeout -> Color(0xFFFF9F0A)
                        else -> Color(0xFF2F3A32).copy(alpha = 0.78f)
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 一张可点的统计卡（同参考 :206-234）：标题 + 大号数值，整卡可点。 */
@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 只读的系统信息卡（同参考 :236-248 的六行）。 */
@Composable
private fun InfoCard(systemInfo: HomeSystemInfo, xposedService: XposedService?) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            InfoText(title = stringResource(R.string.info_system_version), content = systemInfo.systemVersion)
            InfoText(title = stringResource(R.string.info_app_version), content = systemInfo.appVersion)
            InfoText(title = stringResource(R.string.info_android_version), content = systemInfo.androidVersion)
            InfoText(
                title = stringResource(R.string.info_lsposed_version),
                content = lsposedVersionText(xposedService).ifBlank { stringResource(R.string.unknown_value) },
            )
            InfoText(
                title = stringResource(R.string.info_build_time),
                content = systemInfo.buildDate.ifBlank { stringResource(R.string.unknown_value) },
            )
            InfoText(
                title = stringResource(R.string.info_device_model),
                content = systemInfo.deviceModel.ifBlank { stringResource(R.string.unknown_value) },
                bottomPadding = 0.dp,
            )
        }
    }
}

/** 一行信息：标题（headline1 = 17sp）+ 内容（body2 = 14sp），行距由 bottomPadding 控制。 */
@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 24.dp) {
    Text(
        text = title,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = content,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}

/** 只读的构建/系统信息（同参考 :266-285 的 HomeSystemInfo）。 */
private data class HomeSystemInfo(
    val systemVersion: String,
    val appVersion: String,
    val androidVersion: String,
    val buildDate: String,
    val deviceModel: String,
)

private fun homeSystemInfo(context: Context): HomeSystemInfo {
    val buildTimestamp = BuildConfig.BUILD_TIMESTAMP
    // 兜底：非常规构建下 BUILD_TIMESTAMP 可能为 0，那就退回 APK 自己的安装/更新时间，绝不写死。
    val installedAt = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
    return HomeSystemInfo(
        systemVersion = Build.DISPLAY,
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        buildDate = buildDate(if (buildTimestamp > 0L) buildTimestamp else installedAt),
        deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
    )
}

/** 时间戳 -> yyyy-MM-dd HH:mm；<= 0 时给空串，由调用方回落成「未知」。 */
private fun buildDate(timeMillis: Long): String {
    if (timeMillis <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
}
