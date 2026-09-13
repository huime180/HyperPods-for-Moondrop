/*
 * HyperPods for Moondrop — ROM 代数识别（HyperOS 3 / HyperOS 4 / 更旧 / 未知）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * ── 为什么需要这个文件 ────────────────────────────────────────────────────────
 * 同一个 APK 要同时跑在两台设备上：
 *   · HyperOS 3 手机（Android 16 / SDK 36）—— 我们手上**没有**这台机器的 ROM APK；
 *   · HyperOS 4 平板（Android 17 / SDK 37，Xiaomi Pad 8 Pro / 25091RP04C）
 *     —— `_refs/_device/apks/` 有它的真机 APK，本文件里凡标 VERIFIED 的都出自它。
 * 以前每个 hook 都是「列一长串候选类名，谁能解析就挂谁」，那只是**碰巧能容忍**，
 * 并不能说明我们知道自己跑在哪一代 ROM 上。本文件把「我们在哪一代」变成一次显式判定，
 * 并把每一代的目标类 / 方法 / 资源名候选表集中在这里；hook 文件只消费结论，不再各写一份。
 *
 * ── 判定顺序（最具体优先；全部走反射 + runCatching，不引用任何隐藏编译期符号）────
 *   a) ro.mi.os.version.code        —— "4" => HYPEROS_4，"3" => HYPEROS_3，"1"/"2" => HYPEROS_2_OR_OLDER
 *   b) ro.mi.os.version.name        —— 取字符串里第一段数字（"OS4.0" => 4）
 *   c) ro.mi.os.version.incremental —— 同上（"OS4.0.0.37.XPYCNXM" => 4）
 *   d) Build.VERSION.SDK_INT 兜底   —— sdkFallbackKind()，**启发式，不是证据**（日志里 evidence=heur:…）
 *
 * 真机实测（本机就是 HyperOS 4 平板，getprop 原样读出）：
 *   ro.mi.os.version.code=4            ro.mi.os.version.name=OS4.0
 *   ro.mi.os.version.incremental=OS4.0.0.37.XPYCNXM
 *   ro.build.version.sdk=37            ro.product.model=25091RP04C (Xiaomi Pad 8 Pro)
 *   => 每个被注入进程的第一条本模块日志必然是：
 *      HyperPods-Rom: kind=HYPEROS_4 sdk=37 mi.os.version.code=4 name=OS4.0 incremental=OS4.0.0.37.XPYCNXM evidence=prop:ro.mi.os.version.code=4
 *   HyperOS 3 手机上同一条日志应以 kind=HYPEROS_3 … mi.os.version.code=3 name=OS3.0 开头
 *   （该侧数据来自旧 ROM 参考实现，见下）。
 *
 * ── 证据等级（每个候选都标了）────────────────────────────────────────────────
 *   VERIFIED_H4_DEX      —— 已在真机 HyperOS 4 APK（_refs/_device/apks/）的 dex 字符串表里核对到该类存在
 *   VERIFIED_H4_RES      —— 已在真机 HyperOS 4 com.android.settings 的 resources.arsc 里核对到该 entry 名
 *   UNVERIFIED_ON_DEVICE —— 从旧 ROM 参考实现（OppoPods / PuddingPods / HyperPods）抄来；
 *                           本机没有 HyperOS 3 的设备 APK，**未在设备上验证**
 *   HEURISTIC            —— 纯命名 / 结构推断，没有任何 ROM 证据
 *
 * ── 用法（两层）──────────────────────────────────────────────────────────────
 *   第一层（主档）= 检测到的这一代的候选表；第二层（兜底）= 其余各代候选表。
 *   这样「主档优先、旧代码的候选容忍度作为最后兜底」——探测对了就走本代分支，
 *   探测错了也只是退化到以前的行为，绝不会更差。UNKNOWN 时主档就是并集。
 */
package moe.chenxy.hyperpods.hook

import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** ROM 代数。顺序即「从新到旧」，兜底时按此顺序拼接（HyperOS 4 优先，与旧行为一致）。 */
enum class RomKind {
    HYPEROS_4,
    HYPEROS_3,
    HYPEROS_2_OR_OLDER,
    UNKNOWN
}

/** 候选的证据等级（见文件头）。 */
enum class Evidence {
    VERIFIED_H4_DEX,
    VERIFIED_H4_RES,
    UNVERIFIED_ON_DEVICE,
    HEURISTIC;

    /** 是否真的在设备 APK 上核对过（报告里用来区分「实证」与「抄来的」）。 */
    val verifiedOnDevice: Boolean
        get() = this == VERIFIED_H4_DEX || this == VERIFIED_H4_RES

    val label: String
        get() = when (this) {
            VERIFIED_H4_DEX -> "verified(HyperOS4-dex)"
            VERIFIED_H4_RES -> "verified(HyperOS4-resources)"
            UNVERIFIED_ON_DEVICE -> "unverified-on-device(older-refs)"
            HEURISTIC -> "heuristic"
        }
}

/** 一个候选及其证据。 */
data class Candidate(val name: String, val evidence: Evidence)

/** 资源 entry 名匹配规则：默认 startsWith，contains=true 时用 contains。 */
data class AncToken(val token: String, val evidence: Evidence, val contains: Boolean = false)

