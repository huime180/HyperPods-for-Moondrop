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
 * ── ROM 代数 ──────────────────────────────────────────────────────────────
 * 本文件正文里的类名**不再硬编码**：策略实现 / 小米蓝牙 SDK / 多点相关类全部来自
 * RomProfile（MILINK_* / MULTIPOINT_* / HOST_EXTENSION / HEADSET_INFO 组）。
 * RomProfile 先给「检测到的这一代」的主档，再给其余代数的兜底，所以：
 *   HyperOS 4 -> 三个 runtime.model.*HeadsetStrategy 先试（已用真机 dex 核对）；
 *   HyperOS 3 -> AncBatteryController / ProfileContext / AncBatteryModel 先试（unverified-on-device）。
 * 两代的名字都在候选里，探测错了只是退化，不会失效。
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

    // ── 目标类候选：全部来自 RomProfile（按检测到的 ROM 代数给药）──────────────
    // 这里不再硬编码任何单一代 ROM 的类名。RomProfile 返回「本代主档 + 其余代数兜底」，
    // 主档优先命中；探测错了也只是退化到旧代码那套候选容忍度，不会更差。
    //
    // evidence（详见 RomProfile）：
    //   MILINK_MX_SDK  两代同名；HyperOS 4 侧已在 com.milink.service.apk 的 dex 里核对。
    //   MILINK_STRATEGY H4=三个 runtime.model.*HeadsetStrategy（已核对）；
    //                   H3=AncBatteryController/ProfileContext/AncBatteryModel（unverified-on-device，来自旧参考实现）。
    private val mxBluetoothClasses: List<String> get() = RomProfile.mxBluetoothClasses
    private val headsetStrategyClasses: List<String> get() = RomProfile.headsetStrategyClasses
    private val headsetInfoClasses: List<String> get() = RomProfile.headsetInfoClasses

    // ── 多点 / 一拖二（multipoint / OneBringTwo）───────────────────────────────
    // 真机 dex 实证：这套机制**只存在于 com.milink.service**
    //   （multipoint 标识符：milink 432 处，com.android.settings 与 com.xiaomi.bluetooth 各 0 处）。
    // 设置页那条「正在双设备连接」只是设置自己的文案资源，判定逻辑在 milink：
    //   isMmaHeadset single/multipoint isSupportControl= / generateSupportControlProperty
    //   no supportControlHost / supportControlHost= / HeadsetMultipointInfo.isSupportControl
    // 换句话说：拿不到有效「控制主机」描述时，框架就拒绝放行 ANC —— 这就是两台设备都报
    // 「正在双设备连接」的原因；HyperOS 4 上它同时让面板完全渲染不出电量/降噪。
    /**
     * 多点 / 一拖二的候选类。这些方法签名在 HyperOS 4 的 dex 里逐条核对过（见 RomProfile 的
     * MULTIPOINT_* / HOST_EXTENSION / HEADSET_INFO 组）；HyperOS 3 一侧取自旧参考实现，
     * 标记 unverified-on-device。候选顺序 = 本代主档优先，其余代兜底。
     */
    private val multipointQueryClasses: List<String> get() = RomProfile.multipointQueryClasses
    private val multipointInfoClasses: List<String> get() = RomProfile.multipointInfoClasses
    private val headsetMultipointInfoClasses: List<String>
        get() = RomProfile.headsetMultipointInfoClasses
    private val multipointProcessorClasses: List<String>
        get() = RomProfile.multipointProcessorClasses
    private val hostExtensionClasses: List<String> get() = RomProfile.hostExtensionClasses

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

    /** 「重启作用域」接收器用的进程 Context（MiBluetoothService/Manager 的 getInstance 参数）。 */
    override fun processContextOrNull(): Context? = context

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

    /** 最近一次见到的 HeadsetInfo 实例：构造 HeadsetMultipointInfo 时要拿它当参数。 */
    private var lastHeadsetInfo: Any? = null

    /** 最近一次见到的多点处理器：它的主机表是「是否真的存在多点主机」的权威来源。 */
    private var lastMultipointProcessor: Any? = null

    /**
     * 应用进程报来的双设备连接（一拖二）开关状态：null = 还不知道。
     * ⚠ 目前 ControlBridge 没有把 PodEvent.DualConnectionChanged 发给任何 hook 进程，
     *   所以这个值通常是 null（见文件尾报告）；一旦应用侧补上广播，这里的闸门自动生效。
     */
    @Volatile
    private var dualConnectionOn: Boolean? = null

    /** 「已应答多点查询」只打一次日志，避免每帧刷屏。 */
    private var multipointAnsweredLogged = false

    /**
     * 一旦框架真的登记了「远端主机」（另一台手机/平板加入），就绝不能再声称「没有其它主机」。
     * 这是**当前就能拿到**的诚实信号（不依赖应用进程补广播）：MultipointProcessor 的
     * remoteHost / foundHost 会被逐个观察，只记录不改写。
     */
    @Volatile
    private var remoteHostSeen = false

    /**
     * `notifyPropertyChanged(device, updateType, delayMs)` 的第二参在本 ROM 上是
     * `com.miui.headset.api.HeadsetUpdateType` 的整数值（枚举，静态不可读）。
     * 这里**不猜**：先挂住系统自己的调用把它记下来，之后用同一个值回放。
     */
    @Volatile
    private var learnedUpdateType: Int = UPDATE_TYPE_UNKNOWN

    private var lastPanelRefreshMs = 0L

    override fun onHook() {
        Log.d(TAG, "MiLink hook initializing (${RomProfile.summary()})")
        Log.d(
            TAG,
            "target candidates: strategy=${headsetStrategyClasses.joinToString()} " +
                "mxSdk=${mxBluetoothClasses.joinToString()}"
        )
        hookContextEntry()
        hookMxBluetoothRuntime()
        hookHeadsetStrategies()
        hookHeadsetInfo()
        hookMultipoint()
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
        lastHeadsetInfo = null
        lastMultipointProcessor = null
        dualConnectionOn = null
        multipointAnsweredLogged = false
        remoteHostSeen = false
        lastPanelRefreshMs = 0L
        knownMoondropAddresses.clear()
        hookedSignatures.clear()
    }

    // ── 1) 上下文入口：拿到 com.milink.service 的 Context 才能注册广播接收器 ──────

    private fun hookContextEntry() {
        mxBluetoothClasses.forEach { className ->
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
        mxBluetoothClasses.forEach { className ->
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
        headsetStrategyClasses.forEach { className ->
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
        val className = firstPresentClass(headsetInfoClasses)
        if (className == null) {
            Log.w(TAG, "HeadsetInfo hook skipped: none of $headsetInfoClasses present")
            return
        }
        Log.d(TAG, "HeadsetInfo target=$className candidates=$headsetInfoClasses")
        hookInfo(className, "getDeviceId") { FAKE_DEVICE_ID }
        hookInfo(className, "component3") { FAKE_DEVICE_ID }
        hookInfo(className, "getPowers", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookInfo(className, "component4", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookInfo(className, "getMode") { miLinkAncState() }
        hookInfo(className, "component5") { miLinkAncState() }
    }

    // ── 多点 / 一拖二：回答「不是多点主机、没有其它主机、控制可用」 ─────────────

    private fun hookMultipoint() {
        // 1) 多点查询本体：原生返回 null 时补一个描述。
        hookOnceAny(
            multipointQueryClasses, "getMultipointInfo", arrayOf(String::class.java)
        ) { param, returnType ->
            if (param.result != null) return@hookOnceAny
            if (!shouldAnswerNoMultipoint()) return@hookOnceAny
            val info = buildNoMultipointInfo() ?: return@hookOnceAny
            coerceToReturnType(returnType, info)?.let { param.result = it }
            logMultipointAnswered("getMultipointInfo", param.args.getOrNull(0)?.toString().orEmpty())
        }

        // 2) 面板/框架取「主机扩展」里的多点信息：null 会走
        //    "wrong path, primaryHeadsetHost not have headsetMultipointInfo" 分支（面板就不渲染了）。
        hookOnceAny(
            hostExtensionClasses, "getHeadsetMultipointInfo", emptyArray<Class<*>>()
        ) { param, returnType ->
            if (param.result != null) return@hookOnceAny
            if (!shouldAnswerNoMultipoint()) return@hookOnceAny
            val extension = param.instance
            val hostId = runCatching { callMethod(extension, "getHostId") as? String }.getOrNull().orEmpty()
            val info = buildHeadsetMultipointInfo(hostId) ?: return@hookOnceAny
            coerceToReturnType(returnType, info)?.let { param.result = it }
            logMultipointAnswered("HeadsetHostExtension.getHeadsetMultipointInfo", hostId)
        }

        // 3) 捕获多点处理器实例：它的主机表是「是否真有多点主机」的权威来源（只读不改）。
        hookOnceAny(
            multipointProcessorClasses, "getMultipointHeadsetHosts", emptyArray<Class<*>>()
        ) { param, _ ->
            lastMultipointProcessor = param.instance
            val size = (param.result as? Map<*, *>)?.size ?: -1
            Log.d(TAG, "multipoint host registry size=$size (processor captured for gating)")
        }

        // 4) 远端主机登记事件：只观察，用来把「没有其它主机」这句话收紧成真话。
        hookOnceAny(
            multipointProcessorClasses, "remoteHost", arrayOf(String::class.java)
        ) { param, _ ->
            lastMultipointProcessor = param.instance
            noteRemoteHost("remoteHost", param.args.getOrNull(0)?.toString().orEmpty())
        }
        hookOnceAny(
            multipointProcessorClasses, "foundHost", arrayOf(String::class.java)
        ) { param, _ ->
            lastMultipointProcessor = param.instance
            noteRemoteHost("foundHost", param.args.getOrNull(0)?.toString().orEmpty())
        }
    }

    private fun noteRemoteHost(source: String, host: String) {
        // 这个方法可能被高频调用：只在状态第一次翻转时打日志。
        val first = !remoteHostSeen
        remoteHostSeen = true
        if (first) {
            Log.i(TAG, "multipoint remote host registered via $source host=$host -> will NOT claim 'no other hosts'")
        }
    }

    /**
     * 能不能用「不是多点主机 / 没有其它主机」来回答。两道闸，任一表明真的存在多点状态就不撒谎：
     *   1) 框架自己的主机表 `MultipointProcessor.getMultipointHeadsetHosts()` 非空 —— 实机可判，无需应用配合；
     *   2) 应用进程报来的 `dualConnectionOn == true`（用户真的开了双设备连接）。
     */
    private fun shouldAnswerNoMultipoint(): Boolean {
        if (remoteHostSeen) {
            Log.i(TAG, "multipoint passthrough: a remote multipoint host was registered")
            return false
        }
        val hosts = runCatching {
            callMethod(lastMultipointProcessor, "getMultipointHeadsetHosts") as? Map<*, *>
        }.getOrNull()
        if (hosts != null && hosts.isNotEmpty()) {
            Log.i(TAG, "multipoint passthrough: framework reports ${hosts.size} multipoint host(s)")
            return false
        }
        if (dualConnectionOn == true) {
            Log.i(TAG, "multipoint passthrough: dualConnectionOn=true (OneBringTwo enabled)")
            return false
        }
        return true
    }

    /** MultipointInfo（RomProfile.MULTIPOINT_INFO 组）是 data class，构造器为 (boolean, String, List)。 */
    private fun buildNoMultipointInfo(): Any? = runCatching {
        val className = firstPresentClass(multipointInfoClasses)
            ?: throw ClassNotFoundException("MultipointInfo candidates=$multipointInfoClasses")
        val cls = findClass(className)
        val ctor = cls.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType!!, String::class.java, List::class.java
        )
        ctor.isAccessible = true
        ctor.newInstance(false, "", emptyList<Any>())
    }.onFailure {
        Log.w(TAG, "cannot construct MultipointInfo; leaving native multipoint value", it)
    }.getOrNull()

    /**
     * com.miui.headset.runtime.HeadsetMultipointInfo 构造器：
     *   (HeadsetInfo, long reportTime, boolean isMultipointDevice, String primaryHost,
     *    String supportControlHost, boolean isPrimary, boolean isSupportControl)
     * 这里描述「单主机、不是多点设备、本机就是允许控制的主机、支持控制」。
     */
    private fun buildHeadsetMultipointInfo(hostId: String): Any? {
        val headsetInfo = lastHeadsetInfo
        if (headsetInfo == null) {
            Log.w(TAG, "HeadsetMultipointInfo not built: no HeadsetInfo captured yet")
            return null
        }
        return runCatching {
            val infoClassName = firstPresentClass(headsetInfoClasses)
                ?: throw ClassNotFoundException("HeadsetInfo candidates=$headsetInfoClasses")
            val mpClassName = firstPresentClass(headsetMultipointInfoClasses)
                ?: throw ClassNotFoundException("HeadsetMultipointInfo candidates=$headsetMultipointInfoClasses")
            val cls = findClass(mpClassName)
            val ctor = cls.getDeclaredConstructor(
                findClass(infoClassName),
                Long::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
            ctor.isAccessible = true
            ctor.newInstance(headsetInfo, System.currentTimeMillis(), false, "", hostId, true, true)
        }.onFailure {
            Log.w(TAG, "cannot construct HeadsetMultipointInfo; leaving native value", it)
        }.getOrNull()
    }

    private fun logMultipointAnswered(source: String, hostId: String) {
        if (multipointAnsweredLogged) return
        multipointAnsweredLogged = true
        Log.i(
            TAG,
            "multipoint query answered isMultipointHost=false otherMultipointHosts=[] " +
                "isSupportControl=true source=$source hostId=$hostId " +
                "dualConnection=${dualConnectionOn ?: "unknown"}"
        )
    }

    /**
     * 候选类列表版 hook：第一个能解析出该方法签名的类胜出，缺类静默跳过。
     * 这样同一个 APK 在 HyperOS 3 / 4 上都能挂上（两代 ROM 的类名集合不同）。
     */
    private fun hookOnceAny(
        classes: List<String>,
        methodName: String,
        params: Array<Class<*>>,
        block: (HookParam, Class<*>) -> Unit
    ) {
        for (className in classes) {
            if (findClassOrNull(className) == null) continue
            val resolved = runCatching { resolveMethod(className, methodName, params) }.getOrNull()
            if (resolved == null) {
                Log.d(TAG, "multipoint candidate $className#$methodName not found; trying next")
                continue
            }
            hookOnce(className, methodName, params, "after", block)
            return
        }
        Log.w(TAG, "multipoint hook $methodName/${params.size} skipped: no candidate class resolved")
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

    /** HeadsetInfo（RomProfile.HEADSET_INFO 组解析出的类）的无参 getter / data class componentN。 */
    private fun hookInfo(
        className: String,
        methodName: String,
        reconnectOnRead: Boolean = false,
        provide: () -> Any
    ) {
        hookOnce(className, methodName, emptyArray<Class<*>>(), "after") { param, returnType ->
            if (!isTargetHeadsetInfo(param.instance)) return@hookOnce
            lastHeadsetInfo = param.instance
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
            // 多点闸门的输入：应用进程若把一拖二开关状态报过来（见文件尾报告），这里就能如实放行/放行。
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
                    HyperPodsAction.DUAL_CONNECTION_CHANGED -> {
                        // 只有真的带了 EXTRA_ENABLED 才更新，避免误判成「已开启」而错误地对多点放行。
                        val declared = if (received.hasExtra(HyperPodsAction.EXTRA_ENABLED)) {
                            received.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)
                        } else {
                            null
                        }
                        dualConnectionOn = declared
                        multipointAnsweredLogged = false
                        Log.i(TAG, "dualConnectionOn=$declared (multipoint gate updated) extras=${received.extras?.keySet()}")
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
