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
 *   5) 电量控件：com.android.settings.bluetooth.tws.MiuiHeadsetBattery 的电量环是**独立**于 fragment
 *      状态注入渲染的（它订阅 MMA 服务回调后调 onBatteryChanged(int,int,int)）。真机没有 MMA 服务，
 *      该回调永不到达，电量环会永远停在占位值；因此这里用 WeakHashMap 记住控件，主动喂三路电量。
 *   6) 实时同步：注册 ANC_CHANGED / BATTERY_CHANGED / PODS_CONNECTED / PODS_DISCONNECTED 接收器，
 *      并在主线程每 3s 重新拉取 + 重新注入 fragment 与电量控件（页面存活期间），参考实现同样节奏。
 *
 * ⚠ 所有分支**必须先确认是水月雨设备**（设备名经 core.MoondropModels.match 命中，
 *   或地址出现在本模块已知的水月雨地址集合里），非水月雨设备一律不碰。
 *
 * ROM 代数：本文件里所有系统类名（MiuiHeadsetActivity / MiuiHeadsetFragment / MiuiHeadsetBattery /
 * IMiuiHeadsetService$Stub$Proxy …）都从 RomProfile 的候选表解析（SETTINGS_* 组），
 * onHook() 里先按检测到的 ROM 代数取「本代主档」，再退到其它代数兜底。
 * 这些名字两代恰好相同，但入口统一了：以后某一代改名只改 RomProfile。
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
import android.view.View
import java.util.WeakHashMap
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.core.Gaia
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

@SuppressLint("MissingPermission")
object SettingsHeadsetHook : HookContext() {
    private const val TAG = "HyperPods-Settings"
    private const val PKG_APP = BuildConfig.APPLICATION_ID

    /** MiLink 进程：它的耳机面板读的是它自己进程内的运行时模型，必须单独喂状态。 */
    private const val PKG_MILINK = "com.milink.service"

    /** PuddingPods 记录的水月雨兼容 HyperOS 内部 Device ID（对应四档 ANC 模板）。 */
    private const val FAKE_DEVICE_ID = "01010607"
    private const val FAKE_SUPPORT = "$FAKE_DEVICE_ID,000000000000000010000000"

    /**
     * 目标类名 —— 全部由 RomProfile 按检测到的 ROM 代数解析（本代主档优先，其余代数兜底）。
     *
     * 这些类名在 HyperOS 3 / 4 上**恰好同名**，但 RomProfile 仍然显式给出两代的候选与证据：
     *   HyperOS 4 侧已在本机 com.android.settings.apk 的 dex 里逐条核对（VERIFIED_H4_DEX）；
     *   HyperOS 3 侧来自旧 ROM 参考实现（OppoPods / PuddingPods），标 unverified-on-device。
     * 以后某一代改了名字，只需要改 RomProfile 的表，本文件不动。
     *
     * onHook() 里解析成实际类名；解析不到就留空串 —— 各 hook 的 findMethod 会抛异常并被
     * runCatching 吞掉（只 skip 那一条），不会崩进程、也不影响别的 hook。
     */
    private var clsActivity: String = ""
    private var clsActivityPlugin: String = ""
    private var clsIdConstants: String = ""
    private var clsFragment: String = ""
    private var clsProxy: String = ""
    private var clsHeadsetService: String = ""
    private var clsBattery: String = ""

    /** RomProfile 候选里第一个在本进程真实存在的类名；一个都没有时记一条日志并返回空串。 */
    private fun resolveTargetClass(candidates: List<String>, label: String): String {
        val found = firstPresentClass(candidates)
        if (found == null) Log.w(TAG, "$label: none of $candidates present in this process")
        return found.orEmpty()
    }

    private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"
    private const val EXTRA_BT_ADDRESS = "bluetoothaddress"
    private const val EXTRA_SUPPORT = "MIUI_HEADSET_SUPPORT"
    private const val EXTRA_DEVICE_ID = "DEVICE_ID"
    private const val EXTRA_COME_FROM = "COME_FROM"
    private const val COME_FROM_DEFAULT = "MIUI_BLUETOOTH_SETTINGS"

    private const val REFRESH_INTERVAL_MS = 3_000L

    /** 每 5 次 tick（≈15s）打一次 ANC 控件心跳：即便什么都没装上也一定能看到状态。 */
    private const val ANC_UI_HEARTBEAT_TICKS = 5
    private const val STATE_PREFS = "hyperpods_moondrop_settings_state"
    private const val ANC_PAYLOAD_LEVELS = "0100;0101;0102;0103;0200;0201"

