/*
 * HyperPods for Moondrop — com.android.bluetooth 进程：连接感知 + 电量写回蓝牙栈 + MAC 应答
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 本模块的协议客户端跑在**应用进程**，所以蓝牙进程只做三件事：
 *   1) 感知连接：hook com.android.bluetooth.a2dp.A2dpService#handleConnectionStateChanged(int,int,int)
 *      （按参数个数 3 定位），设备名经 core.MoondropModels.match() 命中才处理；
 *   2) UI/系统集成：连接时显示状态栏 "wireless_headset" 图标，并向应用进程广播
 *      PODS_CONNECTED（EXTRA_DEVICE_NAME / EXTRA_MAC）、断开广播 PODS_DISCONNECTED；
 *   3) 写回系统：接收应用进程的 UPDATE_SYSTEM_BATTERY（EXTRA_LEVEL），
 *      反射取 A2dpService 的 mAdapterService 调 AdapterService#setBatteryLevel(device, level, false)
 *      （与 HyperPods `L2CAPController.setRegularBatteryLevel` 完全一致）。
 *   4) 附带的 MAC 握手应答：SystemUI 的 DeviceCardHook 广播 GET_PODS_MAC 到本进程，
 *      本进程回 PODS_MAC_RECEIVED + EXTRA_MAC 到 com.android.systemui（两者 action 字符串相同，
 *      靠 setPackage 的方向区分，见 utils/data/HyperPodsAction.kt 的注释）。
 *   5) 编码上报：读**系统实际协商出来的** A2DP 编码（A2dpService/BluetoothA2dp 反射，兜底 hook
 *      A2dpService#onCodecConfigChangedFromNative 与 A2dpStateMachine#processCodecConfigEvent），
 *      以 CODEC_CHANGED + EXTRA_CODEC 广播给应用进程 → 详情页「当前编码」。
 *
 * 迟装兜底（bootstrap）：模块可能在耳机已连接之后才被安装/启用，此时
 * handleConnectionStateChanged 不会再触发，因此 hook 服务 onCreate 后延迟 ~1.5s
 * 扫描已连接的 A2DP/HFP 设备并补发一次连接事件（参考 OppoPods HeadsetStateDispatcher）。
 */
package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

@SuppressLint("MissingPermission")
object HeadsetStateDispatcher : HookContext() {
    private const val TAG = "HyperPods-Bluetooth"
    private const val CLS_A2DP_SERVICE = "com.android.bluetooth.a2dp.A2dpService"
    private const val CLS_PROFILE_SERVICE = "com.android.bluetooth.btservice.ProfileService"
    private const val PKG_APP = BuildConfig.APPLICATION_ID
    private const val PKG_SYSTEMUI = "com.android.systemui"
    private const val STATUS_BAR_ICON = "wireless_headset"
    private const val BOOTSTRAP_DELAY_MS = 1_500L
    private const val CLS_A2DP_STATE_MACHINE = "com.android.bluetooth.a2dp.A2dpStateMachine"

    // ── A2DP 编解码（系统真实编码，详情页「当前编码」） ─────────────────────
    // 以下名称全部先在真机 ROM 的 dex 里核对过（_refs/_device/apks/com.android.bluetooth.apk，
    // Android 17 / HyperOS 4.0，用 tools/dex_strings.py + 自写的 method_ids/field_ids 扫描）：
    //   A2dpService.getCodecStatus(BluetoothDevice) → BluetoothCodecStatus
    //   A2dpService.onCodecConfigChangedFromNative(BluetoothDevice, BluetoothCodecConfig) → void
    //   A2dpService.codecConfigUpdated(BluetoothDevice, BluetoothCodecConfig, boolean) → void
    //   A2dpService.mActiveDevice : BluetoothDevice
    //   A2dpStateMachine.processCodecConfigEvent(BluetoothCodecConfig) → void
    //   A2dpStateMachine.mCodecStatus : BluetoothCodecStatus / mDevice : BluetoothDevice /
    //                                 mA2dpService : A2dpService
    //   BluetoothCodecStatus.getCodecConfig() → BluetoothCodecConfig
    //   BluetoothCodecConfig.getCodecName() → String（getCodecType() → int）
    /** A2dpService 上 native 侧编码协商结果回调（带设备 + 新配置）。 */
    private const val METHOD_CODEC_CHANGED_NATIVE = "onCodecConfigChangedFromNative"
    /** A2dpService 上状态机回写编码配置的方法（同 dex 核对，形态 (设备, 新配置, boolean)）。 */
    private const val METHOD_CODEC_CONFIG_UPDATED = "codecConfigUpdated"
    /** A2dpStateMachine 的编码变更事件处理。 */
    private const val METHOD_PROCESS_CODEC_CONFIG_EVENT = "processCodecConfigEvent"
    private const val FIELD_CODEC_STATUS = "mCodecStatus"
    private const val FIELD_STATE_MACHINE_DEVICE = "mDevice"
    private const val FIELD_STATE_MACHINE_SERVICE = "mA2dpService"
    private const val FIELD_ACTIVE_DEVICE = "mActiveDevice"
    /** 连接后编解码协商要等一会儿才出结果：延迟第一次查询，再补一次（不轮询）。 */
    private const val CODEC_QUERY_DELAY_MS = 1_200L
    private const val CODEC_QUERY_RETRY_MS = 2_500L
    private const val CODEC_QUERY_ATTEMPTS = 2

