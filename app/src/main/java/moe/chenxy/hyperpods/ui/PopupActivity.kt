/*
 * HyperPods for Moondrop — 快速弹窗（PuddingPods 形态的主入口）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式与组件逐条对齐参考实现 _refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/PopupActivity.kt：
 *   · 透明 Scaffold 里挂 miuix overlay.OverlayDialog（:213-251）——圆角、窗口变暗、
 *     点框外关闭都由 OverlayDialog 负责（enableWindowDim 默认 true）；
 *   · 内容是一叠 Card：电量（PodStatus）→ 降噪（AncSwitch：三选一 + 降噪子排）
 *     → 快捷开关（提示音含音量 / LHDC / 双设备连接）（:266-287）；
 *   · 底部一排等宽 TextButton：「更多设置」+「关闭」（:288-303）。
 * 由广播 chen.action.hyperpods.moondrop.show_popup 启动（见 AndroidManifest）。
 * 「低延迟模式」开关已按用户要求移除；hasLowLatency 不再门控任何 UI。
 * 弹窗也可能是用户第一眼看到的界面（超级岛/通知直达），因此这里同样主动申请一次运行时权限。
 */
package moe.chenxy.hyperpods.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.MainActivity
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.components.AncSwitch
import moe.chenxy.hyperpods.ui.components.MutualExclusionDialog
import moe.chenxy.hyperpods.ui.components.MutualExclusionState
import moe.chenxy.hyperpods.ui.components.MutualExclusionTarget
import moe.chenxy.hyperpods.ui.components.PodStatus
import moe.chenxy.hyperpods.ui.components.PromptToneSection
import moe.chenxy.hyperpods.ui.components.rememberMutualExclusionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // 应用打开时主动申请蓝牙/通知权限（已授权则什么都不做）
            RequestRuntimePermissionsOnLaunch()

            val colorSchemeMode = when (loadThemeMode(this@PopupActivity)) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            AppTheme(colorSchemeMode = colorSchemeMode) {
                PopupContent(
                    onMore = {
                        startActivity(Intent(this@PopupActivity, MainActivity::class.java))
                        finish()
                    },
                    onDone = { finish() },
                )
            }
        }
    }
}

@Composable
private fun PopupContent(onMore: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val snapshot = rememberPodSnapshot()
    val capabilities = snapshot.capabilities
    val exclusion = rememberMutualExclusionState()
    var showDialog by remember { mutableStateOf(true) }

    val isDarkMode = when (loadThemeMode(context)) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    // 与参考实现 PopupActivity.kt:210 同一取色逻辑（弹框底色自己给，避免透明卡片看不清）
    val dialogBgColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF7F7F7)
    // 提示音与提示音音量是同一行：任一能力位为 true 就该出现这张卡
    val hasQuickToggle = capabilities.hasPromptTone ||
        capabilities.hasPromptVolume ||
        capabilities.hasLhdc ||
        capabilities.hasDualConnection

    Scaffold(containerColor = Color.Transparent) { _ ->
        OverlayDialog(
            title = snapshot.modelName.ifBlank { stringResource(R.string.unknown_model) },
            show = showDialog,
            backgroundColor = dialogBgColor,
            onDismissRequest = { showDialog = false },
            onDismissFinished = { onDone() },
        ) {
            PopupBody(
                snapshot = snapshot,
                hasQuickToggle = hasQuickToggle,
                exclusion = exclusion,
                onMore = onMore,
                onClose = { showDialog = false },
            )
        }

        // LHDC / 双设备连接互斥确认框：同一 Scaffold 宿主，后组合所以叠在弹窗上层
        MutualExclusionDialog(state = exclusion)
    }
}

/** 弹窗正文：一叠 Card + 底部两个等宽 TextButton（对齐参考实现 PortraitPopupBody）。 */
@Composable
private fun PopupBody(
    snapshot: PodSnapshot,
    hasQuickToggle: Boolean,
    exclusion: MutualExclusionState,
    onMore: () -> Unit,
    onClose: () -> Unit,
) {
    val capabilities = snapshot.capabilities

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            PodStatus(
                battery = snapshot.battery,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            )
        }

        if (!snapshot.connected) {
            Text(
                text = stringResource(R.string.waiting_for_pod_hint),
                modifier = Modifier.padding(top = 10.dp),
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 13.sp,
            )
        } else {
            if (snapshot.ancModes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    AncSwitch(
                        modes = snapshot.ancModes,
                        selectedIndex = snapshot.ancIndex,
                        onSelect = { index -> MoondropLink.setAnc(index) },
                    )
                }
            }

            if (hasQuickToggle) {
                Spacer(Modifier.height(12.dp))
                SmallTitle(text = stringResource(R.string.quick_controls))
                Card(modifier = Modifier.fillMaxWidth()) {
                    // 提示音开关 + 提示音音量：合并成同一行（内部按能力位决定显示开关/滑条）
                    PromptToneSection(snapshot)
                    if (capabilities.hasLhdc) {
                        SwitchPreference(
                            title = stringResource(R.string.lhdc_title),
                            summary = stringResource(R.string.lhdc_summary),
                            checked = snapshot.lhdcOn ?: false,
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

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.more_settings),
                onClick = onMore,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.close),
                onClick = onClose,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
