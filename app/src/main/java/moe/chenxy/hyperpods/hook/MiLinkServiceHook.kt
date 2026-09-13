/*
 * HyperPods for Moondrop — com.milink.service 进程：把耳机身份/电量/降噪镜像进 HyperOS 耳机运行时
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么必须有它：
 *   HyperOS 的「蓝牙耳机」卡片 / 设备详情页（com.miui.circulateplus.world.headset.HeadSetsDetail）
 *   **不读** BluetoothDevice 的能力，而是读 com.milink.service 里的耳机运行时模型：
 *       DiscoveryImpl / HeadsetStrategy
 *         -> IHeadsetStateStrategy.getDeviceId / getAncState / getBatteryLevelCache ...
 *         -> HeadsetInfo(deviceId, powers, mode, ...)
 *         -> HeadsetDeviceInfo -> HeadSetsDetail 渲染
 *   真机没有任何 MiLink 后端，这些 getter 查不到设备就返回 0/空，于是面板什么都不渲染——
 *   这就是「设置 hook 没有效果、没有变化」的根因。
 *
 * ── 与 OppoPods 参考实现的关系（重要） ────────────────────────────────────────
 * 本文件**不是** OppoPods `hook/MiLinkServiceHook.kt` 的逐行移植。参考实现是给旧版 MiLink
 * （17.2.x）写的，本机 ROM 是 **Android 17 / HyperOS 4.0**，其 MiLink 运行时已被重写成
 * 「策略模式」，参考实现挂的多数类/方法在这台机器上**已经不存在**：
 *
 *   OppoPods 挂的                              本 ROM 实测
 *   ProfileContext.getBatteryLevel(device)      ✗ 该类已变成网络 profile 类（isPublish/setCallingPackage）
 *   ProfileContext.getDeviceId(device)          ✗ 只剩无参 getDeviceId()
 *   AncBatteryController.getAncState(device)    ✗ 这些方法不在该类上（它现在只是调用方）
 *   AncBatteryController.getBatteryLevelCache   ✗ 同上
 *   AncBatteryController.setAncStateBlock       ✗ 同上
 *   AncBatteryController.getHeadsetPropertyBlock ✗ 同上
 *   headsetPropertyChangeListener.invoke(d, t)  ✗ 已换成 notifyPropertyChanged(device, int, long)
 *
 * 本文件改挂**本 ROM 实际存在**的那一层（全部用 `tools/dex_strings.py` 从真机 APK 的
 * dex 类/方法表核对过，签名逐条一致）：
 *
 *   1) 策略实现三件套（哪个策略被选中都能兜住）：
 *        com.miui.headset.runtime.model.XiaomiHeadsetStrategy
 *        com.miui.headset.runtime.model.ThirdPartyHeadsetStrategy
 *        com.miui.headset.runtime.model.AirPodsHeadsetStrategy
 *      方法（与 IHeadsetStateStrategy 接口一致）：
 *        getDeviceId(BluetoothDevice): String
 *        getAncState(BluetoothDevice): int
 *        getBatteryLevelCache(BluetoothDevice): List
 *        getBatteryCache(BluetoothDevice): List
 *        getHeadsetPropertyBlock(BluetoothDevice): int
 *        setAncStateBlock(BluetoothDevice, int): int
 *        notifyPropertyChanged(BluetoothDevice, int, long): void   ← 只用于「学习」notify 类型
 *   2) 小米蓝牙 SDK（Xiaomi 策略的后端，也是身份握手所在）：
 *        com.xiaomi.mxbluetoothsdk.service.MxBluetoothService
 *        com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager
 *   3) 面板直接渲染的数据对象：
 *        com.miui.headset.api.HeadsetInfo
 *          getDeviceId()/component3(), getPowers()/component4(), getMode()/component5()
 *
 * 有意**不**移植（OppoPods 有、本模块不需要或不适用）：
 *   空间音频、自定义按钮（MiRing / SynergyView 劫持）、查找耳机、游戏模式、
 *   CircuplateServiceInfo 开关态、跨包加载图标、HeadSetsDetail 的 onDetachedFromWindow 生命周期抑制。
 *
 * 与 OppoPods 的行为差异（有意为之）：
 *   · FAKE_DEVICE_ID 用本模块的 "01010607"（与 `SettingsHeadsetHook` 完全一致），
 *     保证「系统设置页」与「MiLink 卡片」指向同一台虚拟设备；
 *   · 降噪档位映射直接用本模块 UI 下标（0 关/1 降噪/2 通透/3 抗风/4 自适应），
 *     与 `SettingsHeadsetHook.miuiAncToUiIndex` 同构，避免两个面板互相打架；
 *   · 写返回值前先核对**真实返回类型**（`coerceToReturnType`），对不上就保留原返回值——
 *     某台 ROM 把 List 改成 int[] 时只会少改一条 getter，绝不让 com.milink.service 崩。
 *
 * ⚠ 所有 hook 注册都用 `hookOnce`（内部 runCatching + 去重），缺类/改签名只跳过该条 hook。
 * ⚠ 所有对外广播都 setPackage(...)（Android 14+ 丢弃隐式广播）。
 * ⚠ 已知接线缺口：应用进程的 ControlBridge 只把 ANC_CHANGED / BATTERY_CHANGED 发给
 *   com.android.settings 与 com.xiaomi.bluetooth，**没有发给 com.milink.service**（该文件不在
 *   本次允许修改的范围内）。因此本 hook 依赖 SettingsHeadsetHook 把收到的状态转发过来
 *   （见 SettingsHeadsetHook.forwardToMiLink）。根治办法是在 ControlBridge.pushAnc/pushBattery
 *   的目标里加上 "com.milink.service"。
 */
