/*
 * HyperPods for Moondrop — 耳机详情页
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 所有开关行都由 PodCapabilities 硬门控：能力位为 false 时该行根本不会出现在组合树里。
 *
 * 列表容器用的是 androidx.compose.foundation.lazy.LazyColumn：
 * 本仓库解析到的 miuix 产物没有 top.yukonga.miuix.kmp.basic.LazyColumn（CI 实测），
 * 因此 topAppBarScrollBehavior 只能作为保留参数（见下方注释），无法绑到列表上。
 */
package moe.chenxy.hyperpods.ui

import android.content.Intent
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
    val capabilities = snapshot.capabilities
    val hasAnyToggle = capabilities.hasLed ||
        capabilities.hasPromptTone ||
        capabilities.hasLhdc ||
        capabilities.hasDualConnection ||
        capabilities.hasLowLatency

    LazyColumn(
        modifier = modifier.height(getWindowSize().height.dp),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)
    ) {
        item {
            DeviceHeroCard(snapshot)
        }

        item {
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

        if (snapshot.ancModes.isNotEmpty()) {
            item {
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
        }

        if (capabilities.hasGain) {
            item {
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
        }

        if (hasAnyToggle) {
            item {
                ToggleCard(snapshot)
            }
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

        item {
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

/** 能力门控的开关区：指示灯 / 提示音 / LHDC / 双设备连接 / 低延迟。 */
@Composable
private fun ToggleCard(snapshot: PodSnapshot) {
    val context = LocalContext.current
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

/**
 * 提示音音量。
 *
 * UI 值 0..promptVolumeMax ↔ 设备原始字节：raw = ui * 255 / max，ui = raw * max / 255。
 * 拖动过程中只改本地显示，松手才下发，避免刷屏设备。
 */
@Composable
private fun PromptVolumeRow(snapshot: PodSnapshot) {
    val max = snapshot.promptVolumeMax.coerceAtLeast(1)
    val raw = snapshot.promptVolumeRaw
    val uiValue = if (raw < 0) 0 else (raw * max / 255).coerceIn(0, max)
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
                MoondropLink.setPromptVolumeRaw((ui * 255 / max).coerceIn(0, 255))
            }
        )
    }
}
