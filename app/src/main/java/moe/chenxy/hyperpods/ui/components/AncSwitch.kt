/*
 * HyperPods for Moondrop — 降噪选择器（主排三格 + 降噪子排）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 形态对齐参照实现 _refs/own-HyperPods 的 ui/components/AncSwitch.kt:277-332（AncButton）
 * 与 :408-496（MoondropAncSwitch）—— 本次「换 HyperPods 风格」的参照物：
 *   · 主排最多三格：通透 → 降噪 → 关闭（顺序固定），**没有轨道底色**；
 *   · 每格是「图标在上、文案在下」的竖排按钮（图标 60dp / 紧凑档 40dp），
 *     选中时换成 on 变体图标（参照实现用 Crossfade 做切换动画）+ primary 文案色；
 *   · 「降噪」当前生效时，下方展开子排：自定义 / 抗风噪 / 基本（参照实现的 ANC_SUB_ORDER
 *     adaptive → anti_wind → anc 与本项目既有的 SUB_MODE_ORDER 完全同序）；
 *   · 横屏弹窗用 [compact] 档（图标 40dp、内边距减半）。
 *
 * 档位来源与「不摆假控件」：
 *   档位表是 [moe.chenxy.hyperpods.pods.PodSnapshot.ancModes]
 *   （= pods/MoondropLink.kt 按型号档案给出的 UI 顺序表），
 *   主排/子排的每一项都用 `indexOf` 在**这张表里**解析真实下标，表里没有的档位直接不渲染 ——
 *   绝不写死下标、也不摆一个拨不动的假控件。拿不到的档位在本项目里意味着
 *   该型号档案没有这一档（或能力探测还没回来，ancModes 为空时整个控件不出现）。
 *
 * 乐观态（本项目的机制，参照实现没有）：
 *   点下去立刻反馈，设备 1.5s 内没回读就退回设备真实状态 —— 不让乐观态永久骗人。
 *
 * 图标：主排三格与降噪子排三档各带一枚矢量图标（on/off 两态，与参照实现逐项对应）：
 *   通透   → ic_transparent_on / ic_transparent_off
 *   降噪   → ic_openanc_on      / ic_openanc_off
 *   关闭   → ic_closeanc_on     / ic_closeanc_off
 *   自定义 → ic_adaptive_on     / ic_adaptive_off（参照实现的 adaptive 图标，本轮搬入）
 *   抗风噪 → ic_anti_wind_on    / ic_anti_wind_off（两边仓库都没有现成图，按同一规格自绘）
 *   基本   → 复用降噪族 ic_openanc_*（参照实现的子排也是这么映射：只有 adaptive 单独配图）
 * 这些都是「彩色圆底 + 白色字形」的自带底色徽标，因此不再叠 tint，直接原样绘制；
 * idle 图标另有 drawable-night 变体（深灰圆底 + 白字形，配色随参照）。
 * 子排与参照实现一样直接复用 [AncButton]（compact 档），因此三档同样有图标，不再只有文案。
 */
package moe.chenxy.hyperpods.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.core.AncMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

/** 顶部三选一里的「组」。 */
private enum class AncGroup { TRANSPARENCY, NOISE_CONTROL, OFF }

/** 一个可下发档位：带它在 [PodSnapshot.ancModes] 里的真实下标。 */
private data class AncChoice(val mode: AncMode, val index: Int)

/** 主排一格：组 + 文案 + 点它要下发的档位 + on/off 两枚图标资源 id。 */
private data class AncTopChoice(
    val group: AncGroup,
    val labelRes: Int,
    val target: AncChoice,
    @androidx.annotation.DrawableRes val onIconRes: Int,
    @androidx.annotation.DrawableRes val offIconRes: Int,
)

/** 降噪组下的三个子档位，顺序固定：自适应 / 抗风 / 普通（与参照实现同序）。 */
private val SUB_MODE_ORDER = listOf(AncMode.ADAPTIVE, AncMode.ANTI_WIND, AncMode.NOISE_CANCELLATION)

/** 「降噪」组默认下发的子档位（普通降噪）。 */
private val DEFAULT_SUB_MODE = AncMode.NOISE_CANCELLATION

private const val ANIM_DURATION = 300

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
 * 子排文案：降噪组里的 NOISE_CANCELLATION 在子排上叫「基本」，避免与主排组名「降噪」重复。
 */
@Composable
private fun subModeLabel(mode: AncMode): String = when (mode) {
    AncMode.NOISE_CANCELLATION -> stringResource(R.string.anc_mode_normal)
    else -> ancModeLabel(mode)
}

/**
 * 子排每档的 on/off 图标。
 *
 * 「自定义」用参照实现的 ic_adaptive_*；「抗风噪」两边仓库都没有现成图，按同一规格自绘
 * （见文件头）；「基本」复用降噪族的 ic_openanc_* —— 与参照实现的子排映射一致
 * （参照同样只给 adaptive 单独配图，其余都落在 openanc 上）。
 */
@androidx.annotation.DrawableRes
private fun subModeOnIcon(mode: AncMode): Int = when (mode) {
    AncMode.ADAPTIVE -> R.drawable.ic_adaptive_on
    AncMode.ANTI_WIND -> R.drawable.ic_anti_wind_on
    else -> R.drawable.ic_openanc_on
}

