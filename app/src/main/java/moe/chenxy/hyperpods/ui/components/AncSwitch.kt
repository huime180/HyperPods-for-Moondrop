/*
 * HyperPods for Moondrop — 降噪分段控件
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 分段数完全由 [PodSnapshot.ancModes] 决定（3..6 段），不再写死 4 段。
 * 轨道总宽 = segmentWidth * 段数，segmentWidth 取 min(82dp, 可用宽度 / 段数)，
 * 因此段数多时不会超出屏幕，段数少时居中。
 */
package moe.chenxy.hyperpods.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.core.AncMode
import top.yukonga.miuix.kmp.basic.Surface

/** 单个分段的最大宽度（段数少时用这个宽度，居中显示）。 */
private val MAX_SEGMENT_WIDTH = 82.dp

/** 轨道高度。 */
private val TRACK_HEIGHT = 46.dp

/** 降噪档位的本地化文案。 */
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
 * 降噪分段控件。
 *
 * @param modes        快照里的档位列表（顺序即 UI 顺序）
 * @param selectedIndex 当前档位下标；-1 表示未知（会吸附到第 0 段）
 * @param onSelect     点击回调，参数为 UI 下标，调用方直接交给 [moe.chenxy.hyperpods.pods.MoondropLink.setAnc]
 */
@Composable
fun AncSwitch(
    modes: List<AncMode>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = modes.map { ancModeLabel(it) }
    SegmentedSelector(
        labels = labels,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier
    )
}

/**
 * 通用分段选择器（降噪 / 增益共用）。
 *
 * @param labels        每段的文案
 * @param selectedIndex 当前选中下标
 */
@Composable
fun SegmentedSelector(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return

    val isDarkMode = isSystemInDarkTheme()
    val count = labels.size
    val selected = selectedIndex.coerceIn(0, count - 1)
    val labelFontSize = if (count >= 5) 9.sp else 11.sp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val segmentWidth = minOf(MAX_SEGMENT_WIDTH, maxWidth / count)
        val fullWidth = segmentWidth * count
        val thumbOffset = animateDpAsState(
            targetValue = segmentWidth * selected,
            label = "SegmentedThumbOffset",
            animationSpec = spring(0.78f, Spring.StiffnessLow)
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(fullWidth),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TRACK_HEIGHT)
                    .background(
                        if (isDarkMode) Color(0xFF3A3A3C) else Color(0xFFE2E2E8),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box {
                    // 白色滑块
                    Row(modifier = Modifier.width(segmentWidth).fillMaxHeight()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDarkMode) Color(0xFF5A5A5E) else Color.White,
                            modifier = Modifier
                                .width(segmentWidth)
                                .fillMaxHeight()
                                .padding(3.dp)
                                .offset(x = thumbOffset.value)
                                .shadow(10.dp, RoundedCornerShape(8.dp))
                        ) {}
                    }

                    // 每段的可点区域 + 文案
                    Row(
                        modifier = Modifier.width(fullWidth).fillMaxHeight(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        labels.forEachIndexed { index, label ->
                            Column(
                                modifier = Modifier
                                    .width(segmentWidth)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onSelect(index) }
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                BasicText(
                                    text = label,
                                    style = TextStyle(
                                        fontSize = labelFontSize,
                                        fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        color = if (isDarkMode) Color.White else Color(0xFF1B1B1F)
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