package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

@SuppressLint("MissingPermission")
object MiLinkServiceHook : HookContext() {
    private const val TAG = "HyperPods-MiLink"

    /** 协议客户端所在进程（本模块应用）。 */
    private const val PKG_APP = BuildConfig.APPLICATION_ID

    /** 与 SettingsHeadsetHook 共用同一个伪装 Device ID，两个面板才会指向同一台虚拟设备。 */
    private const val FAKE_DEVICE_ID = "01010607"

    // ── 小米蓝牙 SDK（身份握手 + 降噪命令）────────────────────────────────────
    private const val CLS_MX_SERVICE = "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService"
    private const val CLS_MX_MANAGER = "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager"

    // ── 耳机状态策略实现（本 ROM 的真实数据层）────────────────────────────────
    private const val CLS_STRATEGY_XIAOMI = "com.miui.headset.runtime.model.XiaomiHeadsetStrategy"
    private const val CLS_STRATEGY_THIRD_PARTY = "com.miui.headset.runtime.model.ThirdPartyHeadsetStrategy"
    private const val CLS_STRATEGY_AIRPODS = "com.miui.headset.runtime.model.AirPodsHeadsetStrategy"

    /** 面板直接渲染的数据对象（Kotlin data class，getter + componentN 双形态）。 */
    private const val CLS_HEADSET_INFO = "com.miui.headset.api.HeadsetInfo"

    private const val STATE_PREFS = "hyperpods_moondrop_milink_state"

    /** 面板读取电量时顺带拉一次应用状态的最小间隔（避免读一次发一次广播）。 */
    private const val PANEL_REFRESH_THROTTLE_MS = 5_000L

    /** 还没观察到过 notifyPropertyChanged 的类型值。 */
    private const val UPDATE_TYPE_UNKNOWN = Int.MIN_VALUE

    // ── 进程内状态 ───────────────────────────────────────────────────────────
    private val knownMoondropAddresses = LinkedHashSet<String>()

    /** 已挂载的方法签名（phase:signature）；三个策略共享父类方法时避免重复挂载。 */
    private val hookedSignatures = LinkedHashSet<String>()

    private var context: Context? = null
    private var statusReceiver: BroadcastReceiver? = null

    @Volatile
    private var receiverRegistered = false

    private var currentAddress: String? = null
    private var currentName: String? = null

    /** 当前降噪档位 = 本模块 UI 下标（顺序见 core.MoondropModels.AncMode）。 */
    private var currentAncUi: Int = 0

