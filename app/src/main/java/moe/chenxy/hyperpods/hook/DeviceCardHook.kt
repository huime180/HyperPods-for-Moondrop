/*
 * HyperPods for Moondrop — SystemUI（miui.systemui.plugin）融合设备中心耳机卡
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 机制（对照 HyperPods `hook/DeviceCardHook.kt`）：
 *   · hook miui.systemui.devicecenter.devices.DeviceInfoWrapper#performClicked(Context)
 *   · 只有 getDeviceType() == "third_headset" 的卡片才处理（其它卡片放行）
 *   · 卡片的 id 就是蓝牙 MAC：向 com.android.bluetooth 广播 GET_PODS_MAC 索取当前水月雨耳机 MAC
 *     （蓝牙进程回 PODS_MAC_RECEIVED + EXTRA_MAC）；MAC 与卡片 id 相同 -> 打开本模块 UI 并吞掉点击
 *   · hook miui.systemui.controlcenter.panel.main.MainPanelController#onCreate 缓存控制中心面板，
 *     打开我们的 UI 时调用 exitOrHide() 收起面板
 *
 * 与原实现的关键差异（**非阻塞**，原实现 Thread.sleep(50) 忙等最多 500ms 会卡 SystemUI 主线程）：
 *   1) 广播接收器在一个独立 HandlerThread 上「提前」注册（onHook 时就用
 *      ActivityThread.currentApplication() 拿到进程 Context 注册；拿不到时退化为首次点击注册）；
 *   2) 点击时若已有缓存 MAC 且与卡片 id 匹配 -> 立刻打开 UI 并吞掉点击；
 *   3) 否则只发一次 GET_PODS_MAC 请求然后**放行**这次点击（绝不阻塞、绝不 sleep），
 *      用户再点一次即可命中（此时缓存已就绪）。
 */
package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

@SuppressLint("PrivateApi")
object DeviceCardHook : HookContext() {
    private const val TAG = "HyperPods-DeviceCard"
    private const val CLS_DEVICE_INFO_WRAPPER = "miui.systemui.devicecenter.devices.DeviceInfoWrapper"
    private const val CLS_MAIN_PANEL_CONTROLLER = "miui.systemui.controlcenter.panel.main.MainPanelController"
    private const val DEVICE_TYPE_THIRD_HEADSET = "third_headset"
    private const val PKG_BLUETOOTH = "com.android.bluetooth"

    /** 蓝牙进程回包缓存的「当前已连接水月雨耳机」MAC（空 = 尚未握手）。 */
    @Volatile
    private var cachedMac: String = ""

    @Volatile
    private var cachedName: String = ""

    /** 最近一次被点击、但当时还没有 MAC 的卡片 id（仅用于日志/诊断）。 */
    @Volatile
    private var pendingCardId: String = ""

    @Volatile
    private var receiverRegistered = false

    private var receiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var mainPanelController: Any? = null

    override fun onHook() {
        registerMacReceiverUpFront()
        hookMainPanelController()
        hookDeviceInfoWrapper()
    }

    override fun onHotReloading() {
        runCatching {
            receiver?.let { r -> receiverContext?.unregisterReceiver(r) }
        }.onFailure { Log.w(TAG, "unregister MAC receiver failed", it) }
        receiver = null
        receiverContext = null
        receiverRegistered = false
        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
        handler = null
        mainPanelController = null
        cachedMac = ""
        cachedName = ""
        pendingCardId = ""
    }

    // ── MAC 广播接收（独立 HandlerThread，绝不在 SystemUI 主线程等待） ──────────────

    private fun registerMacReceiverUpFront() {
        val context = SystemApisUtils.currentApplication()
        if (context == null) {
            Log.w(TAG, "process Context not ready; MAC receiver will register on first click")
            return
        }
        registerMacReceiver(context)
    }

