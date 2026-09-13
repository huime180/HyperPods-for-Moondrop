/*
 * HyperPods for Moondrop — com.android.settings 进程：伪装成小米原生耳机 + 把系统页面的操作路由回本模块
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 技术路线移植自 OppoPods `hook/SettingsHeadsetHook.kt`（HyperOS 上验证过的「冒充原生耳机」做法）：
 *
 *   1) 冒充身份：FAKE_DEVICE_ID = "01010607"（PuddingPods 项目记录的水月雨 PUDDING 兼容 HyperOS 内部
 *      Device ID）、FAKE_SUPPORT = "01010607,000000000000000010000000"。
 *      在 MiuiHeadsetActivity / MiuiHeadsetActivityPlugin#onCreate 里把 intent 的
 *      MIUI_HEADSET_SUPPORT / DEVICE_ID / COME_FROM 改写成伪装的模板 ID，
 *      于是系统会按「小米原生四档 ANC 耳机」的模板渲染这个页面。
 *   2) 强制身份 getter：MiuiHeadsetActivity#getDeviceID / getSupport，
 *      以及 HeadsetIDConstants 的静态判定 checkSupport / isTWS01Headset / isK77sHeadset / isBleMmaConnect。
 *   3) 拦截 AIDL 代理 com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy：
 *      真机上根本没有 MMA 服务，所以 checkSupport -> FAKE_SUPPORT、isMiTWS/checkIsMiTWS -> true、
 *      getRingFindState -> false、changeAncMode(Int, BluetoothDevice) -> 翻译成本模块 UI 档位后
 *      广播 HyperPodsAction.ANC_SELECT 给应用进程并吞掉调用（result = null）；
 *      connect / getDeviceConfig / getCommonConfig 直接 no-op。
 *   4) 注入状态：反射调用 MiuiHeadsetFragment 的
 *      updateAtUiInfo(payload) / updateAncUi(level, false) / refreshStatus(address, payload)
 *      （方法不存在时静默跳过），payload 用 MIUI 约定的
 *      "<ancMode>|0100;0101;0102;0103;0200;0201|<l>,<r>,<case>|00"，
 *      电量 255 = 未知、`value or 128` = 充电中（与 core/PodSnapshot 的 BATTERY_UNKNOWN 语义一致）。
 *   5) 实时同步：注册 ANC_CHANGED / BATTERY_CHANGED / PODS_CONNECTED / PODS_DISCONNECTED 接收器，
 *      并在主线程每 3s 重新拉取 + 重新注入一次（页面存活期间），参考实现同样节奏。
 *
 * ⚠ 所有分支**必须先确认是水月雨设备**（设备名经 core.MoondropModels.match 命中，
 *   或地址出现在本模块已知的水月雨地址集合里），非水月雨设备一律不碰。
 * ⚠ 未做的部分（见文件尾 TODO）：MiuiHeadsetBattery 电量控件的 onBatteryChanged 注入。
 */
package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.WeakHashMap
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

@SuppressLint("MissingPermission")
object SettingsHeadsetHook : HookContext() {
    private const val TAG = "HyperPods-Settings"
    private const val PKG_APP = BuildConfig.APPLICATION_ID

    /** PuddingPods 记录的水月雨兼容 HyperOS 内部 Device ID（对应四档 ANC 模板）。 */
    private const val FAKE_DEVICE_ID = "01010607"
    private const val FAKE_SUPPORT = "$FAKE_DEVICE_ID,000000000000000010000000"

    private const val CLS_ACTIVITY = "com.android.settings.bluetooth.MiuiHeadsetActivity"
    private const val CLS_ACTIVITY_PLUGIN = "com.android.settings.bluetooth.MiuiHeadsetActivityPlugin"
    private const val CLS_ID_CONSTANTS = "com.android.settings.bluetooth.HeadsetIDConstants"
    private const val CLS_FRAGMENT = "com.android.settings.bluetooth.MiuiHeadsetFragment"
    private const val CLS_PROXY = "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy"
    private const val CLS_HEADSET_SERVICE = "com.android.bluetooth.ble.app.IMiuiHeadsetService"

