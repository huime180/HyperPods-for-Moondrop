/*
 * HyperPods for Moondrop — 设备页（单页滚动）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式与行词汇对齐参考实现 _refs/OppoPods/.../ui/PodDetailPage.kt:94-212：
 *   · 顶层是 androidx 的 LazyColumn（miuix 0.9.3 自己不带 LazyColumn），
 *     contentPadding 由外层 Scaffold 的 innerPadding 推出，左右各 12dp；
 *   · 每块内容装在 miuix 的 Card 里，块间距 12dp；
 *   · 开关行用 preference.SwitchPreference（title + summary），
 *     下拉行用 preference.OverlayDropdownPreference，跳转行用 preference.ArrowPreference，
 *     滑条行用 preference.SliderPreference —— 与 OppoPods 同一套组件词汇。
 *
 * 行顺序（本项目的功能面）：机型 + 传输/编码 → 电量 → 降噪 → 增益 → 指示灯/提示音
 *   → 提示音音量 → LHDC → 双设备连接 → 低延迟 → 刷新 → 系统蓝牙设置 → 关于。
 * 所有功能行仍由 PodCapabilities 硬门控：能力位为 false 时该行不会出现在组合树里。
 */
package moe.chenxy.hyperpods.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.components.AncSwitch
import moe.chenxy.hyperpods.ui.components.MutualExclusionDialog
import moe.chenxy.hyperpods.ui.components.MutualExclusionTarget
import moe.chenxy.hyperpods.ui.components.PodStatus
import moe.chenxy.hyperpods.ui.components.rememberMutualExclusionState
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val VERIFIED_GREEN = Color(0xFF34C759)

/** 卡片之间的统一间距（与 OppoPods PodDetailPage 的 12dp 一致）。 */
private val CARD_GAP = 12.dp

/** 系统设置页读取的耳机 extra（与 hook/SettingsHeadsetHook.kt 的两个常量一致）。 */
private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"
private const val EXTRA_BT_ADDRESS = "bluetoothaddress"

/**
 * @param contentPadding 外层 Scaffold 的 innerPadding（顶部要避开折叠式 TopAppBar）
 * @param onOpenAbout    「关于」行 → 导航到关于页（由 MainUI 的返回栈负责）
 */
@Composable
fun PodDetailPage(
    contentPadding: PaddingValues,
    snapshot: PodSnapshot,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = snapshot.capabilities
    val exclusion = rememberMutualExclusionState()
    val hasSwitchRow = capabilities.hasLed || capabilities.hasPromptTone
    val hasCodecRow = capabilities.hasLhdc || capabilities.hasDualConnection || capabilities.hasLowLatency

    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + CARD_GAP,
            bottom = contentPadding.calculateBottomPadding() + CARD_GAP,
            start = 12.dp,
            end = 12.dp,
        ),
        overscrollEffect = null,
    ) {
        item { DeviceHeroCard(snapshot) }

        item { SmallTitle(text = stringResource(R.string.battery_title)) }

        item {
            Card {
                PodStatus(
                    battery = snapshot.battery,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                )
            }
        }

        if (snapshot.ancModes.isNotEmpty()) {
            item { SmallTitle(text = stringResource(R.string.anc_title)) }

            item {
                Card {
                    AncSwitch(
                        modes = snapshot.ancModes,
                        selectedIndex = snapshot.ancIndex,
                        onSelect = { index -> MoondropLink.setAnc(index) },
                    )
                }
            }
        }

        if (capabilities.hasGain && snapshot.gainLabels.isNotEmpty()) {
            item {
                Card(modifier = Modifier.padding(top = CARD_GAP)) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.gain_title),
                        items = snapshot.gainLabels,
                        selectedIndex = snapshot.gainIndex.coerceIn(0, snapshot.gainLabels.lastIndex),
                        onSelectedIndexChange = { index -> MoondropLink.setGain(index) },
                    )
                }
            }
        }

        if (hasSwitchRow || hasCodecRow || capabilities.hasPromptVolume) {
            item {
                Card(modifier = Modifier.padding(top = CARD_GAP)) {
                    if (capabilities.hasLed) {
                        SwitchPreference(
                            title = stringResource(R.string.led_title),
                            summary = stringResource(R.string.led_summary),
                            checked = snapshot.ledOn ?: false,
                            onCheckedChange = { on -> MoondropLink.setLed(on) },
                        )
                    }
                    if (capabilities.hasPromptTone) {
                        SwitchPreference(
                            title = stringResource(R.string.prompt_tone_title),
                            summary = stringResource(R.string.prompt_tone_summary),
                            checked = snapshot.promptToneOn ?: false,
                            onCheckedChange = { on -> MoondropLink.setPromptTone(on) },
                        )
                    }
                    if (capabilities.hasLhdc) {
                        SwitchPreference(
                            title = stringResource(R.string.lhdc_title),
                            summary = stringResource(R.string.lhdc_summary),
                            checked = snapshot.lhdcOn ?: false,
                            // LHDC 与双设备连接固件互斥：冲突时先弹确认框，不下发
                            onCheckedChange = { on ->
                                if (exclusion.request(snapshot, MutualExclusionTarget.LHDC, on)) {
                                    MoondropLink.setLhdc(on)
                                }
                            },
                        )
                    }
                    if (capabilities.hasDualConnection) {
                        SwitchPreference(
                            title = stringResource(R.string.dual_connection_title),
                            summary = stringResource(R.string.dual_connection_summary),
                            checked = snapshot.dualConnectionOn ?: false,
                            onCheckedChange = { on ->
                                if (exclusion.request(snapshot, MutualExclusionTarget.DUAL_CONNECTION, on)) {
                                    MoondropLink.setDualConnection(on)
                                }
                            },
                        )
                    }
                    if (capabilities.hasLowLatency) {
                        SwitchPreference(
                            title = stringResource(R.string.low_latency_title),
                            summary = stringResource(R.string.low_latency_summary),
                            checked = snapshot.lowLatencyOn ?: false,
                            onCheckedChange = { on ->
                                // 低延迟是 HyperOS 系统侧功能，不是 GAIA 命令：
                                // 广播给自己包名 → manifest 的 ControlReceiver → ControlBridge 处理并转发给蓝牙进程。
                                context.sendBroadcast(
                                    Intent(HyperPodsAction.LOW_LATENCY_SELECT)
                                        .setPackage(BuildConfig.APPLICATION_ID)
                                        .putExtra(HyperPodsAction.EXTRA_ENABLED, on)
                                )
                            },
                        )
                    }
                    if (capabilities.hasPromptVolume) {
                        PromptVolumePreference(snapshot)
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.padding(top = CARD_GAP)) {
                ArrowPreference(
                    title = stringResource(R.string.refresh),
                    summary = stringResource(R.string.refresh_summary),
                    onClick = { MoondropLink.refreshAll() },
                )
            }
        }

        if (snapshot.deviceAddress.isNotEmpty()) {
            item {
                Card(modifier = Modifier.padding(top = CARD_GAP)) {
                    ArrowPreference(
                        title = stringResource(R.string.system_bluetooth_settings),
                        summary = stringResource(R.string.system_bluetooth_settings_summary),
                        onClick = { openSystemBluetoothSettings(context, snapshot.deviceAddress) },
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.padding(top = CARD_GAP)) {
                ArrowPreference(
                    title = stringResource(R.string.about),
                    onClick = onOpenAbout,
                )
            }
        }
    }

    // 互斥确认框（OverlayDialog）与开关拦截器共用同一个 state
    MutualExclusionDialog(state = exclusion)
}

/** 机型卡：型号名（中文）、连接状态、可信标记、传输通道、当前编码。 */
@Composable
private fun DeviceHeroCard(snapshot: PodSnapshot) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = snapshot.modelName.ifBlank { stringResource(R.string.unknown_model) },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (snapshot.modelVerified) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(VERIFIED_GREEN.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.model_verified),
                            color = VERIFIED_GREEN,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Text(
                text = if (snapshot.connected) {
                    stringResource(R.string.conn_connected)
                } else {
                    stringResource(R.string.conn_disconnected)
                },
                modifier = Modifier.padding(top = 4.dp),
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 13.sp,
            )
        }

        BasicComponent(
            title = stringResource(R.string.transport),
            summary = snapshot.transport.ifBlank { stringResource(R.string.unknown_value) },
        )
        BasicComponent(
            title = stringResource(R.string.active_codec),
            summary = snapshot.activeCodec.ifBlank { stringResource(R.string.unknown_value) },
        )
    }
}