    @Synchronized
    private fun registerMacReceiver(context: Context) {
        if (receiverRegistered) return
        val appContext = context.applicationContext ?: context
        val thread = HandlerThread("hyperpods_mac_bridge")
        val started = runCatching { thread.start() }.isSuccess
        val handler = if (started) Handler(thread.looper) else Handler(context.mainLooper)
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != HyperPodsAction.PODS_MAC_RECEIVED) return
                cachedMac = intent.getStringExtra(HyperPodsAction.EXTRA_MAC).orEmpty()
                cachedName = intent.getStringExtra(HyperPodsAction.EXTRA_DEVICE_NAME).orEmpty()
                Log.i(TAG, "pods mac=$cachedMac name=$cachedName pendingCardId=$pendingCardId")
            }
        }
        // 5 参重载（broadcastPermission = null + scheduler + flags）：发送方是 com.android.bluetooth，
        // 属跨应用广播，必须 RECEIVER_EXPORTED。
        val registered = runCatching {
            appContext.registerReceiver(
                broadcastReceiver,
                IntentFilter(HyperPodsAction.PODS_MAC_RECEIVED),
                null,
                handler,
                Context.RECEIVER_EXPORTED
            )
        }.onFailure { Log.w(TAG, "register MAC receiver skipped", it) }.isSuccess
        if (!registered) return
        this.receiver = broadcastReceiver
        this.receiverContext = appContext
        this.handler = handler
        this.handlerThread = if (started) thread else null
        this.receiverRegistered = true
        Log.d(TAG, "MAC receiver registered on ${if (started) "HandlerThread" else "main looper"}")
    }

    private fun requestMac(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(HyperPodsAction.GET_PODS_MAC).apply {
                setPackage(PKG_BLUETOOTH)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.d(TAG, "GET_PODS_MAC sent to $PKG_BLUETOOTH")
        }.onFailure { Log.w(TAG, "GET_PODS_MAC failed", it) }
    }

    // ── 控制中心面板缓存（打开我们的 UI 前收起面板） ─────────────────────────────

    private fun hookMainPanelController() {
        runCatching {
            val method = findMethodByParamCountOrNull(CLS_MAIN_PANEL_CONTROLLER, "onCreate", 0)
                ?: findMethodByParamCountOrNull(CLS_MAIN_PANEL_CONTROLLER, "onCreate", 1)
                ?: findAnyMethod(CLS_MAIN_PANEL_CONTROLLER, "onCreate")
            hookAfter(method) {
                mainPanelController = instance
                Log.d(TAG, "MainPanelController cached: ${instance?.javaClass?.name}")
            }
            Log.d(TAG, "hooked $CLS_MAIN_PANEL_CONTROLLER#onCreate")
        }.onFailure { Log.w(TAG, "hook $CLS_MAIN_PANEL_CONTROLLER.onCreate skipped", it) }
    }

    private fun hidePanel() {
        val controller = mainPanelController ?: run {
            Log.d(TAG, "exitOrHide skipped: no MainPanelController cached")
            return
        }
        runCatching { callMethod(controller, "exitOrHide") }
            .recoverCatching { callMethod(controller, "exitOrHide", false) }
            .onFailure { Log.w(TAG, "exitOrHide failed", it) }
    }

    // ── 设备卡点击 ─────────────────────────────────────────────────────────────

    private fun hookDeviceInfoWrapper() {
        runCatching {
            val method = findMethodOrNull(CLS_DEVICE_INFO_WRAPPER, "performClicked", Context::class.java)
                ?: findMethodByParamCountOrNull(CLS_DEVICE_INFO_WRAPPER, "performClicked", 1)
                ?: throw NoSuchMethodException("$CLS_DEVICE_INFO_WRAPPER#performClicked")
            hookBefore(method) {
                runCatching { onCardClicked(this) }
                    .onFailure { Log.w(TAG, "performClicked handling failed", it) }
            }
            Log.d(TAG, "hooked $CLS_DEVICE_INFO_WRAPPER#performClicked(${method.parameterTypes.joinToString { it.name }})")
        }.onFailure { Log.w(TAG, "hook $CLS_DEVICE_INFO_WRAPPER.performClicked skipped", it) }
    }

    private fun onCardClicked(param: HookParam) {
        val context = param.args.firstOrNull { it is Context } as? Context
        val deviceInfo = runCatching { callMethod(param.instance, "getDeviceInfo") }.getOrNull()
        val deviceType = runCatching { callMethod(deviceInfo, "getDeviceType") as? String }.getOrNull()
            ?: runCatching { callMethod(param.instance, "getDeviceType") as? String }.getOrNull()
        val cardId = runCatching { callMethod(deviceInfo, "getId") as? String }.getOrNull()
            ?: runCatching { callMethod(param.instance, "getId") as? String }.getOrNull()

        if (context == null) {
            Log.w(TAG, "performClicked without Context, pass through")
            return
        }
        if (deviceType != DEVICE_TYPE_THIRD_HEADSET) return

        if (!receiverRegistered) registerMacReceiver(context.applicationContext ?: context)

        val mac = cachedMac
        if (mac.isNotEmpty() && cardId != null && mac.equals(cardId, ignoreCase = true)) {
            Log.i(TAG, "card click matched mac=$mac name=$cachedName -> open module UI")
            openModuleUi(context)
            hidePanel()
            // 吞掉点击：这张卡片背后没有小米原生设备，交给蓝牙图标/系统页处理会变成空操作。
            param.result = null
            return
        }

        // 非阻塞：请求一次 MAC，然后放行本次点击（绝不 Thread.sleep）。缓存就绪后再点即命中。
        pendingCardId = cardId.orEmpty()
        requestMac(context)
        Log.i(TAG, "card click id=$cardId cachedMac=$mac -> MAC requested, click passed through")
    }

    private fun openModuleUi(context: Context) {
        runCatching {
            context.startActivity(Intent(HyperPodsAction.SHOW_UI).apply {
                setPackage(BuildConfig.APPLICATION_ID)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { Log.w(TAG, "startActivity(${HyperPodsAction.SHOW_UI}) failed", it) }
    }
}
