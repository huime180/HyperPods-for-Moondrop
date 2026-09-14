/*
 * HyperPods for Moondrop — 应用内状态桥
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么还需要它：
 *   协议客户端（MoondropLink）跑在应用进程，但它的状态变化要分发给应用里的两个消费者：
 *     · pods/PodNotification.kt —— 应用自己那条耳机状态通知（连上/电量变化重画，断开撤掉）；
 *     · ui/ConnectionPopupActivity —— 首次拿到有效电量时弹一次连接弹窗。
 *   这两件事都要一个长期存活的 [PodListener]，而 listener 只能在初始化那一刻注册，
 *   所以由本对象在 [ensureInit] 里注册一次，之后一直转发。
 *
 * 它**不再**做任何跨进程转发：原先把 ANC / 电量 / 手势 / 通知状态发给
 * com.android.bluetooth、com.android.settings、com.xiaomi.bluetooth、com.milink.service，
 * 那些接收方全是 Xposed hook；本应用已不是模块，这些转发与它们的接收方一起删除。
 * manifest 里那个只为「让系统进程用显式广播唤醒本应用」而存在的跨进程广播接收器也一并删除：
 * 现在唯一的外部唤醒入口是 pods/BluetoothConnectReceiver.kt 监听的系统 A2DP 广播。
 *
 * 幂等：[ensureInit] 由 UI（ui/PodState.kt）与 pods/BluetoothConnectReceiver.kt 各调用一次。
 */
package moe.chenxy.hyperpods.pods

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import moe.chenxy.hyperpods.ui.ConnectionPopupActivity
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

private const val TAG = "ControlBridge"

/** 电量 Bundle 编码：255 = 未知；置 bit7 表示充电中（与 MIUI 原生耳机页的约定一致）。 */
private object BatteryCodecWire {
    const val UNKNOWN = 255

    fun encode(level: Int, charging: Boolean): Int = when {
        level < 0 -> UNKNOWN
        charging -> (level.coerceIn(0, 100)) or 128
        else -> level.coerceIn(0, 100)
    }

    fun toBundle(b: BatterySnapshot): Bundle = Bundle().apply {
        putInt("left", encode(b.left, b.leftCharging))
        putInt("right", encode(b.right, b.rightCharging))
        putInt("case", encode(b.case, b.caseCharging))
        putBoolean("left_charging", b.leftCharging)
        putBoolean("right_charging", b.rightCharging)
        putBoolean("case_charging", b.caseCharging)
    }
}

object ControlBridge {

    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null

    /**
     * 上次弹过「连接弹窗」的设备地址：同一台设备的一次连接只在首次拿到有效电量时弹一次。
     * 断开（[PodEvent.Disconnected]）时清空，重连后可以再弹。
     *
     * 为什么按地址而不是一个布尔量：进程内的 [PodEvent.Connected] 是**全量状态事件**
     * （MoondropLink.emitState 每次状态变化都会发一条），不能拿它当「刚连上」的信号；
     * 而 MoondropLink.connect() 换设备时先 disconnectInternal(notify = false)，
     * 不会有 Disconnected 事件 —— 用地址才能覆盖「不先断开、直接连另一台」的情况。
     */
    @Volatile private var popupShownForAddress: String? = null

    /** 幂等初始化：接上协议客户端并注册状态转发器。 */
    @Synchronized
    fun ensureInit(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        MoondropLink.init(context.applicationContext, forwarder)
        // 应用进程自己的那条状态通知（pods/PodNotification.kt）：初始化时对一次账，
        // 进程重启后把自己那条重画（耳机没连就撤掉，不留旧通知）。
        PodNotification.prime(context.applicationContext)
        initialized = true
        Log.i(TAG, "ControlBridge initialized")
    }

    // ── 状态变化 → 应用内消费者 ────────────────────────────────────────────

    private val forwarder = object : PodListener {
        override fun onEvent(event: PodEvent) {
            val ctx = appContext ?: return
            when (event) {
                is PodEvent.Connected -> {
                    val snap = event.snapshot
                    if (!snap.connected) return
                    PodNotification.onSnapshot(ctx, snap)
                    maybeShowConnectionPopup(ctx, snap)
                }
                is PodEvent.BatteryChanged -> {
                    val snap = MoondropLink.snapshot()
                    if (!snap.connected) return
                    PodNotification.onSnapshot(ctx, snap)
                    maybeShowConnectionPopup(ctx, snap)
                }
                is PodEvent.Disconnected -> {
                    popupShownForAddress = null
                    PodNotification.cancel(ctx)
                }
                else -> Unit
            }
        }
    }

    /**
     * 首次拿到有效电量时弹出「连接弹窗」，并把这一刻的设备名与电量直接作为 extra 带上：
     * 弹窗首帧就有内容（不再依赖 hook 时代那条 BATTERY_CHANGED 广播来刷新 ——
     * 那条广播的发送方已随模块一起删除）。
     */
    private fun maybeShowConnectionPopup(context: Context, snapshot: PodSnapshot) {
        if (!snapshot.battery.anyKnown) return
        val address = snapshot.deviceAddress
        if (address.isEmpty() || address == popupShownForAddress) return
        popupShownForAddress = address
        runCatching {
            context.startActivity(
                Intent(context, ConnectionPopupActivity::class.java)
                    .putExtra(
                        ConnectionPopupActivity.EXTRA_STATUS,
                        BatteryCodecWire.toBundle(snapshot.battery),
                    )
                    .putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, snapshot.deviceName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.onFailure { Log.w(TAG, "connection popup launch failed: ${it.message}") }
    }
}
