/*
 * HyperPods for Moondrop — 应用侧跨进程控制桥
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么需要它：
 *   协议客户端（MoondropLink）跑在**本模块的应用进程**，而控制入口分散在系统各进程——
 *   `com.android.settings` 里被伪装的耳机页、`com.android.bluetooth` 里的连接感知。
 *   没有这个桥，就会出现「系统设置页点降噪没反应」「电量没写进系统蓝牙栈」
 *   「超级岛/通知不发」这三类接线缺口。
 *
 * 它由 **manifest 声明的广播接收器** 承载（见 AndroidManifest.xml 的 ControlReceiver），
 * 因此即使本应用没有在运行，系统也能用显式广播把进程拉起来处理控制命令。
 * 显式广播（setPackage）不受 Android 8+ 隐式广播限制。
 *
 * 方向总览：
 *   com.android.bluetooth --PODS_CONNECTED/DISCONNECTED--> 本桥 --> MoondropLink.connect()
 *   com.android.settings  --*_SELECT / UI_INIT / REQUEST_*--> 本桥 --> MoondropLink.setXxx()
 *   本桥 --ANC_CHANGED / BATTERY_CHANGED--> com.android.settings（被伪装的耳机页显示）
 *   com.android.bluetooth --CODEC_CHANGED--> 本桥 --> MoondropLink.onSystemCodecChanged()（详情页「当前编码」）
 *   本桥 --UI_INIT--> com.android.bluetooth（请它重放一次系统真实编码：UI 打开时 + LHDC 切换后）
 *   本桥 --UPDATE_SYSTEM_BATTERY--> com.android.bluetooth（写进 AdapterService，系统 UI 显示电量）
 *   本桥 --UPDATE_PODS_NOTIFICATION / SEND_STRONG_TOAST / CANCEL_*--> com.xiaomi.bluetooth（通知）
 */
package moe.chenxy.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

private const val TAG = "ControlBridge"

/** HyperOS 融合设备中心 / 系统耳机面板的数据层进程 */
private const val MILINK = "com.milink.service"

