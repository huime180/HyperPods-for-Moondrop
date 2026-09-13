/*
 * HyperPods for Moondrop — 提示音音量滑条
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 说明：miuix 0.5.1（本项目 pin 的版本）里没有可确认存在的横向 Slider，
 * 这里用纯 Compose foundation（Canvas + pointerInput）自绘一个，
 * 避免引用未确认的 Miuix API，也不额外引入 material3 依赖。
 */
package moe.chenxy.hyperpods.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.chenxy.hyperpods.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SLIDER_ACCENT = Color(0xFF3482FF)

/**
 * 提示音音量滑条。
 *
 * @param value        UI 值（0..[max]）
 * @param max          设备档案里的 UI 最大值（[moe.chenxy.hyperpods.pods.PodSnapshot.promptVolumeMax]）
 * @param onValueChange 拖动过程中的即时回调（只更新本地 UI）
 * @param onCommit      拖动结束 / 点按时的回调（调用方在这里下发到设备）
 */
@Composable
fun PromptVolumeSlider(
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDarkMode = isSystemInDarkTheme()
    val safeMax = max.coerceAtLeast(1)
    val safeValue = value.coerceIn(0, safeMax)
    val trackWidth = remember { mutableIntStateOf(0) }
    val pending = remember { mutableIntStateOf(safeValue) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BasicText(
                text = stringResource(R.string.prompt_volume_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
            )
            BasicText(
                text = "$safeValue / $safeMax",
                style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .onSizeChanged { trackWidth.intValue = it.width }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val width = trackWidth.intValue
                        if (width > 0) {
                            val ui = fractionToValue(offset.x, width, safeMax)
                            pending.intValue = ui
                            onValueChange(ui)
                            onCommit(ui)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val width = trackWidth.intValue
                            if (width > 0) {
                                val ui = fractionToValue(offset.x, width, safeMax)
                                pending.intValue = ui
                                onValueChange(ui)
                            }
                        },
                        onDragEnd = { onCommit(pending.intValue) },
                        onHorizontalDrag = { change, _ ->
                            val width = trackWidth.intValue
                            if (width > 0) {
                                val ui = fractionToValue(change.position.x, width, safeMax)
                                pending.intValue = ui
                                onValueChange(ui)
                            }
                        }
                    )
                }
        ) {
            val centerY = size.height / 2f
            val trackHeight = 6.dp.toPx()
            val corner = trackHeight / 2f
            val fillWidth = size.width * (safeValue.toFloat() / safeMax)

            drawRoundRect(
                color = if (isDarkMode) Color(0xFF3A3A3C) else Color(0xFFE2E2E8),
                topLeft = Offset(0f, centerY - corner),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(corner, corner)
            )
            drawRoundRect(
                color = SLIDER_ACCENT,
                topLeft = Offset(0f, centerY - corner),
                size = Size(fillWidth, trackHeight),
                cornerRadius = CornerRadius(corner, corner)
            )
            val thumbRadius = 9.dp.toPx()
            val maxCenterX = (size.width - thumbRadius).coerceAtLeast(thumbRadius)
            drawCircle(
                color = SLIDER_ACCENT,
                radius = thumbRadius,
                center = Offset(fillWidth.coerceIn(thumbRadius, maxCenterX), centerY)
            )
        }
    }
}

private fun fractionToValue(x: Float, widthPx: Int, max: Int): Int =
    ((x / widthPx) * max).roundToInt().coerceIn(0, max)
