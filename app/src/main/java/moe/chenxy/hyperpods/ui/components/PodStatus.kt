/*
 * HyperPods for Moondrop — 电量卡片
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 电池图标全部用 Canvas 画（本项目没有 airpods_* 之类的图片资源，也不新增资源）。
 * 关键约束：设备可能只上报「单设备电量」（[BatterySnapshot.singleDevice]），
 * 此时显示一个「整机」数值，而不是两个空白；rightKnown 为 true 时绝不渲染空的右耳。
 */
package moe.chenxy.hyperpods.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.BatterySnapshot
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 电量低于该百分比时用红色。 */
private const val LOW_BATTERY_THRESHOLD = 30

private val CHARGING_GREEN = Color(0xFF34C759)
private val LOW_BATTERY_RED = Color(0xFFFF3B30)

@Composable
fun PodStatus(battery: BatterySnapshot, modifier: Modifier = Modifier) {
    val darkMode = isSystemInDarkTheme()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (battery.singleDevice) {
            // 单设备电量形态（type 0）：只显示一个「整机」数值
            val level = if (battery.leftKnown) battery.left else battery.right
            if (level != -1) {
                BatteryRow(
                    label = stringResource(R.string.batt_whole),
                    level = level,
                    charging = battery.leftCharging || battery.rightCharging,
                    darkMode = darkMode
                )
            }
        } else {
            if (battery.leftKnown) {
                BatteryRow(
                    label = stringResource(R.string.batt_left),
                    level = battery.left,
                    charging = battery.leftCharging,
                    darkMode = darkMode
                )
            }
            // rightKnown == true 时必须渲染右耳（「右耳不显示」是本项目要修的根因问题之一）
            if (battery.rightKnown) {
                BatteryRow(
                    label = stringResource(R.string.batt_right),
                    level = battery.right,
                    charging = battery.rightCharging,
                    darkMode = darkMode
                )
            }
            if (battery.caseKnown) {
                BatteryRow(
                    label = stringResource(R.string.batt_case),
                    level = battery.case,
                    charging = battery.caseCharging,
                    darkMode = darkMode
                )
            }
        }

        if (!battery.anyKnown) {
            Text(stringResource(R.string.batt_unknown), fontSize = 13.sp)
        }
    }
}

/** 一行：左侧标签，右侧画出来的电池 + 百分比。 */
@Composable
fun BatteryRow(
    label: String,
    level: Int,
    charging: Boolean,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BasicText(
            text = if (charging && level > 0) "$label · ${stringResource(R.string.batt_charging)}" else label,
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onBackground
            )
        )
        Battery(level = level, charging = charging, darkMode = darkMode)
    }
}

/**
 * 电池图标 + 百分比文字。
 *
 * 未连接 / 无数据的组件**不显示 0 %，而显示「离线」**：
 * 水月雨固件对未连接的一侧常上报 0x00 或 0xFF，两者都不能当成"真的 0% 电量"，
 * 否则用户会看到一只耳显示 0% 而误以为电量耗尽。
 */
@Composable
fun Battery(level: Int, charging: Boolean, darkMode: Boolean, modifier: Modifier = Modifier) {
    val offline = level <= 0
    Row(
        modifier = modifier.width(100.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BatteryIcon(
            batteryLevel = level.coerceAtLeast(0),
            isCharging = charging && !offline,
            isDarkMode = darkMode,
        )
        BasicText(
            text = if (offline) stringResource(R.string.batt_offline) else "$level %",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (offline) MiuixTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                else batteryColor(level, charging, darkMode)
            )
        )
    }
}

/** 纯 Canvas 画的电池：外壳 + 按比例填充的电池芯 + 正极触点。 */
@Composable
fun BatteryIcon(batteryLevel: Int, isCharging: Boolean, isDarkMode: Boolean, modifier: Modifier = Modifier) {
    val outline = if (isDarkMode) Color.White else Color.DarkGray
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(50.dp)
            .height(20.dp)
            .border(1.dp, outline, RoundedCornerShape(5.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
            val fillWidth = size.width * (batteryLevel.coerceIn(0, 100) / 100f)
            drawRoundRect(
                color = batteryColor(batteryLevel, isCharging, isDarkMode),
                size = Size(fillWidth, size.height),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = Fill
            )
        }

        Canvas(modifier = Modifier.align(Alignment.CenterEnd)) {
            drawRoundRect(
                color = outline,
                topLeft = Offset(x = 1f, y = -1.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                size = Size(2.dp.toPx(), 4.dp.toPx())
            )
        }
    }
}

/** 充电 = 绿色；≤30% = 红色；其余 = 中性色。 */
private fun batteryColor(level: Int, charging: Boolean, darkMode: Boolean): Color = when {
    charging -> CHARGING_GREEN
    level <= LOW_BATTERY_THRESHOLD -> LOW_BATTERY_RED
    darkMode -> Color.LightGray
    else -> Color.Gray
}