    private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"
    private const val EXTRA_BT_ADDRESS = "bluetoothaddress"
    private const val EXTRA_SUPPORT = "MIUI_HEADSET_SUPPORT"
    private const val EXTRA_DEVICE_ID = "DEVICE_ID"
    private const val EXTRA_COME_FROM = "COME_FROM"
    private const val COME_FROM_DEFAULT = "MIUI_BLUETOOTH_SETTINGS"

    private const val REFRESH_INTERVAL_MS = 3_000L
    private const val STATE_PREFS = "hyperpods_moondrop_settings_state"
    private const val ANC_PAYLOAD_LEVELS = "0100;0101;0102;0103;0200;0201"

    private val knownMoondropAddresses = LinkedHashSet<String>()
    private val headsetFragments = WeakHashMap<Any, Boolean>()

    private var context: Context? = null
    private var statusReceiver: BroadcastReceiver? = null

    @Volatile
    private var receiverRegistered = false

    private var currentAddress: String? = null
    private var currentName: String? = null

    /** 当前降噪档位，取值 = 本模块 UI 下标（MoondropModels.AncMode 顺序：0 关/1 降噪/2 通透/3 抗风/4 自适应/5 直播）。 */
    private var currentAncUi: Int = 0

    /** 三路电量的原始 HyperOS 编码：255 = 未知，`value or 128` = 充电中（约定见 SystemApisUtils 文件头）。 */
    private var batteryRaw: IntArray = intArrayOf(255, 255, 255)

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshLoopStarted = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (headsetFragments.keys.any { isMoondropFragment(it) }) {
                requestAppStatus("settings-periodic")
                updateFragments()
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            } else {
                refreshLoopStarted = false
                Log.d(TAG, "settings periodic refresh stopped: no active Moondrop fragment")
            }
        }
    }

    override fun onHook() {
        hookActivityEntry()
        hookSupportChecks()
        hookServiceProxy()
        hookFragmentState()
    }

    override fun onHotReloading() {
        refreshHandler.removeCallbacksAndMessages(null)
        refreshLoopStarted = false
        runCatching { statusReceiver?.let { receiver -> context?.unregisterReceiver(receiver) } }
            .onFailure { Log.w(TAG, "unregister status receiver failed", it) }
        statusReceiver = null
        receiverRegistered = false
        context = null
        headsetFragments.clear()
    }

    // ── 1) 冒充身份：改写 intent + 强制 getter ────────────────────────────────

    private fun hookActivityEntry() {
        runCatching {
            val onCreate = findMethod(CLS_ACTIVITY, "onCreate", Bundle::class.java)
            hookBefore(onCreate) { patchHeadsetIntent(this, "MiuiHeadsetActivity") }
            hookActivityStringGetter(CLS_ACTIVITY, "getDeviceID") { FAKE_DEVICE_ID }
            hookActivityStringGetter(CLS_ACTIVITY, "getSupport") { FAKE_SUPPORT }
            Log.d(TAG, "hooked $CLS_ACTIVITY#onCreate/getDeviceID/getSupport")
        }.onFailure { Log.w(TAG, "hook $CLS_ACTIVITY skipped", it) }

        runCatching {
            val onCreate = findMethod(CLS_ACTIVITY_PLUGIN, "onCreate", Bundle::class.java)
            hookBefore(onCreate) { patchHeadsetIntent(this, "MiuiHeadsetActivityPlugin") }
            Log.d(TAG, "hooked $CLS_ACTIVITY_PLUGIN#onCreate")
        }.onFailure { Log.w(TAG, "hook $CLS_ACTIVITY_PLUGIN skipped", it) }
    }

    private fun patchHeadsetIntent(param: HookParam, who: String) {
        val activity = param.instance as? Context ?: return
        registerStatusReceiver(activity)
        val intent = runCatching { callMethod(param.instance, "getIntent") as? Intent }.getOrNull() ?: return
        val device = SystemApisUtils.parcelableDevice(intent, EXTRA_DEVICE)
        val address = SystemApisUtils.deviceAddress(device)
            .ifEmpty { intent.getStringExtra(EXTRA_BT_ADDRESS).orEmpty() }
        val isPods = isMoondropDevice(device) || isMoondropAddress(address)
        Log.d(TAG, "$who.onCreate device=${describe(device)} address=$address comeFrom=${intent.getStringExtra(EXTRA_COME_FROM)} isMoondrop=$isPods")
        if (!isPods) return
        intent.putExtra(EXTRA_SUPPORT, FAKE_SUPPORT)
        intent.putExtra(EXTRA_COME_FROM, intent.getStringExtra(EXTRA_COME_FROM) ?: COME_FROM_DEFAULT)
        intent.putExtra(EXTRA_DEVICE_ID, FAKE_DEVICE_ID)
        Log.i(TAG, "$who intent patched: $EXTRA_DEVICE_ID=$FAKE_DEVICE_ID address=$address")
    }

    private fun hookActivityStringGetter(className: String, methodName: String, value: () -> String) {
        runCatching {
            val method = findMethodByParamCount(className, methodName, 0)
            hookAfter(method) {
                if (!activityIsMoondrop(instance)) return@hookAfter
                result = value()
                Log.d(TAG, "$className.$methodName forced=${value()}")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName skipped", it) }
    }

    // ── 2) HeadsetIDConstants 静态判定 ────────────────────────────────────────

    private fun hookSupportChecks() {
        hookStringStaticResult(CLS_ID_CONSTANTS, "checkSupport") { value ->
            value.startsWith(FAKE_DEVICE_ID) || value.contains(FAKE_DEVICE_ID)
        }
        hookStringStaticResult(CLS_ID_CONSTANTS, "isTWS01Headset") { value -> value == FAKE_DEVICE_ID }
        hookStringStaticResult(CLS_ID_CONSTANTS, "isK77sHeadset") { false }
        hookBleMmaConnect(CLS_ID_CONSTANTS, "isBleMmaConnect", Context::class.java)
        runCatching { findClass(CLS_HEADSET_SERVICE) }.getOrNull()?.let { serviceClass ->
            hookBleMmaConnect(CLS_ID_CONSTANTS, "isBleMmaConnect", serviceClass)
        }
    }

    /**
     * 只在「传入值就是我们的伪装 Device ID」时才改写结果——该值只会因为我们改写
     * intent 而出现，因此非水月雨设备永远不会被这条 hook 命中。
     */
    private fun hookStringStaticResult(className: String, methodName: String, resultForValue: (String) -> Any) {
        runCatching {
            val method = findMethod(className, methodName, String::class.java)
            hookAfter(method) {
                val value = args.getOrNull(0) as? String ?: return@hookAfter
                if (value != FAKE_DEVICE_ID && !value.startsWith(FAKE_DEVICE_ID)) return@hookAfter
                result = resultForValue(value)
                Log.d(TAG, "$className.$methodName($value) forced result=$result")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    /** HeadsetIDConstants.isBleMmaConnect(?, BluetoothDevice, String)：让系统认为 MMA 已连接。 */
    private fun hookBleMmaConnect(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            val method = findMethod(className, methodName, *parameterTypes)
            hookAfter(method) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val deviceId = args.lastOrNull { it is String } as? String
                if (deviceId == FAKE_DEVICE_ID || isMoondropDevice(device) || isMoondropAddress(deviceId)) {
                    result = true
                    Log.d(TAG, "$className.$methodName forced true deviceId=$deviceId")
                }
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(${parameterTypes.joinToString { it.name }}) skipped", it) }
    }

    // ── 3) AIDL 代理拦截 ─────────────────────────────────────────────────────

    private fun hookServiceProxy() {
        // 返回值直通 FAKE_SUPPORT：checkSupport(BluetoothDevice) / getDeviceInfo()
        hookProxyStringResult("checkSupport", BluetoothDevice::class.java) { FAKE_SUPPORT }
        hookProxyStringArgResult("getDeviceInfo") { FAKE_SUPPORT }
        // 空间音频/音频切换开关：本模块不提供该能力，固定返回 "1"（与参考实现同形，待实机确认）
        hookProxyStringArgResult("isSupportAudioSwitch") { "1" }
        hookProxyStringArgResult(
            "setCommonCommand", Int::class.java, String::class.java, BluetoothDevice::class.java
        ) { args ->
            val command = args.getOrNull(0) as? Int
            if (command == 102) "0" else "1"
        }
        hookProxyBooleanStringResult("isMiTWS") { true }
        hookProxyBooleanStringResult("checkIsMiTWS") { true }
        hookProxyBooleanStringResult("getRingFindState") { false }
        // 真机没有 MMA 服务：这些调用直接 no-op，避免系统页面卡在「连接中」
        hookProxyVoidDeviceNoop("connect", BluetoothDevice::class.java)
        hookProxyVoidDeviceNoop("getDeviceConfig", BluetoothDevice::class.java)
        hookProxyVoidDeviceStringNoop("getCommonConfig", BluetoothDevice::class.java, String::class.java)
        // ANC：MIUI 四档 -> 本模块 UI 档位，然后广播给应用进程并吞掉调用
        hookProxyVoidDeviceCommand("changeAncMode", Int::class.java, BluetoothDevice::class.java) { args ->
            val miuiMode = args.getOrNull(0) as? Int ?: return@hookProxyVoidDeviceCommand null
            miuiAncToUiIndex(miuiMode)
        }
        hookProxyVoidDeviceCommand("changeAncLevel", String::class.java, BluetoothDevice::class.java) { args ->
            val level = args.getOrNull(0) as? String ?: return@hookProxyVoidDeviceCommand null
            miuiAncFromLevel(level)
        }
    }

    private fun hookProxyStringResult(methodName: String, vararg parameterTypes: Class<*>, provide: () -> String) {
        runCatching {
            val method = findMethod(CLS_PROXY, methodName, *parameterTypes)
            hookBefore(method) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                if (!isMoondropDevice(device)) return@hookBefore
                result = provide()
                Log.d(TAG, "proxy $methodName forced result=$result device=${describe(device)}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyStringArgResult(
        methodName: String,
        vararg parameterTypes: Class<*>,
        provide: (List<Any?>) -> String
    ) {
        runCatching {
            val method = findMethod(CLS_PROXY, methodName, *parameterTypes)
            hookBefore(method) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val addressArg = args.lastOrNull { it is String } as? String
                if (!isMoondropDevice(device) && !isMoondropAddress(addressArg)) return@hookBefore
                result = provide(args)
                Log.d(TAG, "proxy $methodName forced result=$result address=${SystemApisUtils.deviceAddress(device)}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyBooleanStringResult(methodName: String, provide: () -> Boolean) {
        runCatching {
            val method = findMethod(CLS_PROXY, methodName, String::class.java)
            hookBefore(method) {
                val address = args.getOrNull(0) as? String
                if (!isMoondropAddress(address)) return@hookBefore
                result = provide()
                Log.d(TAG, "proxy $methodName forced result=$result address=$address")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName(String) skipped", it) }
    }

    private fun hookProxyVoidDeviceNoop(methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            val method = findMethod(CLS_PROXY, methodName, *parameterTypes)
            hookBefore(method) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                if (!isMoondropDevice(device)) return@hookBefore
                result = null
                Log.d(TAG, "proxy $methodName swallowed device=${describe(device)}")
            }
        }.onFailure { Log.w(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceStringNoop(methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            val method = findMethod(CLS_PROXY, methodName, *parameterTypes)
            hookBefore(method) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                if (!isMoondropDevice(device)) return@hookBefore
                result = null
                Log.d(TAG, "proxy $methodName swallowed device=${describe(device)}")
            }
        }.onFailure { Log.w(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceCommand(
        methodName: String,
        vararg parameterTypes: Class<*>,
        uiIndex: (List<Any?>) -> Int?
    ) {
        runCatching {
            val method = findMethod(CLS_PROXY, methodName, *parameterTypes)
            hookBefore(method) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                if (!isMoondropDevice(device)) return@hookBefore
                val index = uiIndex(args) ?: return@hookBefore
                currentAncUi = index
                sendAncSelect(index)
                saveState(context)
                result = null
                Log.i(TAG, "proxy $methodName handled address=${SystemApisUtils.deviceAddress(device)} uiIndex=$index")
            }
        }.onFailure { Log.w(TAG, "hook proxy command $methodName skipped", it) }
    }

    // ── 4) 片段状态注入 / 页面内操作回传 ─────────────────────────────────────

    private fun hookFragmentState() {
        runCatching {
            val onCreateView = findMethodByParamCount(CLS_FRAGMENT, "onCreateView", 3)
            hookAfter(onCreateView) { onFragmentAlive("onCreateView") }
            Log.d(TAG, "hooked $CLS_FRAGMENT#onCreateView")
        }.onFailure { Log.w(TAG, "hook $CLS_FRAGMENT.onCreateView skipped", it) }

        runCatching {
            val onServiceConnected = findMethodByParamCountOrNull(CLS_FRAGMENT, "onServiceConnected", 0)
            if (onServiceConnected != null) {
                hookAfter(onServiceConnected) { onFragmentAlive("onServiceConnected") }
                Log.d(TAG, "hooked $CLS_FRAGMENT#onServiceConnected")
            }
        }.onFailure { Log.w(TAG, "hook $CLS_FRAGMENT.onServiceConnected skipped", it) }

        // 没有 MMA 服务时系统会刷新成「连接失败」，直接吞掉并重新注入我们的状态
        runCatching {
            val refresh = findMethodOrNull(CLS_FRAGMENT, "refreshStatus", String::class.java, String::class.java)
            if (refresh != null) {
                hookBefore(refresh) {
                    val key = args.getOrNull(0) as? String
                    if (isMoondropFragment(instance) && key?.startsWith("MMA_CONNECTION_FAILED") == true) {
                        injectFragmentStatus(instance)
                        result = null
                        Log.w(TAG, "swallowed refreshStatus($key) for virtual device")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "hook $CLS_FRAGMENT.refreshStatus skipped", it) }

        runCatching {
            val failed = findMethodOrNull(CLS_FRAGMENT, "handleConnectMmaFailed", String::class.java)
            if (failed != null) {
                hookBefore(failed) {
                    if (isMoondropFragment(instance)) {
                        injectFragmentStatus(instance)
                        result = null
                        Log.w(TAG, "swallowed handleConnectMmaFailed for virtual device")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "hook $CLS_FRAGMENT.handleConnectMmaFailed skipped", it) }

        hookFragmentAncCommand("updateAncMode", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!) { args ->
            miuiAncToUiIndex(args.getOrNull(0) as? Int ?: 0)
        }
        hookFragmentAncCommand("updateAncLevel", String::class.java, Boolean::class.javaPrimitiveType!!) { args ->
            miuiAncFromLevel(args.getOrNull(0) as? String ?: "")
        }
    }

    private fun onFragmentAlive(reason: String) {
        registerStatusReceiver(runCatching { getObjectField(instance, "mActivity") as? Context }.getOrNull())
        val podFragment = isMoondropFragment(instance)
        Log.d(TAG, "fragment.$reason ${fragmentDebug(instance)} isMoondrop=$podFragment")
        if (!podFragment) return
        instance?.let { headsetFragments[it] = true }
        requestAppStatus("fragment-$reason")
        startPeriodicRefresh()
        injectFragmentStatus(instance)
    }

    private fun hookFragmentAncCommand(methodName: String, vararg parameterTypes: Class<*>, uiIndex: (List<Any?>) -> Int) {
        runCatching {
            val method = findMethod(CLS_FRAGMENT, methodName, *parameterTypes)
            hookBefore(method) {
                if (!isMoondropFragment(instance)) return@hookBefore
                // 第二个参数 updateDevice=false 表示只是刷新 UI，不是用户操作，放行。
                val updateDevice = args.getOrNull(1) as? Boolean ?: true
                if (!updateDevice) return@hookBefore
                val index = uiIndex(args)
                currentAncUi = index
                sendAncSelect(index)
                saveState(context)
                runCatching { callMethod(instance, "updateAncUi", settingsAncLevel(), false) }
                injectFragmentStatus(instance)
                result = null
                Log.i(TAG, "fragment $methodName handled from settings ui -> uiIndex=$index")
            }
        }.onFailure { Log.w(TAG, "hook $CLS_FRAGMENT.$methodName skipped", it) }
    }

    private fun injectFragmentStatus(fragment: Any?) {
        if (fragment == null) return
        val payload = "${settingsAncMode()}|$ANC_PAYLOAD_LEVELS|${settingsBatteryString()}|00"
        Log.d(TAG, "injectFragmentStatus payload=$payload ${fragmentDebug(fragment)}")
        runCatching { callMethod(fragment, "updateAtUiInfo", payload) }
            .onFailure { Log.d(TAG, "updateAtUiInfo unavailable on $CLS_FRAGMENT", it) }
        runCatching { callMethod(fragment, "updateAncUi", settingsAncLevel(), false) }
            .onFailure { Log.d(TAG, "updateAncUi unavailable on $CLS_FRAGMENT", it) }
        val address = currentAddress ?: fragmentAddress(fragment)
        if (address != null) {
            runCatching { callMethod(fragment, "refreshStatus", address, settingsRefreshPayload()) }
                .onFailure { Log.d(TAG, "refreshStatus unavailable on $CLS_FRAGMENT", it) }
        }
    }

    private fun updateFragments() {
        headsetFragments.keys.toList().forEach { fragment ->
            if (isMoondropFragment(fragment)) injectFragmentStatus(fragment)
        }
    }

    // ── 5) 与应用进程的广播桥 ────────────────────────────────────────────────

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        val appContext = ctx.applicationContext ?: ctx
        context = appContext
        loadState()
        val filter = IntentFilter().apply {
            addAction(HyperPodsAction.PODS_CONNECTED)
            addAction(HyperPodsAction.PODS_DISCONNECTED)
            addAction(HyperPodsAction.ANC_CHANGED)
            addAction(HyperPodsAction.BATTERY_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    HyperPodsAction.PODS_CONNECTED -> {
                        val address = intent.getStringExtra(HyperPodsAction.EXTRA_MAC)
                        val name = intent.getStringExtra(HyperPodsAction.EXTRA_DEVICE_NAME)
                        if (!address.isNullOrEmpty()) {
                            currentAddress = address
                            knownMoondropAddresses += address.uppercase()
                        }
                        if (!name.isNullOrEmpty()) currentName = name
                        saveState(appContext)
                        requestAppStatus("pods-connected")
                        updateFragments()
                    }
                    HyperPodsAction.PODS_DISCONNECTED -> {
                        Log.d(TAG, "pods disconnected address=${intent.getStringExtra(HyperPodsAction.EXTRA_MAC)}")
                        updateFragments()
                    }
                    HyperPodsAction.ANC_CHANGED -> {
                        currentAncUi = intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, currentAncUi)
                        saveState(appContext)
                        updateFragments()
                    }
                    HyperPodsAction.BATTERY_CHANGED -> {
                        batteryRaw = SystemApisUtils.readBatteryExtras(intent)
                        saveState(appContext)
                        updateFragments()
                    }
                }
                Log.d(TAG, "state $action address=$currentAddress ancUi=$currentAncUi battery=${settingsBatteryString()}")
            }
        }
        // 发送方是蓝牙进程 / 模块应用进程（不同应用），必须 RECEIVER_EXPORTED。
        val registered = runCatching { appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "register status receiver skipped", it) }
            .isSuccess
        if (!registered) return
        statusReceiver = receiver
        receiverRegistered = true
        requestAppStatus("receiver-register")
        Log.d(TAG, "status receiver registered context=$appContext")
    }

    /**
     * 向应用进程要一次全量状态。
     * ⚠ 注意方向：本模块的协议客户端在应用进程（不像 HyperPods 的 L2CAPController 跑在蓝牙进程），
     * 所以这里用 HyperPodsAction.UI_INIT / REQUEST_BATTERY 指向 BuildConfig.APPLICATION_ID。
     */
    private fun requestAppStatus(reason: String) {
        val ctx = context ?: return
        listOf(HyperPodsAction.UI_INIT, HyperPodsAction.REQUEST_BATTERY).forEach { action ->
            runCatching {
                ctx.sendBroadcast(Intent(action).apply {
                    setPackage(PKG_APP)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
            }.onFailure { Log.w(TAG, "request $action failed", it) }
        }
        Log.d(TAG, "requested app status reason=$reason")
    }

    private fun sendAncSelect(uiIndex: Int) {
        val ctx = context ?: run {
            Log.w(TAG, "sendAncSelect skipped: no context (uiIndex=$uiIndex)")
            return
        }
        runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.ANC_SELECT).apply {
                setPackage(PKG_APP)
                putExtra(HyperPodsAction.EXTRA_STATUS, uiIndex)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.i(TAG, "ANC_SELECT uiIndex=$uiIndex -> $PKG_APP")
        }.onFailure { Log.w(TAG, "ANC_SELECT broadcast failed", it) }
    }

    private fun startPeriodicRefresh() {
        if (refreshLoopStarted) return
        refreshLoopStarted = true
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
        Log.d(TAG, "settings periodic refresh started")
    }

    // ── 状态存取（设置进程本地缓存，进程重启后页面仍有值） ───────────────────

    private fun saveState(ctx: Context?) {
        val store = (ctx ?: context)?.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE) ?: return
        runCatching {
            store.edit()
                .putString("address", currentAddress)
                .putString("name", currentName)
                .putInt("anc_ui", currentAncUi)
                .putInt("battery_left", batteryRaw[0])
                .putInt("battery_right", batteryRaw[1])
                .putInt("battery_case", batteryRaw[2])
                .apply()
        }.onFailure { Log.w(TAG, "saveState failed", it) }
    }

    private fun loadState() {
        val store = context?.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE) ?: return
        runCatching {
            currentAddress = store.getString("address", currentAddress)
            currentName = store.getString("name", currentName)
            currentAncUi = store.getInt("anc_ui", currentAncUi)
            batteryRaw = intArrayOf(
                store.getInt("battery_left", batteryRaw[0]),
                store.getInt("battery_right", batteryRaw[1]),
                store.getInt("battery_case", batteryRaw[2])
            )
            currentAddress?.let { knownMoondropAddresses += it.uppercase() }
        }.onFailure { Log.w(TAG, "loadState failed", it) }
    }

    // ── 设备判定（所有 hook 的第一道闸） ─────────────────────────────────────

    private fun isMoondropDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = SystemApisUtils.deviceAddress(device)
        if (address.isNotEmpty() && isMoondropAddress(address)) return true
        val name = SystemApisUtils.deviceName(device)
        if (MoondropModels.match(name) != null) {
            if (address.isNotEmpty()) {
                knownMoondropAddresses += address.uppercase()
                currentAddress = address
            }
            if (name.isNotEmpty()) currentName = name
            return true
        }
        return false
    }

    private fun isMoondropAddress(address: String?): Boolean {
        if (address.isNullOrEmpty()) return false
        val normalized = address.uppercase()
        if (normalized == currentAddress?.uppercase()) return true
        if (knownMoondropAddresses.contains(normalized)) return true
        // 少数调用把「设备名/DeviceID」当字符串参数传进来，这里也认一次。
        return MoondropModels.match(normalized) != null
    }

    private fun activityIsMoondrop(instance: Any?): Boolean {
        if (isMoondropDevice(activityDevice(instance))) return true
        val deviceId = runCatching { getObjectField(instance, "mDeviceId") as? String }.getOrNull()
        if (deviceId == FAKE_DEVICE_ID) return true
        val address = runCatching { getObjectField(instance, "mAddress") as? String }.getOrNull()
        return isMoondropAddress(address)
    }

    private fun activityDevice(instance: Any?): BluetoothDevice? {
        listOf("mDevice", "mCachedDevice", "mBluetoothDevice").forEach { field ->
            val device = runCatching { getObjectField(instance, field) as? BluetoothDevice }.getOrNull()
            if (device != null) return device
        }
        val intent = runCatching { callMethod(instance, "getIntent") as? Intent }.getOrNull()
        return SystemApisUtils.parcelableDevice(intent, EXTRA_DEVICE)
    }

    private fun isMoondropFragment(fragment: Any?): Boolean {
        if (fragment == null) return false
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        return isMoondropDevice(device) ||
            deviceId == FAKE_DEVICE_ID ||
            support?.startsWith(FAKE_DEVICE_ID) == true
    }

    private fun fragmentAddress(fragment: Any?): String? =
        SystemApisUtils.deviceAddress(
            runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        ).ifEmpty { currentAddress?.orEmpty() }.takeIf { it.isNotEmpty() }

    // ── MIUI 档位 <-> 本模块 UI 下标 ─────────────────────────────────────────

    /**
     * MIUI 四档模板（01010607）的 ANC 值 -> 本模块 UI 下标。
     * ⚠ 猜测：按 FxxkMoondrop 记录的「App 按钮语义 0=关 1=降噪 2=透传 3=抗风 4=自适应」对齐。
     * 实测时看日志 "proxy changeAncMode handled ... uiIndex=" 校准。
     */
    private fun miuiAncToUiIndex(miuiMode: Int): Int = when (miuiMode) {
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 4
        else -> 0
    }

    /** 片段 / 代理传来的档位字符串（"0100"/"0200"...）-> 本模块 UI 下标。 */
    private fun miuiAncFromLevel(level: String): Int = when {
        level.startsWith("01") -> 1
        level.startsWith("02") -> 2
        level.startsWith("03") -> 3
        level.startsWith("04") -> 4
        else -> 0
    }

    /** payload 第一段：MIUI 的 ancMode 数值（0 关 / 1 降噪 / 2 通透 / 3 抗风 / 4 自适应）。 */
    private fun settingsAncMode(): String = currentAncUi.coerceIn(0, 4).toString()

    /** updateAncUi / payload 用的档位串。⚠ "0300"/"0400" 为猜测值，待实机确认。 */
    private fun settingsAncLevel(): String = when (currentAncUi) {
        1 -> "0100"
        2 -> "0200"
        3 -> "0300"
        4 -> "0400"
        else -> "0000"
    }

    private fun settingsBatteryString(): String = batteryRaw.joinToString(",")

    /** refreshStatus 的 16 字段负载（字段位置沿用参考实现）。 */
    private fun settingsRefreshPayload(): String {
        val values = MutableList(16) { "" }
        values[0] = batteryRaw[0].toString()
        values[1] = batteryRaw[1].toString()
        values[2] = batteryRaw[2].toString()
        values[7] = settingsAncLevel()
        values[8] = "false"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun describe(device: BluetoothDevice?): String {
        if (device == null) return "null"
        return "BluetoothDevice(address=${SystemApisUtils.deviceAddress(device)},name=${SystemApisUtils.deviceName(device)})"
    }

    private fun fragmentDebug(fragment: Any?): String {
        if (fragment == null) return "fragment(null)"
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        val supportAnc = runCatching { getObjectField(fragment, "mSupportAnc") }.getOrNull()
        return "fragment(device=${describe(device)},deviceId=$deviceId,support=$support,supportAnc=$supportAnc)"
    }
}

/*
 * TODO(实机核对，本机无 JDK/SDK，无法编译与真机验证)：
 *  1. MiuiHeadsetFragment#updateAtUiInfo / updateAncUi / refreshStatus 的真实签名与字段含义
 *     （当前按 OppoPods 在 HyperOS 上的用法调用）。
 *  2. MIUI 四档模板的 changeAncMode 数值语义（1/2/3/4 与关/降噪/通透/抗风的对应关系）。
 *  3. updateAncUi 的档位串是否需要 "0300"/"0400"（抗风/自适应）。
 *  4. MiuiHeadsetBattery（tws 电量控件）的注入未实现：若系统页面电量环不跟随
 *     蓝牙栈电量显示，再补 hook com.android.settings.bluetooth.tws.MiuiHeadsetBattery
 *     构造函数 + onBatteryChanged(int,int,int)（参考 OppoPods hookBatteryView）。
 */
