/*
 * HyperPods for Moondrop — 主界面
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 状态来源有两条，必须同时存在：
 *   ① 进程内 [PodEvent]（[MoondropLink.init] 注册的 [PodListener]）—— 主状态源，字段最全；
 *   ② 跨进程广播（本模块跨 4 个进程，广播只带简单 extra）—— 只当作「触发器」：
 *      收到就调用 [MoondropLink.refreshAll] 让本进程重新读一次设备状态。
 *
 * 设备发现：优先用 PODS_CONNECTED 广播里的 EXTRA_MAC 精确命中（多台水月雨耳机时不会连错），
 * 冷启动没有广播时退回「已配对设备里按型号名匹配、优先上次连接地址」。
 *
 * 控制桥：hook 进程（设置页 / 系统侧）需要操作耳机时只会广播 *_SELECT，
 * 而 MoondropLink 只存在于应用进程，所以下面这个 receiver 必须把它们落到 MoondropLink 上。
 *
 * ⚠ 容器组件说明（CI 实测）：本仓库解析到的 miuix 产物里 **没有**
 *   top.yukonga.miuix.kmp.basic.LazyColumn / HorizontalPager / icon.icons.*，
 *   因此分页器用 androidx.compose.foundation.pager.HorizontalPager，
 *   底部导航用纯 Compose 画的标签栏（不猜任何图标 API）。
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
 * UI 侧的本地偏好：只保存「上一次连接过的耳机地址」用于冷启动时的优先重连。
 * 注意：这里刻意不复用 HyperPodsPrefsKey —— 那份契约里没有「上次连接地址」键，
 * 而 utils/data/ 不允许改动。
 */
private const val UI_PREFS = "hyperpods_moondrop_ui"
private const val KEY_LAST_ADDRESS = "last_connected_address"

/**
 * 低延迟开关要落到系统蓝牙进程（HyperOS 系统侧功能，不是 GAIA 命令）。
 * 字面值抄自 hook/DeviceCardHook.kt / hook/XposedEntry.kt。
 */
