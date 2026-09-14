/*
 * HyperPods for Moondrop — 电量卡片（三栏式）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式对齐参照实现 _refs/own-HyperPods 的 ui/components/PodStatus.kt:43-149
 * （本次「换 HyperPods 风格」的参照物）：
 *   · 一整行、三个等宽栏（左耳 / 右耳 / 充电盒），栏间是 0.5dp 的细分隔线；
 *   · 每栏自上而下：标签（粗体、短标签用全角空格补齐到三字宽）→ 百分比 →
 *     10 档 PNG 电池图标（drawable-nodpi 的 common_1..10 / charge_1..10，
 *     充电态换 charge_*，深色变体在 drawable-night-nodpi）；
 *   · 横屏用 [compact] 收窄高度（弹窗横屏正文即如此传参）。
 *
 * 与参照实现**不同的**两处取舍（都是本项目既有的口径，刻意保留）：
 *   ① 没有读数的一侧 / 读回 0 显示「离线」，绝不显示 0 %
 *      —— 固件对未连接的一侧回 0x00，系统电量兜底也会写 0（见 BatteryState），
 *      真能上报的耳机不可能是真的 0 %；参照实现在「未连接」时显示「-」，
 *      本项目统一成「离线」这一个词，中英两套 strings 里本来就有它。
 *   ② [BatterySnapshot.singleDevice]（只上报整机值的机型，如羽翼 EDGE）：
 *      参照实现没有这个概念，这里仍保留「整机」单栏形态 —— 把左右耳画成两个
 *      相同的数字是谎报，宁可只显示一路。
 *
 * 电池图标走 [themedPainterResource]（与参照实现逐字同形）：AppTheme 会在应用内
 * 强制深浅色并覆写 LocalConfiguration，而 painterResource 只认系统 uiMode，
 * 应用内主题与系统不一致时必须按应用主题重建 Resources 才能取到正确的那套夜间图。
 */
package moe.chenxy.hyperpods.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.BatterySnapshot
import top.yukonga.miuix.kmp.basic.Text

/**
 * 三路电量卡片的正文。
 *
 * @param battery 三路电量快照（left / right / case + 各自的 *Known / *Charging）
 * @param compact 横屏弹窗用的紧凑档（栏高、内边距与图标都更小）
 */
@Composable
fun PodStatus(
    battery: BatterySnapshot,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // 三路都还没读到（刚连上 / 未连接）：保留既有的过渡提示，不画三栏空壳
    if (!battery.anyKnown) {
        Text(stringResource(R.string.batt_unknown), fontSize = 13.sp)
        return
    }

    val dividerColor = if (isSystemInDarkTheme()) Color(0xFF333333) else Color(0xFFEEEEEE)
    val dividerHeight = if (compact) 40.dp else 56.dp

    // 单设备机型（type 0）：只画一路「整机」，不把同一个数字摆成左右两栏
    if (battery.singleDevice) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BatteryColumn(
                label = stringResource(R.string.batt_whole),
                level = if (battery.leftKnown) battery.left else battery.right,
                known = battery.leftKnown || battery.rightKnown,
                charging = battery.leftCharging || battery.rightCharging,
                compact = compact,
            )
        }
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatteryColumn(
            label = stringResource(R.string.batt_left),
            level = battery.left,
            known = battery.leftKnown,
            charging = battery.leftCharging,
            modifier = Modifier.weight(1f),
            compact = compact,
        )
        ColumnDivider(color = dividerColor, height = dividerHeight)
        BatteryColumn(
            label = stringResource(R.string.batt_right),
            level = battery.right,
            known = battery.rightKnown,
            charging = battery.rightCharging,
            modifier = Modifier.weight(1f),
            compact = compact,
        )
        ColumnDivider(color = dividerColor, height = dividerHeight)
        BatteryColumn(
            label = stringResource(R.string.batt_case),
            level = battery.case,
            known = battery.caseKnown,
            charging = battery.caseCharging,
            modifier = Modifier.weight(1f),
            compact = compact,
        )
    }
}

/** 栏间细分隔线（参照实现用的是 0.5dp 宽、定高的 Box）。 */
@Composable
private fun ColumnDivider(color: Color, height: Dp) {
    Box(
        modifier = Modifier
            .width(0.5.dp)
            .height(height)
            .background(color)
    )
}

