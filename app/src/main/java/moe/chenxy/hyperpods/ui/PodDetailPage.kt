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
 *     提示音开关 + 提示音音量合并成一行（components/PromptTone.kt）—— 与 OppoPods 同一套组件词汇。
 *
 * 行顺序（本项目的功能面）：机型 + 传输/编码 → 电量 → 降噪（三选一 + 子排）→ 增益
 *   → 指示灯 / 提示音(含音量) / LHDC / 双设备连接
 *   → 手势操作（单独一张卡的跳转行，hasGestures 门控）
 *   → 刷新 → 系统蓝牙设置 → 关于。
 * 所有功能行仍由 PodCapabilities 硬门控：能力位为 false 时该行不会出现在组合树里。
 * 「低延迟模式」开关已按用户要求移除（系统侧功能仍在系统蓝牙详情页可用），
 * 因此 hasLowLatency 不再门控任何 UI。
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.components.AncSwitch
import moe.chenxy.hyperpods.ui.components.MutualExclusionDialog
import moe.chenxy.hyperpods.ui.components.MutualExclusionTarget
import moe.chenxy.hyperpods.ui.components.PodStatus
import moe.chenxy.hyperpods.ui.components.PromptToneSection
import moe.chenxy.hyperpods.ui.components.rememberMutualExclusionState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
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
    onOpenGestures: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = snapshot.capabilities
    val exclusion = rememberMutualExclusionState()
    // 开关行卡片：任一能力位为 true 就出现（提示音与音量已合并为同一行）
    val hasSwitchCard = capabilities.hasLed ||
        capabilities.hasPromptTone ||
        capabilities.hasPromptVolume ||
        capabilities.hasLhdc ||
        capabilities.hasDualConnection

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

        if (hasSwitchCard) {
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
                    // 提示音开关 + 提示音音量：合并成同一行（内部按能力位决定显示开关/滑条）
                    PromptToneSection(snapshot)
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
                }
            }
        }

        if (capabilities.hasGestures) {
            item {
                Card(modifier = Modifier.padding(top = CARD_GAP)) {
                    ArrowPreference(
                        title = stringResource(R.string.gesture_title),
                        summary = stringResource(R.string.gesture_summary),
                        onClick = onOpenGestures,
                    )
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
            summary = activeCodecLabel(snapshot),
        )
    }
}

/** 系统报的编码名里带 LHDC 的（本 ROM 实测名：LHDCv5 / LHDC_V2 / LHDC_V3）。 */
private fun isLhdcCodec(name: String): Boolean = name.contains("LHDC", ignoreCase = true)

/**
 * 「当前编码」行的显示值 —— 显示永远不与 LHDC 开关自相矛盾。
 *
 * 两个数据来源相互独立，而且会短暂打架：
 *   · [PodSnapshot.activeCodec] = **系统 A2DP 实际协商到的编码**，由
 *     pods/MoondropLink.onSystemCodecChanged() 从 CODEC_CHANGED 广播填入（数据侧不在本文件职责内）；
 *   · [PodSnapshot.lhdcOn] = **耳机侧 GAIA 的 LHDC 开关**。
 * 在耳机上打开 LHDC 之后，系统侧要重新协商才会从 AAC 切到 LHDC，这段时间里 activeCodec
 * 仍然是 AAC。早先这里直接把 activeCodec 印出来，于是出现「LHDC 开关是开的、当前编码却
 * 写着 AAC」的自相矛盾（用户报的就是这个）。
 *
 * 规则：
 *   ① LHDC 开：系统报的还不是 LHDC（空 / AAC / SBC / LDAC）→ 显示「LHDC（系统切换中）」，
 *      **绝不**在此时把 AAC 当成当前编码；系统已经协商到 LHDC 则照实显示它。
 *   ② LHDC 关但系统还停在 LHDC：同样不能与开关冲突 → 显示「基础编码（系统切换中）」。
 *   ③ 其余：系统编码就是事实，照实显示（AAC 只会在 LHDC 关 / 未知时走到这里）。
 *   ④ 没读到系统编码：显示「未知」，不谎报 AAC。
 *
 * 真正不同步的病因在数据侧（系统编码广播与 GAIA 开关不同步、断开后 activeCodec 也不清空），
 * 本次改动范围只到 ui 目录，因此这里只保证 UI 不再背书一个与开关冲突的值。
 */
@Composable
private fun activeCodecLabel(snapshot: PodSnapshot): String {
    val systemCodec = snapshot.activeCodec.trim()
    val systemIsLhdc = isLhdcCodec(systemCodec)
    return when {
        snapshot.lhdcOn == true && !systemIsLhdc -> stringResource(R.string.codec_lhdc_pending)
        snapshot.lhdcOn == false && systemIsLhdc -> stringResource(R.string.codec_base_pending)
        systemCodec.isNotEmpty() -> systemCodec
        else -> stringResource(R.string.unknown_value)
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
