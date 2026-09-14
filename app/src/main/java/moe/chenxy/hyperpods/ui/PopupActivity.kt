/*
 * HyperPods for Moondrop — 快速弹窗（PuddingPods 形态的主入口）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式与组件逐条对齐参考实现 moondrop-pods 的 PopupActivity.kt：
 *   · 透明 Scaffold 里挂 miuix overlay.OverlayDialog（该文件 :270-304）——圆角、窗口变暗、
 *     点框外关闭都由 OverlayDialog 负责（enableWindowDim 默认 true）；
 *   · 正文按屏幕方向分成 PortraitPopupBody 与 LandscapePopupBody 两套（该文件 :282-302 的分派，
 *     横屏正文是两列：左列电量 + 降噪，右列增益 + 其余）；取向同样用 LocalConfiguration 判断；
 *   · 内容是一叠 Card，块间 12dp：电量（PodStatus）→ 降噪（AncSwitch）→ 增益
 *     → 快捷控制（默认折叠；展开后是 提示音含音量 / LHDC / 双设备连接）；
 *   · 底部一排等宽 TextButton：「更多设置」+「关闭」。
 *
 * 保留本项目原有的两处硬性修复（参考实现没有，因为它们的弹窗没有这个裁切问题）：
 *   · 弹窗正文总高上界 + 可滚动中段；
 *   · 底部按钮行在滚动区之外，先于中段被测量，**绝不被压没**。
 *
 * 版式修复：内容被裁、「双设备连接」与底部按钮消失
 *   症状：行数增加后，弹窗里排在最后的行与两个底部按钮不显示了。
 *   根因：Miuix 0.9.3 的 DialogContentLayout 只在「大屏」档位给弹窗内容加高度上界
 *         `heightIn(max = 窗口高 * 2/3)`，手机档位是 `Dp.Unspecified`
 *         （miuix-ui 0.9.3 源码 layout/DialogContentLayout.kt:311）；正文又是一个
 *         **不可滚动的 Column**：Column 自上而下逐个子项测量，空间耗尽后排在末尾的子项
 *         只拿到 0 高度而被压没 —— 与用户报的现象一致。
 *   修法：正文 = 「可滚动中段（weight(1f, fill = false)）」+「固定底部按钮行」，
 *         外层 Column 给 heightIn(max = 屏高 × 60%)。底部行是非权重子项，Column 先测它，
 *         所以按钮既不会被压没、也滚不走；空间不够时由中段让步（滚动）。
 *   取屏幕高度做上界：屏幕不够高时（横屏）也能保证按钮行有位置。
 *
 * 由广播 chen.action.hyperpods.moondrop.show_popup 启动（见 AndroidManifest）。
 * 「低延迟模式」开关已按用户要求移除；hasLowLatency 不再门控任何 UI。
 * 弹窗也可能是用户第一眼看到的界面（超级岛/通知直达），因此这里同样主动申请一次运行时权限。
 */
package moe.chenxy.hyperpods.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 正文（中段 + 底部按钮）总高的上界 = 屏幕高度的这个比例。
 * 见文件头「版式修复」段：Miuix 手机档位不给弹窗高度上界，必须自己给。
 * 0.6 屏高 + 标题 / 窗口内边距 / 系统栏后仍在屏内，同时给滚动区留出足够高度。
 */
private const val POPUP_BODY_MAX_HEIGHT_FRACTION = 0.6f

/** 极小屏兜底：正文至少保留这么高，别把滚动区压成一条缝（仍小于 0.6 × 常见屏高）。 */
private val MIN_POPUP_BODY_HEIGHT = 160.dp

