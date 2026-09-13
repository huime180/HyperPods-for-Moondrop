/*
 * HyperPods for Moondrop — 详情页（单页滚动）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 顺序照 PuddingPods 的详情页：机型 → 电量 → 降噪 → 增益 → 指示灯 → 提示音 → 提示音音量
 *                            → LHDC → 双设备连接 → 低延迟 → 刷新 → 系统蓝牙设置 → 关于
 * 所有功能行都由 PodCapabilities 硬门控：能力位为 false 时该行不会出现在组合树里。
 *
 * 列表容器用 androidx.compose.foundation.lazy.LazyColumn：
 * 本仓库解析到的 miuix 产物没有 top.yukonga.miuix.kmp.basic.LazyColumn（CI 实测），
 * 所以 topAppBarScrollBehavior 只能作为保留参数，无法绑到列表上。
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.components.AncSwitch
import moe.chenxy.hyperpods.ui.components.PodStatus
import moe.chenxy.hyperpods.ui.components.PromptVolumeSlider
import moe.chenxy.hyperpods.ui.components.SegmentedSelector
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.getWindowSize

private val VERIFIED_GREEN = Color(0xFF34C759)

/** 系统设置页读取的耳机 extra（与 hook/SettingsHeadsetHook.kt 的两个常量一致）。 */
private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"
private const val EXTRA_BT_ADDRESS = "bluetoothaddress"

/**
 * @param topAppBarScrollBehavior 保留参数：Miuix 的折叠式 LazyColumn 在当前 miuix 产物里不存在，
 *   等依赖版本支持后再用它绑定 `Modifier.nestedScroll(...)`。目前不参与布局。
 */
@Composable
fun PodDetailPage(
    topAppBarScrollBehavior: ScrollBehavior,
    padding: PaddingValues,
    snapshot: PodSnapshot,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = snapshot.capabilities
    val hasSwitchRow = capabilities.hasLed || capabilities.hasPromptTone
    val hasCodecRow = capabilities.hasLhdc || capabilities.hasDualConnection || capabilities.hasLowLatency

    LazyColumn(
        modifier = modifier.height(getWindowSize().height.dp),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)
    ) {
        if (!snapshot.connected) {
            item {
                WaitingPodsPage()
            }
        } else {
            item { DeviceHeroCard(snapshot) }
            item { BatteryCard(snapshot) }

            if (snapshot.ancModes.isNotEmpty()) {
                item { AncCard(snapshot) }
            }

            if (capabilities.hasGain) {
                item { GainCard(snapshot) }
            }

            if (hasSwitchRow) {
                item { SwitchCard(snapshot) }
            }

            if (capabilities.hasPromptVolume) {
                item {
                    Card(
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    ) {
                        PromptVolumeRow(snapshot)
                    }
                }
            }

            if (hasCodecRow) {
                item { CodecCard(snapshot) }
            }
        }

        item { RefreshCard() }

        if (snapshot.deviceAddress.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
                ) {
                    BasicComponent(
                        title = stringResource(R.string.system_bluetooth_settings),
                        summary = stringResource(R.string.system_bluetooth_settings_summary),
                        onClick = { openSystemBluetoothSettings(context, snapshot.deviceAddress) },
                        enabled = true
                    )
                }
            }
        }

        item {
            AboutBlock(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp))
        }
    }
}

/** 顶部机型卡：型号名（中文）、连接状态、可信标记、传输通道、当前编码。 */
@Composable
private fun DeviceHeroCard(snapshot: PodSnapshot) {
    Card(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snapshot.modelName.ifBlank { stringResource(R.string.unknown_model) },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (snapshot.modelVerified) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(VERIFIED_GREEN.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        BasicText(
                            text = stringResource(R.string.model_verified),
                            style = TextStyle(fontSize = 11.sp, color = VERIFIED_GREEN)
                        )
                    }
                }
            }
            BasicText(
                text = if (snapshot.connected) {
                    stringResource(R.string.conn_connected)
                } else {
                    stringResource(R.string.conn_disconnected)
                },
                style = TextStyle(
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            )
        }

        BasicComponent(
            title = stringResource(R.string.transport),
            summary = snapshot.transport.ifBlank { stringResource(R.string.unknown_value) },
            enabled = false
        )
        BasicComponent(
            title = stringResource(R.string.active_codec),
            summary = snapshot.activeCodec.ifBlank { stringResource(R.string.unknown_value) },
            enabled = false
        )
    }
}