    /** BluetoothCodecConfig 上的编码名字段（AOSP 的私有字段名；取不到名字时才会用到）。 */
    private val CODEC_NAME_FIELDS = arrayOf("mCodecName", "codecName")

    // ── 低延迟（HyperOS 系统侧）候选 codec ─────────────────────────────────────
    // 数值取自 BluetoothCodecConfig 的公开/系统常量本身；这里写字面量是为了不在编译期
    // 引用框架隐藏 API（@SystemApi 成员不在公开 SDK 里）。
    private const val CODEC_TYPE_SBC = 0
    private const val CODEC_TYPE_AAC = 1
    private const val CODEC_TYPE_LDAC = 4
    private const val CODEC_TYPE_APTX_ADAPTIVE = 7
    private const val CODEC_TYPE_LHDC = 8
    private const val CODEC_TYPE_LC3 = 10

    /** BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST（隐藏常量，数值固定为 0）。 */
    private const val CODEC_PRIORITY_HIGHEST = 0

    /** 低延迟候选 codec 优先级：LHDC > LDAC > aptX Adaptive > LC3 > AAC。 */
    private val LOW_LATENCY_CODEC_TYPES =
        intArrayOf(CODEC_TYPE_LHDC, CODEC_TYPE_LDAC, CODEC_TYPE_APTX_ADAPTIVE, CODEC_TYPE_LC3, CODEC_TYPE_AAC)

    /** 当前追踪的水月雨耳机（供 MAC 握手与断开判定）。 */
    @Volatile
    private var activeMac: String = ""

    @Volatile
    private var activeName: String = ""

    /** hook 到的 A2dpService 实例（Context + mAdapterService 的宿主）。 */
    @Volatile
    private var a2dpService: Any? = null

    private var appContext: Context? = null
    private var requestReceiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null

    @Volatile
    private var receiverRegistered = false

    /** 低延迟开关最近一次已知状态：隐藏 API 不可用时用它回一条「保持原状态」的广播。 */
    @Volatile
    private var lastLowLatencyEnabled = false

    /** A2DP profile 代理回调（异步两段式：拿到 proxy 后立即置空并 closeProfileProxy）。 */
    @Volatile
    private var a2dpProxyListener: Any? = null

    /** 打开低延迟前的 codec 配置，关闭时用于恢复（仅本进程生命周期内有效）。 */
    @Volatile
    private var lowLatencyPreviousConfig: Any? = null

    /** 最近一次上报给应用进程的编码名 + 设备（去重：hook 反复触发时不刷广播）。 */
    @Volatile
    private var lastReportedCodec: String = ""

    @Volatile
    private var lastReportedCodecMac: String = ""

    /** 读编码用的 A2DP 代理回调：与低延迟开关分开存，互不覆盖。 */
    @Volatile
    private var codecProxyListener: Any? = null

    private var codecHandler: Handler? = null
    private var codecRunnable: Runnable? = null

    private var bootstrapHandler: Handler? = null
    private var bootstrapRunnable: Runnable? = null

    override fun onHook() {
        hookConnectionStateChanged()
        hookServiceCreateForBootstrap()
        hookCodecConfigChanged()
    }

    override fun onHotReloading() {
        bootstrapRunnable?.let { runnable -> bootstrapHandler?.removeCallbacks(runnable) }
        bootstrapRunnable = null
        bootstrapHandler = null
        runCatching { requestReceiver?.let { receiver -> receiverContext?.unregisterReceiver(receiver) } }
            .onFailure { Log.w(TAG, "unregister request receiver failed", it) }
        requestReceiver = null
        receiverContext = null
        receiverRegistered = false
        a2dpProxyListener = null
        lowLatencyPreviousConfig = null
        codecRunnable?.let { runnable -> codecHandler?.removeCallbacks(runnable) }
        codecRunnable = null
        codecHandler = null
        codecProxyListener = null
        lastReportedCodec = ""
        lastReportedCodecMac = ""
        a2dpService = null
        appContext = null
        activeMac = ""
        activeName = ""
    }

    // ── 1) 连接状态 ────────────────────────────────────────────────────────────

    private fun hookConnectionStateChanged() {
        runCatching {
            val method = findMethodByParamCount(CLS_A2DP_SERVICE, "handleConnectionStateChanged", 3)
            hookAfter(method) {
                val service = instance
                if (service != null) a2dpService = service
                val device = args.getOrNull(0) as? BluetoothDevice
                val fromState = (args.getOrNull(1) as? Int) ?: -1
                val currState = (args.getOrNull(2) as? Int) ?: -1
                if (device == null || currState == fromState) return@hookAfter
                runCatching { dispatchConnectionState(service, device, currState) }
                    .onFailure { Log.w(TAG, "dispatchConnectionState failed", it) }
            }
            Log.d(TAG, "hooked $CLS_A2DP_SERVICE#handleConnectionStateChanged(int,int,int)")
        }.onFailure { Log.w(TAG, "hook $CLS_A2DP_SERVICE.handleConnectionStateChanged skipped", it) }
    }

