/*
 * HyperPods for Moondrop — UI 侧状态订阅（弹窗与详情页共用）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 职责边界（与 pods/ControlBridge.kt 分工，避免重复下发）：
 *   · 连接/控制：由 manifest 声明的 ControlReceiver → ControlBridge 负责
 *     （即使应用没在前台也能被显式广播唤醒），所以 UI 不注册任何 *_SELECT 接收器、
 *     也不自己 connect/disconnect。
 *   · 这里只做「状态展示」：
 *       ① 进程内 [PodEvent]（[MoondropLink.addListener]）—— 主状态源，字段最全；
 *       ② 跨进程广播（状态变化动作）—— 触发器：收到就 refreshAll() 重新读一次。
 *   · 冷启动兜底：用户直接从桌面/弹窗进入、而耳机早已连上（不会有 PODS_CONNECTED 广播）时，
 *     做一次已配对设备发现并 connect；仅当当前未连接时执行。
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.pods.ControlBridge
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodEvent
import moe.chenxy.hyperpods.pods.PodListener
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

private const val TAG = "MoondropPodState"

/**
 * UI 侧本地偏好：只保存「上一次连接过的耳机地址」，供冷启动兜底优先重连。
 * 注意：刻意不复用 HyperPodsPrefsKey —— 那份契约里没有「上次连接地址」键。
 */
private const val UI_PREFS = "hyperpods_moondrop_ui"
private const val KEY_LAST_ADDRESS = "last_connected_address"

/**
 * 订阅耳机状态，返回可直接用于组合的 [PodSnapshot]。
 *
 * 弹窗（[PopupActivity]）与详情页（MainUI 的设备页）都用它，保证两条入口的状态口径一致：
 * 同一个 [MoondropLink] 多监听者列表 + 同一个 ControlBridge 转发器。
 */
@Composable
fun rememberPodSnapshot(): PodSnapshot {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(MoondropLink.snapshot()) }
    // 广播触发的「重新读一次」信号：自增即让下面的 LaunchedEffect 重新拉一次快照
    var refreshSignal by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val uiListener = object : PodListener {
            override fun onEvent(event: PodEvent) {
                snapshot = when (event) {
                    is PodEvent.Connected -> event.snapshot
                    else -> MoondropLink.snapshot()
                }
            }
        }
        // ControlBridge 负责 appContext + 跨进程转发（幂等）；UI 只追加自己的监听者
        ControlBridge.ensureInit(context.applicationContext)
        MoondropLink.addListener(uiListener)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    // 连接/断开由 ControlBridge 处理，这里只刷新本地展示
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

    // 冷启动兜底连接：仅未连接时执行一次
    LaunchedEffect(Unit) {
        if (!MoondropLink.snapshot().connected) {
            val target = findBondedMoondropDevice(context)
            if (target == null) {
                Log.i(TAG, "no bonded Moondrop device; waiting state")
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

    return snapshot
}

/**
 * 冷启动兜底：已配对设备里挑一个水月雨耳机，优先偏好里记录的「上次连接地址」。
 * 主路径是 ControlBridge 收到蓝牙进程的 PODS_CONNECTED 广播后按 MAC 精确连接。
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
