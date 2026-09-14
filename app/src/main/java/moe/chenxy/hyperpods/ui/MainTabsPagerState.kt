/*
 * MiuixMoondrop — 页签切换的分页器状态
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 照搬参考实现 moondrop-pods 的 ui/MainTabsPagerState.kt:17-65：
 * 页签点击时按「页距 × 页数」平滑滚动，并在滚动期间抑制反向同步，
 * 避免 selectedTab 与 pagerState.currentPage 互相打架。
 * 只用 androidx.compose.foundation + kotlinx.coroutines，不新增依赖。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

internal class MainTabsPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(targetPage: Int) {
        if (targetPage == selectedPage) return

        navJob?.cancel()
        selectedPage = targetPage
        isNavigating = true

        val layoutInfo = pagerState.layoutInfo
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        val distanceInPages = targetPage - pagerState.currentPage - pagerState.currentPageOffsetFraction
        val scrollPixels = distanceInPages * pageSize
        val duration = 100 * abs(targetPage - pagerState.currentPage).coerceAtLeast(2) + 100

        navJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.animateScrollBy(
                    value = scrollPixels,
                    animationSpec = tween(easing = EaseInOut, durationMillis = duration),
                )
            } finally {
                if (navJob == currentJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetPage) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}