    /** 三路电量的 HyperOS 原始编码：255 未知，`value or 128` 充电中（解码用 SystemApisUtils）。 */
    private var batteryRaw: IntArray = intArrayOf(
        SystemApisUtils.BATTERY_RAW_UNKNOWN,
        SystemApisUtils.BATTERY_RAW_UNKNOWN,
        SystemApisUtils.BATTERY_RAW_UNKNOWN
    )

    /** 最近一次见到的策略实例，用于反向通知面板重读属性。 */
    private var lastStrategy: Any? = null
    private var lastStrategyDevice: BluetoothDevice? = null

    /**
     * `notifyPropertyChanged(device, updateType, delayMs)` 的第二参在本 ROM 上是
     * `com.miui.headset.api.HeadsetUpdateType` 的整数值（枚举，静态不可读）。
     * 这里**不猜**：先挂住系统自己的调用把它记下来，之后用同一个值回放。
     */
    @Volatile
    private var learnedUpdateType: Int = UPDATE_TYPE_UNKNOWN

    private var lastPanelRefreshMs = 0L

    override fun onHook() {
        Log.d(TAG, "MiLink hook initializing (HyperOS 4.0 verified target set)")
        hookContextEntry()
        hookMxBluetoothRuntime()
        hookHeadsetStrategies()
        hookHeadsetInfo()
    }

    override fun onHotReloading() {
        runCatching { statusReceiver?.let { receiver -> context?.unregisterReceiver(receiver) } }
            .onFailure { Log.w(TAG, "unregister status receiver failed", it) }
        statusReceiver = null
        receiverRegistered = false
        context = null
        lastStrategy = null
        lastStrategyDevice = null
        learnedUpdateType = UPDATE_TYPE_UNKNOWN
        lastPanelRefreshMs = 0L
        knownMoondropAddresses.clear()
        hookedSignatures.clear()
    }

    // ── 1) 上下文入口：拿到 com.milink.service 的 Context 才能注册广播接收器 ──────

    private fun hookContextEntry() {
        listOf(CLS_MX_SERVICE, CLS_MX_MANAGER).forEach { className ->
            // 本 ROM 实测存在：getInstanceForIsMiTWS / getInstanceForThirdParty / getInstance / getInstanceForAirpods（均 1 参 Context）
            listOf("getInstanceForIsMiTWS", "getInstanceForThirdParty", "getInstance").forEach { name ->
                hookOnce(className, name, arrayOf(Context::class.java), "before") { param, _ ->
                    registerStatusReceiver(param.args.getOrNull(0) as? Context)
                }
            }
        }
    }

    // ── 2) 小米蓝牙 SDK：让耳机被当成「原生 Mi TWS」并接管降噪命令 ────────────────

    private fun hookMxBluetoothRuntime() {
        listOf(CLS_MX_SERVICE, CLS_MX_MANAGER).forEach { className ->
            hookDevice(className, "checkIsMiTWS") { 1 }
            hookDevice(className, "getDeviceId") { FAKE_DEVICE_ID }
            hookDevice(className, "getBatteryLevel") { 1 }
            hookDevice(className, "getAncState") { miLinkAncState() }
            hookDevice(className, "getDeviceRunInfo") { 0 }
            hookDevice(className, "getWearStatus") { "0,0" }
            hookDevice(className, "isLeAudio") { false }
            hookString(className, "isMiTWS") { true }
            // 降噪命令：档位用本模块 UI 下标；调用被吞掉，只广播给应用进程。
            // （命令走 hookOnce 而不是 hookDevice：hookDevice 的尾参是返回值提供者，签名不同。）
            hookOnce(className, "openAnc", arrayOf(BluetoothDevice::class.java), "before") { param, returnType -> handleAncCommand(param, returnType, 1) }
            hookOnce(className, "closeAnc", arrayOf(BluetoothDevice::class.java), "before") { param, returnType -> handleAncCommand(param, returnType, 0) }
            hookOnce(className, "openTransparent", arrayOf(BluetoothDevice::class.java), "before") { param, returnType -> handleAncCommand(param, returnType, 2) }
        }
    }

