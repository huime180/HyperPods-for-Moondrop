/*
 * HyperPods for Moondrop — 详情页外壳（单页滚动，无底部导航 / 无分页器）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * PuddingPods 的页面分工：快速弹窗负责日常操作，独立详情页负责完整功能与设置。
 * 因此这里只有一根滚动列表 + 一个顶栏，没有 NavigationBar / HorizontalPager。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import moe.chenxy.hyperpods.R
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DetailScreen() {
    val snapshot = rememberPodSnapshot()
    val topAppBarScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val hazeState = remember { HazeState() }

    val title = if (snapshot.connected) {
        snapshot.modelName.ifBlank { stringResource(R.string.unknown_model) }
    } else {
        stringResource(R.string.app_name)
    }

    val hazeStyle = HazeStyle(
        backgroundColor = if (topAppBarScrollBehavior.state.heightOffset > -1) Color.Transparent else MiuixTheme.colorScheme.background,
        tint = HazeTint(
            MiuixTheme.colorScheme.background.copy(
                if (topAppBarScrollBehavior.state.heightOffset > -1) 1f
                else lerp(1f, 0.67f, (topAppBarScrollBehavior.state.heightOffset + 1) / -143f)
            )
        )
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BoxWithConstraints {
                if (maxWidth > 840.dp) {
                    SmallTopAppBar(
                        color = Color.Transparent,
                        title = title,
                        modifier = Modifier
                            .hazeChild(
                                hazeState
                            ) {
                                style = hazeStyle
                                blurRadius = 25.dp
                                noiseFactor = 0f
                            },
                        scrollBehavior = topAppBarScrollBehavior
                    )
                } else {
                    TopAppBar(
                        color = Color.Transparent,
                        title = title,
                        scrollBehavior = topAppBarScrollBehavior,
                        modifier = Modifier
                            .hazeChild(
                                hazeState
                            ) {
                                style = hazeStyle
                                blurRadius = 25.dp
                                noiseFactor = 0f
                            }
                    )
                }
            }
        },
    ) { padding ->
        PodDetailPage(
            topAppBarScrollBehavior = topAppBarScrollBehavior,
            padding = padding,
            snapshot = snapshot,
            modifier = Modifier.haze(state = hazeState),
        )
    }
}
