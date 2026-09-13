/*
 * HyperPods for Moondrop — 关于页
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 列表容器用 androidx.compose.foundation.lazy.LazyColumn：
 * 本仓库解析到的 miuix 产物没有 top.yukonga.miuix.kmp.basic.LazyColumn（CI 实测）。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.getWindowSize

/**
 * @param topAppBarScrollBehavior 保留参数：Miuix 的折叠式 LazyColumn 在当前 miuix 产物里不存在，
 *   等依赖版本支持后再用它绑定 `Modifier.nestedScroll(...)`。目前不参与布局。
 */
@Composable
fun AboutPage(
    topAppBarScrollBehavior: ScrollBehavior,
    padding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.height(getWindowSize().height.dp).padding(12.dp),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(72.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground)
                )
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.padding(top = 8.dp),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp
                )
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    modifier = Modifier.padding(top = 2.dp),
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp
                )
            }

            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.about_credits_title),
                    summary = stringResource(R.string.about_credits_summary),
                    enabled = false
                )
                BasicComponent(
                    title = stringResource(R.string.about_license_title),
                    summary = stringResource(R.string.about_license_value),
                    enabled = false
                )
            }
        }
    }
}