/**
 * 设置页 MiuiHeadsetFragment 的状态注入 / 刷新入口方法名。
 * 方法名也是「可能随 ROM 改」的目标，因此和类名一样按代数给表（而不是散在 hook 文件里）。
 */
data class FragmentMethods(
    val updateAtUiInfo: Candidate,
    val updateAncUi: Candidate,
    val refreshStatus: Candidate,
    val handleConnectMmaFailed: Candidate,
    val updateAncMode: Candidate,
    val updateAncLevel: Candidate
)

/** 候选组：hook 文件按组取候选，不再自己硬编码类名。 */
enum class CandidateGroup {
    /** com.milink.service 耳机状态策略实现（电量 / 降噪的真实数据层）。 */
    MILINK_STRATEGY,
    /** com.milink.service 里的小米蓝牙 SDK（身份握手 + 降噪命令）。 */
    MILINK_MX_SDK,
    /** 多点（一拖二）查询入口。 */
    MULTIPOINT_QUERY,
    /** com.miui.headset.api.MultipointInfo 数据对象。 */
    MULTIPOINT_INFO,
    /** com.miui.headset.runtime.HeadsetMultipointInfo 数据对象。 */
    HEADSET_MULTIPOINT_INFO,
    /** 多点处理器（主机表 = 是否真的存在多点主机的权威来源）。 */
    MULTIPOINT_PROCESSOR,
    /** 耳机主机扩展（面板取多点信息的地方）。 */
    HOST_EXTENSION,
    /** com.miui.headset.api.HeadsetInfo 面板数据对象。 */
    HEADSET_INFO,
    /** com.xiaomi.bluetooth 进程里 AIDL 服务端实现（含混淆后的 Stub）。 */
    BT_BINDER,
    /** 设置页 Activity。 */
    SETTINGS_ACTIVITY,
    /** 设置页 Activity 的 qigsaw 插件变体。 */
    SETTINGS_ACTIVITY_PLUGIN,
    /** HeadsetIDConstants 静态判定。 */
    SETTINGS_ID_CONSTANTS,
    /** MiuiHeadsetFragment（状态注入）。 */
    SETTINGS_FRAGMENT,
    /** IMiuiHeadsetService$Stub$Proxy（AIDL 客户端代理）。 */
    SETTINGS_PROXY,
    /** tws 电量控件。 */
    SETTINGS_BATTERY_VIEW,
    /** A2dpService -> AdapterService 的字段名（电量写回蓝牙栈）。 */
    ADAPTER_SERVICE_FIELD,
    /** com.xiaomi.bluetooth 的通知 / 超级岛类。 */
    BT_NOTIFICATION,
    /** com.android.bluetooth.ble.app.IMiuiHeadsetService（AIDL 接口本身，作为参数类型用）。 */
    SETTINGS_SERVICE_IFACE,
    /** 原生耳机页里 ANC 控件本身的类（比资源 entry 名更稳的定位锚点）。 */
    NATIVE_ANC_VIEW,
    /** com.android.bluetooth.ble.app.headset.BluetoothHeadsetService（通知构造器宿主）。 */
    BT_HEADSET_SERVICE
}

object RomProfile {
    const val TAG = "HyperPods-Rom"

    private const val PROP_VERSION_CODE = "ro.mi.os.version.code"
    private const val PROP_VERSION_NAME = "ro.mi.os.version.name"
    private const val PROP_VERSION_INCREMENTAL = "ro.mi.os.version.incremental"

    /** 判定结果 + 原始输入（日志 / 报告用）。 */
    data class Detection(
        val kind: RomKind,
        val evidence: String,
        val versionCode: String,
        val versionName: String,
        val incremental: String,
        val sdkInt: Int,
        val deviceModel: String
    ) {
        fun logLine(): String =
            "kind=$kind sdk=$sdkInt mi.os.version.code=$versionCode name=$versionName " +
                "incremental=$incremental evidence=$evidence"
    }

    @Volatile
    private var cached: Detection? = null

    private val loggedOnce = AtomicBoolean(false)