    private fun dispatchConnectionState(service: Any?, device: BluetoothDevice, currState: Int) {
        val context = service as? Context ?: appContext ?: run {
            Log.w(TAG, "no Context for A2DP state handling")
            return
        }
        appContext = context.applicationContext ?: context
        registerRequestReceiver(context)

        val name = SystemApisUtils.deviceName(device)
        val address = SystemApisUtils.deviceAddress(device)
        val isPods = MoondropModels.match(name) != null
        Log.d(TAG, "A2DP state=$currState name=$name address=$address isMoondrop=$isPods")

        val disconnected = currState == BluetoothHeadset.STATE_DISCONNECTED ||
            currState == BluetoothHeadset.STATE_DISCONNECTING

        if (!isPods) {
            // 非水月雨设备一律不碰；但若是主动追踪的设备断开（改名/改名失败场景），也补一次断开事件。
            if (disconnected && address.isNotEmpty() && address == activeMac) {
                activeMac = ""
                activeName = ""
                dispatchDisconnected(device, name)
            }
            return
        }

        if (currState == BluetoothHeadset.STATE_CONNECTED) {
            activeMac = address
            activeName = name
            SystemApisUtils.setIconVisibility(SystemApisUtils.statusBarManager(context), STATUS_BAR_ICON, true)
            dispatchConnected(device, name)
            // 编码协商在连接完成后才出结果：延迟查询 + 补一次（UI 不必等下一次编码切换）
            scheduleCodecReport(device)
        } else if (disconnected) {
            if (address.isNotEmpty() && address == activeMac) {
                activeMac = ""
                activeName = ""
            }
            SystemApisUtils.setIconVisibility(SystemApisUtils.statusBarManager(context), STATUS_BAR_ICON, false)
            dispatchDisconnected(device, name)
        }
    }

