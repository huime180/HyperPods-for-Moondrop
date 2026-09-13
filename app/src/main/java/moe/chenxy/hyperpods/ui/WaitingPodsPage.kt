/*
 * HyperPods for Moondrop — 等待耳机连接
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
fun WaitingPodsPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
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