    /** 解析一次并缓存（本进程内不变）。 */
    fun detection(): Detection {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val resolved = resolve()
            cached = resolved
            return resolved
        }
    }

    /** 检测到的代数。 */
    val kind: RomKind
        get() = detection().kind

    /** 是否 HyperOS 4 或更新（HyperOS 4 平板 = Android 17 / SDK 37）。 */
    val isHyperOS4: Boolean
        get() = kind == RomKind.HYPEROS_4

    /** 是否真的是小米 HyperOS（而不是 AOSP / MIUI 12 之类）。 */
    val isHyperOS: Boolean
        get() = kind != RomKind.UNKNOWN

    /**
     * 每个进程打一次：`HyperPods-Rom: kind=… sdk=… mi.os.version.code=… name=…`
     * XposedEntry.onModuleLoaded（每进程一次）调用。
     */
    fun logOnce(processName: String? = null) {
        if (!loggedOnce.compareAndSet(false, true)) return
        val d = detection()
        val where = if (processName.isNullOrEmpty()) "" else " process=$processName"
        Log.i(TAG, d.logLine() + where)
        when {
            d.kind == RomKind.UNKNOWN ->
                Log.w(TAG, "ROM generation UNKNOWN -> using the union of all generations' candidates")
            !d.evidence.startsWith("prop:") ->
                Log.w(TAG, "ROM generation came from the SDK heuristic, NOT from a HyperOS property: ${d.evidence}")
            else -> Unit
        }
    }

    /** 一行摘要（hook 初始化日志里带上，省得再去翻 RomProfile 的日志行）。 */
    fun summary(): String = detection().logLine()

    // ── 判定 ─────────────────────────────────────────────────────────────────

    private fun resolve(): Detection {
        val code = systemProperty(PROP_VERSION_CODE)
        val name = systemProperty(PROP_VERSION_NAME)
        val incremental = systemProperty(PROP_VERSION_INCREMENTAL)
        val sdk = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)
        val model = runCatching { Build.MODEL }.getOrDefault("")

        // a) ro.mi.os.version.code —— HyperOS 的整数代号，最权威。
        generationFromProp(code)?.let { n ->
            return Detection(kindFromNumber(n), "prop:$PROP_VERSION_CODE=$code", code, name, incremental, sdk, model)
        }
        // b) ro.mi.os.version.name（"OS4.0" / "OS3.0" / "OS2.0"）。
        generationFromProp(name)?.let { n ->
            return Detection(kindFromNumber(n), "prop:$PROP_VERSION_NAME=$name", code, name, incremental, sdk, model)
        }
        // c) ro.mi.os.version.incremental（"OS4.0.0.37.XPYCNXM"）。
        generationFromProp(incremental)?.let { n ->
            return Detection(
                kindFromNumber(n), "prop:$PROP_VERSION_INCREMENTAL=$incremental", code, name, incremental, sdk, model
            )
        }
        // d) SDK 兜底 —— 启发式，不是证据（日志里 evidence 前缀是 heur:）。
        return Detection(sdkFallbackKind(sdk), "heur:sdk=$sdk (NOT proof)", code, name, incremental, sdk, model)
    }

    /**
     * 从属性值里取「代数数字」。只有 1..20 才算代号；其余（空、非数字、明显不是代号的数字）返回 null，
     * 让调用方继续尝试下一个信号。返回值 non-null 即「这条信号说了话」，不会再退回 SDK 启发式。
     */
    private fun generationFromProp(value: String): Int? {
        if (value.isNullOrEmpty()) return null
        val leading = value.trim().takeWhile { it.isDigit() }.toIntOrNull()
            ?: Regex("(\\d+)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return leading?.takeIf { it in 1..20 }
    }

    private fun kindFromNumber(n: Int): RomKind = when (n) {
        4 -> RomKind.HYPEROS_4
        3 -> RomKind.HYPEROS_3
        1, 2 -> RomKind.HYPEROS_2_OR_OLDER
        else -> RomKind.UNKNOWN // 5..20：本模块不认识的代数，标记 UNKNOWN 而不是硬猜
    }

    /**
     * SDK 兜底映射（**启发式，不是证据**）：
     *   SDK >= 36 -> HYPEROS_4   （HyperOS 4 平板实测 SDK 37；
     *                             注意 HyperOS 3 的 Android 16 也是 SDK 36，所以这里只能表示「至少 4」）
     *   SDK 34/35 -> HYPEROS_3   （Android 14/15，HyperOS 3 早期机型可能落在这里）
     *   其它      -> UNKNOWN
     * 之所以敢这么兜：这台 APK 的两个目标设备是 HyperOS 3 与 4，落到 34/35/36+ 之外基本就不是本模块的 ROM。
     */
    private fun sdkFallbackKind(sdk: Int): RomKind = when {
        sdk >= 36 -> RomKind.HYPEROS_4
        sdk == 34 || sdk == 35 -> RomKind.HYPEROS_3
        else -> RomKind.UNKNOWN
    }

    private fun systemProperty(name: String): String =
        runCatching { SystemApisUtils.systemProperty(name).trim() }.getOrDefault("")

    // ── 候选表 ───────────────────────────────────────────────────────────────
    //
    // 每组给三份（H4 / H3 / H2-or-older），forKind() 决定主档与兜底顺序。

    // com.milink.service 耳机状态策略实现。
    // H4（已核对）：运行时被重写成「策略模式」，真实数据层是三个 Strategy 实现。
    private val STRATEGY_H4 = listOf(
        Candidate("com.miui.headset.runtime.model.XiaomiHeadsetStrategy", Evidence.VERIFIED_H4_DEX),
        Candidate("com.miui.headset.runtime.model.ThirdPartyHeadsetStrategy", Evidence.VERIFIED_H4_DEX),
        Candidate("com.miui.headset.runtime.model.AirPodsHeadsetStrategy", Evidence.VERIFIED_H4_DEX)
    )
    // H3（未在设备上验证）：OppoPods / PuddingPods 这两个跑在旧 HyperOS 上的模块挂的是这一层。
    // 注意这三个类在 HyperOS 4 的 APK 里**依然存在**，只是职责被改（ProfileContext 变成网络 profile 类），
    // 因此它们在 H4 上被放在兜底档，不会抢在 Strategy 之前生效。
    private val STRATEGY_H3 = listOf(
        Candidate("com.miui.headset.runtime.AncBatteryController", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.miui.headset.runtime.ProfileContext", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.miui.headset.runtime.AncBatteryModel", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val STRATEGY_H2 = STRATEGY_H3

    // 小米蓝牙 SDK：两代同名。
    private val MX_SDK_H4 = listOf(
        Candidate("com.xiaomi.mxbluetoothsdk.service.MxBluetoothService", Evidence.VERIFIED_H4_DEX),
        Candidate("com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager", Evidence.VERIFIED_H4_DEX)
    )
    private val MX_SDK_H3 = listOf(
        Candidate("com.xiaomi.mxbluetoothsdk.service.MxBluetoothService", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val MX_SDK_H2 = MX_SDK_H3

    // 多点查询入口（全部声明 getMultipointInfo(String): MultipointInfo）。
    private val MULTIPOINT_QUERY_H4 = listOf(
        Candidate("com.miui.headset.api.Query", Evidence.VERIFIED_H4_DEX),
        Candidate("com.miui.headset.runtime.QueryLocal", Evidence.VERIFIED_H4_DEX),
        Candidate("com.miui.headset.runtime.QueryServer", Evidence.VERIFIED_H4_DEX),
        Candidate("com.miui.circulate.api.protocol.headset.HeadsetServiceController", Evidence.VERIFIED_H4_DEX),
        Candidate("com.miui.headset.api.HeadsetClient\$queryProxyAdapter\$1", Evidence.VERIFIED_H4_DEX)
    )
    // H3（未在设备上验证）：旧 MiLink（OppoPods 时代）的多点/查询走 circulate 那条链，所以把它排前面。
    private val MULTIPOINT_QUERY_H3 = listOf(
        Candidate("com.miui.circulate.api.protocol.headset.HeadsetServiceController", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.miui.headset.api.Query", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.miui.headset.runtime.QueryLocal", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.miui.headset.runtime.QueryServer", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.miui.headset.api.HeadsetClient\$queryProxyAdapter\$1", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val MULTIPOINT_QUERY_H2 = MULTIPOINT_QUERY_H3

    private val MULTIPOINT_INFO_H4 = listOf(
        Candidate("com.miui.headset.api.MultipointInfo", Evidence.VERIFIED_H4_DEX)
    )
    private val MULTIPOINT_INFO_H3 = listOf(
        Candidate("com.miui.headset.api.MultipointInfo", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val MULTIPOINT_INFO_H2 = MULTIPOINT_INFO_H3

    private val HEADSET_MULTIPOINT_INFO_H4 = listOf(
        Candidate("com.miui.headset.runtime.HeadsetMultipointInfo", Evidence.VERIFIED_H4_DEX)
    )
    private val HEADSET_MULTIPOINT_INFO_H3 = listOf(
        Candidate("com.miui.headset.runtime.HeadsetMultipointInfo", Evidence.UNVERIFIED_ON_DEVICE),
        // 旧参考实现走 circulate 的 HeadsetDeviceInfo 描述设备；只在 HostExtension 那条路走不通时才试。
        Candidate("com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo", Evidence.HEURISTIC)
    )
    private val HEADSET_MULTIPOINT_INFO_H2 = HEADSET_MULTIPOINT_INFO_H3

    private val MULTIPOINT_PROCESSOR_H4 = listOf(
        Candidate("com.miui.headset.runtime.MultipointProcessor", Evidence.VERIFIED_H4_DEX)
    )
    private val MULTIPOINT_PROCESSOR_H3 = listOf(
        Candidate("com.miui.headset.runtime.MultipointProcessor", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val MULTIPOINT_PROCESSOR_H2 = MULTIPOINT_PROCESSOR_H3

    private val HOST_EXTENSION_H4 = listOf(
        Candidate("com.miui.headset.runtime.HeadsetHostExtension", Evidence.VERIFIED_H4_DEX)
    )
    private val HOST_EXTENSION_H3 = listOf(
        Candidate("com.miui.headset.runtime.HeadsetHostExtension", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val HOST_EXTENSION_H2 = HOST_EXTENSION_H3

    private val HEADSET_INFO_H4 = listOf(
        Candidate("com.miui.headset.api.HeadsetInfo", Evidence.VERIFIED_H4_DEX)
    )
    // H3：PuddingPods（旧 HyperOS 上的另一个模块）也挂 com.miui.headset.api.HeadsetInfo，但本机没验证过。
    private val HEADSET_INFO_H3 = listOf(
        Candidate("com.miui.headset.api.HeadsetInfo", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val HEADSET_INFO_H2 = HEADSET_INFO_H3

    // com.xiaomi.bluetooth 的 AIDL 服务端实现。
    // H4（已核对）：HeadsetBinder 继承混淆后的 Stub q$a（本 ROM 的 R8 名，属于本 build 特有）。
    private val BT_BINDER_H4 = listOf(
        Candidate("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService\$HeadsetBinder", Evidence.VERIFIED_H4_DEX),
        Candidate("com.android.bluetooth.ble.app.q\$a", Evidence.VERIFIED_H4_DEX)
    )
    // H3（未在设备上验证）：PuddingPods 这个旧 ROM 模块挂的名字。
    private val BT_BINDER_H3 = listOf(
        Candidate("com.android.bluetooth.ble.app.headset.BinderC6776v", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.android.bluetooth.ble.app.headset.v", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub", Evidence.HEURISTIC),
        Candidate("com.android.bluetooth.ble.app.q\$a", Evidence.HEURISTIC)
    )
    private val BT_BINDER_H2 = BT_BINDER_H3

    // 设置页：两代同名（H4 已核对；H3 由 OppoPods / PuddingPods 佐证，未在设备上验证）。
    private val SETTINGS_ACTIVITY_H4 = listOf(
        Candidate("com.android.settings.bluetooth.MiuiHeadsetActivity", Evidence.VERIFIED_H4_DEX)
    )
    private val SETTINGS_ACTIVITY_H3 = listOf(
        Candidate("com.android.settings.bluetooth.MiuiHeadsetActivity", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val SETTINGS_ACTIVITY_H2 = SETTINGS_ACTIVITY_H3

    private val SETTINGS_ACTIVITY_PLUGIN_H4 = listOf(
        Candidate("com.android.settings.bluetooth.MiuiHeadsetActivityPlugin", Evidence.VERIFIED_H4_DEX)
    )
    private val SETTINGS_ACTIVITY_PLUGIN_H3 = listOf(
        Candidate("com.android.settings.bluetooth.MiuiHeadsetActivityPlugin", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val SETTINGS_ACTIVITY_PLUGIN_H2 = SETTINGS_ACTIVITY_PLUGIN_H3

    private val SETTINGS_ID_CONSTANTS_H4 = listOf(
        Candidate("com.android.settings.bluetooth.HeadsetIDConstants", Evidence.VERIFIED_H4_DEX)
    )
    private val SETTINGS_ID_CONSTANTS_H3 = listOf(
        Candidate("com.android.settings.bluetooth.HeadsetIDConstants", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val SETTINGS_ID_CONSTANTS_H2 = SETTINGS_ID_CONSTANTS_H3

    private val SETTINGS_FRAGMENT_H4 = listOf(
        Candidate("com.android.settings.bluetooth.MiuiHeadsetFragment", Evidence.VERIFIED_H4_DEX)
    )
    private val SETTINGS_FRAGMENT_H3 = listOf(
        Candidate("com.android.settings.bluetooth.MiuiHeadsetFragment", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val SETTINGS_FRAGMENT_H2 = SETTINGS_FRAGMENT_H3

    private val SETTINGS_PROXY_H4 = listOf(
        Candidate("com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy", Evidence.VERIFIED_H4_DEX)
    )
    private val SETTINGS_PROXY_H3 = listOf(
        Candidate("com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val SETTINGS_PROXY_H2 = SETTINGS_PROXY_H3

    private val SETTINGS_BATTERY_VIEW_H4 = listOf(
        Candidate("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", Evidence.VERIFIED_H4_DEX)
    )
    private val SETTINGS_BATTERY_VIEW_H3 = listOf(
        Candidate("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", Evidence.UNVERIFIED_ON_DEVICE),
        // 旧代码 TODO 里留的另一个候选；HyperOS 4 的 APK 里**不存在**（只有 tws.* 那一份），
        // 没有任何 ROM 证据，标 HEURISTIC 放在 H3 兜底。
        Candidate("com.android.settings.bluetooth.MiuiHeadsetBattery", Evidence.HEURISTIC)
    )
    private val SETTINGS_BATTERY_VIEW_H2 = SETTINGS_BATTERY_VIEW_H3

    // A2dpService -> AdapterService 的字段名。
    // H4（已核对 com.android.bluetooth.apk）：ProfileService 上叫 adapterService（不是 mAdapterService）。
    private val ADAPTER_FIELD_H4 = listOf(
        Candidate("adapterService", Evidence.VERIFIED_H4_DEX),
        Candidate("mAdapterService", Evidence.HEURISTIC)
    )
    // H3：参考实现用的是 mAdapterService，所以旧代排在前面；两个都保留，谁在就用谁。
    private val ADAPTER_FIELD_H3 = listOf(
        Candidate("mAdapterService", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("adapterService", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val ADAPTER_FIELD_H2 = ADAPTER_FIELD_H3

    // com.xiaomi.bluetooth 通知类（两代同名：H4 已核对，PuddingPods 在旧 ROM 上也挂它）。
    private val NOTIFICATION_H4 = listOf(
        Candidate("com.android.bluetooth.ble.app.MiuiBluetoothNotification", Evidence.VERIFIED_H4_DEX)
    )
    private val NOTIFICATION_H3 = listOf(
        Candidate("com.android.bluetooth.ble.app.MiuiBluetoothNotification", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val NOTIFICATION_H2 = NOTIFICATION_H3

    // com.android.bluetooth.ble.app.IMiuiHeadsetService（设置页里作为 isBleMmaConnect 的参数类型）。
    private val SERVICE_IFACE_H4 = listOf(
        Candidate("com.android.bluetooth.ble.app.IMiuiHeadsetService", Evidence.VERIFIED_H4_DEX)
    )
    private val SERVICE_IFACE_H3 = listOf(
        Candidate("com.android.bluetooth.ble.app.IMiuiHeadsetService", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val SERVICE_IFACE_H2 = SERVICE_IFACE_H3

    // 设置页 MiuiHeadsetFragment 的方法名。
    // H4：六个名字都在真机 com.android.settings.apk 的 dex 里核对到（classes2.dex）。
    private val FRAGMENT_METHODS_H4 = FragmentMethods(
        Candidate("updateAtUiInfo", Evidence.VERIFIED_H4_DEX),
        Candidate("updateAncUi", Evidence.VERIFIED_H4_DEX),
        Candidate("refreshStatus", Evidence.VERIFIED_H4_DEX),
        Candidate("handleConnectMmaFailed", Evidence.VERIFIED_H4_DEX),
        Candidate("updateAncMode", Evidence.VERIFIED_H4_DEX),
        Candidate("updateAncLevel", Evidence.VERIFIED_H4_DEX)
    )
    // H3：同名；旧参考实现用的也是这几个名字，但没在 HyperOS 3 设备上验证过。
    private val FRAGMENT_METHODS_H3 = FragmentMethods(
        Candidate("updateAtUiInfo", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("updateAncUi", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("refreshStatus", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("handleConnectMmaFailed", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("updateAncMode", Evidence.UNVERIFIED_ON_DEVICE),
        Candidate("updateAncLevel", Evidence.UNVERIFIED_ON_DEVICE)
    )

    // com.xiaomi.bluetooth 里的耳机服务类（两代同名：H4 已核对，PuddingPods 在旧 ROM 上也引用它）。
    private val BT_SERVICE_H4 = listOf(
        Candidate("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService", Evidence.VERIFIED_H4_DEX)
    )
    private val BT_SERVICE_H3 = listOf(
        Candidate("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val BT_SERVICE_H2 = BT_SERVICE_H3

    // 原生 ANC 控件本身的类。
    // H4（已核对）：com.android.settings.bluetooth.MiuiHeadsetAncAdjustView
    //   —— 它内部有 AncLevelChangeListener / LabeledSeekBarExploreByTouchHelper，
    //      正是解码「滑杆 max∈2..5」那条结构特征对应的控件，按类名定位比按滑杆可靠。
    private val ANC_VIEW_H4 = listOf(
        Candidate("com.android.settings.bluetooth.MiuiHeadsetAncAdjustView", Evidence.VERIFIED_H4_DEX)
    )
    private val ANC_VIEW_H3 = listOf(
        Candidate("com.android.settings.bluetooth.MiuiHeadsetAncAdjustView", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val ANC_VIEW_H2 = ANC_VIEW_H3

    /**
     * (主档, 兜底档)：主档 = 检测到的代数，兜底 = 其余代数（顺序 H4 → H3 → H2）。
     * UNKNOWN 时主档就是并集、兜底为空。对 Candidate 与 AncToken 通用。
     */
    private fun <T> tiers(h4: List<T>, h3: List<T>, h2: List<T>): Pair<List<T>, List<T>> = when (kind) {
        RomKind.HYPEROS_4 -> h4 to (h3 + h2)
        RomKind.HYPEROS_3 -> h3 to (h4 + h2)
        RomKind.HYPEROS_2_OR_OLDER -> h2 to (h4 + h3)
        RomKind.UNKNOWN -> (h4 + h3 + h2) to emptyList<T>()
    }

    /** 主档优先、兜底随后（按类名去重）。 */
    private fun forKind(h4: List<Candidate>, h3: List<Candidate>, h2: List<Candidate>): List<Candidate> {
        val (primary, fallback) = tiers(h4, h3, h2)
        return (primary + fallback).distinctBy { it.name }
    }

    /** 某一组的候选（主档优先，含证据）。 */
    fun candidates(group: CandidateGroup): List<Candidate> = when (group) {
        CandidateGroup.MILINK_STRATEGY -> forKind(STRATEGY_H4, STRATEGY_H3, STRATEGY_H2)
        CandidateGroup.MILINK_MX_SDK -> forKind(MX_SDK_H4, MX_SDK_H3, MX_SDK_H2)
        CandidateGroup.MULTIPOINT_QUERY -> forKind(MULTIPOINT_QUERY_H4, MULTIPOINT_QUERY_H3, MULTIPOINT_QUERY_H2)
        CandidateGroup.MULTIPOINT_INFO -> forKind(MULTIPOINT_INFO_H4, MULTIPOINT_INFO_H3, MULTIPOINT_INFO_H2)
        CandidateGroup.HEADSET_MULTIPOINT_INFO ->
            forKind(HEADSET_MULTIPOINT_INFO_H4, HEADSET_MULTIPOINT_INFO_H3, HEADSET_MULTIPOINT_INFO_H2)
        CandidateGroup.MULTIPOINT_PROCESSOR ->
            forKind(MULTIPOINT_PROCESSOR_H4, MULTIPOINT_PROCESSOR_H3, MULTIPOINT_PROCESSOR_H2)
        CandidateGroup.HOST_EXTENSION -> forKind(HOST_EXTENSION_H4, HOST_EXTENSION_H3, HOST_EXTENSION_H2)
        CandidateGroup.HEADSET_INFO -> forKind(HEADSET_INFO_H4, HEADSET_INFO_H3, HEADSET_INFO_H2)
        CandidateGroup.BT_BINDER -> forKind(BT_BINDER_H4, BT_BINDER_H3, BT_BINDER_H2)
        CandidateGroup.SETTINGS_ACTIVITY -> forKind(SETTINGS_ACTIVITY_H4, SETTINGS_ACTIVITY_H3, SETTINGS_ACTIVITY_H2)
        CandidateGroup.SETTINGS_ACTIVITY_PLUGIN ->
            forKind(SETTINGS_ACTIVITY_PLUGIN_H4, SETTINGS_ACTIVITY_PLUGIN_H3, SETTINGS_ACTIVITY_PLUGIN_H2)
        CandidateGroup.SETTINGS_ID_CONSTANTS ->
            forKind(SETTINGS_ID_CONSTANTS_H4, SETTINGS_ID_CONSTANTS_H3, SETTINGS_ID_CONSTANTS_H2)
        CandidateGroup.SETTINGS_FRAGMENT -> forKind(SETTINGS_FRAGMENT_H4, SETTINGS_FRAGMENT_H3, SETTINGS_FRAGMENT_H2)
        CandidateGroup.SETTINGS_PROXY -> forKind(SETTINGS_PROXY_H4, SETTINGS_PROXY_H3, SETTINGS_PROXY_H2)
        CandidateGroup.SETTINGS_BATTERY_VIEW ->
            forKind(SETTINGS_BATTERY_VIEW_H4, SETTINGS_BATTERY_VIEW_H3, SETTINGS_BATTERY_VIEW_H2)
        CandidateGroup.ADAPTER_SERVICE_FIELD -> forKind(ADAPTER_FIELD_H4, ADAPTER_FIELD_H3, ADAPTER_FIELD_H2)
        CandidateGroup.BT_NOTIFICATION -> forKind(NOTIFICATION_H4, NOTIFICATION_H3, NOTIFICATION_H2)
        CandidateGroup.SETTINGS_SERVICE_IFACE -> forKind(SERVICE_IFACE_H4, SERVICE_IFACE_H3, SERVICE_IFACE_H2)
        CandidateGroup.NATIVE_ANC_VIEW -> forKind(ANC_VIEW_H4, ANC_VIEW_H3, ANC_VIEW_H2)
        CandidateGroup.BT_HEADSET_SERVICE -> forKind(BT_SERVICE_H4, BT_SERVICE_H3, BT_SERVICE_H2)
    }

    /** 某一组的候选类名（主档优先）。 */
    fun names(group: CandidateGroup): List<String> = candidates(group).map { it.name }

    // 便捷访问器（hook 文件里读起来更清楚）。
    val headsetStrategyClasses: List<String> get() = names(CandidateGroup.MILINK_STRATEGY)
    val mxBluetoothClasses: List<String> get() = names(CandidateGroup.MILINK_MX_SDK)
    val multipointQueryClasses: List<String> get() = names(CandidateGroup.MULTIPOINT_QUERY)
    val multipointInfoClasses: List<String> get() = names(CandidateGroup.MULTIPOINT_INFO)
    val headsetMultipointInfoClasses: List<String> get() = names(CandidateGroup.HEADSET_MULTIPOINT_INFO)
    val multipointProcessorClasses: List<String> get() = names(CandidateGroup.MULTIPOINT_PROCESSOR)
    val hostExtensionClasses: List<String> get() = names(CandidateGroup.HOST_EXTENSION)
    val headsetInfoClasses: List<String> get() = names(CandidateGroup.HEADSET_INFO)
    val bluetoothBinderClasses: List<String> get() = names(CandidateGroup.BT_BINDER)
    val settingsActivityClasses: List<String> get() = names(CandidateGroup.SETTINGS_ACTIVITY)
    val settingsActivityPluginClasses: List<String> get() = names(CandidateGroup.SETTINGS_ACTIVITY_PLUGIN)
    val settingsIdConstantsClasses: List<String> get() = names(CandidateGroup.SETTINGS_ID_CONSTANTS)
    val settingsFragmentClasses: List<String> get() = names(CandidateGroup.SETTINGS_FRAGMENT)
    val settingsProxyClasses: List<String> get() = names(CandidateGroup.SETTINGS_PROXY)
    val settingsBatteryViewClasses: List<String> get() = names(CandidateGroup.SETTINGS_BATTERY_VIEW)
    val adapterServiceFields: List<String> get() = names(CandidateGroup.ADAPTER_SERVICE_FIELD)
    val bluetoothNotificationClasses: List<String> get() = names(CandidateGroup.BT_NOTIFICATION)
    val headsetServiceIfaceClasses: List<String> get() = names(CandidateGroup.SETTINGS_SERVICE_IFACE)
    val nativeAncViewClasses: List<String> get() = names(CandidateGroup.NATIVE_ANC_VIEW)
    val bluetoothHeadsetServiceClasses: List<String> get() = names(CandidateGroup.BT_HEADSET_SERVICE)

    /**
     * 设置页 MiuiHeadsetFragment 的方法名：HyperOS 3 / 更旧用 H3 表（unverified-on-device），
     * HyperOS 4 与 UNKNOWN 用 H4 表（六个名字都在真机 APK 的 dex 里核对过）。
     */
    val settingsFragmentMethods: FragmentMethods
        get() = when (kind) {
            RomKind.HYPEROS_3, RomKind.HYPEROS_2_OR_OLDER -> FRAGMENT_METHODS_H3
            else -> FRAGMENT_METHODS_H4
        }

    // ── 原生耳机页 ANC 控件的资源 entry 名候选 ────────────────────────────────
    //
    // 本模块不硬编码 view id（release 会重命名 entry），而是拿运行时的
    // View.resources.getResourceEntryName(view.id) 去匹配名字族。
    // H4 实测（com.android.settings 的 resources.arsc 里逐条抓到）：
    //   headset_anc_layout_width/hight/marginTop、headset_anc_level_layout_*、
    //   headset_anc_level_Text_*、headset_anc_mode_text_size、headset_anc_text_*、
    //   headset_anc_image_high、headset_anc_margintop
    //   miheadset_ancClosed/ancDepth/ancEquilibrium/ancHigh/ancLow/ancMedium/ancMild/anc_indicate/anc_manual
    // => 视图 id 极可能就是 headset_anc_layout / headset_anc_level_layout / headset_anc_level_Text 等。
    private val ANC_H4 = listOf(
        AncToken("headset_anc", Evidence.VERIFIED_H4_RES),
        AncToken("miheadset_anc", Evidence.VERIFIED_H4_RES),
        AncToken("anc_layout", Evidence.VERIFIED_H4_RES, contains = true),
        AncToken("anc_level", Evidence.VERIFIED_H4_RES, contains = true),
        AncToken("anc_mode", Evidence.VERIFIED_H4_RES, contains = true),
        AncToken("anc_seek", Evidence.HEURISTIC, contains = true)
    )
    // H3（未在设备上验证）：同样的 MIUI 命名族（旧参考实现也这么找），
    // 外加旧 MiLink 面板的真实 entry 名（OppoPods 在旧 ROM 上用的 audio_effect_view / mi_audio_ringing_view）。
    private val ANC_H3 = listOf(
        AncToken("headset_anc", Evidence.UNVERIFIED_ON_DEVICE),
        AncToken("miheadset_anc", Evidence.UNVERIFIED_ON_DEVICE),
        AncToken("anc_layout", Evidence.UNVERIFIED_ON_DEVICE, contains = true),
        AncToken("anc_level", Evidence.UNVERIFIED_ON_DEVICE, contains = true),
        AncToken("anc_mode", Evidence.UNVERIFIED_ON_DEVICE, contains = true),
        AncToken("audio_effect_view", Evidence.UNVERIFIED_ON_DEVICE),
        AncToken("mi_audio_ringing_view", Evidence.UNVERIFIED_ON_DEVICE)
    )
    private val ANC_H2 = ANC_H3

    /** 负向排除：含这些子串的 entry 名一律不算（cancel / balance / advanced 等「含 anc 但不是降噪」）。 */
    private val ANC_NEGATIVE = listOf("cancel", "balance", "advanced", "enhance", "financ")

    /**
     * 返回 (主档, 兜底档)：主档 = 检测到的代数的 entry 名候选，兜底档 = 其余代数。
     * 匹配时先只用主档；一个都没命中时再用兜底档（保持旧代码的候选容忍度作为最后兜底）。
     */
    fun nativeAncTokens(): Pair<List<AncToken>, List<AncToken>> {
        val (rawPrimary, rawFallback) = tiers(ANC_H4, ANC_H3, ANC_H2)
        val primary = rawPrimary.distinctBy { it.token }
        val fallback = rawFallback.distinctBy { it.token }
            .filter { fb -> primary.none { it.token == fb.token } }
        return primary to fallback
    }

    /** entry 名是否命中给定 token 组（含负向排除）。 */
    fun matchesNativeAncEntry(name: String, tokens: List<AncToken>): Boolean {
        if (name.isEmpty() || tokens.isEmpty()) return false
        val n = name.lowercase()
        if (ANC_NEGATIVE.any { n.contains(it) }) return false
        return tokens.any { token ->
            val t = token.token.lowercase()
            if (token.contains) n.contains(t) else n.startsWith(t)
        }
    }

    // ── 报告 ─────────────────────────────────────────────────────────────────

    /** 证据清单（排障 / 交接报告用）：当前代数选中的每个候选 + 证据等级。 */
    fun report(): String {
        val sb = StringBuilder()
        sb.append("ROM ").append(summary()).append('\n')
        sb.append("device=").append(detection().deviceModel).append('\n')
        CandidateGroup.entries.forEach { group ->
            sb.append("  ").append(group.name).append(": ")
            sb.append(candidates(group).joinToString(", ") { it.name + "[" + it.evidence.label + "]" })
            sb.append('\n')
        }
        val fm = settingsFragmentMethods
        sb.append("  SETTINGS_FRAGMENT_METHODS: ")
        sb.append(
            listOf(fm.updateAtUiInfo, fm.updateAncUi, fm.refreshStatus, fm.handleConnectMmaFailed,
                fm.updateAncMode, fm.updateAncLevel)
                .joinToString(", ") { it.name + "[" + it.evidence.label + "]" }
        )
        sb.append('\n')
        val (primary, fallback) = nativeAncTokens()
        sb.append("  NATIVE_ANC_ENTRY(primary): ")
        sb.append(primary.joinToString(", ") { it.token + (if (it.contains) "*" else "") + "[" + it.evidence.label + "]" })
        sb.append('\n')
        if (fallback.isNotEmpty()) {
            sb.append("  NATIVE_ANC_ENTRY(fallback): ")
            sb.append(fallback.joinToString(", ") { it.token + (if (it.contains) "*" else "") + "[" + it.evidence.label + "]" })
            sb.append('\n')
        }
        return sb.toString()
    }
}
