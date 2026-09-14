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
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.chenxy.hyperpods.ui.ConnectionPopupActivity
import moe.chenxy.hyperpods.ui.MODULE_PREFS_GROUP
import moe.chenxy.hyperpods.ui.canStartActivityFromBackground
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

private const val TAG = "ControlBridge"

/**
 * 弹窗延后这么久再启动：等首批电量帧落定。
 *
 * 真机实测（布丁，2026-09-15）：连接后第一批电量帧只带**左耳**（`左耳 2%`，右耳/充电盒未知），
 * 286 ms 之后才是完整的 `左耳 59% · 右耳 64%`。上一版拿到第一批就启动，弹窗于是整段存活期
 * 都停在那个残缺读数上；这里多等 600 ms，启动时再取**当时**的快照，首帧就是对的。
 * 顺带跳过「刚连上又立刻断开」的抖动窗口（下面 [cancelPendingPopup]）。
 */
private const val POPUP_SETTLE_DELAY_MS = 600L

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

    /** 延后启动用的主线程队列（[PodListener] 的回调本来就在主线程，这里只是排一次延后）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 已排队但还没启动的弹窗任务；断开时撤销（见 [cancelPendingPopup]）。 */
    private var pendingPopup: Runnable? = null

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
                    // 断开＝这次会话结束：撤销还没启动的弹窗，并允许重连后再弹一次
                    cancelPendingPopup()
                    popupShownForAddress = null
                    PodNotification.cancel(ctx)
                }
                else -> Unit
            }
        }
    }

    /**
     * 首次拿到有效电量时**排队**弹出「连接弹窗」（延后 [POPUP_SETTLE_DELAY_MS] 让首批帧落定）。
     *
     * 只排队、不在这里直接启动的原因见该常量的说明；启动那一刻会用
     * [MoondropLink.snapshot] 取**最新**的设备名与电量塞进 extra，因此首帧内容是对的。
     * 弹窗自己还会订阅进程内状态持续纠正（ui/ConnectionPopupActivity），
     * 所以即便设备后续再报新值，卡片也不会停在旧数上。
     */
    private fun maybeShowConnectionPopup(context: Context, snapshot: PodSnapshot) {
        if (!snapshot.battery.anyKnown) return
        if (!autoPopupEnabled(context)) return
        val address = snapshot.deviceAddress
        if (address.isEmpty() || address == popupShownForAddress) return
        // 先认领地址：这样后续每一帧电量都不会再排队第二次（「不重复弹」）。
        // 即使这一次启动被系统判为后台启动而静默丢弃，也不反复尝试刷屏。
        popupShownForAddress = address
        if (pendingPopup != null) return
        val pending = Runnable {
            pendingPopup = null
            launchConnectionPopup(context)
        }
        pendingPopup = pending
        mainHandler.postDelayed(pending, POPUP_SETTLE_DELAY_MS)
    }

    /** 真正的启动动作：用启动这一刻的进程内快照做首帧内容；失败只打日志。 */
    private fun launchConnectionPopup(context: Context) {
        val snapshot = MoondropLink.snapshot()
        if (!snapshot.connected || !snapshot.battery.anyKnown) return
        // 后台启动 Activity 被系统拦掉时是**静默**的（不抛异常、不弹窗），所以这里先留一条
        // 日志指明原因；是否放行由系统决定，因此判断结果不影响下面的启动尝试。
        if (!canStartActivityFromBackground(context)) {
            Log.w(
                TAG,
                "connection popup may be blocked by BAL: 缺「显示在其他应用上层」/「后台弹出界面」权限",
            )
        }
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

    /**
     * 设置页「连接时自动唤出弹窗」开关（默认开）。
     *
     * 与 [PodNotification] 的 SHOW_NOTIFICATION 同一套宽容策略：偏好读不到时按 true 处理 ——
     * 偏好文件异常不该让用户连弹窗都看不到。实时读，改完开关下一次连接即生效。
     */
    private fun autoPopupEnabled(context: Context): Boolean {
        val prefs = runCatching {
            context.getSharedPreferences(MODULE_PREFS_GROUP, Context.MODE_PRIVATE)
        }.getOrNull() ?: return true
        return runCatching { prefs.getBoolean(HyperPodsPrefsKey.AUTO_POPUP_ON_CONNECT, true) }
            .getOrDefault(true)
    }

    /** 撤销还没启动的弹窗任务（断开时调用）。 */
    private fun cancelPendingPopup() {
        pendingPopup?.let { mainHandler.removeCallbacks(it) }
        pendingPopup = null
    }
}