@Composable
private fun BatteryCard(snapshot: PodSnapshot) {
    Card(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(stringResource(R.string.battery_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            PodStatus(snapshot.battery)
        }
    }
}

@Composable
private fun AncCard(snapshot: PodSnapshot) {
    Card(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(stringResource(R.string.anc_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            AncSwitch(
                modes = snapshot.ancModes,
                selectedIndex = snapshot.ancIndex,
                onSelect = { index -> MoondropLink.setAnc(index) }
            )
        }
    }
}

@Composable
private fun GainCard(snapshot: PodSnapshot) {
    Card(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(stringResource(R.string.gain_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            SegmentedSelector(
                labels = snapshot.gainLabels,
                selectedIndex = snapshot.gainIndex,
                onSelect = { index -> MoondropLink.setGain(index) }
            )
        }
    }
}

/** 指示灯 + 提示音开关（各自能力门控）。 */
@Composable
private fun SwitchCard(snapshot: PodSnapshot) {
    val capabilities = snapshot.capabilities
    Card(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {
        if (capabilities.hasLed) {
            SuperSwitch(
                title = stringResource(R.string.led_title),
                summary = stringResource(R.string.led_summary),
                checked = snapshot.ledOn ?: false,
                onCheckedChange = { on -> MoondropLink.setLed(on) }
            )
        }
        if (capabilities.hasPromptTone) {
            SuperSwitch(
                title = stringResource(R.string.prompt_tone_title),
                summary = stringResource(R.string.prompt_tone_summary),
                checked = snapshot.promptToneOn ?: false,
                onCheckedChange = { on -> MoondropLink.setPromptTone(on) }
            )
        }
    }
}

/** LHDC + 双设备连接 + 低延迟（各自能力门控）。 */
@Composable
private fun CodecCard(snapshot: PodSnapshot) {
    val context = LocalContext.current
    val capabilities = snapshot.capabilities
    Card(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {
        if (capabilities.hasLhdc) {
            SuperSwitch(
                title = stringResource(R.string.lhdc_title),
                summary = stringResource(R.string.lhdc_summary),
                checked = snapshot.lhdcOn ?: false,
                onCheckedChange = { on -> MoondropLink.setLhdc(on) }
            )
        }
        if (capabilities.hasDualConnection) {
            SuperSwitch(
                title = stringResource(R.string.dual_connection_title),
                summary = stringResource(R.string.dual_connection_summary),
                checked = snapshot.dualConnectionOn ?: false,
                onCheckedChange = { on -> MoondropLink.setDualConnection(on) }
            )
        }
        if (capabilities.hasLowLatency) {
            SuperSwitch(
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
                }
            )
        }
    }
}

@Composable
private fun RefreshCard() {
    Card(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {
        BasicComponent(
            title = stringResource(R.string.refresh),
            summary = stringResource(R.string.refresh_summary),
            onClick = { MoondropLink.refreshAll() },
            enabled = true
        )
    }
}

/**
 * 提示音音量。
 *
 * UI 值 0..promptVolumeMax 与设备值**同一单位**（0..100 百分比）——官方 App 日志实测
 * `updateV2VoiceConf: enabled=true, volume=20, index=1`，音量不是 0..255 的原始字节。
 * 因此这里恒等映射；拖动过程中只改本地显示，松手才下发，避免刷屏设备。
 */
@Composable
private fun PromptVolumeRow(snapshot: PodSnapshot) {
    val max = snapshot.promptVolumeMax.coerceAtLeast(1)
    val raw = snapshot.promptVolumeRaw
    val uiValue = if (raw < 0) 0 else raw.coerceIn(0, max)
    val localVolume = remember(raw, max) { mutableIntStateOf(uiValue) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        BasicText(
            text = stringResource(R.string.prompt_volume_caption),
            style = TextStyle(
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        )
        PromptVolumeSlider(
            value = localVolume.intValue,
            max = max,
            onValueChange = { ui -> localVolume.intValue = ui },
            onCommit = { ui ->
                MoondropLink.setPromptVolumeRaw(ui.coerceIn(0, max))
            }
        )
    }
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