/** 卡片之间的统一间距（与参考实现弹窗里的 12dp 一致）。 */
private val CARD_GAP = 12.dp

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
    // 「快捷控制」默认折叠（用户需求：通知栏弹窗里的快捷操作可以折叠）；
    // 状态提到这里，横竖屏切换 / 重组都不会把用户展开的那次操作丢掉。
    var quickControlsExpanded by remember { mutableStateOf(false) }

    val isDarkMode = when (loadThemeMode(context)) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    // 与参考实现 PopupActivity.kt:267 同一取色逻辑（弹框底色自己给，避免透明卡片看不清）
    val dialogBgColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF7F7F7)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
                isLandscape = isLandscape,
                quickControlsExpanded = quickControlsExpanded,
                onToggleQuickControls = { quickControlsExpanded = !quickControlsExpanded },
                exclusion = exclusion,
                onMore = onMore,
                onClose = { showDialog = false },
            )
        }

        // LHDC / 双设备连接互斥确认框：同一 Scaffold 宿主，后组合所以叠在弹窗上层
        MutualExclusionDialog(state = exclusion)
    }
}

/**
 * 弹窗正文（对齐参考实现的两套正文，并保留本项目的裁切修复）：
 *
 * ```
 * Column(heightIn(max = 0.6 × 屏高))                        // 正文总高上界
 *   ├─ 中段 weight(1f, fill = false) + verticalScroll        // 竖屏一列 / 横屏两列，均可滚
 *   ├─ Spacer(16dp)
 *   └─ Row { 更多设置 | 关闭 }                                // 固定底部，永远可见
 * ```
 *
 * 底部 Row 与 Spacer 是非权重子项，Column 先测它们 → 它们**不可能**被压成 0 高度；
 * 空间不够时由中段的 `weight` 让步（滚动），而不是把底部按钮挤出弹窗。
 */
@Composable
private fun PopupBody(
    snapshot: PodSnapshot,
    hasQuickToggle: Boolean,
    isLandscape: Boolean,
    quickControlsExpanded: Boolean,
    onToggleQuickControls: () -> Unit,
    exclusion: MutualExclusionState,
    onMore: () -> Unit,
    onClose: () -> Unit,
) {
    val hasGain = snapshot.capabilities.hasGain && snapshot.gainLabels.isNotEmpty()

    // 正文总高上界（见文件头「版式修复」段）：取屏幕高度而不是弹窗高度，
    // 因为手机档位弹窗高度就是 Unspecified（没有上界可用）。
    val maxBodyHeight = (
        LocalConfiguration.current.screenHeightDp.dp * POPUP_BODY_MAX_HEIGHT_FRACTION
        ).coerceAtLeast(MIN_POPUP_BODY_HEIGHT)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxBodyHeight),
    ) {
        if (isLandscape) {
            // 横屏两列（同参考实现 LandscapePopupBody）：左列电量 + 降噪，右列增益 + 快捷控制。
            // 两列各自垂直滚动；底部按钮仍在两列之外，不会被挤出弹窗。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    BatteryCard(snapshot, compact = true)
                    if (!snapshot.connected) {
                        WaitingHint()
                    } else if (snapshot.ancModes.isNotEmpty()) {
                        Spacer(Modifier.height(CARD_GAP))
                        AncCard(snapshot, compact = true)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 未连接时右列留空（提示文案只在左列出现一次），不重复显示
                    if (snapshot.connected) {
                        if (hasGain) {
                            GainCard(snapshot)
                        }
                        if (hasQuickToggle) {
                            if (hasGain) Spacer(Modifier.height(CARD_GAP))
                            QuickControlsSection(
                                snapshot = snapshot,
                                expanded = quickControlsExpanded,
                                onToggle = onToggleQuickControls,
                                exclusion = exclusion,
                            )
                        }
                    }
                }
            }
        } else {
            // 竖屏一列（同参考实现 PortraitPopupBody 的内容顺序）。
            // weight(1f, fill = false)：内容短时按内容高度收（折叠态不多占高度），
            // 内容长时吃掉剩余空间并垂直滚动，绝不把下面的固定按钮顶出弹窗。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                BatteryCard(snapshot)

                if (!snapshot.connected) {
                    WaitingHint()
                } else {
                    // 降噪：常驻可见，保持在折叠组之外
                    if (snapshot.ancModes.isNotEmpty()) {
                        Spacer(Modifier.height(CARD_GAP))
                        AncCard(snapshot)
                    }

                    if (hasGain) {
                        Spacer(Modifier.height(CARD_GAP))
                        GainCard(snapshot)
                    }

                    if (hasQuickToggle) {
                        Spacer(Modifier.height(CARD_GAP))
                        QuickControlsSection(
                            snapshot = snapshot,
                            expanded = quickControlsExpanded,
                            onToggle = onToggleQuickControls,
                            exclusion = exclusion,
                        )
                    }
                }
            }
        }

        // ── 固定底部：更多设置 / 关闭（在滚动区之外，滚动永远够不到它们）──
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

