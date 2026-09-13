/*
 * HyperPods for Moondrop — 主界面
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 职责边界（与 pods/ControlBridge.kt 分工，避免重复下发）：
 *   · 连接/控制：由 manifest 声明的 ControlReceiver → ControlBridge 负责
 *     （即使本应用没在前台也能被显式广播唤醒，所以 UI 不重复 connect/disconnect/写设备）。
 *   · 本文件只做「状态展示」：
 *       ① 进程内 [PodEvent]（[MoondropLink.addListener]）—— 主状态源，字段最全；
 *       ② 跨进程广播（状态变化动作）—— 触发器：收到就 refreshAll() 重新读一次。
 *   · 冷启动兜底：用户从桌面直接打开本应用、而耳机早已连上（不会有 PODS_CONNECTED 广播）时，
 *     在这里做一次已配对设备发现并 connect；仅当当前未连接时执行。
 *
 * ⚠ 容器组件说明（CI 实测，本仓库解析到的 miuix 产物）：
 *   basic.LazyColumn / basic.HorizontalPager / icon.icons.* 均不存在，因此
 *   分页器用 androidx.compose.foundation.pager.HorizontalPager，
 *   底部导航用纯 Compose 标签栏（不猜任何图标 API）。
 */
package moe.chenxy.hyperpods.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.pods.ControlBridge
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodEvent
import moe.chenxy.hyperpods.pods.PodListener
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "MoondropMainUI"

/**
 * UI 侧本地偏好：只保存「上一次连接过的耳机地址」，供冷启动兜底优先重连。
 * 注意：这里刻意不复用 HyperPodsPrefsKey —— 那份契约里没有「上次连接地址」键，
 * 而 utils/data/ 不允许改动。
 */
