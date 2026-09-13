/*
 * HyperPods for Moondrop — 页面骨架（Navigation 3 单返回栈）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 逐条对齐参考实现 _refs/OppoPods/.../ui/MainUI.kt：
 *   · Screen : NavKey 密封接口 + mutableStateListOf 返回栈（MainUI.kt:93-103, :111）；
 *   · entryProvider<Screen> { entry<...> { ... } }（:725-726）；
 *   · 每个 entry 自己带 Scaffold + TopAppBar + MiuixScrollBehavior(rememberTopAppBarState())，
 *     顶栏动作用 MiuixIcons.Back / Refresh / Settings（:730-764, :885-915）；
 *   · rememberDecoratedNavEntries(backStack, entryProvider) + NavDisplay(entries, onBack)（:1240-1254）。
 *
 * 页面：设备页（Home，含未连接时的等待页）/ 设置页（Settings）/ 关于页（About）。
 * 刻意不引入底部导航或分页器：返回栈就是唯一导航模型。
 */
package moe.chenxy.hyperpods.ui

import android.app.Activity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 返回栈上的页面键（NavKey 是 Navigation 3 的键类型）。 */
sealed interface Screen : NavKey {
    data object Home : Screen
    data object Settings : Screen
    data object About : Screen
}

@Composable
fun MainUI(
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val snapshot = rememberPodSnapshot()
    val settings = rememberModuleSettings()
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }

    // 每个 entry 自带 Scaffold + TopAppBar，页面切换时整页一起动
    val entryProvider = entryProvider<Screen> {
        entry<Screen.Home> {
            val homeTitle = if (snapshot.connected) {
                snapshot.modelName.ifBlank { stringResource(R.string.unknown_model) }
            } else {
                stringResource(R.string.app_name)
            }
            val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = homeTitle,
                        largeTitle = homeTitle,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { (context as? Activity)?.finish() }) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        },
                        actions = {
                            if (snapshot.connected) {
                                IconButton(onClick = { MoondropLink.refreshAll() }) {
                                    Icon(
                                        imageVector = MiuixIcons.Refresh,
                                        contentDescription = stringResource(R.string.refresh),
                                    )
                                }
                            }
                            IconButton(onClick = { backStack.add(Screen.Settings) }) {
                                Icon(
                                    imageVector = MiuixIcons.Settings,
                                    contentDescription = stringResource(R.string.settings_title),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                if (snapshot.connected) {
                    PodDetailPage(
                        contentPadding = padding,
                        snapshot = snapshot,
                        onOpenAbout = { backStack.add(Screen.About) },
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                    )
                } else {
                    WaitingPodsPage(modifier = Modifier.padding(padding))
                }
            }
        }

        entry<Screen.Settings> {
            SubPageScaffold(
                title = stringResource(R.string.settings_title),
                onBack = { if (backStack.size > 1) backStack.removeLast() },
            ) { padding, contentModifier ->
                SettingsPage(
                    modifier = contentModifier,
                    contentPadding = padding,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    settings = settings,
                    onOpenAbout = { backStack.add(Screen.About) },
                )
            }
        }

        entry<Screen.About> {
            SubPageScaffold(
                title = stringResource(R.string.about),
                onBack = { if (backStack.size > 1) backStack.removeLast() },
            ) { padding, contentModifier ->
                AboutContent(
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
 * 子页统一骨架：SmallTopAppBar（固定不折叠）+ 返回箭头，内容拿到
 * Scaffold 的 innerPadding 和已经挂好滚动联动的 Modifier。
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
            SmallTopAppBar(
                title = title,
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
