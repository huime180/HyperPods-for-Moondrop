/*
 * HyperPods for Moondrop — 主界面
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 状态来源有两条，必须同时存在：
 *   ① 进程内 [PodEvent]（[MoondropLink.init] 注册的 [PodListener]）—— 主状态源，字段最全；
 *   ② 跨进程广播（本模块跨 4 个进程，广播只带简单 extra）—— 只当作「触发器」：
 *      收到就调用 [MoondropLink.refreshAll] 让本进程重新读一次设备状态。
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import top.yukonga.miuix.kmp.basic.HorizontalPager
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.Info
import top.yukonga.miuix.kmp.icon.icons.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "MoondropMainUI"

/**
 * UI 侧的本地偏好：只保存「上一次连接过的耳机地址」用于优先重连。
 * 注意：这里刻意不复用 [moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey]——
 * 那个契约里没有「上次连接地址」键，且 utils/data 不允许改动。
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
    // 收到「耳机已连接（新设备）」时自增，驱动下面 LaunchedEffect 重新做设备发现并连接
    var connectSignal by remember { mutableIntStateOf(0) }

    // ── 状态源 ①（进程内事件） + 状态源 ②（跨进程广播触发器） ──────────────
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
                    // 系统蓝牙进程报告耳机已连接：先发现目标设备（下面 LaunchedEffect），
                    // 再由 MoondropLink 建立 GAIA 控制通道。
                    HyperPodsAction.PODS_CONNECTED -> {
                        connectSignal++
                        MoondropLink.refreshAll()
                    }

                    HyperPodsAction.PODS_DISCONNECTED -> {
                        snapshot = MoondropLink.snapshot()
                    }

                    HyperPodsAction.BATTERY_CHANGED -> {
                        coroutineScope.launch { MoondropLink.refreshBattery() }
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

        // 通知其它进程「UI 起来了，请重放一遍状态」（必须 setPackage，Android 14+ 丢弃隐式广播）
        context.sendBroadcast(Intent(HyperPodsAction.UI_INIT).setPackage(BuildConfig.APPLICATION_ID))
        MoondropLink.refreshAll()

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            MoondropLink.init(context.applicationContext, object : PodListener {
                override fun onEvent(event: PodEvent) = Unit
            })
        }
    }

    // ── 设备发现 + 连接 ────────────────────────────────────────────────────
    LaunchedEffect(connectSignal) {
        val target = findMoondropBondedDevice(context)
        if (target == null) {
            Log.i(TAG, "no bonded Moondrop device found")
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

    val items = listOf(
        NavigationItem(stringResource(R.string.pod_info), MiuixIcons.Settings),
        NavigationItem(stringResource(R.string.about), MiuixIcons.Info),
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
            NavigationBar(
                color = Color.Transparent,
                modifier = Modifier
                    .hazeChild(
                        hazeState
                    ) {
                        style = hazeStyle
                        blurRadius = 25.dp
                        noiseFactor = 0f
                    },
                items = items,
                selected = targetPage,
                onClick = { index ->
                    if (index in 0..1) {
                        targetPage = index
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
                }
            )
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
    HorizontalPager(
        modifier = modifier,
        pagerState = pagerState,
        pageContent = { page ->
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
    )
}

/**
 * 找到已配对的、能被型号档案识别的水月雨耳机。
 * 优先取偏好里记录的「上次连接地址」，否则取第一个命中的设备。
 *
 * 需要 BLUETOOTH_CONNECT 权限（未授予时返回 null，UI 走等待页）。
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun findMoondropBondedDevice(context: Context): BluetoothDevice? {
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Log.w(TAG, "BLUETOOTH_CONNECT not granted; skip device discovery")
        return null
    }
    val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
    if (adapter == null || !adapter.isEnabled) return null
    val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return null
    val preferred = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_ADDRESS, null)
    val candidates = bonded.filter { MoondropModels.match(it.name) != null }
    return candidates.firstOrNull { it.address == preferred } ?: candidates.firstOrNull()
}