private const val UI_PREFS = "hyperpods_moondrop_ui"
private const val KEY_LAST_ADDRESS = "last_connected_address"

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(FlowPreview::class)
@Composable
fun MainUI() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val topAppBarScrollBehavior0 = MiuixScrollBehavior(rememberTopAppBarState())
    val topAppBarScrollBehavior1 = MiuixScrollBehavior(rememberTopAppBarState())

    val topAppBarScrollBehaviorList = listOf(
        topAppBarScrollBehavior0, topAppBarScrollBehavior1
    )

    val pagerState = rememberPagerState(pageCount = { 2 })
    var targetPage by remember { mutableIntStateOf(pagerState.currentPage) }

    val currentScrollBehavior = when (pagerState.currentPage) {
        0 -> topAppBarScrollBehaviorList[0]
        else -> topAppBarScrollBehaviorList[1]
    }

    var snapshot by remember { mutableStateOf(MoondropLink.snapshot()) }
    // 广播触发的「重新读一次」信号：自增即让下面 LaunchedEffect 重新拉一次快照
    var refreshSignal by remember { mutableIntStateOf(0) }

    // ── 状态源 ①（进程内事件） + 状态源 ②（跨进程状态广播触发器） ──────────
    DisposableEffect(context) {
        // 多监听者：ControlBridge 自己的转发器与我们这个 UI 监听者并存
        val uiListener = object : PodListener {
            override fun onEvent(event: PodEvent) {
                snapshot = when (event) {
                    is PodEvent.Connected -> event.snapshot
                    else -> MoondropLink.snapshot()
                }
            }
        }
        // ControlBridge 负责 appContext + 跨进程转发；幂等，UI 打开时确保它已就绪
        ControlBridge.ensureInit(context.applicationContext)
        MoondropLink.addListener(uiListener)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    // 连接/断开由 ControlBridge 处理；这里只刷新本地展示
                    HyperPodsAction.PODS_CONNECTED,
                    HyperPodsAction.PODS_DISCONNECTED -> {
                        refreshSignal++
                    }

                    HyperPodsAction.BATTERY_CHANGED -> {
                        MoondropLink.requestBatteryRefresh()
                    }

                    // 其余状态动作只带简单 extra，统一「重新读一次」即可（快照里字段更全）
                    HyperPodsAction.ANC_CHANGED,
                    HyperPodsAction.GAIN_CHANGED,
                    HyperPodsAction.LED_CHANGED,
                    HyperPodsAction.PROMPT_TONE_CHANGED,
                    HyperPodsAction.PROMPT_VOLUME_CHANGED,
                    HyperPodsAction.LHDC_CHANGED,
                    HyperPodsAction.DUAL_CONNECTION_CHANGED,
                    HyperPodsAction.LOW_LATENCY_CHANGED,
                    HyperPodsAction.CAPABILITIES_CHANGED -> {
                        MoondropLink.refreshAll()
                        refreshSignal++
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(HyperPodsAction.PODS_CONNECTED)
            addAction(HyperPodsAction.PODS_DISCONNECTED)
            addAction(HyperPodsAction.BATTERY_CHANGED)
            addAction(HyperPodsAction.ANC_CHANGED)
            addAction(HyperPodsAction.GAIN_CHANGED)
            addAction(HyperPodsAction.LED_CHANGED)
            addAction(HyperPodsAction.PROMPT_TONE_CHANGED)
            addAction(HyperPodsAction.PROMPT_VOLUME_CHANGED)
            addAction(HyperPodsAction.LHDC_CHANGED)
            addAction(HyperPodsAction.DUAL_CONNECTION_CHANGED)
            addAction(HyperPodsAction.LOW_LATENCY_CHANGED)
            addAction(HyperPodsAction.CAPABILITIES_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)

        // 通知其它进程「UI 起来了，请重放一遍状态」：
        // manifest 里的 ControlReceiver 会收到并执行 refreshAll()（必须 setPackage）。
        context.sendBroadcast(Intent(HyperPodsAction.UI_INIT).setPackage(BuildConfig.APPLICATION_ID))

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            MoondropLink.removeListener(uiListener)
        }
    }

    // ── 冷启动兜底连接（仅未连接时执行一次） ────────────────────────────────
    LaunchedEffect(Unit) {
        if (!MoondropLink.snapshot().connected) {
            val target = findBondedMoondropDevice(context)
            if (target == null) {
                Log.i(TAG, "no bonded Moondrop device; waiting page")
            } else {
                context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_ADDRESS, target.address)
                    .apply()
                Log.i(TAG, "cold-start connect to ${target.address} (${target.name})")
                MoondropLink.connect(target)
            }
        }
        snapshot = MoondropLink.snapshot()
    }

    LaunchedEffect(refreshSignal) {
        snapshot = MoondropLink.snapshot()
    }

    val mainTitle = if (snapshot.connected) {
        snapshot.modelName.ifBlank { stringResource(R.string.unknown_model) }
    } else {
        stringResource(R.string.app_name)
    }
    val aboutTitle = stringResource(R.string.about)
    val currentTitle = when (pagerState.currentPage) {
        0 -> mainTitle
        else -> aboutTitle
    }

    val tabLabels = listOf(
        stringResource(R.string.pod_info),
        stringResource(R.string.about),
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.debounce(150).collectLatest {
            targetPage = pagerState.currentPage
        }
    }

    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = if (currentScrollBehavior.state.heightOffset > -1) Color.Transparent else MiuixTheme.colorScheme.background,
        tint = HazeTint(
            MiuixTheme.colorScheme.background.copy(
                if (currentScrollBehavior.state.heightOffset > -1) 1f
                else lerp(1f, 0.67f, (currentScrollBehavior.state.heightOffset + 1) / -143f)
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
                        title = currentTitle,
                        modifier = Modifier
                            .hazeChild(
                                hazeState
                            ) {
                                style = hazeStyle
                                blurRadius = 25.dp
                                noiseFactor = 0f
                            },
                        scrollBehavior = currentScrollBehavior
                    )
                } else {
                    TopAppBar(
                        color = Color.Transparent,
                        title = currentTitle,
                        scrollBehavior = currentScrollBehavior,
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
        bottomBar = {
            // 纯 Compose 标签栏：本仓库 miuix 产物里 NavigationItem 的图标参数无法核实，
            // 按「不猜 API」原则用 BasicText 标签代替图标（行为与 HyperPods 的 NavigationBar 一致）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeChild(
                        hazeState
                    ) {
                        style = hazeStyle
                        blurRadius = 25.dp
                        noiseFactor = 0f
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabLabels.forEachIndexed { index, label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        targetPage = index
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = label,
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = if (index == targetPage) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    color = MiuixTheme.colorScheme.onBackground.copy(
                                        alpha = if (index == targetPage) 1f else 0.55f
                                    )
                                )
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        AppHorizontalPager(
            modifier = Modifier.imePadding().haze(state = hazeState),
            pagerState = pagerState,
            topAppBarScrollBehaviorList = topAppBarScrollBehaviorList,
            padding = padding,
            snapshot = snapshot,
        )
    }
}

@Composable
fun AppHorizontalPager(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    topAppBarScrollBehaviorList: List<ScrollBehavior>,
    padding: PaddingValues,
    snapshot: PodSnapshot,
) {
    // androidx.compose.foundation.pager.HorizontalPager：
    // 与 HyperPods 使用的 PagerState / rememberPagerState 同属 compose.foundation 的 pager 包。
    HorizontalPager(
        pagerState,
        modifier = modifier
    ) { page ->
        when (page) {
            0 -> Crossfade(snapshot.connected, label = "MainUIShowDetailAnim") { connected ->
                if (connected) {
                    PodDetailPage(
                        topAppBarScrollBehavior = topAppBarScrollBehaviorList[0],
                        padding = padding,
                        snapshot = snapshot,
                    )
                } else {
                    WaitingPodsPage()
                }
            }

            else -> AboutPage(
                topAppBarScrollBehavior = topAppBarScrollBehaviorList[1],
                padding = padding
            )
        }
    }
}

/**
 * 冷启动兜底：已配对设备里挑一个水月雨耳机，优先偏好里记录的「上次连接地址」。
 * 主路径是 ControlBridge 收到蓝牙进程的 PODS_CONNECTED 广播后精确连接（带 MAC）。
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun findBondedMoondropDevice(context: Context): BluetoothDevice? {
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Log.w(TAG, "BLUETOOTH_CONNECT not granted; skip device discovery")
        return null
    }
    val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return null
    if (!adapter.isEnabled) return null
    val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return null
    val preferred = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_ADDRESS, null)
    val candidates = bonded.filter { MoondropModels.match(it.name) != null }
    return candidates.firstOrNull { it.address == preferred } ?: candidates.firstOrNull()
}
