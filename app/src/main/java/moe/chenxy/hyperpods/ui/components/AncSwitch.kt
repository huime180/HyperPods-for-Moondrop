/*
 * HyperPods for Moondrop — 降噪分组控件（顶部三选一 + 降噪子排）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 交互（用户需求）：
 *   · 顶部一排三选一：通透 / 降噪 / 关闭（顺序固定）；
 *   · 选中「降噪」时，下方 AnimatedVisibility 展开子排：自适应 / 抗风 / 普通；
 *   · 切到「通透」或「关闭」时子排收起；
 *   · 设备回读的档位落在降噪三个子档位（NOISE_CANCELLATION / ADAPTIVE / ANTI_WIND）时，
 *     顶部「降噪」高亮；落在 TRANSPARENCY / OFF 时对应项高亮。
 *
 * 下标解析：三个顶级组与三个子档位的下标全部由 [PodSnapshot.ancModes] 的 indexOf 动态解析，
 * 设备没上报的档位直接不渲染，绝不写死下标。点「降噪」组时下发「上一次选过的子档位」，
 * 默认「普通」= AncMode.NOISE_CANCELLATION。
 *
 * 视觉：顶部与子排都是「圆角轨道 Surface + 选中项填色胶囊」，
 * 对齐参考实现 _refs/OppoPods/.../ui/components/AncSwitch.kt:100-155
 * （选项 = 圆角容器 + 居中文案 + pressable 按压反馈 + 选中态变色），
 * 并沿用本项目既有 AncSwitch 的 miuix Surface / RoundedCornerShape 词汇；
 * 颜色走 Miuix 语义 token（primary / onPrimary / secondaryContainer / onSecondaryVariant）。
 */
package moe.chenxy.hyperpods.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.core.AncMode
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

/** 顶部三选一里的「组」。 */
private enum class AncGroup { TRANSPARENCY, NOISE_CONTROL, OFF }

/** 一个可下发档位：带它在 [PodSnapshot.ancModes] 里的真实下标。 */
private data class AncChoice(val mode: AncMode, val index: Int)

/** 顶部一个选项：组 + 文案 + 点它要下发的档位。 */
private data class AncTopChoice(val group: AncGroup, val labelRes: Int, val target: AncChoice)

/** 降噪组下的三个子档位，顺序固定：自适应 / 抗风 / 普通。 */
private val SUB_MODE_ORDER = listOf(AncMode.ADAPTIVE, AncMode.ANTI_WIND, AncMode.NOISE_CANCELLATION)

/** 「降噪」组默认下发的子档位（普通降噪）。 */
private val DEFAULT_SUB_MODE = AncMode.NOISE_CANCELLATION

private val TRACK_CORNER = 14.dp
private val PILL_CORNER = 10.dp
private val PILL_HEIGHT = 44.dp
private val SUB_PILL_HEIGHT = 34.dp
private const val SELECT_ANIM_MS = 180

/** 乐观显示最长时间：设备 1.5s 内没回读到新档位就退回设备真实状态。 */
private const val OPTIMISTIC_TIMEOUT_MS = 1500L

/**
 * 降噪档位的本地化文案。
 *
 * 注意 [AncMode.LIVE] 仍然映射（否则 when 不穷尽）：LIVE 只出现在 MoondropModels 的
 * `ancV2Identity()` 里，而该档案没有任何机型引用（见 ADAPTATION.md:126、:390 的死代码结论），
 * 因此本期 UI 不渲染 LIVE 选项，只保留文案映射。
 */
@Composable
fun ancModeLabel(mode: AncMode): String = when (mode) {
    AncMode.OFF -> stringResource(R.string.anc_mode_off)
    AncMode.NOISE_CANCELLATION -> stringResource(R.string.anc_mode_nc)
    AncMode.TRANSPARENCY -> stringResource(R.string.anc_mode_transparency)
    AncMode.ANTI_WIND -> stringResource(R.string.anc_mode_anti_wind)
    AncMode.ADAPTIVE -> stringResource(R.string.anc_mode_adaptive)
    AncMode.LIVE -> stringResource(R.string.anc_mode_live)
}

/**
 * 子排文案：降噪组里的 NOISE_CANCELLATION 在子排上叫「普通」，避免与顶部组名「降噪」重复。
 */
@Composable
private fun subModeLabel(mode: AncMode): String = when (mode) {
    AncMode.NOISE_CANCELLATION -> stringResource(R.string.anc_mode_normal)
    else -> ancModeLabel(mode)
}

/** 设备档位 → 顶部组；null = 未知（-1 下标）或不属于三组（LIVE）。 */
private fun AncMode?.toAncGroup(): AncGroup? = when (this) {
    AncMode.TRANSPARENCY -> AncGroup.TRANSPARENCY
    AncMode.OFF -> AncGroup.OFF
    AncMode.NOISE_CANCELLATION, AncMode.ADAPTIVE, AncMode.ANTI_WIND -> AncGroup.NOISE_CONTROL
    else -> null
}