    // ── 3) 耳机状态策略：本 ROM 真实的数据层（哪个策略被选中都兜住）──────────────

    private fun hookHeadsetStrategies() {
        listOf(CLS_STRATEGY_XIAOMI, CLS_STRATEGY_THIRD_PARTY, CLS_STRATEGY_AIRPODS).forEach { className ->
            hookDevice(className, "getDeviceId") { FAKE_DEVICE_ID }
            hookDevice(className, "getAncState") { miLinkAncState() }
            hookDevice(className, "getBatteryLevelCache", reconnectOnRead = true) { miLinkBatteryLevels() }
            hookDevice(className, "getBatteryCache", reconnectOnRead = true) { miLinkBatteryLevels() }
            hookDevice(className, "getHeadsetPropertyBlock", reconnectOnRead = true) { minBatteryPercent() }
            // 面板/系统把降噪状态写回模型时的入口：翻译成本模块 UI 下标并广播给应用进程。
            hookOnce(
                className, "setAncStateBlock",
                arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!), "before"
            ) { param, returnType -> handleAncStateBlock(param, returnType) }
            // 只用于学习：记录系统自己用的 updateType，绝不在没学到之前瞎猜一个值去调用。
            hookOnce(
                className, "notifyPropertyChanged",
                arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
                "before"
            ) { param, _ -> learnUpdateType(param) }
        }
    }

    // ── 4) 面板直接渲染的数据对象 ─────────────────────────────────────────────

    private fun hookHeadsetInfo() {
        hookInfo("getDeviceId") { FAKE_DEVICE_ID }
        hookInfo("component3") { FAKE_DEVICE_ID }
        hookInfo("getPowers", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookInfo("component4", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookInfo("getMode") { miLinkAncState() }
        hookInfo("component5") { miLinkAncState() }
    }

    // ── hook 回调体（保持 hook 注册处简洁）────────────────────────────────────

    /** 降噪命令：吞掉原生调用，只把本模块 UI 下标广播给应用进程。 */
    private fun handleAncCommand(param: HookParam, returnType: Class<*>, uiIndex: Int) {
        val device = param.args.getOrNull(0) as? BluetoothDevice ?: return
        if (!isMoondropDevice(device)) return
        rememberStrategy(param.instance, device)
        captureRuntimeContext(param.instance)
        currentAncUi = uiIndex
        sendAncSelect(uiIndex)
        coerceToReturnType(returnType, miLinkAncState())?.let { param.result = it }
        Log.i(TAG, "ANC command handled address=${SystemApisUtils.deviceAddress(device)} uiIndex=$uiIndex")
    }

    /**
     * 系统把降噪状态写进模型：翻译成本模块 UI 下标，
     * **仅在档位真的变化时**广播给应用进程（避免「应用改档 -> 面板回写 -> 又广播一次」的环路）。
     */
    private fun handleAncStateBlock(param: HookParam, returnType: Class<*>) {
        val device = param.args.getOrNull(0) as? BluetoothDevice ?: return
        if (!isMoondropDevice(device)) return
        rememberStrategy(param.instance, device)
        captureRuntimeContext(param.instance)
        val rawMode = param.args.getOrNull(1) as? Int ?: return
        val uiIndex = uiIndexFromMiLink(rawMode)
        val changed = uiIndex != currentAncUi
        currentAncUi = uiIndex
        saveState(context)
        if (changed) sendAncSelect(uiIndex)
        if (learnedUpdateType != UPDATE_TYPE_UNKNOWN) notifyPanel()
        coerceToReturnType(returnType, miLinkAncState())?.let { param.result = it }
        Log.i(
            TAG,
            "setAncStateBlock address=${SystemApisUtils.deviceAddress(device)} rawMode=$rawMode uiIndex=$uiIndex changed=$changed"
        )
    }

    /** 记录系统自己用的 notify 类型（本 ROM 是 HeadsetUpdateType 枚举的整数值）。 */
    private fun learnUpdateType(param: HookParam) {
        val device = param.args.getOrNull(0) as? BluetoothDevice ?: return
        if (!isMoondropDevice(device)) return
        val type = param.args.getOrNull(1) as? Int ?: return
        rememberStrategy(param.instance, device)
        if (type != learnedUpdateType) {
            learnedUpdateType = type
            Log.i(TAG, "learned notifyPropertyChanged updateType=$type address=${SystemApisUtils.deviceAddress(device)}")
        }
    }

    // ── 通用 hook 包装 ───────────────────────────────────────────────────────

    /** 以 BluetoothDevice 为单个参数的 getter/命令。 */
    private fun hookDevice(
        className: String,
        methodName: String,
        phase: String = "after",
        reconnectOnRead: Boolean = false,
        provide: () -> Any
    ) {
        hookOnce(className, methodName, arrayOf(BluetoothDevice::class.java), phase) { param, returnType ->
            val device = param.args.getOrNull(0) as? BluetoothDevice ?: return@hookOnce
            if (!isMoondropDevice(device)) return@hookOnce
            val old = param.result
            rememberStrategy(param.instance, device)
            captureRuntimeContext(param.instance)
            if (reconnectOnRead) requestPanelAppStatus("$className.$methodName")
            val value = provide()
            coerceToReturnType(returnType, value)?.let { param.result = it }
            Log.d(TAG, "$className.$methodName old=$old new=$value address=${SystemApisUtils.deviceAddress(device)}")
        }
    }

    /** 以 MAC 字符串为唯参数的判定方法。 */
    private fun hookString(className: String, methodName: String, provide: () -> Any) {
        hookOnce(className, methodName, arrayOf(String::class.java), "after") { param, returnType ->
            val address = param.args.getOrNull(0) as? String ?: return@hookOnce
            if (!isMoondropAddress(address)) return@hookOnce
            val old = param.result
            val value = provide()
            coerceToReturnType(returnType, value)?.let { param.result = it }
            Log.d(TAG, "$className.$methodName address=$address old=$old new=$value")
        }
    }

    /** com.miui.headset.api.HeadsetInfo 的无参 getter / data class componentN。 */
    private fun hookInfo(methodName: String, reconnectOnRead: Boolean = false, provide: () -> Any) {
        hookOnce(CLS_HEADSET_INFO, methodName, emptyArray<Class<*>>(), "after") { param, returnType ->
            if (!isTargetHeadsetInfo(param.instance)) return@hookOnce
            val old = param.result
            if (reconnectOnRead) requestPanelAppStatus("HeadsetInfo.$methodName")
            val value = provide()
            coerceToReturnType(returnType, value)?.let { param.result = it }
            Log.d(TAG, "HeadsetInfo.$methodName old=$old new=$value")
        }
    }

    /**
     * 解析 + 去重 + 挂载。任何一步失败都只记日志（缺类 / 缺方法 / 改签名都只跳过这一条）。
     * 之所以要「去重」：三个策略类可能把同一个方法继承自同一个父类，
     * 那样 findAnyMethod 会拿到同一个 Method，重复挂载会让回调执行多次。
     */
    private fun hookOnce(
        className: String,
        methodName: String,
        params: Array<Class<*>>,
        phase: String,
        block: (HookParam, Class<*>) -> Unit
    ) {
        runCatching {
            val method = resolveMethod(className, methodName, params)
            val key = "$phase:${method.toGenericString()}"
            if (!hookedSignatures.add(key)) return@runCatching
            // 返回类型在这里取出来传给回调：HookParam 上没有这个方法（不改 HookContext）。
            val returnType = method.returnType
            val body: HookParam.() -> Unit = { block(this, returnType) }
            when (phase) {
                "before" -> hookBefore(method, body)
                else -> hookAfter(method, body)
            }
            Log.d(TAG, "hooked $phase ${method.declaringClass.name}#${method.name}/${method.parameterTypes.size}")
        }.onFailure { Log.w(TAG, "hook $className.$methodName/$phase skipped", it) }
    }

    /** 先按声明签名找，找不到再沿继承链找（策略实现常把方法放在父类里）。 */
    private fun resolveMethod(className: String, methodName: String, params: Array<Class<*>>): Method =
        runCatching { findMethod(className, methodName, *params) }.getOrNull()
            ?: runCatching { findAnyMethod(className, methodName, params.size) }.getOrNull()
            ?: throw NoSuchMethodException("$className#$methodName/${params.size}")

    /**
     * 把要写回的返回值对齐到真实返回类型；无法安全对齐时返回 null（调用方保留原返回值）。
     *
     * 为什么需要它：参考实现直接 `result = listOf(...)` / `result = 1`，
     * 一旦 ROM 把返回类型从 `List<Integer>` 改成 `int[]`（或反过来），
     * 写回的值就会在框架里 ClassCastException 并**崩掉 com.milink.service**。
     * 本 ROM 实测 getPowers/getBatteryLevelCache 都返回 java.util.List，这里仍然保留兜底。
     */
    private fun coerceToReturnType(returnType: Class<*>, value: Any): Any? {
        if (returnType.name == "void" || returnType == java.lang.Void::class.java) return null
        return when {
            returnType == Int::class.javaPrimitiveType -> value as? Int
            returnType == Boolean::class.javaPrimitiveType -> value as? Boolean
            returnType == Long::class.javaPrimitiveType -> value as? Long
            returnType == IntArray::class.java -> (value as? List<*>)?.mapNotNull { it as? Int }?.toIntArray()
            returnType.isInstance(value) -> value
            else -> null
        }
    }

    // ── 与应用进程的广播桥 ───────────────────────────────────────────────────

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        val appContext = ctx.applicationContext ?: ctx
        context = appContext
        loadState()

        val filter = IntentFilter().apply {
            addAction(HyperPodsAction.PODS_CONNECTED)
            addAction(HyperPodsAction.PODS_DISCONNECTED)
            addAction(HyperPodsAction.BATTERY_CHANGED)
            addAction(HyperPodsAction.ANC_CHANGED)
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
                        notifyPanel()
                    }
                    HyperPodsAction.PODS_DISCONNECTED -> {
                        Log.d(TAG, "pods disconnected address=${received.getStringExtra(HyperPodsAction.EXTRA_MAC)}")
                        notifyPanel()
                    }
                    HyperPodsAction.ANC_CHANGED -> {
                        currentAncUi = received.getIntExtra(HyperPodsAction.EXTRA_STATUS, currentAncUi)
                        saveState(appContext)
                        notifyPanel()
                    }
                    HyperPodsAction.BATTERY_CHANGED -> {
                        batteryRaw = SystemApisUtils.readBatteryExtras(received)
                        saveState(appContext)
                        notifyPanel()
                    }
                    else -> Log.d(TAG, "ignored action=$action")
                }
                Log.d(
                    TAG,
                    "state action=$action address=$currentAddress ancUi=$currentAncUi battery=${batteryRaw.joinToString(",")}"
                )
            }
        }

        // 发送方是模块应用进程 / com.android.settings（不同应用），必须 RECEIVER_EXPORTED。
        val registered = runCatching { appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "register status receiver skipped", it) }
            .isSuccess
        if (!registered) return
        statusReceiver = receiver
        receiverRegistered = true
        requestAppStatus("receiver-register")
        Log.i(TAG, "status receiver registered context=$appContext")
    }

    /** 向应用进程要一次全量状态（协议客户端在应用进程，不在 com.milink.service）。 */
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

    /** 面板每读一次电量都可能触发，这里做节流，避免读一次发一次广播。 */
    private fun requestPanelAppStatus(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPanelRefreshMs < PANEL_REFRESH_THROTTLE_MS) return
        lastPanelRefreshMs = now
        requestAppStatus("panel-$reason")
    }

    /** 面板上用户点了降噪：把本模块 UI 下标广播给应用进程。 */
    private fun sendAncSelect(uiIndex: Int) {
        val ctx = context ?: run {
            Log.w(TAG, "sendAncSelect skipped: no context uiIndex=$uiIndex")
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

    // ── 反向通知：让已经渲染的面板重读一次属性 ─────────────────────────────────

    private fun rememberStrategy(strategy: Any?, device: BluetoothDevice) {
        lastStrategy = strategy
        lastStrategyDevice = device
    }

    /**
     * 回放 `notifyPropertyChanged(device, learnedUpdateType, 0L)`。
     * 本 ROM 上该方法的第二参是 `HeadsetUpdateType` 枚举的整数值，静态读不到，
     * 所以只在系统自己调用过一次之后（learnUpdateType 记录）才回放——不猜。
     */
    private fun notifyPanel() {
        val strategy = lastStrategy ?: return
        val device = lastStrategyDevice ?: return
        val type = learnedUpdateType
        if (type == UPDATE_TYPE_UNKNOWN) {
            Log.d(TAG, "notifyPanel skipped: notifyPropertyChanged type not observed yet")
            return
        }
        runCatching {
            callMethod(strategy, "notifyPropertyChanged", device, type, 0L)
            Log.d(TAG, "notifyPanel updateType=$type address=${SystemApisUtils.deviceAddress(device)}")
        }.onFailure { Log.w(TAG, "notifyPanel failed updateType=$type", it) }
    }

    /** 控制器自己带着 Context：即使 getInstanceForIsMiTWS 没被调用，也能拿到 Context 注册接收器。 */
    private fun captureRuntimeContext(owner: Any?) {
        val ownerContext = runCatching { getObjectField(owner, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastStrategy, "context") as? Context }.getOrNull()
            ?: return
        registerStatusReceiver(ownerContext.applicationContext ?: ownerContext)
    }

    // ── 状态编解码 ───────────────────────────────────────────────────────────

    /** 降噪档位（本模块 UI 下标）-> MiLink/MIUI 数值。 */
    private fun miLinkAncState(): Int = currentAncUi.coerceIn(0, 4)

    /** MiLink/MIUI 数值 -> 本模块 UI 下标（与 SettingsHeadsetHook.miuiAncToUiIndex 同构）。 */
    private fun uiIndexFromMiLink(mode: Int): Int = when (mode) {
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 4
        else -> 0
    }

    /**
     * MiLink 的三路电量载荷：`[充电盒, 左, 右, 盒充电, 左充电, 右充电]`
     * （字段顺序沿用 OppoPods 在 HyperOS 上的实测值，本 ROM 的返回类型仍是 java.util.List）。
     * 电量未知 = 255，充电位 = 1/0。
     */
    private fun miLinkBatteryLevels(): List<Int> {
        loadState()
        return listOf(
            plainLevel(batteryRaw[2]),
            plainLevel(batteryRaw[0]),
            plainLevel(batteryRaw[1]),
            chargingFlag(batteryRaw[2]),
            chargingFlag(batteryRaw[0]),
            chargingFlag(batteryRaw[1])
        )
    }

    /** getHeadsetPropertyBlock：左右耳已知电量取较小值；都未知 = 0。 */
    private fun minBatteryPercent(): Int {
        loadState()
        val known = listOf(batteryRaw[0], batteryRaw[1])
            .map { SystemApisUtils.decodeLevel(it) }
            .filter { it >= 0 }
        return known.minOrNull() ?: 0
    }

    private fun plainLevel(raw: Int): Int {
        val level = SystemApisUtils.decodeLevel(raw)
        return if (level < 0) SystemApisUtils.BATTERY_RAW_UNKNOWN else level
    }

    private fun chargingFlag(raw: Int): Int = if (SystemApisUtils.decodeCharging(raw)) 1 else 0

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
        if (normalized == FAKE_DEVICE_ID || normalized.startsWith(FAKE_DEVICE_ID)) return true
        return MoondropModels.match(normalized) != null
    }

    /**
     * HeadsetInfo 只有地址与名字：地址可能还没学到，所以名字也认一次
     * （getAddress/component1 + getName/component2）。
     */
    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        val address = listOf("getAddress", "component1")
            .firstNotNullOfOrNull { name -> runCatching { callMethod(info, name) as? String }.getOrNull() }
        if (address != null && isMoondropAddress(address)) return true
        val deviceName = listOf("getName", "component2")
            .firstNotNullOfOrNull { name -> runCatching { callMethod(info, name) as? String }.getOrNull() }
        return MoondropModels.match(deviceName) != null
    }

    // ── 状态存取（com.milink.service 本地缓存，进程重启后仍有值） ───────────────

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
}