/** 电池编码：255 = 未知；置 bit7 表示充电中（与 MIUI 原生耳机页的约定一致）。 */
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
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var appContext: Context? = null

    /** 动态注册的编码接收器（见 registerCodecReceiver 的说明；非空即已注册）。 */
    @Volatile private var codecReceiver: BroadcastReceiver? = null

    /** 幂等初始化：注册跨进程状态转发器。 */
    @Synchronized
    fun ensureInit(context: Context) {
        appContext = context.applicationContext
        registerCodecReceiver(context.applicationContext)
        if (initialized) return
        MoondropLink.init(context.applicationContext, forwarder)
        // LHDC 开关后系统 A2DP 会**异步**重新协商编码，而 CODEC_CHANGED 不保证会来：
        // 把「请蓝牙进程重放编码」的通道交给协议侧，由它在切换后有界地调用几次
        // （次数/间隔在 MoondropLink 里，见 reprobeSystemCodec）。
        MoondropLink.setSystemCodecReprobe { reprobeSystemCodec() }
        initialized = true
        Log.i(TAG, "ControlBridge initialized")
    }

    // ── 收到控制命令 ───────────────────────────────────────────────────────

    fun handle(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "control action=$action extras=${intent.extras}")
        when (action) {
            // 蓝牙进程告知连接状态
            HyperPodsAction.PODS_CONNECTED -> {
                val mac = intent.getStringExtra(HyperPodsAction.EXTRA_MAC)
                val name = intent.getStringExtra(HyperPodsAction.EXTRA_DEVICE_NAME)
                Log.i(TAG, "PODS_CONNECTED name=$name mac=$mac")
                onConnected(context, mac, name)
            }
            HyperPodsAction.PODS_DISCONNECTED -> {
                MoondropLink.disconnect()
                connectedDevice = null
                cancelNotification(context)
            }

            // 蓝牙进程报告系统实际协商的 A2DP 编码
            HyperPodsAction.CODEC_CHANGED ->
                runCatching { onCodecChanged(intent) }
                    .onFailure { Log.w(TAG, "CODEC_CHANGED handling failed", it) }

            // 系统侧请求状态重放
            HyperPodsAction.UI_INIT,
            HyperPodsAction.REQUEST_CAPABILITIES -> {
                MoondropLink.refreshAll()
                // 顺手请蓝牙进程重放一次「系统真实编码」：详情页一打开就能拿到正确编码
                requestSystemCodec(context)
            }
            HyperPodsAction.REQUEST_BATTERY ->
                MoondropLink.requestBatteryRefresh()

            // 控制命令（来自被伪装的系统设置页，或本模块 UI）
            HyperPodsAction.ANC_SELECT ->
                MoondropLink.setAnc(intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1))
            HyperPodsAction.GAIN_SELECT ->
                MoondropLink.setGain(intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1))
            HyperPodsAction.LED_SELECT ->
                MoondropLink.setLed(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))
            HyperPodsAction.PROMPT_TONE_SELECT ->
                MoondropLink.setPromptTone(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))
            HyperPodsAction.PROMPT_VOLUME_SELECT ->
                MoondropLink.setPromptVolumeRaw(
                    intent.getIntExtra(HyperPodsAction.EXTRA_PROMPT_VOLUME_RAW, -1)
                )
            HyperPodsAction.LHDC_SELECT ->
                MoondropLink.setLhdc(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))
            HyperPodsAction.DUAL_CONNECTION_SELECT ->
                MoondropLink.setDualConnection(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))
            // 原生耳机页的「手势控制」卡片 → 改某个槽位某个耳的动作
            HyperPodsAction.GESTURE_SELECT -> {
                val slot = intent.getIntExtra(HyperPodsAction.EXTRA_GESTURE_SLOT, -1)
                val ear = intent.getIntExtra(HyperPodsAction.EXTRA_GESTURE_EAR, -1)
                val actionId = intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1)
                val slots = moe.chenxy.hyperpods.core.Gaia.GestureSlot.entries
                val ears = moe.chenxy.hyperpods.core.Gaia.Ear.entries
                if (slot in slots.indices && ear in ears.indices && actionId >= 0) {
                    MoondropLink.setGesture(slots[slot], ears[ear], actionId)
                } else {
                    Log.w(TAG, "GESTURE_SELECT ignored: slot=$slot ear=$ear action=$actionId")
                }
            }
            // 原生页打开/周期刷新时索要当前配置 —— 没有配置就不回，绝不回一份假值
            HyperPodsAction.REQUEST_GESTURE -> {
                MoondropLink.refreshAll()
                pushGesture(context)
            }
            HyperPodsAction.LOW_LATENCY_SELECT -> {
                // 低延迟是 HyperOS 系统侧功能：转发给 com.android.bluetooth 里具有
                // BLUETOOTH_PRIVILEGED 的 hook 去操作系统 A2DP 编解码。
                val on = intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)
                MoondropLink.onLowLatencyChanged(on)
                sendTo(context, "com.android.bluetooth", HyperPodsAction.LOW_LATENCY_SELECT) {
                    it.putExtra(HyperPodsAction.EXTRA_ENABLED, on)
                }
            }
        }
    }

    // ── 系统编码（CODEC_CHANGED） ──────────────────────────────────────────
    //
    // AndroidManifest 里 ControlReceiver 的 intent-filter 没有 codec_changed，而显式广播
    // 同样必须命中 intent-filter 才会投递到 manifest 接收器；本任务只允许改这 3 个文件，
    // 所以这里在应用进程内**动态注册**一个接收器专门收蓝牙进程报来的编码。
    // ensureInit() 由 ControlReceiver（PODS_CONNECTED / UI_INIT 触发）与 UI
    // （ui/PodState.kt 的 rememberPodSnapshot）各调用一次，UI 活着的时候必定已注册。

    @Synchronized
    private fun registerCodecReceiver(context: Context) {
        if (codecReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == HyperPodsAction.CODEC_CHANGED) {
                    runCatching { onCodecChanged(intent) }
                        .onFailure { Log.w(TAG, "codec receiver failed", it) }
                }
            }
        }
        // 发送方是 com.android.bluetooth（另一个应用），必须 RECEIVER_EXPORTED。
        val registered = runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(HyperPodsAction.CODEC_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "register codec receiver skipped: ${it.message}") }.isSuccess
        if (!registered) return
        codecReceiver = receiver
        Log.d(TAG, "codec receiver registered (${HyperPodsAction.CODEC_CHANGED})")
    }

    /** CODEC_CHANGED{EXTRA_CODEC} → MoondropLink（详情页 hero 的「当前编码」）。 */
    private fun onCodecChanged(intent: Intent) {
        val codec = intent.getStringExtra(HyperPodsAction.EXTRA_CODEC)
        val mac = intent.getStringExtra(HyperPodsAction.EXTRA_MAC)
        if (codec.isNullOrBlank()) {
            Log.w(TAG, "CODEC_CHANGED without ${HyperPodsAction.EXTRA_CODEC} extra (mac=$mac)")
            return
        }
        Log.i(TAG, "CODEC_CHANGED codec=$codec mac=$mac")
        MoondropLink.onSystemCodecChanged(codec)
    }

    /** 请 com.android.bluetooth 里的 hook 读一次系统 A2DP 编码并回 CODEC_CHANGED。 */
    private fun requestSystemCodec(context: Context) {
        sendTo(context, "com.android.bluetooth", HyperPodsAction.UI_INIT) {
            it.putExtra(HyperPodsAction.EXTRA_MAC, connectedDevice?.address)
            it.putExtra(HyperPodsAction.EXTRA_DEVICE, connectedDevice)
        }
    }

    /**
     * 协议侧（MoondropLink）在 LHDC 切换后请求重放系统编码的入口。
     *
     * 每次调用只发一条 UI_INIT；次数由协议侧限死（MoondropLink.reprobeSystemCodec），
     * 而且蓝牙进程侧对「同一设备 + 同一编码」还会去重，因此不会刷屏该进程。
     */
    private fun reprobeSystemCodec() {
        Log.i(TAG, "codec re-probe: UI_INIT -> com.android.bluetooth")
        appContext?.let { requestSystemCodec(it) }
    }

    private fun onConnected(context: Context, mac: String?, name: String?) {
        // 只接管水月雨设备
        if (moe.chenxy.hyperpods.core.MoondropModels.match(name) == null &&
            moe.chenxy.hyperpods.core.MoondropModels.match(mac) == null
        ) {
            Log.d(TAG, "ignore non-moondrop device: $name")
            return
        }
        val device = resolveDevice(context, mac)
        if (device == null) {
            Log.w(TAG, "cannot resolve BluetoothDevice for $mac (BLUETOOTH_CONNECT 可能未授权)")
            return
        }
        connectedDevice = device
        MoondropLink.connect(device)
    }

    private fun resolveDevice(context: Context, address: String?): BluetoothDevice? {
        if (address.isNullOrEmpty()) return null
        return runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.getRemoteDevice(address)
        }.onFailure { Log.w(TAG, "getRemoteDevice($address) failed: ${it.message}") }.getOrNull()
    }

    // ── 状态变化 → 转发到系统各进程 ────────────────────────────────────────

    private val forwarder = object : PodListener {
        override fun onEvent(event: PodEvent) {
            val ctx = appContext ?: return
            when (event) {
                is PodEvent.Connected -> {
                    val snap = event.snapshot
                    if (!snap.connected) return
                    pushAnc(ctx, snap)
                    pushBattery(ctx, snap.battery)
                    pushNotification(ctx, snap)
                }
                is PodEvent.BatteryChanged -> {
                    pushBattery(ctx, event.battery)
                    MoondropLink.snapshot().takeIf { it.connected }?.let { pushNotification(ctx, it) }
                }
                is PodEvent.AncChanged ->
                    pushAnc(ctx, MoondropLink.snapshot())
                // 双设备连接(OneBringTwo)的真实开关必须送到 milink：
                // 那里是 multipoint 门的判定方，它不知道真实值就会一直按"未知"处理，
                // 而"未知"时我们会保守地回答"不是多设备主机"（见 MiLinkServiceHook 的三道 gate）。
                is PodEvent.DualConnectionChanged -> {
                    sendTo(ctx, MILINK, HyperPodsAction.DUAL_CONNECTION_CHANGED) {
                        it.putExtra(HyperPodsAction.EXTRA_ENABLED, event.on)
                        it.putExtra(HyperPodsAction.EXTRA_DEVICE, connectedDevice)
                    }
                    sendTo(ctx, "com.android.settings", HyperPodsAction.DUAL_CONNECTION_CHANGED) {
                        it.putExtra(HyperPodsAction.EXTRA_ENABLED, event.on)
                    }
                }
                is PodEvent.GestureChanged -> pushGesture(ctx, event.conf.toPayload())
                is PodEvent.Disconnected -> cancelNotification(ctx)
                else -> Unit
            }
        }
    }

    private fun pushAnc(context: Context, snap: PodSnapshot) {
        if (snap.ancIndex < 0) return
        sendTo(context, "com.android.settings", HyperPodsAction.ANC_CHANGED) {
            it.putExtra(HyperPodsAction.EXTRA_STATUS, snap.ancIndex)
        }
        // HyperOS 的「融合设备中心」耳机面板数据层在 com.milink.service，
        // 不往这里发状态，系统耳机页就永远是空的。
        sendTo(context, MILINK, HyperPodsAction.ANC_CHANGED) {
            it.putExtra(HyperPodsAction.EXTRA_STATUS, snap.ancIndex)
            it.putExtra(HyperPodsAction.EXTRA_DEVICE, connectedDevice)
            it.putExtra(HyperPodsAction.EXTRA_MAC, connectedDevice?.address)
        }
    }

    private fun pushBattery(context: Context, battery: BatterySnapshot) {
        if (!battery.anyKnown) return
        // 1) 写进系统蓝牙栈（融合设备中心 / 系统蓝牙页显示电量）
        val level = when {
            battery.leftKnown && battery.rightKnown -> minOf(battery.left, battery.right)
            battery.leftKnown -> battery.left
            battery.rightKnown -> battery.right
            else -> -1
        }
        if (level >= 0) {
            sendTo(context, "com.android.bluetooth", HyperPodsAction.UPDATE_SYSTEM_BATTERY) {
                it.putExtra(HyperPodsAction.EXTRA_LEVEL, level)
                it.putExtra(HyperPodsAction.EXTRA_DEVICE, connectedDevice)
                it.putExtra(HyperPodsAction.EXTRA_STATUS, level)
            }
        }
        // 2) 给被伪装的系统耳机页
        sendTo(context, "com.android.settings", HyperPodsAction.BATTERY_CHANGED) {
            it.putExtra(HyperPodsAction.EXTRA_BATTERY, BatteryCodecWire.toBundle(battery))
        }
        // 3) 给小米蓝牙进程（通知 / 电量展示）
        sendTo(context, "com.xiaomi.bluetooth", HyperPodsAction.UPDATE_PODS_NOTIFICATION) {
            it.putExtra(HyperPodsAction.EXTRA_BATTERY, BatteryCodecWire.toBundle(battery))
            it.putExtra(HyperPodsAction.EXTRA_DEVICE, connectedDevice)
        }
        // 4) 给 com.milink.service —— HyperOS 融合设备中心耳机面板的数据层
        sendTo(context, MILINK, HyperPodsAction.BATTERY_CHANGED) {
            it.putExtra(HyperPodsAction.EXTRA_BATTERY, BatteryCodecWire.toBundle(battery))
            it.putExtra(HyperPodsAction.EXTRA_LEVEL, level)
            it.putExtra(HyperPodsAction.EXTRA_DEVICE, connectedDevice)
            it.putExtra(HyperPodsAction.EXTRA_MAC, connectedDevice?.address)
        }
    }

    private fun pushNotification(context: Context, snap: PodSnapshot) {
        if (!snap.battery.anyKnown) return
        sendTo(context, "com.xiaomi.bluetooth", HyperPodsAction.SEND_STRONG_TOAST) {
            it.putExtra(HyperPodsAction.EXTRA_BATTERY, BatteryCodecWire.toBundle(snap.battery))
            it.putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, snap.deviceName)
            it.putExtra(HyperPodsAction.EXTRA_DEVICE, connectedDevice)
        }
    }

    /**
     * 把手势配置推给系统设置页（hook 侧用它渲染「手势控制」卡片）。
     * 没有配置就**不发** —— 原生页在收到配置前不显示手势值，而不是回落成厂商默认值。
     */
    private fun pushGesture(context: Context, payload: ByteArray? = null) {
        val bytes = payload ?: MoondropLink.snapshot().gestureConf?.toPayload() ?: return
        sendTo(context, "com.android.settings", HyperPodsAction.GESTURE_CHANGED) {
            it.putExtra(HyperPodsAction.EXTRA_GESTURE_PAYLOAD, bytes)
        }
    }

    private fun cancelNotification(context: Context) {
        sendTo(context, "com.xiaomi.bluetooth", HyperPodsAction.CANCEL_PODS_NOTIFICATION) {
            it.putExtra(HyperPodsAction.EXTRA_DEVICE, connectedDevice)
        }
    }

    /** 显式广播（必须 setPackage：Android 14+ 会丢弃未指定包名的隐式广播）。 */
    private inline fun sendTo(
        context: Context,
        pkg: String,
        action: String,
        configure: (Intent) -> Unit = {},
    ) {
        runCatching {
            val i = Intent(action).setPackage(pkg)
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            configure(i)
            context.sendBroadcast(i)
        }.onFailure { Log.w(TAG, "sendTo $pkg/$action failed: ${it.message}") }
    }

    /** 本模块自身也订阅这些动作时用得到 */
    val CONTROL_ACTIONS = arrayOf(
        HyperPodsAction.PODS_CONNECTED,
        HyperPodsAction.PODS_DISCONNECTED,
        HyperPodsAction.UI_INIT,
        HyperPodsAction.REQUEST_BATTERY,
        HyperPodsAction.REQUEST_CAPABILITIES,
        HyperPodsAction.ANC_SELECT,
        HyperPodsAction.GAIN_SELECT,
        HyperPodsAction.LED_SELECT,
        HyperPodsAction.PROMPT_TONE_SELECT,
        HyperPodsAction.PROMPT_VOLUME_SELECT,
        HyperPodsAction.LHDC_SELECT,
        HyperPodsAction.DUAL_CONNECTION_SELECT,
        HyperPodsAction.LOW_LATENCY_SELECT,
        HyperPodsAction.CODEC_CHANGED,
    )

    val APP_ID: String = BuildConfig.APPLICATION_ID
}

/**
 * 由 manifest 声明的接收器：系统用显式广播即可把本应用进程拉起来处理控制命令，
 * 因此「系统设置页点降噪」不依赖用户先打开本应用。
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            ControlBridge.ensureInit(context)
            ControlBridge.handle(context, intent)
        }.onFailure { Log.w(TAG, "ControlReceiver failed for ${intent.action}", it) }
    }
}
