/*
 * HyperPods for Moondrop — 电量卡片
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 电池图标全部用 Canvas 画（本项目没有 airpods_* 之类的图片资源，也不新增资源）。
 *
 * 关键约束：
 *   ① **未连接 / 没有数据的组件显示「离线」，绝不显示 0 %。**
 *      水月雨固件对未连接的一侧会回 0x00，pods 层的系统电量兜底
 *      （BatteryState.fallbackFromSystem）也会把系统广播的 0 写进 rawLeft/rawRight；
 *      旧 UI 把它当普通读数画出「0 %」，用户看到一只耳 0% 会误以为电量耗尽。
 *   ② 数据契约以 [BatterySnapshot] 的 leftKnown / rightKnown / caseKnown 为准
 *      （BATTERY_UNKNOWN = -1 表示无数据），**不要用 0 去当"未知"**；
 *      渲染层的「离线」判定见 isOffline()。
 *   ③ 设备可能只上报「单设备电量」（[BatterySnapshot.singleDevice]），
 *      此时继续显示一个「整机」数值（而不是左右两行）。
 *   ④ 三路都还没读到时（刚连上 / 完全未连接）保留既有的「正在读取电量…」过渡提示。
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
        when {
            // 三路都还没有数据（刚连上 / 完全未连接）：保留既有的过渡提示
            !battery.anyKnown -> {
                Text(stringResource(R.string.batt_unknown), fontSize = 13.sp)
            }

            // 单设备电量形态（type 0）：只显示一个「整机」数值
            battery.singleDevice -> {
                BatteryRow(
                    label = stringResource(R.string.batt_whole),
                    level = if (battery.leftKnown) battery.left else battery.right,
                    known = battery.leftKnown || battery.rightKnown,
                    charging = battery.leftCharging || battery.rightCharging,
                    darkMode = darkMode,
                )
            }

            // 分体电量：左耳 / 右耳 / 充电盒各一行；其中没有数据的行显示「离线」
            else -> {
                BatteryRow(
                    label = stringResource(R.string.batt_left),
                    level = battery.left,
                    known = battery.leftKnown,
                    charging = battery.leftCharging,
                    darkMode = darkMode,
                )
                // rightKnown == true 时必须给出右耳（「右耳不显示」是本项目要修的根因问题之一）；
                // false 时也不再"整行消失"，而是明确显示「离线」
                BatteryRow(
                    label = stringResource(R.string.batt_right),
                    level = battery.right,
                    known = battery.rightKnown,
                    charging = battery.rightCharging,
                    darkMode = darkMode,
                )
                BatteryRow(
                    label = stringResource(R.string.batt_case),
                    level = battery.case,
                    known = battery.caseKnown,
                    charging = battery.caseCharging,
                    darkMode = darkMode,
                )
            }
        }
    }
}

/**
 * 该组件是否按「离线」渲染。
 *
 * ① 数据契约：`*Known == false`（[moe.chenxy.hyperpods.core.BATTERY_UNKNOWN] = -1）＝ 本轮没有该组件的数据；
 * ② 读回 0：固件对未连接的一侧回 0x00、系统电量兜底也会写 0，
 *    而一个真能上报的耳机不可能是真的 0 %，所以 0 在**展示层**同样按「离线」处理，
 *    不画成 0 %（这正是用户报的现象）。
 *
 * 注意 ② 只是渲染层的取舍，没有改数据契约：`known` 谓词仍是「有没有数据」的唯一真值来源。
 */
private fun isOffline(known: Boolean, level: Int): Boolean = !known || level <= 0

/**
 * 一行：左侧标签，右侧画出来的电池 + 百分比；离线时显示「离线」（不显示 0 %，也不显示「充电中」）。
 *
 * @param level  原始读数（[BatterySnapshot] 的 left/right/case；-1 = 无数据）
 * @param known  对应的 leftKnown / rightKnown / caseKnown
 */
@Composable
fun BatteryRow(
    label: String,
    level: Int,
    known: Boolean = true,
    charging: Boolean = false,
    darkMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val offline = isOffline(known, level)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BasicText(
            // 离线组件不显示「充电中」角标
            text = if (charging && !offline) "$label · ${stringResource(R.string.batt_charging)}" else label,
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onBackground
            )
        )
        Battery(level = level, offline = offline, charging = charging, darkMode = darkMode)
    }
}

/** 电池图标 + 百分比文字；[offline] 为 true 时显示「离线」。 */
@Composable
fun Battery(
    level: Int,
    offline: Boolean = false,
    charging: Boolean = false,
    darkMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.width(100.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BatteryIcon(
            batteryLevel = if (offline) 0 else level.coerceIn(0, 100),
            isCharging = charging && !offline,
            isDarkMode = darkMode
        )
        BasicText(
            text = if (offline) stringResource(R.string.battery_offline) else "$level %",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (offline) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else batteryColor(level, charging, darkMode)
            )
        )
    }
}

/** 纯 Canvas 画的电池：外壳 + 按比例填充的电池芯 + 正极触点（离线时 level 传 0，只剩空心外壳）。 */
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