    /**
     * 原生耳机页里「手势/按键配置」入口的 preference key（res/xml/headsetlayout.xml，H4 实证）。
     * 点它原本会打开 MiuiHeadsetKeyConfigFragment（读的是厂商 AIDL 的 12 字符配置串），
     * 我们把这个入口接管掉（见 hookGestureEntry）。
     */
    private const val PREF_KEY_GESTURE = "key_config"

    private val knownMoondropAddresses = LinkedHashSet<String>()
    private val headsetFragments = WeakHashMap<Any, Boolean>()

    /** 已构造的 MiuiHeadsetBattery 电量控件 -> 它负责的设备（弱引用，页面销毁后自动释放）。 */
    private val batteryViews = WeakHashMap<Any, BluetoothDevice>()

    private var context: Context? = null
    private var statusReceiver: BroadcastReceiver? = null

    /** 「重启作用域」接收器用的进程 Context（页面/服务入口里偷到的，见 registerStatusReceiver）。 */
    override fun processContextOrNull(): Context? = context

    @Volatile
    private var receiverRegistered = false

    private var currentAddress: String? = null
    private var currentName: String? = null

    /**
     * 当前打开的「水月雨耳机页」对应的设备地址。
     * 用于兜底放行**完全没有设备参数**的代理调用（此时无法按参数判定），
     * 避免在别的蓝牙设备页面上误伤。本 ROM 上 getDeviceInfo / isSupportAudioSwitch
     * 实测都带一个 String 参数，走 isOursToken/地址比对分支，不再依赖这里的兜底。
     */
    private var activePageAddress: String? = null

    /** 当前降噪档位，取值 = 本模块 UI 下标（MoondropModels.AncMode 顺序：0 关/1 降噪/2 通透/3 抗风/4 自适应/5 直播）。 */
    private var currentAncUi: Int = 0

    /** 三路电量的原始 HyperOS 编码：255 = 未知，`value or 128` = 充电中（约定见 SystemApisUtils 文件头）。 */
    private var batteryRaw: IntArray = intArrayOf(255, 255, 255)

    /**
     * 应用进程同步过来的手势配置（feature 22 TOUCHV2，5 字节，每字节高 4 位=左耳 / 低 4 位=右耳）。
     * null = 还没同步到 —— 此时原生页的手势卡片显示「未同步」，绝不回落到厂商那套默认值。
     */
    private var gestureConf: Gaia.GestureConf? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshLoopStarted = false