/**
 * 电量卡（离线 / 单设备 / 三路电量的判定都在 components/PodStatus.kt 内）。
 *
 * @param compact 横屏弹窗用紧凑档（同参照实现 LandscapePopupBody 里 `compact = true` 的传法）
 */
@Composable
private fun BatteryCard(snapshot: PodSnapshot, compact: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth()) {
        PodStatus(
            battery = snapshot.battery,
            // 横屏紧凑档：内边距同步收窄（同参照实现 LandscapePopupBody 的 10dp/10dp）
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 10.dp else 16.dp,
            ),
            compact = compact,
        )
    }
}

/** 降噪卡：主排三格 + 降噪子排，档位来自设备真实上报的 ancModes（缺失档位不渲染）。 */
@Composable
private fun AncCard(snapshot: PodSnapshot, compact: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth()) {
        AncSwitch(
            modes = snapshot.ancModes,
            selectedIndex = snapshot.ancIndex,
            onSelect = { index -> MoondropLink.setAnc(index) },
            compact = compact,
        )
    }
}

/** 增益卡（同参考实现弹窗里的增益行）：能力位为真且设备上报了档位时才出现。 */
@Composable
private fun GainCard(snapshot: PodSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        OverlayDropdownPreference(
            title = stringResource(R.string.gain_title),
            items = snapshot.gainLabels,
            selectedIndex = snapshot.gainIndex.coerceIn(0, snapshot.gainLabels.lastIndex),
            onSelectedIndexChange = { index -> MoondropLink.setGain(index) },
        )
    }
}

/** 未连接时的中性提示（不显示任何 0% 之类的假数据）。 */
@Composable
private fun WaitingHint() {
    Text(
        text = stringResource(R.string.waiting_for_pod_hint),
        modifier = Modifier.padding(top = 10.dp),
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        fontSize = 13.sp,
    )
}

/**
 * 「快捷控制」可折叠分组：标题行（带展开 / 收起箭头）+ 展开后的开关卡
 * （提示音含音量 / LHDC / 双设备连接，每行都由能力位门控）。
 */
@Composable
private fun QuickControlsSection(
    snapshot: PodSnapshot,
    expanded: Boolean,
    onToggle: () -> Unit,
    exclusion: MutualExclusionState,
) {
    val capabilities = snapshot.capabilities

    QuickControlsHeader(expanded = expanded, onToggle = onToggle)
    // 收起时整块不渲染、不占高度；展开 / 收起走 expandVertically / shrinkVertically，
    // 与 ui/components/AncSwitch.kt 的降噪子排同一套写法
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
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

/**
 * 「快捷控制」分组头：整行可点，右侧箭头表示展开 / 收起。
 *
 * 版式（缩进与行高）沿用原先的 miuix SmallTitle：其默认内边距是
 * PaddingValues(28.dp, 8.dp)（0.9.3 源码 basic/SmallTitle.kt:37 SmallTitleDefaults.InsideMargin），
 * 文字样式同样取 MiuixTheme.textStyles.subtitle（SmallTitle 内部就是它），
 * 只是换成可点 Row 以便挂箭头指示器。箭头用 miuix-icons 的 ExpandMore / ExpandLess。
 */
@Composable
private fun QuickControlsHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.quick_controls),
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Icon(
            imageVector = if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.quick_controls_collapse else R.string.quick_controls_expand
            ),
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}
