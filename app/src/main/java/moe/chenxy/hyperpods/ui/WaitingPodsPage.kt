/*
 * HyperPods for Moondrop — 等待耳机连接（详情页里的未连接状态块）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 作为详情页 LazyColumn 的一个 item 使用，所以只占满宽度、不占满高度。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WaitingPodsPage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.waiting_for_pod))
        BasicText(
            text = stringResource(R.string.waiting_for_pod_hint),
            modifier = Modifier.padding(top = 12.dp),
            style = TextStyle(
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        )
    }
}