@androidx.annotation.DrawableRes
private fun subModeOffIcon(mode: AncMode): Int = when (mode) {
    AncMode.ADAPTIVE -> R.drawable.ic_adaptive_off
    AncMode.ANTI_WIND -> R.drawable.ic_anti_wind_off
    else -> R.drawable.ic_openanc_off
}

/** 设备档位 → 主排组；null = 未知（-1 下标）或不属于三组（LIVE）。 */
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
 * @param compact       横屏弹窗的紧凑档
 */
@Composable
fun AncSwitch(
    modes: List<AncMode>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
            onIconRes = R.drawable.ic_transparent_on,
            offIconRes = R.drawable.ic_transparent_off,
        )
    }
    if (groupTarget != null) {
        topChoices += AncTopChoice(
            group = AncGroup.NOISE_CONTROL,
            labelRes = R.string.anc_mode_nc,
            target = groupTarget,
            onIconRes = R.drawable.ic_openanc_on,
            offIconRes = R.drawable.ic_openanc_off,
        )
    }
    if (offIndex != null) {
        topChoices += AncTopChoice(
            group = AncGroup.OFF,
            labelRes = R.string.anc_mode_off,
            target = AncChoice(AncMode.OFF, offIndex),
            onIconRes = R.drawable.ic_closeanc_on,
            offIconRes = R.drawable.ic_closeanc_off,
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
            .padding(vertical = if (compact) 8.dp else 16.dp)
    ) {
        // ── 主排：通透 / 降噪 / 关闭（图标在上、文案在下）──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            topChoices.forEach { top ->
                AncButton(
                    onIconRes = top.onIconRes,
                    offIconRes = top.offIconRes,
                    label = stringResource(top.labelRes),
                    isSelected = activeGroup == top.group,
                    onClick = { send(top.target) },
                    modifier = Modifier.weight(1f),
                    compact = compact,
                )
            }
        }

        // ── 「降噪」组展开子排：自定义 / 抗风噪 / 基本 ──
        // 只有一种降噪时没有可选性，整排不出现（同参照实现 ncVariants.size > 1）
        if (activeGroup == AncGroup.NOISE_CONTROL && subChoices.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            ) {
                subChoices.forEach { choice ->
                    // 与参照实现的子排一致：复用主排同一个 AncButton，取 compact 档
                    AncButton(
                        onIconRes = subModeOnIcon(choice.mode),
                        offIconRes = subModeOffIcon(choice.mode),
                        label = subModeLabel(choice.mode),
                        isSelected = activeMode == choice.mode,
                        onClick = { send(choice) },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                }
            }
        }
    }
}

/**
 * 主排一格：图标（上）+ 文案（下）。选中时换 on 变体图标（Crossfade，同参照实现），
 * 文案色在 onBackground / primary 之间做 300ms 动画。
 *
 * 图标自带底色，因此不再叠 tint，直接用 [themedPainterResource] 原样绘制。
 */
@Composable
private fun AncButton(
    @androidx.annotation.DrawableRes offIconRes: Int,
    @androidx.annotation.DrawableRes onIconRes: Int,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val boxSize = if (compact) 40.dp else 60.dp
    val idleIconSize = if (compact) 32.dp else 48.dp

    val textColor by animateColorAsState(
        targetValue = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
        animationSpec = tween(ANIM_DURATION),
        label = "anc_text_color",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(modifier = Modifier.size(boxSize), contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = isSelected,
                animationSpec = tween(ANIM_DURATION),
                label = "anc_icon",
            ) { selected ->
                Image(
                    painter = themedPainterResource(if (selected) onIconRes else offIconRes),
                    contentDescription = label,
                    modifier = Modifier.size(if (selected) boxSize else idleIconSize),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/**
 * 按**应用内主题**（而不是系统 uiMode）取图标：本项目的 AppTheme 会在应用内强制浅色/深色时
 * 覆写 LocalConfiguration，而 [painterResource] 只认 LocalContext.resources 的系统 uiMode，
 * 因此应用内主题与系统不一致时会取到另一套 night / day 变体。
 *
 * 与系统 uiMode 一致时直接走 [painterResource]（零额外开销）；不一致时才按应用主题重建
 * Resources 取出 drawable 并转成 [BitmapPainter]。逐字对齐参照实现
 * _refs/own-HyperPods ui/components/AncSwitch.kt:338-372 的同名函数。
 */
@Composable
private fun themedPainterResource(@androidx.annotation.DrawableRes id: Int): Painter {
    val context = LocalContext.current
    val themeConfig = LocalConfiguration.current
    val sysNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val themeNightMode = themeConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK

    return if (sysNightMode == themeNightMode) {
        painterResource(id)
    } else {
        val themedResources = remember(context, themeNightMode) {
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or themeNightMode
                }
            ).resources
        }
        remember(id, themeNightMode) {
            val drawable = themedResources.getDrawable(id, null)
            if (drawable is BitmapDrawable) {
                BitmapPainter(drawable.bitmap.asImageBitmap())
            } else {
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                BitmapPainter(bitmap.asImageBitmap())
            }
        }
    }
}
