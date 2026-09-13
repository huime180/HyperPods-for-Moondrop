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

    private var bootstrapHandler: Handler? = null
    private var bootstrapRunnable: Runnable? = null

    override fun onHook() {
        hookConnectionStateChanged()
        hookServiceCreateForBootstrap()
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
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    // 注意：GET_PODS_MAC 与 PODS_MAC_RECEIVED 是同一个 action 字符串，
                    // 靠 setPackage 的方向区分（请求发到本进程，应答发到 com.android.systemui）。
                    HyperPodsAction.GET_PODS_MAC -> replyMac(ctx ?: appCtx)
                    HyperPodsAction.UPDATE_SYSTEM_BATTERY -> runCatching { applySystemBattery(intent) }
                        .onFailure { Log.w(TAG, "applySystemBattery failed", it) }
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
        Log.d(TAG, "request receiver registered (GET_PODS_MAC / UPDATE_SYSTEM_BATTERY)")
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
}