/**
 * 提示音音量行（SliderPreference：Miuix 原生滑条行，形态与参考实现的 preference 行一致）。
 *
 * UI 值 0..promptVolumeMax 与设备值**同一单位**（0..100 百分比）——官方 App 日志实测
 * `updateV2VoiceConf: enabled=true, volume=20, index=1`，不是 0..255 的原始字节，因此恒等映射。
 * 拖动过程中只改本地显示，松手（onValueChangeFinished）才下发，避免刷屏设备。
 */
@Composable
private fun PromptVolumePreference(snapshot: PodSnapshot) {
    val max = snapshot.promptVolumeMax.coerceAtLeast(1)
    val raw = snapshot.promptVolumeRaw
    val uiValue = if (raw < 0) 0 else raw.coerceIn(0, max)
    var localValue by remember(raw, max) { mutableIntStateOf(uiValue) }

    SliderPreference(
        title = stringResource(R.string.prompt_volume_title),
        value = localValue.toFloat(),
        onValueChange = { value -> localValue = value.roundToInt().coerceIn(0, max) },
        valueRange = 0f..max.toFloat(),
        valueText = "$localValue / $max",
        onValueChangeFinished = { MoondropLink.setPromptVolumeRaw(localValue) },
    )
}

/**
 * 打开系统蓝牙设备详情页（HyperOS 上是 MiuiHeadsetActivity，已被本模块 hook 接管并路由回本模块），
 * 里面有系统级的 LHDC / 低延迟 / 音量同步等开关。失败则退回系统蓝牙列表页。
 *
 * ⚠ 两个字符串常量非公开 API，取自本仓库 hook/SettingsHeadsetHook.kt（它正是从系统设置页的
 * intent 里读这两个 extra 的），因此与实际系统页面一致。
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun openSystemBluetoothSettings(context: Context, address: String) {
    val device = runCatching {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        } else {
            null
        }
    }.getOrNull()

    val opened = runCatching {
        context.startActivity(
            Intent(ACTION_BLUETOOTH_DEVICE_DETAIL_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (device != null) putExtra(EXTRA_DEVICE, device)
                putExtra(EXTRA_BT_ADDRESS, address)
            }
        )
    }.isSuccess
    if (opened) return

    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 系统蓝牙设备详情页 action（Settings 内部常量，非 SDK 公开 API）。 */
private const val ACTION_BLUETOOTH_DEVICE_DETAIL_SETTINGS = "android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS"
