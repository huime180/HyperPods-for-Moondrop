/*
 * HyperPods for Moondrop — 模块页两张状态卡的蓝牙数据源
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 两件事：
 *   ① rememberBluetoothStatus()：蓝牙开关 + 已配对设备数量。直接读 BluetoothAdapter
 *      （同参考实现 MainUI.kt:879-887 的 readBluetoothState），没有 BLUETOOTH_CONNECT 权限时
 *      一律回落成「关 / 0」，不谎报；蓝牙开关或配对状态一变就重读。
 *   ② rememberBluetoothServiceResponsive()：蓝牙进程里的模块最近是否真的在响应。
 *
 * ② 与参考实现的差异（如实说明）：
 *   参考实现是让蓝牙进程在收到 UI_INIT 后回一条 ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE，
 *   应用侧记下时间戳并用 75 秒窗口判活（参考 MainUI.kt:304-307 与 364-370）。
 *   本项目没有这条广播，而它位于 hook/ 目录下的蓝牙进程代码里，本轮不允许改动，
 *   所以这里改用本项目蓝牙进程确实会发出的广播来判活：PODS_CONNECTED / PODS_DISCONNECTED /
 *   CODEC_CHANGED / LOW_LATENCY_CHANGED（四个都来自 hook/HeadsetStateDispatcher.kt）。
 *   探活请求复用 HyperPodsAction.UI_INIT —— 与 pods/ControlBridge.kt:220-227 完全同一条
 *   （蓝牙进程收到它只会重放一次当前 A2DP 编码，不改变任何设置），30 秒一次、75 秒过期，
 *   与参考实现的节拍一致。
 *
 *   已知局限：耳机没有通过模块连上时，蓝牙进程不会回 CODEC_CHANGED，此时这一格只能显示
 *   「未响应」；只要链路本身连着（linkConnected），就直接算「在响应」——链路就是蓝牙进程
 *   里跑起来的东西，那已经是最硬的证据。
 */
package moe.chenxy.hyperpods.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

/** 蓝牙进程被注入时的包名（应用进程与它的对端关系见 pods/ControlBridge.kt）。 */
private const val PKG_BLUETOOTH = "com.android.bluetooth"

/** 蓝牙进程多久没回过话就算「未响应」；与参考实现 75 秒的窗口一致。 */
private const val SERVICE_ALIVE_WINDOW_MS = 75_000L

/** 探活间隔；与参考实现 30 秒一次的 UI_INIT 节拍一致。 */
private const val SERVICE_PROBE_INTERVAL_MS = 30_000L

/** 窗口判定的轮询间隔；与参考实现 5 秒一次的重算一致。 */
private const val RESPONSIVE_TICK_MS = 5_000L

/** 蓝牙开关 + 已配对设备数（模块页两张 StatCard 的显示值）。 */
@Stable
internal data class BluetoothStatus(
    val enabled: Boolean,
    val bondedCount: Int,
)

/** 权限缺失 / adapter 拿不到时的中性值：蓝牙按「关」、配对数量按 0。 */
private val UNKNOWN_BLUETOOTH_STATUS = BluetoothStatus(enabled = false, bondedCount = 0)

/** 读一次蓝牙状态，并订阅蓝牙开关 / 配对状态广播做增量重读。 */
@Composable
internal fun rememberBluetoothStatus(): BluetoothStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readBluetoothStatus(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> status = readBluetoothStatus(context)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return status
}

/** 读蓝牙开关 + 已配对设备数；无权限时返回 [UNKNOWN_BLUETOOTH_STATUS]，绝不放行异常。 */
@SuppressLint("MissingPermission")
private fun readBluetoothStatus(context: Context): BluetoothStatus {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return UNKNOWN_BLUETOOTH_STATUS
    return runCatching {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        BluetoothStatus(
            enabled = adapter?.isEnabled == true,
            bondedCount = adapter?.bondedDevices?.size ?: 0,
        )
    }.getOrDefault(UNKNOWN_BLUETOOTH_STATUS)
}

/**
 * 蓝牙进程里的模块是否还在响应。
 *
 * @param linkConnected 当前是否有耳机通过模块连着。为真时直接算「在响应」：那条链路本身就是
 *                      在蓝牙进程里建立并维持的，这已经是最硬的证据。
 */
@Composable
internal fun rememberBluetoothServiceResponsive(linkConnected: Boolean): Boolean {
    val context = LocalContext.current
    var lastAliveMs by remember { mutableStateOf(0L) }
    var responsive by remember { mutableStateOf(false) }

    // 收到蓝牙进程真实发来的广播 = 它刚刚还在干活
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    HyperPodsAction.PODS_CONNECTED,
                    HyperPodsAction.PODS_DISCONNECTED,
                    HyperPodsAction.CODEC_CHANGED,
                    HyperPodsAction.LOW_LATENCY_CHANGED -> lastAliveMs = SystemClock.elapsedRealtime()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(HyperPodsAction.PODS_CONNECTED)
            addAction(HyperPodsAction.PODS_DISCONNECTED)
            addAction(HyperPodsAction.CODEC_CHANGED)
            addAction(HyperPodsAction.LOW_LATENCY_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // 探活：定期发一条 UI_INIT 给蓝牙进程（它只会重放一次当前编码，不改任何设置）
    LaunchedEffect(context) {
        while (true) {
            runCatching {
                context.sendBroadcast(
                    Intent(HyperPodsAction.UI_INIT).apply {
                        setPackage(PKG_BLUETOOTH)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                )
            }
            delay(SERVICE_PROBE_INTERVAL_MS)
        }
    }

    // 窗口到期就翻成「未响应」
    LaunchedEffect(lastAliveMs, linkConnected) {
        while (true) {
            val elapsed = if (lastAliveMs > 0L) {
                SystemClock.elapsedRealtime() - lastAliveMs
            } else {
                Long.MAX_VALUE
            }
            responsive = linkConnected || elapsed <= SERVICE_ALIVE_WINDOW_MS
            delay(RESPONSIVE_TICK_MS)
        }
    }

    return responsive
}
