/*
 * HyperPods for Moondrop — 模块页骨架（底部三页签 + HorizontalPager）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 结构对齐参考实现 moondrop-pods 的 ui/MainTabs.kt:61-533：
 *   · 外层 Scaffold 只挂 bottomBar（MainBottomNavigation）+ 分页器，
 *     每个页签自己再带一个 Scaffold + TopAppBar（顶部栏随页签一起动）；
 *   · 页签切换走 MainTabsPagerState（点击平滑滚动，滑动时反向同步 selectedTab）；
 *   · 重启作用域确认框挂在同一个 Scaffold 里（OverlayDialog 渲染到根 Scaffold 的弹层宿主）。
 *
 * 与参考实现的差异（本项目没有对应能力，刻意不搬）：
 *   · 浮动 / 毛玻璃底栏需要 miuix-blur 依赖，本项目未引入，因此底栏只有固定一种形态；
 *   · 参考实现的模块页有一张「LSPosed 是否已激活 / 作用域是否齐全」的状态卡，
 *     它依赖 io.github.libxposed.service.XposedService 连接。本项目的 libxposed api
 *     是 compileOnly（运行时由框架注入），应用进程拿不到该服务，所以不做这张卡，
 *     模块页改为展示本项目确实能读到的模块级设置与系统信息。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.components.RestartScopeDialog
import moe.chenxy.hyperpods.ui.components.RestartScopeState
import moe.chenxy.hyperpods.ui.pages.EarphonesTabPage
import moe.chenxy.hyperpods.ui.pages.HomePage
import moe.chenxy.hyperpods.ui.pages.SettingsPage
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 页签内容底部的固定留白。
 *
 * 外层 Scaffold 已经用底栏高度撑出了系统导航栏 + 底栏的空间，所以页签内部只留这一点留白，
 * 不再叠加内层 Scaffold 的 innerPadding.bottom（那会把系统导航栏内边距重复算一遍）。
 */
private val PAGE_BOTTOM_PADDING = 12.dp

@Composable
internal fun MainTabsScaffold(
    tabs: List<MainTab>,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    snapshot: PodSnapshot,
    settings: ModuleSettingsState,
    themeMode: MutableState<Int>,
    onThemeModeChange: (Int) -> Unit,
    restartScope: RestartScopeState,
    onRequestRestartScope: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenGestures: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { tabs.size },
    )
    val coroutineScope = rememberCoroutineScope()
    val mainPagerState = remember(pagerState, coroutineScope) {
        MainTabsPagerState(pagerState, coroutineScope)
    }

    LaunchedEffect(selectedTab) {
        val targetPage = selectedTab.ordinal
        if (mainPagerState.selectedPage != targetPage) {
            mainPagerState.animateToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    LaunchedEffect(mainPagerState.selectedPage, tabs) {
        val page = mainPagerState.selectedPage
        if (page in tabs.indices && selectedTab != tabs[page]) {
            onTabSelected(tabs[page])
        }
    }

    Scaffold(
        bottomBar = {
            MainBottomNavigation(
                tabs = tabs,
                // 滑动过程中以分页器的实际落点为准，点击时以 selectedTab 为准
                selectedTab = tabs.getOrElse(mainPagerState.selectedPage) { selectedTab },
                onTabClick = {
                    mainPagerState.animateToPage(it.ordinal)
                    onTabSelected(it)
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { page -> tabs[page] },
            ) { page ->
                when (tabs[page]) {
                    MainTab.Module -> ModuleTabPage(
                        settings = settings,
                        onRequestRestartScope = onRequestRestartScope,
                    )

                    MainTab.Earphones -> EarphonesTabShell(
                        snapshot = snapshot,
                        onOpenAbout = onOpenAbout,
                        onOpenGestures = onOpenGestures,
                    )

                    MainTab.Settings -> SettingsTabShell(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        hasGestures = snapshot.capabilities.hasGestures,
                        onOpenAbout = onOpenAbout,
                        onOpenGestures = onOpenGestures,
                        onRequestRestartScope = onRequestRestartScope,
                    )
                }
            }
        }

        RestartScopeDialog(state = restartScope)
    }
}

/** 模块页：模块级设置 + 系统信息，右上角是「重启作用域」（同参考实现的模块页动作位）。 */
@Composable
private fun ModuleTabPage(
    settings: ModuleSettingsState,
    onRequestRestartScope: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                largeTitle = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onRequestRestartScope) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.restart_scope),
                        )
                    }
                },
            )
        },
    ) { pagePadding ->
        HomePage(
            settings = settings,
            modifier = Modifier
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            // 底部不用内层 Scaffold 的 innerPadding：底栏高度已由外层 Scaffold 的
            // padding 让出来了，再用一次会把系统导航栏内边距重复算一遍
            // （同参考实现 MainTabs.kt:313-325 传固定 pageBottomContentPadding 的取法）。
            contentPadding = PaddingValues(
                top = pagePadding.calculateTopPadding(),
                bottom = PAGE_BOTTOM_PADDING,
            ),
        )
    }
}

/** 耳机页：连上时是设备详情，未连上时是设备选择（等待连接）页。 */
@Composable
private fun EarphonesTabShell(
    snapshot: PodSnapshot,
    onOpenAbout: () -> Unit,
    onOpenGestures: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val title = if (snapshot.connected) {
        snapshot.modelName.ifBlank { stringResource(R.string.pod_info) }
    } else {
        stringResource(R.string.pod_info)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                largeTitle = title,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (snapshot.connected) {
                        IconButton(onClick = { MoondropLink.refreshAll() }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { pagePadding ->
        EarphonesTabPage(
            snapshot = snapshot,
            onOpenAbout = onOpenAbout,
            onOpenGestures = onOpenGestures,
            contentPadding = PaddingValues(
                top = pagePadding.calculateTopPadding(),
                bottom = PAGE_BOTTOM_PADDING,
            ),
            modifier = Modifier
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        )
    }
}

/** 设置页：应用外观与各入口。 */
@Composable
private fun SettingsTabShell(
    themeMode: MutableState<Int>,
    onThemeModeChange: (Int) -> Unit,
    hasGestures: Boolean,
    onOpenAbout: () -> Unit,
    onOpenGestures: () -> Unit,
    onRequestRestartScope: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                largeTitle = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { pagePadding ->
        SettingsPage(
            modifier = Modifier
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = pagePadding.calculateTopPadding(),
                bottom = PAGE_BOTTOM_PADDING,
            ),
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            hasGestures = hasGestures,
            onOpenGestures = onOpenGestures,
            onRequestRestartScope = onRequestRestartScope,
            onOpenAbout = onOpenAbout,
        )
    }
}
