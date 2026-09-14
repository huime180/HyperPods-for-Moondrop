/*
 * HyperPods for Moondrop — 页面骨架（Navigation 3 单返回栈 + 底部三页签）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 两级结构，对齐参考实现 moondrop-pods 的 ui/MainUI.kt 与 ui/MainTabs.kt：
 *   · 第一级 = 返回栈（NavDisplay）：Main（模块页，内含底部三页签）
 *     / About（关于）/ Gesture（手势操作）；
 *   · 第二级 = Main 内部的底部导航 + HorizontalPager，见 ui/MainTabs.kt。
 *
 * 返回栈仍是 Navigation 3 的老写法（与改造前一致，参考实现同款）：
 *   Screen : NavKey 密封接口 + mutableStateListOf 返回栈 + entryProvider<Screen>
 *   + rememberDecoratedNavEntries + NavDisplay(entries, onBack)。
 *
 * 页签选择状态刻意放在 MainUI（NavDisplay 之外）而不是页签内部：
 * 这样从「设置」进「关于」再返回时，仍然停在「设置」页签上，不会被重置成第一个页签。
 *
 * 模块页两张状态卡要的数据也都由这里持有并往下传（同参考实现 MainUI.kt:121-132 与 554-559）：
 *   · LSPosed 服务          -> ui/XposedServiceState.kt 的 rememberXposedService()
 *   · 蓝牙开关 / 已配对数量 -> ui/BluetoothStatus.kt 的 rememberBluetoothStatus()
 *   · 蓝牙进程是否在响应    -> ui/BluetoothStatus.kt 的 rememberBluetoothServiceResponsive()
 * 以及「配对蓝牙」卡片要的 showDevicePicker（同参考实现 MainUI.kt:123、468-471）。
 */
package moe.chenxy.hyperpods.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import moe.chenxy.hyperpods.ui.components.rememberRestartScopeState
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
    /** 模块页（内含底部三页签：模块 / 耳机 / 设置） */
    data object Main : Screen
    data object About : Screen
    /** 手势操作页（TOUCHV2）：入口 = 设备页的「手势操作」行 + 设置页的同名入口，两者都由 hasGestures 门控 */
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
    // LSPosed 框架服务（模块页状态卡的「是否已激活」）与应用进程绑定一次，全 UI 共用
    val xposedService = rememberXposedService()
    // 模块页两张统计卡：蓝牙开关 + 已配对设备数（读 BluetoothAdapter）
    val bluetoothStatus = rememberBluetoothStatus()
    // 模块页状态卡：蓝牙进程里的模块是否还在响应（真实广播 + 75s 窗口，见 ui/BluetoothStatus.kt）
    val bluetoothServiceResponsive = rememberBluetoothServiceResponsive(snapshot.connected)
    val backStack = remember { mutableStateListOf<Screen>(Screen.Main) }
    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Module) }
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }
    // 「配对蓝牙」卡片 -> 强制显示耳机页的设备选择页（同参考实现 MainUI.kt:123 的 showDevicePicker）
    var showDevicePicker by remember { mutableStateOf(false) }
    // 「重启作用域」确认框状态：模块页右上角图标 / 设置页的入口都只调 request()，
    // 弹框里勾选作用域并再确认一次才执行（状态由 MainUI 持有，两个页签共用同一个弹框）。
    val restartScope = rememberRestartScopeState()

    // 首帧只判定一次默认页签（同参考实现 MainUI.kt:187-192）：耳机已经连上就直接落在「耳机」页。
    LaunchedEffect(snapshot.connected) {
        if (!hasAppliedDefaultTab) {
            selectedTab = if (snapshot.connected) MainTab.Earphones else MainTab.Module
            hasAppliedDefaultTab = true
        }
    }

    // 每个 entry 自带 Scaffold + TopAppBar，页面切换时整页一起动
    val entryProvider = entryProvider<Screen> {
        entry<Screen.Main> {
            MainTabsScaffold(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                snapshot = snapshot,
                settings = settings,
                xposedService = xposedService,
                bluetoothServiceResponsive = bluetoothServiceResponsive,
                bluetoothStatus = bluetoothStatus,
                // 「蓝牙状态」卡片：开着就去系统蓝牙设置，关着就先请求打开（同参考实现 :459-466）
                onBluetoothStatusClick = {
                    val action = if (bluetoothStatus.enabled) {
                        Settings.ACTION_BLUETOOTH_SETTINGS
                    } else {
                        BluetoothAdapter.ACTION_REQUEST_ENABLE
                    }
                    val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            Toast.makeText(
                                context,
                                R.string.open_bluetooth_settings_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                },
                // 「配对蓝牙」卡片：去耳机页的已配对设备列表（同参考实现 :468-471 的 openDevicePicker）
                onPairedBluetoothClick = {
                    showDevicePicker = true
                    selectedTab = MainTab.Earphones
                },
                showDevicePicker = showDevicePicker,
                onExitDevicePicker = { showDevicePicker = false },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                restartScope = restartScope,
                onRequestRestartScope = { restartScope.request() },
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
