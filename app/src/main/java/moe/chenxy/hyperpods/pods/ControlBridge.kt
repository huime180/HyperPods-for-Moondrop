/*
 * MiuixMoondrop — 应用内状态桥
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么还需要它：
 *   协议客户端（MoondropLink）跑在应用进程，但它的状态变化要分发给应用里的两个消费者：
 *     · pods/PodNotification.kt —— 应用自己那条耳机状态通知（连上/电量变化重画，断开撤掉）；
 *     · ui/ConnectionPopupActivity —— 首次拿到有效电量时弹一次「连接弹窗」（三路电量）；
 *       状态栏通知在同一条事件里一起刷新（见下），所以两者是**同时**出现的。
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
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

/**
 * 「连接弹窗」的重试间隔与次数。
 *
 * 后台启动 Activity 可能被系统**静默**拦掉（Android 10 起的 BAL 限制，见 ui/Permissions.kt）：
 * 不抛异常、也没有任何回调，所以启动后要回头确认「这次到底起来了没有」——没起来就再试，
 * 最多 [POPUP_MAX_ATTEMPTS] 次，避免在真正的拒绝上无限刷屏。
 */
private const val POPUP_RETRY_DELAY_MS = 800L
private const val POPUP_MAX_ATTEMPTS = 3

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

    /** 拉官方机型图用的 IO 作用域（网络 + 写文件，绝不能落在主线程）。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 这次连接已经为哪个地址试过拉图（断开时清空，重连可再试）。 */
    @Volatile private var imageFetchAttemptedFor: String? = null

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
                    maybeFetchOfficialImage(ctx, snap)
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
                    imageFetchAttemptedFor = null
                    PodNotification.cancel(ctx)
                }
                else -> Unit
            }
        }
    }

    /**
     * 首次拿到有效电量时**排队**弹出「连接弹窗」（ui/ConnectionPopupActivity，三路电量），
     * 延后 [POPUP_SETTLE_DELAY_MS] 让首批电量帧落定。
     *
     * 与状态栏通知的关系：同一条事件里先 [PodNotification.onSnapshot] 刷新通知、再排这一次弹窗，
     * 所以「唤醒通知 + 唤醒连接弹窗」是同时发生的（设置页两个开关各自独立控制）。
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
            launchConnectionPopupWithRetry(context)
        }
        pendingPopup = pending
        mainHandler.postDelayed(pending, POPUP_SETTLE_DELAY_MS)
    }

    /**
     * 弹出「连接弹窗」，并在被系统拦掉时**重试**（「重新弹窗」）。
     *
     * 判定落地的方式：启动前后取 [ConnectionPopupActivity.lastShownAt] /
     * [ConnectionPopupActivity.visible] 对比 —— 弹窗进 onResume 时会写这两个标记。
     * 没写说明这一次启动没落地（BAL 静默拦截），再试一次；试满 [POPUP_MAX_ATTEMPTS] 次
     * 仍未落地就放弃并留下明确日志。
     *
     * 已在显示时用 SINGLE_TOP + CLEAR_TOP 把它带到前台，不叠新实例。
     */
    private fun launchConnectionPopupWithRetry(context: Context) {
        var attempt = 0
        fun attemptOnce() {
            attempt++
            if (!MoondropLink.snapshot().connected) return
            // 缺「显示在其他应用上层」/「后台弹出界面」时系统会静默拦掉，先留一条可诊断日志；
            // 判断结果不影响启动尝试 —— 有的 ROM 即使没这个权限也放行。
            if (!canStartActivityFromBackground(context)) {
                Log.w(TAG, "连接弹窗可能被 BAL 拦掉：缺「显示在其他应用上层」/「后台弹出界面」权限")
            }
            val startedAt = SystemClock.elapsedRealtime()
            val snapshot = MoondropLink.snapshot()
            runCatching {
                context.startActivity(
                    Intent(context, ConnectionPopupActivity::class.java)
                        .putExtra(
                            ConnectionPopupActivity.EXTRA_STATUS,
                            BatteryCodecWire.toBundle(snapshot.battery),
                        )
                        .putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, snapshot.deviceName)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                )
            }.onFailure { Log.w(TAG, "连接弹窗启动失败: ${it.message}") }

            if (attempt >= POPUP_MAX_ATTEMPTS) {
                Log.w(TAG, "连接弹窗试了 $attempt 次仍未确认显示（可能一直受后台启动限制）")
                return
            }
            mainHandler.postDelayed({
                if (ConnectionPopupActivity.visible ||
                    ConnectionPopupActivity.lastShownAt >= startedAt
                ) {
                    Log.i(TAG, "连接弹窗已在第 $attempt 次尝试后显示")
                } else {
                    attemptOnce()
                }
            }, POPUP_RETRY_DELAY_MS)
        }
        attemptOnce()
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

    /**
     * 连接后自动取一次官方机型图（没有就拉一张落盘）。
     *
     * 只在**这次连接里没试过**、且该设备还没有图时试一次：官方目录不在时不要反复重试
     * （用户可以在详情页「机型图片」里手动选）。整个拉取在 IO 线程做，失败只留日志。
     */
    private fun maybeFetchOfficialImage(context: Context, snapshot: PodSnapshot) {
        val address = snapshot.deviceAddress
        if (address.isBlank() || address == imageFetchAttemptedFor) return
        if (PodImageStore.hasImage(context, address)) return
        imageFetchAttemptedFor = address
        val deviceName = snapshot.deviceName.ifBlank { snapshot.modelName }
        ioScope.launch {
            val ok = PodImageStore.fetchOfficialImage(context, address, deviceName)
            Log.i(TAG, "official pod image for $address ($deviceName) -> $ok")
        }
    }

    /**
     * UI（ui/PodState.kt 的 rememberPodSnapshot）打开时补一次自动取图。
     *
     * 为什么需要这个入口：连接路径上的自动取图只在 [PodEvent.Connected] 事件里触发，
     * 而 Connected 是**全量状态事件**。存在两种「已经连着、但这次进程里还没人触发过取图」
     * 的情况：① 用户从通知栏 / 弹窗先打开过一次界面（那时取图失败或还没回来），随后才打开
     * 主界面；② 用户手动「恢复默认」清掉图片后重新进入界面。
     *
     * 刻意**不新写一套取图逻辑**：这里直接复用 [maybeFetchOfficialImage]，所以
     * 「同一地址一次会话只自动试一次」的门闩 [imageFetchAttemptedFor] 依然生效 ——
     * 未连接（deviceAddress 为空）时提前返回，也不会消耗那一次尝试机会。
     */
    fun ensureOfficialImage(snapshot: PodSnapshot) {
        val ctx = appContext ?: return
        maybeFetchOfficialImage(ctx, snapshot)
    }

    /** 撤销还没启动的弹窗任务（断开时调用）。 */
    private fun cancelPendingPopup() {
        pendingPopup?.let { mainHandler.removeCallbacks(it) }
        pendingPopup = null
    }
}
