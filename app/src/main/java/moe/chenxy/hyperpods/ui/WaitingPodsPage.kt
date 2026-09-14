/*
 * HyperPods for Moondrop — 等待耳机连接（详情页的未连接状态整页）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 形态对齐参考实现 _refs/OppoPods/.../ui/MainUI.kt:1257-1288 的 ConnectingPage()：
 * 居中一个 Miuix primary 色的旋转圆环 + 文案；区别只是文案换成「等待耳机连接」，
 * 因为我们的未连接态就是等待系统蓝牙把耳机接上来（本应用不主动配对）。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 未连接时的整页状态：转圈 + 「等待耳机连接」+ 提示。 */
@Composable
fun WaitingPodsPage(modifier: Modifier = Modifier) {
    val primaryColor = MiuixTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "waiting")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(48.dp)) {
                drawArc(
                    color = primaryColor,
                    startAngle = angle,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Text(
                text = stringResource(R.string.waiting_for_pod),
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.waiting_for_pod_hint),
                modifier = Modifier.padding(start = 32.dp, top = 8.dp, end = 32.dp),
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