    private fun dispatchConnected(device: BluetoothDevice, name: String) {
        val context = appContext ?: return
        val intent = Intent(HyperPodsAction.PODS_CONNECTED).apply {
            setPackage(PKG_APP)
            putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, name)
            putExtra(HyperPodsAction.EXTRA_MAC, SystemApisUtils.deviceAddress(device))
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "PODS_CONNECTED broadcast failed", it) }
        Log.i(TAG, "PODS_CONNECTED name=$name mac=${SystemApisUtils.deviceAddress(device)}")
    }

    private fun dispatchDisconnected(device: BluetoothDevice, name: String) {
        // 断开即清掉编码去重记录：下次连接（哪怕编码没变）也重报一次，UI 不必等编码切换。
        lastReportedCodec = ""
        lastReportedCodecMac = ""
        val context = appContext ?: return
        val intent = Intent(HyperPodsAction.PODS_DISCONNECTED).apply {
            setPackage(PKG_APP)
            putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, name)
            putExtra(HyperPodsAction.EXTRA_MAC, SystemApisUtils.deviceAddress(device))
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "PODS_DISCONNECTED broadcast failed", it) }
        Log.i(TAG, "PODS_DISCONNECTED name=$name mac=${SystemApisUtils.deviceAddress(device)}")
    }

    // ── 2) 应用进程 -> 蓝牙栈：MAC 握手应答 + 电量写回 ──────────────────────────

    @Synchronized
    private fun registerRequestReceiver(context: Context) {
        if (receiverRegistered) return
        val appCtx = context.applicationContext ?: context
        val filter = IntentFilter().apply {
            addAction(HyperPodsAction.GET_PODS_MAC)
            addAction(HyperPodsAction.UPDATE_SYSTEM_BATTERY)
            addAction(HyperPodsAction.LOW_LATENCY_SELECT)
            // 应用进程 UI 打开时发来的重放请求：其中一项是「重放系统真实编码」。
            addAction(HyperPodsAction.UI_INIT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val received = intent ?: return
                when (received.action) {
                    // 注意：GET_PODS_MAC 与 PODS_MAC_RECEIVED 是同一个 action 字符串，
                    // 靠 setPackage 的方向区分（请求发到本进程，应答发到 com.android.systemui）。
                    HyperPodsAction.GET_PODS_MAC -> replyMac(ctx ?: appCtx)
                    HyperPodsAction.UPDATE_SYSTEM_BATTERY -> runCatching { applySystemBattery(received) }
                        .onFailure { Log.w(TAG, "applySystemBattery failed", it) }
                    HyperPodsAction.LOW_LATENCY_SELECT -> runCatching { handleLowLatencySelect(received) }
                        .onFailure { Log.w(TAG, "handleLowLatencySelect failed", it) }
                    HyperPodsAction.UI_INIT -> runCatching { reportCurrentCodec() }
                        .onFailure { Log.w(TAG, "codec report on UI_INIT failed", it) }
                }
            }
        }
        // 发送方是 SystemUI / 模块应用进程（不同应用），必须 RECEIVER_EXPORTED。
        val registered = runCatching { appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "register request receiver skipped", it) }
            .isSuccess
        if (!registered) return
        requestReceiver = receiver
        receiverContext = appCtx
        receiverRegistered = true
        Log.d(TAG, "request receiver registered (GET_PODS_MAC / UPDATE_SYSTEM_BATTERY / LOW_LATENCY_SELECT / UI_INIT)")
    }

    private fun replyMac(context: Context) {
        val mac = activeMac
        Log.d(TAG, "GET_PODS_MAC -> reply mac=$mac name=$activeName")
        runCatching {
            context.sendBroadcast(Intent(HyperPodsAction.PODS_MAC_RECEIVED).apply {
                setPackage(PKG_SYSTEMUI)
                putExtra(HyperPodsAction.EXTRA_MAC, mac)
                putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, activeName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure { Log.w(TAG, "MAC reply failed", it) }
    }

    private fun applySystemBattery(intent: Intent) {
        val level = intent.getIntExtra(HyperPodsAction.EXTRA_LEVEL, SystemApisUtils.BATTERY_LEVEL_UNKNOWN)
        if (level !in 0..100) {
            Log.w(TAG, "UPDATE_SYSTEM_BATTERY with invalid level=$level")
            return
        }
        val device = SystemApisUtils.parcelableDevice(intent, HyperPodsAction.EXTRA_DEVICE)
            ?: connectedMoondropDevice()
        if (device == null) {
            Log.w(TAG, "UPDATE_SYSTEM_BATTERY level=$level but no connected Moondrop device")
            return
        }
        // HyperPods 参考实现：getObjectField(mContext /*=A2dpService 本身*/, "mAdapterService")
        // 再 callMethod(service, "setBatteryLevel", device, level, false)
        val adapterService = runCatching { getObjectField(a2dpService, "mAdapterService") }.getOrNull()
            ?: runCatching { getObjectField(appContext, "mAdapterService") }.getOrNull()
        if (adapterService == null) {
            Log.w(TAG, "mAdapterService unavailable; battery level=$level dropped")
            return
        }
        runCatching { callMethod(adapterService, "setBatteryLevel", device, level, false) }
            .onFailure { Log.w(TAG, "AdapterService.setBatteryLevel failed", it) }
        Log.i(TAG, "system battery level=$level -> ${SystemApisUtils.deviceAddress(device)}")
    }

    // ── 3) 迟装兜底：模块在耳机已连接后才生效 ───────────────────────────────────

    private fun hookServiceCreateForBootstrap() {
        runCatching {
            val method = findMethodByParamCountOrNull(CLS_A2DP_SERVICE, "onCreate", 0)
                // AOSP/HyperOS 常把 Service.onCreate 声明在 ProfileService 而不是各 profile 实现里。
                ?: findMethodByParamCount(CLS_PROFILE_SERVICE, "onCreate", 0)
            hookAfter(method) {
                val service = instance
                if (service != null) a2dpService = service
                val context = service as? Context ?: return@hookAfter
                appContext = context.applicationContext ?: context
                registerRequestReceiver(context)
                scheduleConnectedDeviceBootstrap(context)
            }
            Log.d(TAG, "hooked ${method.declaringClass.name}#onCreate for connected-device bootstrap")
        }.onFailure { Log.w(TAG, "hook service onCreate for bootstrap skipped", it) }
    }

    private fun scheduleConnectedDeviceBootstrap(context: Context) {
        bootstrapRunnable?.let { runnable -> bootstrapHandler?.removeCallbacks(runnable) }
        val handler = runCatching { Handler(context.mainLooper) }.getOrElse { Handler(Looper.getMainLooper()) }
        val runnable = Runnable {
            runCatching { bootstrapConnectedDevice(context) }
                .onFailure { Log.w(TAG, "connected-device bootstrap failed", it) }
        }
        bootstrapHandler = handler
        bootstrapRunnable = runnable
        handler.postDelayed(runnable, BOOTSTRAP_DELAY_MS)
        Log.d(TAG, "connected-device bootstrap scheduled in ${BOOTSTRAP_DELAY_MS}ms")
    }

    private fun bootstrapConnectedDevice(context: Context) {
        registerRequestReceiver(context)
        val device = connectedMoondropDevice() ?: run {
            Log.d(TAG, "bootstrap: no connected Moondrop device")
            return
        }
        activeMac = SystemApisUtils.deviceAddress(device)
        activeName = SystemApisUtils.deviceName(device)
        Log.i(TAG, "bootstrap: found ${activeName} / ${activeMac} -> dispatch connected")
        SystemApisUtils.setIconVisibility(SystemApisUtils.statusBarManager(context), STATUS_BAR_ICON, true)
        dispatchConnected(device, activeName)
        scheduleCodecReport(device)
    }

    private fun connectedMoondropDevice(): BluetoothDevice? {
        val context = appContext ?: return null
        val manager = runCatching {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        }.getOrNull() ?: return null
        return listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
            .asSequence()
            .flatMap { profile ->
                runCatching { manager.getConnectedDevices(profile).orEmpty().asSequence() }
                    .getOrElse { emptySequence() }
            }
            .distinctBy { SystemApisUtils.deviceAddress(it) }
            .firstOrNull { MoondropModels.match(SystemApisUtils.deviceName(it)) != null }
    }

    // ── 4) 低延迟开关（HyperOS 系统侧功能，不是 GAIA 命令） ─────────────────────
    //
    // ui/MainUI.kt 广播 LOW_LATENCY_SELECT{EXTRA_ENABLED}，这里 best-effort 打通：
    //   1) 先试厂商可能存在的直通方法：setLowLatencyMode / setLowLatencyAudioEnabled /
    //      setLatencyMode / enableLowLatency（全部反射，命中即返回）；
    //   2) 再退化为 A2DP codec 选择：getCodecStatus(device) 读当前 codec，从
    //      getCodecsSelectableCapabilities() 里按 LOW_LATENCY_CODEC_TYPES 挑候选，
    //      用 setCodecConfigPreference(device, config) 下发；关闭时恢复原 codec 配置。
    //   A2DP 代理通过 BluetoothAdapter#getProfileProxy（反射）+ 公开接口
    //   BluetoothProfile.ServiceListener 异步获取，拿到后立即 closeProfileProxy。
    //   ⚠ **未在真机验证**：HyperOS 是否暴露这些隐藏 API、以及「低延迟」究竟对应哪个
    //     codec/latency 位，需要在 Xiaomi Pad 8 Pro 上按日志校准。
    //   任何一步不可用 -> 回一条 LOW_LATENCY_CHANGED{EXTRA_ENABLED = 保持原状态} 并记日志；
    //   不重试、不轮询、不崩溃。

    private fun handleLowLatencySelect(intent: Intent) {
        val enabled = intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)
        val context = appContext
        val device = SystemApisUtils.parcelableDevice(intent, HyperPodsAction.EXTRA_DEVICE)
            ?: connectedMoondropDevice()
        if (context == null || device == null) {
            Log.w(TAG, "LOW_LATENCY_SELECT enabled=$enabled but context=$context device=$device")
            replyLowLatency(lastLowLatencyEnabled, device, "no context/device")
            return
        }
        val adapter = bluetoothAdapter(context)
        if (adapter == null) {
            Log.w(TAG, "LOW_LATENCY_SELECT: BluetoothAdapter unavailable")
            replyLowLatency(lastLowLatencyEnabled, device, "no adapter")
            return
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                a2dpProxyListener = null
                if (profile != BluetoothProfile.A2DP || proxy == null) return
                var applied = false
                runCatching { applyLowLatency(proxy, device, enabled) }
                    .onSuccess { applied = it }
                    .onFailure { Log.w(TAG, "applyLowLatency failed", it) }
                if (applied) lastLowLatencyEnabled = enabled
                replyLowLatency(
                    if (applied) enabled else lastLowLatencyEnabled,
                    device,
                    if (applied) "applied" else "unsupported by this system"
                )
                runCatching { callMethod(adapter, "closeProfileProxy", BluetoothProfile.A2DP, proxy) }
                    .onFailure { Log.d(TAG, "closeProfileProxy unavailable", it) }
            }

            override fun onServiceDisconnected(profile: Int) {
                a2dpProxyListener = null
                if (profile == BluetoothProfile.A2DP) {
                    Log.w(TAG, "A2DP proxy disconnected during low latency switch")
                }
            }
        }
        a2dpProxyListener = listener
        val requested = runCatching {
            callMethod(adapter, "getProfileProxy", context, listener, BluetoothProfile.A2DP) as? Boolean
        }.getOrNull()
        if (requested != true) {
            a2dpProxyListener = null
            Log.w(TAG, "A2DP getProfileProxy unavailable: this system does not expose the low latency switch")
            replyLowLatency(lastLowLatencyEnabled, device, "getProfileProxy unavailable")
        }
    }

    private fun applyLowLatency(proxy: Any, device: BluetoothDevice, enabled: Boolean): Boolean {
        val address = SystemApisUtils.deviceAddress(device)
        val directMethods = if (enabled) {
            listOf("setLowLatencyMode", "setLowLatencyAudioEnabled", "setLatencyMode", "enableLowLatency")
        } else {
            listOf("setLowLatencyMode", "setLowLatencyAudioEnabled", "setLatencyMode", "disableLowLatency")
        }
        for (name in directMethods) {
            if (runCatching { callMethod(proxy, name, device, enabled) }.isSuccess) {
                Log.i(TAG, "low latency: $name($address, $enabled) accepted by the system")
                return true
            }
        }

        val status = runCatching { callMethod(proxy, "getCodecStatus", device) }.getOrNull()
        if (status == null) {
            Log.w(TAG, "low latency: getCodecStatus(device) unavailable")
            return false
        }
        val current = runCatching { callMethod(status, "getCodecConfig") }.getOrNull()
        val currentType = codecType(current)
        Log.i(TAG, "low latency: device=$address requested=$enabled currentCodecType=$currentType config=$current")

        if (!enabled) {
            val previous = lowLatencyPreviousConfig
            if (previous == null) {
                Log.i(TAG, "low latency: nothing to restore (we never changed the codec)")
                return true
            }
            val restored = runCatching { callMethod(proxy, "setCodecConfigPreference", device, previous) }.isSuccess
            if (restored) lowLatencyPreviousConfig = null
            Log.i(TAG, "low latency: restore previous codec config applied=$restored")
            return restored
        }

        val selectable = selectableCodecs(status)
        val candidates = selectable.mapNotNull { config -> codecType(config)?.let { type -> type to config } }
        if (candidates.isEmpty()) {
            Log.w(TAG, "low latency: the system reported no selectable codec")
            return false
        }
        // 用 toList() 而不是直接对 IntArray 调 firstNotNullOfOrNull：避免依赖
        // 基本类型数组上该扩展是否存在（Iterable 版本一定有）。
        val preferred = LOW_LATENCY_CODEC_TYPES.toList()
            .firstNotNullOfOrNull { wanted -> candidates.firstOrNull { it.first == wanted }?.second }
            ?: candidates.firstOrNull { it.first != CODEC_TYPE_SBC }?.second
        if (preferred == null) {
            Log.w(TAG, "low latency: only SBC is selectable ($candidates)")
            return false
        }
        val preferredType = codecType(preferred)
        if (preferredType == currentType) {
            Log.i(TAG, "low latency: already on the preferred codec type=$preferredType")
            return true
        }
        if (current != null && lowLatencyPreviousConfig == null) lowLatencyPreviousConfig = current
        runCatching { callMethod(preferred, "setCodecPriority", CODEC_PRIORITY_HIGHEST) }
            .onFailure { Log.d(TAG, "setCodecPriority unavailable", it) }
        val applied = runCatching { callMethod(proxy, "setCodecConfigPreference", device, preferred) }.isSuccess
        Log.i(TAG, "low latency: codec $currentType -> $preferredType applied=$applied")
        return applied
    }

    private fun selectableCodecs(status: Any): List<Any> {
        for (name in listOf("getCodecsSelectableCapabilities", "getCodecsLocalCapabilities", "getCodecsCapabilities")) {
            val list = runCatching { callMethod(status, name) }.getOrNull() as? List<*> ?: continue
            val typed = list.filterNotNull()
            if (typed.isNotEmpty()) {
                Log.d(TAG, "low latency: codec list from $name size=${typed.size}")
                return typed
            }
        }
        return emptyList()
    }

    private fun codecType(config: Any?): Int? =
        runCatching { callMethod(config, "getCodecType") as? Int }.getOrNull()

    private fun bluetoothAdapter(context: Context): Any? = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter ?: callMethod(manager, "getAdapter")
    }.getOrNull()

    private fun replyLowLatency(enabled: Boolean, device: BluetoothDevice?, reason: String) {
        val context = appContext ?: return
        runCatching {
            context.sendBroadcast(Intent(HyperPodsAction.LOW_LATENCY_CHANGED).apply {
                setPackage(PKG_APP)
                putExtra(HyperPodsAction.EXTRA_ENABLED, enabled)
                if (device != null) {
                    putExtra(HyperPodsAction.EXTRA_MAC, SystemApisUtils.deviceAddress(device))
                    putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, SystemApisUtils.deviceName(device))
                }
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.i(TAG, "LOW_LATENCY_CHANGED enabled=$enabled reason=$reason")
        }.onFailure { Log.w(TAG, "LOW_LATENCY_CHANGED broadcast failed", it) }
    }

    // ── 5) A2DP 编解码：把系统真实编码（SBC/AAC/LDAC/LHDC…）报给应用进程 ────────
    //
    // 详情页 hero 的「当前编码」必须是**系统实际协商出来的**编码。应用进程读不到：
    // BluetoothCodecStatus/BluetoothCodecConfig 是隐藏 API，且 A2dpService 要求
    // BLUETOOTH_PRIVILEGED；本进程（com.android.bluetooth）有权限，所以在这里读完再广播。
    //
    // 读取路径（全部反射 + runCatching，任何一步不可用只记日志，绝不崩）：
    //   A) 直接问 hook 到的 A2dpService 实例：getCodecStatus(device) → BluetoothCodecStatus
    //   B) BluetoothAdapter#getProfileProxy(A2DP) 拿 BluetoothA2dp 代理（与本文件低延迟开关
    //      完全同一套路）→ proxy.getCodecStatus(device) → BluetoothCodecStatus
    //   拿到状态后统一：status.getCodecConfig() → config.getCodecName()
    //   C) 兜底 hook（编码真正切换时触发；A/B 都被 ROM 挡住时仍然能上报）：
    //      · A2dpService#onCodecConfigChangedFromNative(BluetoothDevice, BluetoothCodecConfig)
    //      · A2dpStateMachine#processCodecConfigEvent(BluetoothCodecConfig) + mCodecStatus 字段
    //
    // ⚠ 刻意**不做 codecType 数值 → 名字的硬映射**：本 ROM 解出 A2dpService.SOURCE_CODEC_TYPE_
    //   APTX_ADAPTIVE = 10（旧代码里的字面量 7 是错的），数值不可靠。能拿到名字就用名字，
    //   拿不到名字只记日志 —— 宁可继续显示「未知」，也不显示错的编码。

    /** 注册「编码变了」兜底 hook（方法与字段名均已对照真机 dex 核对）。 */
    private fun hookCodecConfigChanged() {
        // A2dpService：native 协商结果 + 状态机回写，两条都是「(设备, 新编码配置, …)」形态。
        // 只从 dex 无法判定本 ROM 在切换编码时究竟走哪一条，所以两条都挂；
        // 重复上报由 lastReportedCodec 去重挡掉，不会多刷广播。
        hookServiceCodecCallback(METHOD_CODEC_CHANGED_NATIVE, 2)
        hookServiceCodecCallback(METHOD_CODEC_CONFIG_UPDATED, 3)

        // A2dpStateMachine：每台设备一个状态机，mCodecStatus 是它维护的权威状态
        runCatching {
            val method = findMethodByParamCountOrNull(CLS_A2DP_STATE_MACHINE, METHOD_PROCESS_CODEC_CONFIG_EVENT, 1)
                ?: findAnyMethod(CLS_A2DP_STATE_MACHINE, METHOD_PROCESS_CODEC_CONFIG_EVENT, 1)
            hookAfter(method) {
                runCatching {
                    val machine = instance
                    val service =
                        runCatching { getObjectField(machine, FIELD_STATE_MACHINE_SERVICE) }.getOrNull()
                    if (service != null) a2dpService = service
                    val device = runCatching {
                        getObjectField(machine, FIELD_STATE_MACHINE_DEVICE) as? BluetoothDevice
                    }.getOrNull()
                    val status = runCatching { getObjectField(machine, FIELD_CODEC_STATUS) }.getOrNull()
                    val config = codecConfigOf(status) ?: args.getOrNull(0)
                    reportCodecFromConfig(config, device, "A2dpStateMachine#$METHOD_PROCESS_CODEC_CONFIG_EVENT")
                }.onFailure { Log.w(TAG, "codec hook $METHOD_PROCESS_CODEC_CONFIG_EVENT failed", it) }
            }
            Log.d(TAG, "hooked $CLS_A2DP_STATE_MACHINE#$METHOD_PROCESS_CODEC_CONFIG_EVENT")
        }.onFailure { Log.w(TAG, "hook $METHOD_PROCESS_CODEC_CONFIG_EVENT skipped", it) }
    }

    /**
     * 在 A2dpService 上挂一个「(设备, 新编码配置, …)」形态的回调：
     * [paramCount] 是参数个数（onCodecConfigChangedFromNative=2、codecConfigUpdated=3），
     * 新配置固定取 args[1]、设备固定取 args[0]（两条回调的参数顺序一致，短的 shorty 已核对）。
     */
    private fun hookServiceCodecCallback(methodName: String, paramCount: Int) {
        runCatching {
            val method = findMethodByParamCountOrNull(CLS_A2DP_SERVICE, methodName, paramCount)
                ?: findAnyMethod(CLS_A2DP_SERVICE, methodName, paramCount)
            hookAfter(method) {
                runCatching {
                    val service = instance
                    if (service != null) a2dpService = service
                    reportCodecFromConfig(
                        args.getOrNull(1),
                        args.getOrNull(0) as? BluetoothDevice,
                        "A2dpService#$methodName"
                    )
                }.onFailure { Log.w(TAG, "codec hook $methodName failed", it) }
            }
            Log.d(TAG, "hooked $CLS_A2DP_SERVICE#$methodName/$paramCount")
        }.onFailure { Log.w(TAG, "hook $methodName/$paramCount skipped", it) }
    }

    /** 连接建立后延迟查询编码（协商要时间），最多 CODEC_QUERY_ATTEMPTS 次。 */
    private fun scheduleCodecReport(device: BluetoothDevice) {
        val context = appContext ?: return
        val handler = runCatching { Handler(context.mainLooper) }.getOrElse { Handler(Looper.getMainLooper()) }
        codecRunnable?.let { runnable -> codecHandler?.removeCallbacks(runnable) }
        val runnable = object : Runnable {
            private var attempt = 0

            override fun run() {
                attempt++
                runCatching { reportCurrentCodec(device) }
                    .onFailure { Log.w(TAG, "codec query failed", it) }
                if (attempt < CODEC_QUERY_ATTEMPTS) handler.postDelayed(this, CODEC_QUERY_RETRY_MS)
            }
        }
        codecHandler = handler
        codecRunnable = runnable
        handler.postDelayed(runnable, CODEC_QUERY_DELAY_MS)
        Log.d(TAG, "codec query scheduled for ${SystemApisUtils.deviceAddress(device)}")
    }

    /** 查询当前编码：先直接问 A2dpService 实例，不可用再退化为 A2DP 代理。 */
    private fun reportCurrentCodec(preferred: BluetoothDevice? = null) {
        val device = preferred ?: connectedMoondropDevice() ?: activeDeviceFromService()
        if (device == null) {
            Log.d(TAG, "codec query: no connected device")
            return
        }
        val status = runCatching { callMethod(a2dpService, "getCodecStatus", device) }.getOrNull()
        val direct = codecConfigOf(status)
        if (codecNameOf(direct) != null) {
            reportCodecFromConfig(direct, device, "A2dpService.getCodecStatus")
            return
        }
        Log.d(TAG, "codec query: A2dpService.getCodecStatus unusable (status=$status); falling back to A2DP proxy")
        queryCodecViaProxy(device)
    }

    /** 路径 B：BluetoothAdapter#getProfileProxy(A2DP) → BluetoothA2dp 代理 → getCodecStatus(device)。 */
    private fun queryCodecViaProxy(device: BluetoothDevice) {
        val context = appContext ?: return
        val adapter = bluetoothAdapter(context)
        if (adapter == null) {
            Log.w(TAG, "codec query: BluetoothAdapter unavailable")
            return
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                codecProxyListener = null
                if (profile != BluetoothProfile.A2DP || proxy == null) return
                runCatching {
                    val status = callMethod(proxy, "getCodecStatus", device)
                    reportCodecFromConfig(codecConfigOf(status), device, "BluetoothA2dp.getCodecStatus")
                }.onFailure { Log.w(TAG, "codec via A2DP proxy failed", it) }
                runCatching { callMethod(adapter, "closeProfileProxy", BluetoothProfile.A2DP, proxy) }
                    .onFailure { Log.d(TAG, "closeProfileProxy unavailable", it) }
            }

            override fun onServiceDisconnected(profile: Int) {
                codecProxyListener = null
                if (profile == BluetoothProfile.A2DP) Log.d(TAG, "A2DP proxy disconnected during codec query")
            }
        }
        codecProxyListener = listener
        val requested = runCatching {
            callMethod(adapter, "getProfileProxy", context, listener, BluetoothProfile.A2DP) as? Boolean
        }.getOrNull()
        if (requested != true) {
            codecProxyListener = null
            Log.w(TAG, "codec query: A2DP getProfileProxy unavailable")
        }
    }

    /** BluetoothCodecStatus.getCodecConfig() → BluetoothCodecConfig（任一环不可用回 null）。 */
    private fun codecConfigOf(status: Any?): Any? {
        if (status == null) return null
        return runCatching { callMethod(status, "getCodecConfig") }.getOrNull()
    }

    /** BluetoothCodecConfig.getCodecName()；方法不可用时才试 AOSP 的私有名字段。 */
    private fun codecNameOf(config: Any?): String? {
        if (config == null) return null
        val byMethod = runCatching { callMethod(config, "getCodecName") as? String }.getOrNull()
        if (!byMethod.isNullOrBlank()) return byMethod
        for (field in CODEC_NAME_FIELDS) {
            val value = runCatching { getObjectField(config, field) as? String }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** 拿不到名字时只用于日志，不做数值 → 名字的猜测映射。 */
    private fun codecTypeOf(config: Any?): Int? =
        runCatching { callMethod(config, "getCodecType") as? Int }.getOrNull()

    /** 配置 → 名字 → 广播；名字读不到就当没有（宁可「未知」也不猜）。 */
    private fun reportCodecFromConfig(config: Any?, device: BluetoothDevice?, source: String) {
        val name = codecNameOf(config)
        if (name == null) {
            if (config != null) Log.d(TAG, "codec from $source has no readable name (type=${codecTypeOf(config)})")
            return
        }
        broadcastCodec(name, device, source)
    }

    /** 蓝牙进程 → 应用进程：CODEC_CHANGED{EXTRA_CODEC}（同编码 + 同设备只报一次）。 */
    private fun broadcastCodec(codecName: String, device: BluetoothDevice?, source: String) {
        val context = appContext ?: return
        val name = codecName.trim()
        if (name.isEmpty()) return
        val mac = SystemApisUtils.deviceAddress(device)
        // 只报本模块接管的设备：本进程的编码回调对**所有** A2DP 设备都会触发，
        // 平板上其它耳机/音箱的编码绝不能显示到水月雨详情页里。
        val tracked = (mac.isNotEmpty() && mac == activeMac) ||
            MoondropModels.match(SystemApisUtils.deviceName(device)) != null
        if (!tracked) {
            Log.d(TAG, "CODEC_CHANGED skipped: $name is not the tracked device (mac=$mac)")
            return
        }
        if (name == lastReportedCodec && mac == lastReportedCodecMac) return
        lastReportedCodec = name
        lastReportedCodecMac = mac
        runCatching {
            context.sendBroadcast(Intent(HyperPodsAction.CODEC_CHANGED).apply {
                setPackage(PKG_APP)
                putExtra(HyperPodsAction.EXTRA_CODEC, name)
                if (mac.isNotEmpty()) putExtra(HyperPodsAction.EXTRA_MAC, mac)
                if (device != null) {
                    putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, SystemApisUtils.deviceName(device))
                }
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.i(TAG, "CODEC_CHANGED codec=$name source=$source mac=$mac")
        }.onFailure { Log.w(TAG, "CODEC_CHANGED broadcast failed", it) }
    }

    /** A2dpService.mActiveDevice：A2dpService 实例不可用/无活动设备时回 null。 */
    private fun activeDeviceFromService(): BluetoothDevice? =
        runCatching { getObjectField(a2dpService, FIELD_ACTIVE_DEVICE) as? BluetoothDevice }.getOrNull()
}
