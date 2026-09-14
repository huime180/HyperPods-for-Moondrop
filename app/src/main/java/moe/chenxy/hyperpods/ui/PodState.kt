/*
 * HyperPods for Moondrop — UI 侧状态订阅（弹窗与详情页共用）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 普通 App 收窄后的职责边界：
 *   · 状态源只有一个：进程内的 [PodEvent]（[MoondropLink.addListener]），字段最全；
 *   · 连接/控制由 [ControlBridge] 负责初始化（appContext + 状态转发），
 *     UI 不自己 connect/disconnect，也不注册任何 *_SELECT 接收器；
 *   · 冷启动兜底：用户直接从桌面/弹窗进入、而耳机早已连上（不会有任何广播）时，
 *     做一次已配对设备发现并 connect；仅当当前未连接时执行。
 *
 * 原来的跨进程广播接收器（PODS_CONNECTED / BATTERY_CHANGED / *_CHANGED 一组）随 hook 一起删除：
 * 那些动作全部由被注入的系统进程发出，本应用已不是模块，没有任何发送方了。
 */
package moe.chenxy.hyperpods.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.pods.ControlBridge
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodEvent
import moe.chenxy.hyperpods.pods.PodListener
import moe.chenxy.hyperpods.pods.PodSnapshot

private const val TAG = "MoondropPodState"

/**
 * UI 侧本地偏好：只保存「上一次连接过的耳机地址」，供冷启动兜底优先重连。
 * 注意：刻意不复用 HyperPodsPrefsKey —— 那份契约里没有「上次连接地址」键。
 *
 * internal：pods/BluetoothConnectReceiver.kt（系统蓝牙广播唤起的路径）在
 * 拿不到设备名时也读同一个地址做设备判定，不在这里再抄一份字符串。
 */
internal const val UI_PREFS_GROUP = "hyperpods_moondrop_ui"
internal const val UI_PREFS_KEY_LAST_ADDRESS = "last_connected_address"

/**
 * 订阅耳机状态，返回可直接用于组合的 [PodSnapshot]。
 *
 * 弹窗（[PopupActivity]）与详情页（MainUI 的耳机页）都用它，保证两条入口的状态口径一致：
 * 同一个 [MoondropLink] 多监听者列表 + 同一个 ControlBridge 转发器。
 */
@Composable
fun rememberPodSnapshot(): PodSnapshot {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(MoondropLink.snapshot()) }

    DisposableEffect(context) {
        val uiListener = object : PodListener {
            override fun onEvent(event: PodEvent) {
                snapshot = when (event) {
                    is PodEvent.Connected -> event.snapshot
                    else -> MoondropLink.snapshot()
                }
            }
        }
        // ControlBridge 负责 appContext + 状态转发（幂等）；UI 只追加自己的监听者
        ControlBridge.ensureInit(context.applicationContext)
        MoondropLink.addListener(uiListener)

        onDispose {
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
                context.getSharedPreferences(UI_PREFS_GROUP, Context.MODE_PRIVATE)
                    .edit()
                    .putString(UI_PREFS_KEY_LAST_ADDRESS, target.address)
                    .apply()
                Log.i(TAG, "cold-start connect to ${target.address} (${target.name})")
                MoondropLink.connect(target)
            }
        }
        snapshot = MoondropLink.snapshot()
    }

    return snapshot
}

/**
 * 冷启动兜底：已配对设备里挑一个水月雨耳机，优先偏好里记录的「上次连接地址」。
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
    val preferred = context.getSharedPreferences(UI_PREFS_GROUP, Context.MODE_PRIVATE)
        .getString(UI_PREFS_KEY_LAST_ADDRESS, null)
    val candidates = bonded.filter { MoondropModels.match(it.name) != null }
    return candidates.firstOrNull { it.address == preferred } ?: candidates.firstOrNull()
}