    /** 周期 tick 计数：每 ANC_UI_HEARTBEAT_TICKS 次打一条 ANC 控件心跳，避免刷屏又不至于静默。 */
    private var refreshTick = 0

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (headsetFragments.keys.any { isMoondropFragment(it) }) {
                requestAppStatus("settings-periodic")
                updateFragments()
                // 电量环自己要重喂一次：它不跟随 fragment 的状态注入。
                updateBatteryViews()
                // 三档降噪控件：先重声明；被厂商删掉的话下一轮重新装回去。
                if (NativeThreeModeAncUi.isAttached()) {
                    runCatching { NativeThreeModeAncUi.reassert() }
                } else {
                    headsetFragments.keys.firstOrNull { isMoondropFragment(it) }
                        ?.let { installAncUi(it, "periodic") }
                }
                // 手势控件：同样先重声明；厂商会把 key_config preference 放回来。
                if (NativeGestureUi.isAttached()) {
                    runCatching { NativeGestureUi.reassert() }
                } else {
                    headsetFragments.keys.firstOrNull { isMoondropFragment(it) }
                        ?.let { installGestureUi(it, "periodic") }
                }
                // 心跳：保证「一条 ANC / 手势日志都没有」这种情况再也不可能出现。
                refreshTick++
                if (refreshTick % ANC_UI_HEARTBEAT_TICKS == 0) {
                    Log.i(TAG, "ANC ui heartbeat: ${NativeThreeModeAncUi.stateSummary()}")
                    Log.i(TAG, "gesture ui heartbeat: ${NativeGestureUi.stateSummary()}")
                }
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            } else {
                refreshLoopStarted = false
                Log.d(TAG, "settings periodic refresh stopped: no active Moondrop fragment")
            }
        }
    }

    override fun onHook() {
        Log.d(TAG, "Settings headset hook initializing (${RomProfile.summary()})")
        clsActivity = resolveTargetClass(RomProfile.settingsActivityClasses, "MiuiHeadsetActivity")
        clsActivityPlugin = resolveTargetClass(RomProfile.settingsActivityPluginClasses, "MiuiHeadsetActivityPlugin")
        clsIdConstants = resolveTargetClass(RomProfile.settingsIdConstantsClasses, "HeadsetIDConstants")
        clsFragment = resolveTargetClass(RomProfile.settingsFragmentClasses, "MiuiHeadsetFragment")
        clsProxy = resolveTargetClass(RomProfile.settingsProxyClasses, "IMiuiHeadsetService\$Stub\$Proxy")
        clsHeadsetService = resolveTargetClass(RomProfile.headsetServiceIfaceClasses, "IMiuiHeadsetService")
        clsBattery = resolveTargetClass(RomProfile.settingsBatteryViewClasses, "MiuiHeadsetBattery")
        hookActivityEntry()
        hookSupportChecks()
        hookServiceProxy()
        hookBatteryView()
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
        batteryViews.clear()
        NativeThreeModeAncUi.reset()
        NativeGestureUi.reset()
        gestureConf = null
    }

    // ── 1) 冒充身份：改写 intent + 强制 getter ────────────────────────────────

    private fun hookActivityEntry() {
        runCatching {
            val onCreate = findMethod(clsActivity, "onCreate", Bundle::class.java)
            hookBefore(onCreate) { patchHeadsetIntent(this, "MiuiHeadsetActivity") }
            hookActivityStringGetter(clsActivity, "getDeviceID") { FAKE_DEVICE_ID }
            hookActivityStringGetter(clsActivity, "getSupport") { FAKE_SUPPORT }
            Log.d(TAG, "hooked $clsActivity#onCreate/getDeviceID/getSupport")
        }.onFailure { Log.w(TAG, "hook $clsActivity skipped", it) }

        runCatching {
            val onCreate = findMethod(clsActivityPlugin, "onCreate", Bundle::class.java)
            hookBefore(onCreate) { patchHeadsetIntent(this, "MiuiHeadsetActivityPlugin") }
            Log.d(TAG, "hooked $clsActivityPlugin#onCreate")
        }.onFailure { Log.w(TAG, "hook $clsActivityPlugin skipped", it) }
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
        // 页面变体取证：本 ROM 上 MiuiHeadsetActivity 与 MiuiHeadsetActivityPlugin 是哪一条在跑，
        // 决定原生 ANC 控件长在哪棵树里。
        Log.i(TAG, "headset page variant=$who isMoondrop=$isPods")
        if (!isPods) return
        intent.putExtra(EXTRA_SUPPORT, FAKE_SUPPORT)
        intent.putExtra(EXTRA_COME_FROM, intent.getStringExtra(EXTRA_COME_FROM) ?: COME_FROM_DEFAULT)
        intent.putExtra(EXTRA_DEVICE_ID, FAKE_DEVICE_ID)
        activePageAddress = address.ifEmpty { currentAddress.orEmpty() }
        Log.i(TAG, "$who intent patched: $EXTRA_DEVICE_ID=$FAKE_DEVICE_ID address=$address (active page=$activePageAddress)")
    }

    private fun hookActivityStringGetter(className: String, methodName: String, value: () -> String) {
        runCatching {
            val method = findMethodByParamCount(className, methodName, 0)
            hookAfter(method) {
                if (!activityIsMoondrop(instance)) return@hookAfter
                result = value()
                Log.d(TAG, "$className.$methodName forced=$result")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName skipped", it) }
    }

    // ── 2) HeadsetIDConstants 静态判定 ────────────────────────────────────────

    private fun hookSupportChecks() {
        hookStringStaticResult(clsIdConstants, "checkSupport") { value ->
            value.startsWith(FAKE_DEVICE_ID) || value.contains(FAKE_DEVICE_ID)
        }
        hookStringStaticResult(clsIdConstants, "isTWS01Headset") { value -> value == FAKE_DEVICE_ID }
        hookStringStaticResult(clsIdConstants, "isK77sHeadset") { false }
        hookBleMmaConnect(clsIdConstants, "isBleMmaConnect", Context::class.java)
        runCatching { findClass(clsHeadsetService) }.getOrNull()?.let { serviceClass ->
            hookBleMmaConnect(clsIdConstants, "isBleMmaConnect", serviceClass)
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
        // 返回值直通 FAKE_SUPPORT：checkSupport(BluetoothDevice) / getDeviceInfo(String)
        hookProxyStringResult("checkSupport", BluetoothDevice::class.java) { FAKE_SUPPORT }
        // ⚠ 本 ROM 实测签名是 getDeviceInfo(String): String / isSupportAudioSwitch(String): String。
        //   早先这里没写 String::class.java，findMethod 找不到 0 参重载 -> 这两条 hook 一直是静默失效的。
        hookProxyStringArgResult("getDeviceInfo", String::class.java) { FAKE_SUPPORT }
        hookProxyStringArgResult("isSupportAudioSwitch", String::class.java) { "1" }
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
            val method = findMethod(clsProxy, methodName, *parameterTypes)
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
            val method = findMethod(clsProxy, methodName, *parameterTypes)
            hookBefore(method) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val addressArg = args.lastOrNull { it is String } as? String
                val ours = isMoondropDevice(device) || isOursToken(addressArg) ||
                    (activePageAddress != null && addressArg.equals(activePageAddress, ignoreCase = true)) ||
                    (device == null && addressArg == null && activePageAddress != null)
                if (!ours) return@hookBefore
                result = provide(args)
                Log.d(TAG, "proxy $methodName forced result=$result address=${SystemApisUtils.deviceAddress(device)}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyBooleanStringResult(methodName: String, provide: () -> Boolean) {
        runCatching {
            val method = findMethod(clsProxy, methodName, String::class.java)
            hookBefore(method) {
                val address = args.getOrNull(0) as? String
                if (!isOursToken(address)) return@hookBefore
                result = provide()
                Log.d(TAG, "proxy $methodName forced result=$result address=$address")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName(String) skipped", it) }
    }

    private fun hookProxyVoidDeviceNoop(methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            val method = findMethod(clsProxy, methodName, *parameterTypes)
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
            val method = findMethod(clsProxy, methodName, *parameterTypes)
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
            val method = findMethod(clsProxy, methodName, *parameterTypes)
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

    // ── 3b) 电量控件：MiuiHeadsetBattery（左右耳/充电盒电量环）─────────────────
    //
    // 参考实现里这一段是**独立**于 fragment 状态注入的：电量环由 MiuiHeadsetBattery 自己
    // 订阅 MMA 服务回调后调用 onBatteryChanged(int,int,int) 刷新。真机没有 MMA 服务，
    // 该回调永远不来，电量环就永远停在占位值，所以必须主动喂。
    private fun hookBatteryView() {
        runCatching {
            val constructor = findConstructorByParamCount(clsBattery, 4)
            hookConstructorAfter(constructor) {
                val device = args.getOrNull(0) as? BluetoothDevice
                registerStatusReceiver(args.getOrNull(1) as? Context)
                Log.d(TAG, "$clsBattery.<init> device=${describe(device)} isMoondrop=${isMoondropDevice(device)}")
                if (!isMoondropDevice(device)) return@hookConstructorAfter
                val view = instance ?: return@hookConstructorAfter
                batteryViews[view] = device ?: return@hookConstructorAfter
                requestAppStatus("battery-init")
                updateBatteryView(view)
                Log.d(TAG, "$clsBattery registered address=${SystemApisUtils.deviceAddress(device)}")
            }
            Log.d(TAG, "hooked $clsBattery<init>/4")
        }.onFailure { Log.w(TAG, "hook $clsBattery constructor skipped", it) }

        // 没有 MMA 服务时系统会推一个空/失败回调把电量环刷回占位值：吞掉并重新注入。
        runCatching {
            val method = findMethod(clsBattery, "onBatteryChanged", String::class.java)
            hookBefore(method) {
                val view = instance ?: return@hookBefore
                val device = batteryViews[view]
                Log.d(TAG, "$clsBattery.onBatteryChanged(String) raw=${args.getOrNull(0)} device=${describe(device)}")
                if (!isMoondropDevice(device)) return@hookBefore
                result = null
                updateBatteryView(view)
            }
            Log.d(TAG, "hooked $clsBattery#onBatteryChanged(String)")
        }.onFailure { Log.w(TAG, "hook $clsBattery.onBatteryChanged(String) skipped", it) }
    }

    // ── 4) 片段状态注入 / 页面内操作回传 ─────────────────────────────────────

    private fun hookFragmentState() {
        // 片段上的方法名也随 ROM 变，因此同样走 RomProfile（HyperOS 4 六个名字都已核对）。
        val methods = RomProfile.settingsFragmentMethods
        Log.d(TAG, "fragment methods: ${methods.updateAtUiInfo.name}/${methods.updateAncUi.name}/" +
            "${methods.refreshStatus.name}/${methods.handleConnectMmaFailed.name}/" +
            "${methods.updateAncMode.name}/${methods.updateAncLevel.name}")
        runCatching {
            val onCreateView = findMethodByParamCount(clsFragment, "onCreateView", 3)
            hookAfter(onCreateView) { onFragmentAlive(this, "onCreateView") }
            Log.d(TAG, "hooked $clsFragment#onCreateView")
        }.onFailure { Log.w(TAG, "hook $clsFragment.onCreateView skipped", it) }

        runCatching {
            val onServiceConnected = findMethodByParamCountOrNull(clsFragment, "onServiceConnected", 0)
            if (onServiceConnected != null) {
                hookAfter(onServiceConnected) { onFragmentAlive(this, "onServiceConnected") }
                Log.d(TAG, "hooked $clsFragment#onServiceConnected")
            }
        }.onFailure { Log.w(TAG, "hook $clsFragment.onServiceConnected skipped", it) }

        // 没有 MMA 服务时系统会刷新成「连接失败」，直接吞掉并重新注入我们的状态
        runCatching {
            val refresh = findMethodOrNull(
                clsFragment, methods.refreshStatus.name, String::class.java, String::class.java
            )
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
        }.onFailure { Log.w(TAG, "hook $clsFragment.refreshStatus skipped", it) }

        runCatching {
            val failed = findMethodOrNull(clsFragment, methods.handleConnectMmaFailed.name, String::class.java)
            if (failed != null) {
                hookBefore(failed) {
                    if (isMoondropFragment(instance)) {
                        injectFragmentStatus(instance)
                        result = null
                        Log.w(TAG, "swallowed handleConnectMmaFailed for virtual device")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "hook $clsFragment.handleConnectMmaFailed skipped", it) }

        hookFragmentAncCommand(
            methods.updateAncMode.name, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!
        ) { args ->
            miuiAncToUiIndex(args.getOrNull(0) as? Int ?: 0)
        }
        hookFragmentAncCommand(
            methods.updateAncLevel.name, String::class.java, Boolean::class.javaPrimitiveType!!
        ) { args ->
            miuiAncFromLevel(args.getOrNull(0) as? String ?: "")
        }
        hookGestureEntry()
    }

    /**
     * 手势入口接管：`onPreferenceTreeClick(PreferenceScreen, Preference): Boolean`（H4 DEX 实证签名）。
     *
     * 按参数个数 2 定位（**不引用 androidx.preference 的编译期符号**，与本模块「只用反射碰目标 ROM」
     * 的约定一致），key 用反射 `getKey()` 读。命中 key_config 时返回 true 吞掉原导航，
     * 改为广播 SHOW_UI 打开本模块的手势页 —— 真值在应用进程，厂商那个子页对本耳机显示的是默认值。
     */
    private fun hookGestureEntry() {
        runCatching {
            val click = findMethodByParamCountOrNull(clsFragment, "onPreferenceTreeClick", 2)
            if (click == null) {
                Log.w(TAG, "onPreferenceTreeClick/2 not found; native gesture entry not intercepted")
                return
            }
            hookBefore(click) {
                if (!isMoondropFragment(instance)) return@hookBefore
                val preference = args.getOrNull(1) ?: return@hookBefore
                val key = runCatching { callMethod(preference, "getKey") as? String }.getOrNull()
                if (key != PREF_KEY_GESTURE) return@hookBefore
                Log.i(
                    TAG,
                    "native gesture entry ($key) intercepted -> SHOW_UI " +
                        "(vendor key-config page shows framework defaults, not our device)"
                )
                openAppUi("native-gesture-entry")
                result = true
            }
            Log.d(TAG, "hooked $clsFragment#onPreferenceTreeClick (gesture entry routing)")
        }.onFailure { Log.w(TAG, "hook $clsFragment.onPreferenceTreeClick skipped", it) }
    }

    private fun onFragmentAlive(param: HookParam, reason: String) {
        registerStatusReceiver(runCatching { getObjectField(param.instance, "mActivity") as? Context }.getOrNull())
        val podFragment = isMoondropFragment(param.instance)
        Log.d(TAG, "fragment.$reason ${fragmentDebug(param.instance)} isMoondrop=$podFragment")
        if (!podFragment) return
        param.instance?.let { headsetFragments[it] = true }
        requestAppStatus("fragment-$reason")
        startPeriodicRefresh()
        injectFragmentStatus(param.instance)
        installAncUi(param.instance, reason)
        installGestureUi(param.instance, reason)
        requestGestureState(reason)
    }

    /**
     * 在原生耳机页里装「三档降噪」控件：通透 / 降噪 / 关闭，降噪展开 自适应 / 抗风 / 普通。
     *
     * 参考实现（PuddingPods 的 SettingsHeadsetHook$PuddingAncUi / installPuddingThreeModeUi）
     * 是「隐藏框架自己的 ANC 控件行 + 在同一位置放自己的一套」。具体定位与取舍见
     * NativeThreeModeAncUi 的文件头（含本 ROM 实测的 headset_anc* 资源名取证）。
     */
    private fun installAncUi(fragment: Any?, reason: String) {
        val installed = runCatching {
            NativeThreeModeAncUi.install(fragment, currentAncUi) { index -> onAncSelectedFromNativeUi(index) }
        }.onFailure { Log.w(TAG, "install native three-mode ANC ui failed ($reason)", it) }
            .getOrDefault(false)
        if (installed) {
            Log.i(
                TAG,
                "native ANC ui installed reason=$reason " + NativeThreeModeAncUi.stateSummary()
            )
        } else {
            Log.w(
                TAG,
                "native ANC ui NOT installed reason=$reason " +
                    "(fragment.getView() missing or no injectable container) " +
                    NativeThreeModeAncUi.stateSummary()
            )
        }
        val root = runCatching { callMethod(fragment, "getView") as? View }.getOrNull()
        root?.post { runCatching { NativeThreeModeAncUi.reassert() } }
    }

    /**
     * 原生耳机页「手势控制」段：藏掉厂商那条 key_config preference，托管我们自己的手势卡片。
     *
     * 为什么是「藏 + 托管」而不是驱动厂商那套 UI：厂商的按键模型（左/右 × 双击/三击/长按 共 6 槽）
     * 与 Pudding 的 feature 22 TOUCHV2（5 槽 × 双耳 = 10 个半字节，动作 id 空间也不同）不是一回事，
     * 硬映射只会显示一份与实际不符的手势状态。决策依据见 hook/NativeGestureUi.kt 文件头。
     */
    private fun installGestureUi(fragment: Any?, reason: String) {
        val installed = runCatching {
            NativeGestureUi.install(
                fragment,
                gestureConf,
                onOpenApp = { openAppUi("native-gesture-card") },
                onSelect = { slot, ear, action -> onGestureSelectedFromNativeUi(slot, ear, action) }
            )
        }.onFailure { Log.w(TAG, "install native gesture ui failed ($reason)", it) }
            .getOrDefault(false)
        if (installed) {
            Log.i(TAG, "native gesture ui installed reason=$reason " + NativeGestureUi.stateSummary())
        } else {
            Log.w(TAG, "native gesture ui NOT installed reason=$reason " + NativeGestureUi.stateSummary())
        }
    }

    /** 用户在托管的原生手势卡片里改了一个「手势 × 耳朵」：转发给应用进程（真值只在那边）。 */
    private fun onGestureSelectedFromNativeUi(slot: Int, ear: Int, actionId: Int) {
        Log.i(TAG, "native gesture ui selected slot=$slot ear=$ear action=$actionId")
        sendGestureSelect(slot, ear, actionId)
    }

    /** 用户点了「打开 App 手势设置」/ 原生 key_config 入口被我们接管时的落地动作。 */
    private fun openAppUi(reason: String) {
        val ctx = context ?: run {
            Log.w(TAG, "SHOW_UI skipped: no context ($reason)")
            return
        }
        runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.SHOW_UI).apply {
                setPackage(PKG_APP)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.i(TAG, "SHOW_UI -> $PKG_APP ($reason)")
        }.onFailure { Log.w(TAG, "SHOW_UI broadcast failed", it) }
    }

    /** 把手势槽位选择广播给应用进程（真值只在应用进程；这里读-改-写下发）。 */
    private fun sendGestureSelect(slot: Int, ear: Int, actionId: Int) {
        val ctx = context ?: run {
            Log.w(TAG, "GESTURE_SELECT skipped: no context (slot=$slot ear=$ear action=$actionId)")
            return
        }
        runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.GESTURE_SELECT).apply {
                setPackage(PKG_APP)
                putExtra(HyperPodsAction.EXTRA_GESTURE_SLOT, slot)
                putExtra(HyperPodsAction.EXTRA_GESTURE_EAR, ear)
                putExtra(HyperPodsAction.EXTRA_STATUS, actionId)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.i(TAG, "GESTURE_SELECT slot=$slot ear=$ear action=$actionId -> $PKG_APP")
        }.onFailure { Log.w(TAG, "GESTURE_SELECT broadcast failed", it) }
    }

    /** 向应用进程要一次当前手势配置（native 手势卡片打开时 / 周期刷新时调用）。 */
    private fun requestGestureState(reason: String) {
        val ctx = context ?: return
        runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.REQUEST_GESTURE).apply {
                setPackage(PKG_APP)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.d(TAG, "requested ${HyperPodsAction.REQUEST_GESTURE} reason=$reason")
        }.onFailure { Log.w(TAG, "request ${HyperPodsAction.REQUEST_GESTURE} failed", it) }
    }

    /** 用户在原生页的三档控件里选了档位：与系统控件走完全相同的通道（广播给应用进程）。 */
    private fun onAncSelectedFromNativeUi(uiIndex: Int) {
        currentAncUi = uiIndex
        saveState(context)
        sendAncSelect(uiIndex)
        updateFragments()
        Log.i(TAG, "native three-mode ANC ui selected uiIndex=$uiIndex")
    }

    private fun hookFragmentAncCommand(methodName: String, vararg parameterTypes: Class<*>, uiIndex: (List<Any?>) -> Int) {
        runCatching {
            val method = findMethod(clsFragment, methodName, *parameterTypes)
            hookBefore(method) {
                if (!isMoondropFragment(instance)) return@hookBefore
                // 第二个参数 updateDevice=false 表示只是刷新 UI，不是用户操作，放行。
                val updateDevice = args.getOrNull(1) as? Boolean ?: true
                if (!updateDevice) return@hookBefore
                val index = uiIndex(args)
                currentAncUi = index
                sendAncSelect(index)
                saveState(context)
                runCatching {
                    callMethod(instance, RomProfile.settingsFragmentMethods.updateAncUi.name, settingsAncLevel(), false)
                }
                injectFragmentStatus(instance)
                result = null
                Log.i(TAG, "fragment $methodName handled from settings ui -> uiIndex=$index")
            }
        }.onFailure { Log.w(TAG, "hook $clsFragment.$methodName skipped", it) }
    }

    private fun injectFragmentStatus(fragment: Any?) {
        if (fragment == null) return
        val payload = "${settingsAncMode()}|$ANC_PAYLOAD_LEVELS|${settingsBatteryString()}|00"
        Log.d(TAG, "injectFragmentStatus payload=$payload ${fragmentDebug(fragment)}")
        val methods = RomProfile.settingsFragmentMethods
        runCatching { callMethod(fragment, methods.updateAtUiInfo.name, payload) }
            .onFailure { Log.d(TAG, "${methods.updateAtUiInfo.name} unavailable on $clsFragment", it) }
        runCatching { callMethod(fragment, methods.updateAncUi.name, settingsAncLevel(), false) }
            .onFailure { Log.d(TAG, "${methods.updateAncUi.name} unavailable on $clsFragment", it) }
        // 优先用 fragment 自己的 mDevice 地址；fragmentAddress 内部已回落到 currentAddress。
        val address = fragmentAddress(fragment)
        if (address != null) {
            runCatching { callMethod(fragment, methods.refreshStatus.name, address, settingsRefreshPayload()) }
                .onFailure { Log.d(TAG, "${methods.refreshStatus.name} unavailable on $clsFragment", it) }
        }
        // 同步我们自己的三档控件选中态（框架控件那边由 updateAncUi 负责）。
        runCatching { NativeThreeModeAncUi.refresh(currentAncUi) }
    }

    private fun updateFragments() {
        headsetFragments.keys.toList().forEach { fragment ->
            if (isMoondropFragment(fragment)) injectFragmentStatus(fragment)
        }
    }

    private fun updateBatteryViews() {
        batteryViews.keys.toList().forEach { view ->
            runCatching { updateBatteryView(view) }
                .onFailure { Log.w(TAG, "update battery view failed", it) }
        }
    }

    /**
     * 主动调用 MiuiHeadsetBattery#onBatteryChanged(int,int,int)（不传 String —— 那个重载
     * 是我们用来「拦住系统空回调」的钩子，走它会被自己吞掉）。
     * 三个 Int 就是我们三路电量的原始 HyperOS 编码：255 未知、`value or 128` 充电中。
     */
    private fun updateBatteryView(view: Any?) {
        val values = batteryRaw
        runCatching { callMethod(view, "onBatteryChanged", values[0], values[1], values[2]) }
            .onSuccess { Log.d(TAG, "$clsBattery.onBatteryChanged(int,int,int) forced=${values.joinToString(",")}") }
            .onFailure { Log.d(TAG, "$clsBattery.onBatteryChanged(int,int,int) unavailable", it) }
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
            // 手势配置（feature 22 TOUCHV2）：应用进程是唯一真值来源，这里只负责显示。
            addAction(HyperPodsAction.GESTURE_CHANGED)
            // 多点闸门：这个进程收到的任何一拖二状态都代转给 milink（见 forwardToMiLink）。
            addAction(HyperPodsAction.DUAL_CONNECTION_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val received = intent ?: return
                val action = received.action ?: return
                when (action) {
                    HyperPodsAction.PODS_CONNECTED -> {
                        val address = received.getStringExtra(HyperPodsAction.EXTRA_MAC)
                        val name = received.getStringExtra(HyperPodsAction.EXTRA_DEVICE_NAME)
                        if (!address.isNullOrEmpty()) {
                            currentAddress = address
                            knownMoondropAddresses += address.uppercase()
                        }
                        if (!name.isNullOrEmpty()) currentName = name
                        saveState(appContext)
                        requestAppStatus("pods-connected")
                        updateFragments()
                        forwardToMiLink(received, "pods-connected")
                    }
                    HyperPodsAction.PODS_DISCONNECTED -> {
                        val address = received.getStringExtra(HyperPodsAction.EXTRA_MAC)
                        val pageAddress = activePageAddress
                        if (address.isNullOrEmpty() || pageAddress == null ||
                            address.equals(pageAddress, ignoreCase = true)
                        ) {
                            activePageAddress = null
                        }
                        Log.d(TAG, "pods disconnected address=$address")
                        updateFragments()
                        forwardToMiLink(received, "pods-disconnected")
                    }
                    HyperPodsAction.ANC_CHANGED -> {
                        currentAncUi = received.getIntExtra(HyperPodsAction.EXTRA_STATUS, currentAncUi)
                        saveState(appContext)
                        updateFragments()
                        forwardToMiLink(received, "anc-changed")
                    }
                    HyperPodsAction.BATTERY_CHANGED -> {
                        batteryRaw = SystemApisUtils.readBatteryExtras(received)
                        saveState(appContext)
                        updateFragments()
                        updateBatteryViews()
                        forwardToMiLink(received, "battery-changed")
                    }
                    HyperPodsAction.DUAL_CONNECTION_CHANGED -> {
                        forwardToMiLink(received, "dual-connection-changed")
                    }
                    HyperPodsAction.GESTURE_CHANGED -> {
                        val payload = received.getByteArrayExtra(HyperPodsAction.EXTRA_GESTURE_PAYLOAD)
                        val parsed = Gaia.parseGestureConf(payload)
                        if (parsed != null) {
                            gestureConf = parsed
                            Log.i(TAG, "gesture conf synced from app: $parsed")
                        } else {
                            Log.w(TAG, "GESTURE_CHANGED without a valid ${Gaia.TOUCHV2_CONF_SIZE}-byte payload")
                        }
                        runCatching { NativeGestureUi.refresh(gestureConf) }
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
        listOf(
            HyperPodsAction.UI_INIT,
            HyperPodsAction.REQUEST_BATTERY,
            HyperPodsAction.REQUEST_GESTURE
        ).forEach { action ->
            runCatching {
                ctx.sendBroadcast(Intent(action).apply {
                    setPackage(PKG_APP)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
            }.onFailure { Log.w(TAG, "request $action failed", it) }
        }
        Log.d(TAG, "requested app status reason=$reason")
    }

    /**
     * 把刚收到的状态**原样**转发给 com.milink.service。
     *
     * 为什么需要：应用进程的 ControlBridge 只把 ANC_CHANGED / BATTERY_CHANGED 发给
     * com.android.settings 与 com.xiaomi.bluetooth（见 pods/ControlBridge.kt 的 pushAnc/pushBattery），
     * **没有发给 com.milink.service**，而 MiLinkServiceHook 正是靠这些广播更新它在 milink 进程里的缓存。
     * 根治办法是在 ControlBridge 那两个 sendTo 的目标里加上 "com.milink.service"；
     * 在不能改 pods/ 的前提下，这里由设置进程代转一份（动作名不变，MiLinkServiceHook 监听同样四个动作）。
     */
    private fun forwardToMiLink(source: Intent, reason: String) {
        val ctx = context ?: return
        runCatching {
            val forward = Intent(source.action).apply {
                setPackage(PKG_MILINK)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                source.extras?.let { putExtras(it) }
            }
            ctx.sendBroadcast(forward)
            Log.d(TAG, "forwarded ${source.action} to $PKG_MILINK ($reason)")
        }.onFailure { Log.w(TAG, "forward to $PKG_MILINK failed ($reason)", it) }
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
        refreshTick = 0
        Log.i(TAG, "settings periodic refresh started (ANC ui heartbeat every $ANC_UI_HEARTBEAT_TICKS ticks)")
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
            if (!address.isNullOrEmpty()) {
                knownMoondropAddresses += address.uppercase()
                currentAddress = address
            }
            if (!name.isNullOrEmpty()) currentName = name
            return true
        }
        return false
    }

    /** 参数可能是 MAC，也可能是我们改写进去的伪装 Device ID / 能力串。 */
    private fun isOursToken(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (value == FAKE_DEVICE_ID) return true
        if (value.startsWith(FAKE_DEVICE_ID)) return true
        return isMoondropAddress(value)
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
        ).orEmpty()                     // deviceAddress 返回 String?，先归一成非空串
            .ifEmpty { currentAddress.orEmpty() }
            .takeIf { it.isNotEmpty() }

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
 *  4. MiuiHeadsetBattery（tws 电量控件）已按 OppoPods hookBatteryView 实现：
 *     构造函数（4 参）后登记控件 + onBatteryChanged(String) 拦截 + onBatteryChanged(int,int,int) 主动注入。
 *     若实机日志里 `hooked ...MiuiHeadsetBattery<init>/4` 缺失，说明该类名/构造参数个数与 ROM 不符，
 *     需要照实机改名（候选表在 RomProfile 的 SETTINGS_BATTERY_VIEW 组：HyperOS 4 已核对
 *     `com.android.settings.bluetooth.tws.MiuiHeadsetBattery`；非 tws 的
 *     `com.android.settings.bluetooth.MiuiHeadsetBattery` 在 HyperOS 4 的 APK 里**不存在**，
 *     只作为 HyperOS 3 的 heuristic 候选）。
 */