/**
 * 降噪控件（详情页与快速弹窗共用）。
 *
 * @param modes         设备上报的档位列表（顺序即设备下标顺序）
 * @param selectedIndex 当前档位下标；-1 表示未知
 * @param onSelect      点击回调，参数为设备下标，调用方直接交给
 *                      [moe.chenxy.hyperpods.pods.MoondropLink.setAnc]
 */
@Composable
fun AncSwitch(
    modes: List<AncMode>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (modes.isEmpty()) return

    // ── 下标全部动态解析（indexOf）；缺失的档位 takeIf 掉，不渲染 ──
    val transparencyIndex = modes.indexOf(AncMode.TRANSPARENCY).takeIf { it >= 0 }
    val offIndex = modes.indexOf(AncMode.OFF).takeIf { it >= 0 }
    val subChoices = SUB_MODE_ORDER.mapNotNull { mode ->
        modes.indexOf(mode).takeIf { it >= 0 }?.let { AncChoice(mode, it) }
    }

    val currentMode = modes.getOrNull(selectedIndex)

    // 乐观态：点下去立刻反馈；设备快照回来后以设备为准
    var pendingMode by remember(modes) { mutableStateOf<AncMode?>(null) }
    // 「降噪」组上一次选过的子档位，默认普通（普通降噪）
    var lastSubMode by remember(modes) { mutableStateOf(DEFAULT_SUB_MODE) }

    // 设备状态一变就以设备为准，同时记住设备当前所处的子档位
    LaunchedEffect(currentMode) {
        pendingMode = null
        val mode = currentMode
        if (mode != null && SUB_MODE_ORDER.contains(mode)) lastSubMode = mode
    }
    // 设备一直没回读（未连接 / 命令失败）时，超时退回真实状态，不让乐观态永久骗人
    LaunchedEffect(pendingMode) {
        if (pendingMode != null) {
            delay(OPTIMISTIC_TIMEOUT_MS)
            pendingMode = null
        }
    }

    val activeMode = pendingMode ?: currentMode
    val activeGroup = activeMode.toAncGroup()

    // 点「降噪」组时下发的子档位：优先上一次选过的，设备没上报就退回第一个可用子档位
    val groupTarget = subChoices.firstOrNull { it.mode == lastSubMode } ?: subChoices.firstOrNull()

    val topChoices = ArrayList<AncTopChoice>(3)
    if (transparencyIndex != null) {
        topChoices += AncTopChoice(
            group = AncGroup.TRANSPARENCY,
            labelRes = R.string.anc_mode_transparency,
            target = AncChoice(AncMode.TRANSPARENCY, transparencyIndex),
        )
    }
    if (groupTarget != null) {
        topChoices += AncTopChoice(
            group = AncGroup.NOISE_CONTROL,
            labelRes = R.string.anc_mode_nc,
            target = groupTarget,
        )
    }
    if (offIndex != null) {
        topChoices += AncTopChoice(
            group = AncGroup.OFF,
            labelRes = R.string.anc_mode_off,
            target = AncChoice(AncMode.OFF, offIndex),
        )
    }

    val send: (AncChoice) -> Unit = { target ->
        if (target.mode != currentMode) {
            pendingMode = target.mode
            if (SUB_MODE_ORDER.contains(target.mode)) lastSubMode = target.mode
            onSelect(target.index)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        // ── 顶部一排：通透 / 降噪 / 关闭 ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(TRACK_CORNER),
            color = MiuixTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                topChoices.forEach { top ->
                    AncPill(
                        label = stringResource(top.labelRes),
                        selected = activeGroup == top.group,
                        onClick = { send(top.target) },
                        modifier = Modifier.weight(1f),
                        height = PILL_HEIGHT,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // ── 「降噪」组展开子排：自适应 / 抗风 / 普通 ──
        AnimatedVisibility(
            visible = activeGroup == AncGroup.NOISE_CONTROL && subChoices.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                subChoices.forEach { choice ->
                    AncPill(
                        label = subModeLabel(choice.mode),
                        selected = activeGroup == AncGroup.NOISE_CONTROL && activeMode == choice.mode,
                        onClick = { send(choice) },
                        modifier = Modifier.weight(1f),
                        height = SUB_PILL_HEIGHT,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * 单个选项：圆角胶囊。选中填 [MiuixTheme.colorScheme.primary]，未选中透明（露出轨道底色），
 * 文案随选中态在 onPrimary / onSecondaryVariant 之间做 180ms 颜色动画；
 * 最多两行居中（英文 "Noise Cancelling" 在窄弹窗里会折行）。
 */
@Composable
private fun AncPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = PILL_HEIGHT,
    fontSize: TextUnit = 14.sp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(PILL_CORNER)
    val containerColor by animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(SELECT_ANIM_MS),
        label = "AncPillContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.onPrimary
        } else {
            MiuixTheme.colorScheme.onSecondaryVariant
        },
        animationSpec = tween(SELECT_ANIM_MS),
        label = "AncPillContent",
    )

    Surface(
        modifier = modifier
            .height(height)
            .clip(shape)
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = fontSize,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