/**
 * 一栏：标签 + 百分比 + 电池图标。
 *
 * @param level   原始读数（[BatterySnapshot] 的 left / right / case；-1 = 无数据）
 * @param known   对应的 leftKnown / rightKnown / caseKnown
 * @param charging 充电中（离线时图标不画充电态）
 */
@Composable
private fun BatteryColumn(
    label: String,
    level: Int,
    known: Boolean,
    charging: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // 离线判定与数据契约分开（见文件头取舍 ①）：*Known == false 或 level <= 0 都按离线画。
    // level <= 0 是渲染层取舍：固件对未连接的一侧回 0x00，不显示成 0 %。
    val offline = !known || level <= 0

    // 离线时图标退回「最近一次真实读数」，这样摘下一只耳时图标不会突然空掉；
    // 从未读到过就画空电池（0 档），**不用参照实现的 100 默认值** —— 那会谎报满电。
    var lastKnownLevel by remember { mutableIntStateOf(0) }
    if (!offline) lastKnownLevel = level

    val levelText = if (offline) stringResource(R.string.battery_offline) else "$level %"
    val iconLevel = if (offline) lastKnownLevel else level
    // 充电状态靠图标（charge_* 档）表达，标签保持单行宽度；给无障碍留一句完整描述
    val description = if (charging && !offline) {
        "$label ${stringResource(R.string.batt_charging)} $levelText"
    } else {
        "$label $levelText"
    }

    // 短标签（左耳 / 右耳）用全角空格补齐到三字宽，与最长的「充电盒」左对齐（同参照实现）
    val paddedLabel = if (label.length < 3) label.padEnd(3, '\u3000') else label

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.padding(vertical = if (compact) 2.dp else 4.dp),
        ) {
            Text(
                text = paddedLabel,
                fontSize = if (compact) 15.sp else 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = levelText,
                fontSize = 13.sp,
                color = Color.Gray,
            )
            Image(
                painter = themedPainterResource(getBatteryIconRes(iconLevel, charging && !offline)),
                contentDescription = description,
                modifier = Modifier.size(if (compact) 20.dp else 24.dp),
            )
        }
    }
}

/**
 * 电量档 → 电池图标资源（10 档，充电态用 charge_*）。
 * 逐字对齐参照实现 ui/components/PodStatus.kt:170-210（与本项目连接弹窗的取档逻辑同源）。
 */
private fun getBatteryIconRes(level: Int, isCharging: Boolean): Int {
    val index = when {
        level <= 10 -> 1
        level <= 20 -> 2
        level <= 30 -> 3
        level <= 40 -> 4
        level <= 50 -> 5
        level <= 60 -> 6
        level <= 70 -> 7
        level <= 80 -> 8
        level <= 90 -> 9
        else -> 10
    }
    return if (isCharging) {
        when (index) {
            1 -> R.drawable.charge_1
            2 -> R.drawable.charge_2
            3 -> R.drawable.charge_3
            4 -> R.drawable.charge_4
            5 -> R.drawable.charge_5
            6 -> R.drawable.charge_6
            7 -> R.drawable.charge_7
            8 -> R.drawable.charge_8
            9 -> R.drawable.charge_9
            else -> R.drawable.charge_10
        }
    } else {
        when (index) {
            1 -> R.drawable.common_1
            2 -> R.drawable.common_2
            3 -> R.drawable.common_3
            4 -> R.drawable.common_4
            5 -> R.drawable.common_5
            6 -> R.drawable.common_6
            7 -> R.drawable.common_7
            8 -> R.drawable.common_8
            9 -> R.drawable.common_9
            else -> R.drawable.common_10
        }
    }
}

/**
 * 按**应用内主题**（而不是系统 uiMode）取图标。
 *
 * 与系统 uiMode 一致时直接走 [painterResource]（零额外开销）；不一致时才按应用主题重建
 * Resources 取出 drawable 并转成 [BitmapPainter]（两套 10 档 PNG 都有 night 变体）。
 * 与参照实现 ui/components/PodStatus.kt:218-239 同形，也与本项目
 * ui/components/AncSwitch.kt 里的同名私有函数一致（参照实现在两个文件里也是各有一份）。
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
