/*
 * MiuixMoondrop — 页面骨架（Navigation 3 单返回栈 + 底部两页签）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 两级结构：
 *   · 第一级 = 返回栈（NavDisplay）：Main（内含底部两页签）
 *     / About（关于）/ Gesture（手势操作）；
 *   · 第二级 = Main 内部的底部导航 + HorizontalPager，见 ui/MainTabs.kt。
 *
 * 返回栈仍是 Navigation 3 的老写法（与改造前一致）：
 *   Screen : NavKey 密封接口 + mutableStateListOf 返回栈 + entryProvider<Screen>
 *   + rememberDecoratedNavEntries + NavDisplay(entries, onBack)。
 *
 * 页签选择状态刻意放在 MainUI（NavDisplay 之外）而不是页签内部：
 * 这样从「设置」进「关于」再返回时，仍然停在「设置」页签上，不会被重置成第一个页签。
 *
 * 普通 App 收窄后的默认页签：固定「耳机」。原来的「模块」页签与它的状态卡
 * （LSPosed 服务 / 蓝牙进程是否响应）已随 hook 一起删除，因此这里不再有
 * 「已连上就直接落耳机页」的条件分支 —— 两页里它本来就是主页面。
 */
package moe.chenxy.hyperpods.ui

import android.app.Activity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.pages.AboutPage
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 返回栈上的页面键（NavKey 是 Navigation 3 的键类型）。 */
sealed interface Screen : NavKey {
    /** 主页面（内含底部两页签：耳机 / 设置） */
    data object Main : Screen
    data object About : Screen
    /** 手势操作页（TOUCHV2）：入口 = 设备页（PodDetailPage）的「手势操作」行，由 hasGestures 门控 */
    data object Gesture : Screen
}

@Composable
fun MainUI(
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val snapshot = rememberPodSnapshot()
    val settings = rememberModuleSettings()
    val backStack = remember { mutableStateListOf<Screen>(Screen.Main) }
    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Earphones) }

    // 每个 entry 自带 Scaffold + TopAppBar，页面切换时整页一起动
    val entryProvider = entryProvider<Screen> {
        entry<Screen.Main> {
            MainTabsScaffold(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                snapshot = snapshot,
                settings = settings,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onOpenAbout = { backStack.add(Screen.About) },
                onOpenGestures = { backStack.add(Screen.Gesture) },
            )
        }

        entry<Screen.About> {
            SubPageScaffold(
                title = stringResource(R.string.about),
                onBack = { if (backStack.size > 1) backStack.removeLast() },
            ) { padding, contentModifier ->
                AboutPage(
                    modifier = contentModifier,
                    contentPadding = padding,
                )
            }
        }

        entry<Screen.Gesture> {
            SubPageScaffold(
                title = stringResource(R.string.gesture_title),
                onBack = { if (backStack.size > 1) backStack.removeLast() },
            ) { padding, contentModifier ->
                GesturePage(
                    snapshot = snapshot,
                    modifier = contentModifier,
                    contentPadding = padding,
                )
            }
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryProvider = entryProvider,
    )

    NavDisplay(
        entries = entries,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLast()
            } else {
                (context as? Activity)?.finish()
            }
        },
    )
}

/**
 * 子页统一骨架：可折叠的 TopAppBar（title + largeTitle）+ 返回箭头，内容拿到
 * Scaffold 的 innerPadding 和已经挂好滚动联动的 Modifier。
 * 形态同参考实现 moondrop-pods 的 ui/MainUI.kt:643-674（关于页 entry）。
 */
@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                largeTitle = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        content(
            padding,
            Modifier
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        )
    }
}