private const val PKG_BLUETOOTH = "com.android.bluetooth"

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
    // 收到「耳机已连接」广播时，把目标设备信息记下来并自增信号，驱动下面 LaunchedEffect 连接
    var connectSignal by remember { mutableIntStateOf(0) }
    var pendingMac by remember { mutableStateOf("") }
    var pendingName by remember { mutableStateOf("") }

    // ── 状态源 ①（进程内事件） + 状态源 ②（跨进程广播：状态触发器 + 控制桥） ──
    DisposableEffect(context) {
        MoondropLink.init(context.applicationContext, object : PodListener {
            override fun onEvent(event: PodEvent) {
                snapshot = when (event) {
                    is PodEvent.Connected -> event.snapshot
                    else -> MoondropLink.snapshot()
                }
            }
        })

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    // ── 连接状态 ──────────────────────────────────────────────
                    // 系统蓝牙进程报告耳机已连接（只对水月雨设备广播）：记下目标后建立 GAIA 通道
                    HyperPodsAction.PODS_CONNECTED -> {
                        pendingMac = intent?.getStringExtra(HyperPodsAction.EXTRA_MAC).orEmpty()
                        pendingName = intent?.getStringExtra(HyperPodsAction.EXTRA_DEVICE_NAME).orEmpty()
                        connectSignal++
                        MoondropLink.refreshAll()
                    }

                    HyperPodsAction.PODS_DISCONNECTED -> {
                        pendingMac = ""
                        pendingName = ""
                        MoondropLink.disconnect()
                        snapshot = MoondropLink.snapshot()
                    }

                    // ── 只读的状态重放请求（设置页向应用进程要一次全量状态） ──
                    HyperPodsAction.UI_INIT,
                    HyperPodsAction.REQUEST_CAPABILITIES -> {
                        MoondropLink.refreshAll()
                    }

                    HyperPodsAction.REQUEST_BATTERY -> {
                        coroutineScope.launch { MoondropLink.refreshBattery() }
                    }

                    // ── 控制桥：hook 进程发来的操作请求落到 MoondropLink ────────
                    // 设置页 / 系统侧不持有协议客户端，必须由应用进程代发。
                    HyperPodsAction.ANC_SELECT -> {
                        val index = intent?.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1) ?: -1
                        if (index >= 0) MoondropLink.setAnc(index)
                    }

                    HyperPodsAction.GAIN_SELECT -> {
                        val index = intent?.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1) ?: -1
                        if (index >= 0) MoondropLink.setGain(index)
                    }

                    HyperPodsAction.LED_SELECT -> {
                        MoondropLink.setLed(intent?.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false) == true)
                    }

                    HyperPodsAction.PROMPT_TONE_SELECT -> {
                        MoondropLink.setPromptTone(intent?.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false) == true)
                    }

                    HyperPodsAction.PROMPT_VOLUME_SELECT -> {
                        val raw = intent?.getIntExtra(HyperPodsAction.EXTRA_PROMPT_VOLUME_RAW, -1) ?: -1
                        if (raw >= 0) MoondropLink.setPromptVolumeRaw(raw)
                    }

                    HyperPodsAction.LHDC_SELECT -> {
                        MoondropLink.setLhdc(intent?.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false) == true)
                    }

                    HyperPodsAction.DUAL_CONNECTION_SELECT -> {
                        MoondropLink.setDualConnection(intent?.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false) == true)
                    }

                    // ── 其余状态动作只带简单 extra，统一「重新读一次」即可 ────
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
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            // 连接状态
            addAction(HyperPodsAction.PODS_CONNECTED)
            addAction(HyperPodsAction.PODS_DISCONNECTED)
            // 只读请求
            addAction(HyperPodsAction.UI_INIT)
            addAction(HyperPodsAction.REQUEST_CAPABILITIES)
            addAction(HyperPodsAction.REQUEST_BATTERY)
            // 控制桥（来自 hook 进程）
            addAction(HyperPodsAction.ANC_SELECT)
            addAction(HyperPodsAction.GAIN_SELECT)
            addAction(HyperPodsAction.LED_SELECT)
            addAction(HyperPodsAction.PROMPT_TONE_SELECT)
            addAction(HyperPodsAction.PROMPT_VOLUME_SELECT)
            addAction(HyperPodsAction.LHDC_SELECT)
            addAction(HyperPodsAction.DUAL_CONNECTION_SELECT)
            // 状态变化触发器
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

        // 通知其它进程「UI 起来了，请重放一遍状态」（必须 setPackage，Android 14+ 丢弃隐式广播）
        context.sendBroadcast(Intent(HyperPodsAction.UI_INIT).setPackage(BuildConfig.APPLICATION_ID))
        MoondropLink.refreshAll()

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            // 解绑监听：避免 Activity 销毁后旧的状态对象仍被回调
            MoondropLink.init(context.applicationContext, object : PodListener {
                override fun onEvent(event: PodEvent) = Unit
            })
        }
    }

    // ── 设备发现 + 连接 ────────────────────────────────────────────────────
    LaunchedEffect(connectSignal) {
        val target = resolveTargetDevice(context, pendingMac, pendingName)
        if (target == null) {
            Log.i(TAG, "no Moondrop device to connect (bonded scan + broadcast mac both empty)")
        } else {
            val current = MoondropLink.snapshot()
            if (!current.connected || current.deviceAddress != target.address) {
                context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_ADDRESS, target.address)
                    .apply()
                Log.i(TAG, "connect GAIA channel to ${target.address} (${target.name})")
                MoondropLink.connect(target)
            }
        }
        MoondropLink.refreshAll()
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
            // 纯 Compose 标签栏：miuix 产物里 NavigationItem 的图标参数无法核实，
            // 按「不猜 API」原则用 BasicText 标签代替图标（行为与 NavigationBar 一致）。
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
    // 与 HyperPods 用的 PagerState / rememberPagerState 同一个包（同属 compose.foundation）。
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
 * 解析要连接的目标设备。
 *
 * ① [macFromBroadcast] 非空（来自 PODS_CONNECTED 的 EXTRA_MAC）时精确命中；
 * ② 否则退回已配对设备扫描：只取型号档案能识别的设备，优先偏好里记录的地址；
 * ③ 一个都没有就返回 null，UI 显示等待页（不是错误）。
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun resolveTargetDevice(
    context: Context,
    macFromBroadcast: String,
    nameFromBroadcast: String,
): BluetoothDevice? {
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Log.w(TAG, "BLUETOOTH_CONNECT not granted; skip device discovery")
        return null
    }
    val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return null
    if (!adapter.isEnabled) return null

    if (macFromBroadcast.isNotEmpty() && BluetoothAdapter.checkBluetoothAddress(macFromBroadcast)) {
        val device = runCatching { adapter.getRemoteDevice(macFromBroadcast) }.getOrNull()
        if (device != null) {
            val deviceName = runCatching { device.name }.getOrNull()
            val names = listOfNotNull(
                deviceName?.takeIf { it.isNotEmpty() },
                nameFromBroadcast.takeIf { it.isNotEmpty() }
            )
            // 广播只对水月雨设备发出；名字读不到时信任来源，读到名字则必须是可识别型号
            if (names.isEmpty() || names.any { MoondropModels.match(it) != null }) return device
        }
    }

    val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return null
    val preferred = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_ADDRESS, null)
    val candidates = bonded.filter { MoondropModels.match(it.name) != null }
    return candidates.firstOrNull { it.address == preferred } ?: candidates.firstOrNull()
}
