/*
 * HyperPods for Moondrop — 快速弹窗（PuddingPods 形态的主入口）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * PuddingPods 的入口分工：「点击超级岛或耳机入口，显示电量、降噪和快捷控制」——
 * 所以本 Activity 只放 电量 / 降噪 / 快捷控制，完整功能与设置交给详情页（MainActivity）。
 * 由广播 chen.action.hyperpods.moondrop.show_popup 启动（见 AndroidManifest）。
 *
 * 窗口做法：
 *   · 主题 Theme.HyperPodsMoondrop.Popup 逐条照抄 OppoPods 的 Theme.OppoPods.Popup
 *     （半透明 + 透明背景 + 无标题 + 不用系统遮罩）；
 *   · window.setBackgroundDrawable(ColorDrawable(TRANSPARENT)) 与 OppoPods
 *     ConnectionPopupActivity 同一写法（见 _refs/OppoPods/.../ConnectionPopupActivity.kt）；
 *   · 「背景变暗」和「点卡片外关闭」在 Compose 侧用遮罩 Box + clickable 实现
 *     （本仓库 miuix 产物没有可确认存在的 OverlayDialog）。
 */
package moe.chenxy.hyperpods.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import moe.chenxy.hyperpods.MainActivity
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.components.AncSwitch
import moe.chenxy.hyperpods.ui.components.PodStatus
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 弹窗背后遮罩的不透明度（替代系统 dim）。 */
private const val SCRIM_ALPHA = 0.35f

class PopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        // 与 OppoPods ConnectionPopupActivity 一致：窗口背景真透明，圆角与遮罩都交给 Compose
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        // 兜底：若厂商 ROM 把窗口做成非全屏，点窗口外同样关闭
        setFinishOnTouchOutside(true)

        setContent {
            AppTheme {
                PopupContent(
                    onMore = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
private fun PopupContent(onMore: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val snapshot = rememberPodSnapshot()
    val capabilities = snapshot.capabilities
    val hasQuickToggle = capabilities.hasPromptTone ||
        capabilities.hasLhdc ||
        capabilities.hasDualConnection ||
        capabilities.hasLowLatency

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 遮罩：既当「背景变暗」，又当「点卡片外关闭」
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp)
                // 吞掉卡片内的空白点击，避免误触遮罩把弹窗关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                PopupHeader(snapshot)

                if (!snapshot.connected) {
                    BasicText(
                        text = stringResource(R.string.waiting_for_pod_hint),
                        modifier = Modifier.padding(top = 10.dp),
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    )
                } else {
                    // ── 电量 ──────────────────────────────────────────────
                    SectionTitle(stringResource(R.string.battery_title))
                    PodStatus(snapshot.battery)

                    // ── 降噪（档位数由快照决定） ──────────────────────────
                    if (snapshot.ancModes.isNotEmpty()) {
                        SectionTitle(stringResource(R.string.anc_title))
                        AncSwitch(
                            modes = snapshot.ancModes,
                            selectedIndex = snapshot.ancIndex,
                            onSelect = { index -> MoondropLink.setAnc(index) }
                        )
                    }

                    // ── 快捷控制（能力门控） ──────────────────────────────
                    if (hasQuickToggle) {
                        SectionTitle(stringResource(R.string.quick_controls))
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
                                    // 系统侧功能：发给自己的 ControlReceiver → ControlBridge 处理
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

                Spacer(Modifier.height(8.dp))
                BasicComponent(
                    title = stringResource(R.string.more_settings),
                    onClick = onMore,
                    enabled = true
                )
                BasicComponent(
                    title = stringResource(R.string.close),
                    onClick = onClose,
                    enabled = true
                )
            }
        }
    }
}

/** 弹窗标题：型号名 + 连接状态。 */
@Composable
private fun PopupHeader(snapshot: PodSnapshot) {
    Text(
        snapshot.modelName.ifBlank { stringResource(R.string.unknown_model) },
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
    BasicText(
        text = if (snapshot.connected) {
            stringResource(R.string.conn_connected)
        } else {
            stringResource(R.string.conn_disconnected)
        },
        style = TextStyle(
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    )
}

/** 小结标题（纯 Compose，避免使用无法核实存在的 Miuix 标题组件）。 */
@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}
