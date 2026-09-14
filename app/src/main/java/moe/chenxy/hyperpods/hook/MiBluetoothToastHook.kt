/*
 * HyperPods for Moondrop — com.xiaomi.bluetooth 进程：耳机电量通知 / 通知栏入口
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 机制（对照 HyperPods `hook/MiBluetoothToastHook.kt`、OppoPods 同名文件）：
 *   · hook com.android.bluetooth.ble.app.MiuiBluetoothNotification 的构造函数
 *     （A17 上同时存在 (Context, Looper) 与 (Looper, BluetoothHeadsetService) 两种形态，
 *      由 mibt_memory_trim 决定用哪个，所以按「完整签名」逐个尝试，最后再按参数个数 2 兜底）；
 *   · 从实例字段 mContext（或构造参数 / ActivityThread.currentApplication()）偷一个进程内 Context；
 *   · 用该 Context 注册 SEND_STRONG_TOAST / UPDATE_PODS_NOTIFICATION / CANCEL_PODS_NOTIFICATION 接收器；
 *   · 通知：id = 10003、tag = "BTHeadset$address"、channel = "hyperpods_moondrop_bt_headset"
 *     （IMPORTANCE_MIN），点击进入本模块 UI；取消走 cancelAsUser(tag, 10003, UserHandle.ALL)。
 *   · 文案尽量复用小米蓝牙自己的本地化字符串（miheadset_notification_Box / LeftEar / RightEar /
 *     Disconnect），取不到就退化为中文默认值。
 *
 * ⚠ 与原 HyperPods 的差异：**完全丢弃 AirPods 的 mp4/强提示视频**（原实现内嵌 base64 mp4 并用
 *   FileProvider 交给 SystemUI 播放）。本模块不携带任何 .mp4 / base64 资产。
 *
 * ── 通知的三档（设置页「通知」那一组，见 ui/pages/SettingsPage.kt）────────────────
 *   ① 通知栏显示（HyperPodsPrefsKey.SHOW_NOTIFICATION，默认开）
 *        **原生**状态栏通知：IMPORTANCE_LOW 通道 + Notification.Builder，
 *      任何 ROM（AOSP / 其它厂商 / HyperOS）都生效。这是本文件的基础形态。
 *   ② 焦点显示（SHOW_FOCUS_ISLAND，默认开）—— **仅 HyperOS**：
 *      在上面那条通知上再挂 `miui.focus.param` / `miui.focus.pics`，让它变成焦点通知。
 *   ③ 超级岛提示（SHOW_STRONG_TOAST，默认开）—— **仅 HyperOS**：
 *      焦点通知的 JSON 里再加 island 那一半字段（islandProperty / islandTimeout /
 *      param_island / bigIslandArea / smallIslandArea / textInfo）。
 * ②③ 都受 hook/RomProfile.kt 的 isXiaomiRom（严格判定：只看 HyperOS 自报版本属性）门控，
 * 非 HyperOS 上即使 prefs 是 true 也不会写任何 miui.\* extra —— UI 那边只是把开关置灰，
 * 真正兜底的是这里。
 *
 * 另外，本进程还是**小米耳机 AIDL 服务的服务端**：
 *   com.android.bluetooth.ble.app.headset.BluetoothHeadsetService$HeadsetBinder
 *     extends com.android.bluetooth.ble.app.q$a   （混淆后的 IMiuiHeadsetService$Stub）
 * 系统设置页通过它取耳机能力/电量档位，所以这里同时安装 HeadsetServiceBinderHook
 * （见该文件头：为什么必须挂服务端、而不是只挂 settings 侧的 $Stub$Proxy）。
 */
package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Looper
import android.util.Log
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {
    private const val TAG = "HyperPods-MiBtToast"
    private const val PKG_APP = BuildConfig.APPLICATION_ID
    /**
     * 两个系统类名都由 RomProfile 按检测到的 ROM 代数解析（本代主档优先，其余代数兜底）：
     *   clsBtService    = BT_HEADSET_SERVICE 组（通知构造器的宿主服务；两代同名，H4 已核对）
     *   clsNotification = BT_NOTIFICATION    组（通知 / 超级岛类；两代同名，H4 已核对）
     * onHook() 里解析；解析不到留空串，下面的 find* 会抛异常并被 runCatching 吞掉（只 skip 该条）。
     */
    private var clsBtService: String = ""
    private var clsNotification: String = ""
    private const val VENDOR_PKG = "com.xiaomi.bluetooth"

    private const val NOTIFICATION_ID = 10003
    private const val NOTIFICATION_TAG_PREFIX = "BTHeadset"
    private const val CHANNEL_ID = "hyperpods_moondrop_bt_headset"
    private const val CHANNEL_NAME = "HyperPods"

    // 厂商本地化资源名（缺失即降级）
    private const val RES_BOX = "miheadset_notification_Box"
    private const val RES_LEFT = "miheadset_notification_LeftEar"
    private const val RES_RIGHT = "miheadset_notification_RightEar"
    private const val RES_DISCONNECT = "miheadset_notification_Disconnect"
    private const val RES_ICON = "ic_headset_notification"
    private const val RES_ACCENT_COLOR = "system_notification_accent_color"

    private const val FALLBACK_BOX = "充电盒"
    private const val FALLBACK_LEFT = "左耳"
    private const val FALLBACK_RIGHT = "右耳"
    private const val FALLBACK_DISCONNECT = "断开连接"

    // 焦点通知按钮文案：与上面的 RES_ 开头常量 + FALLBACK_ 开头默认值是同一套做法（先查厂商资源，取不到用默认值）。
    // 注意 miheadset_key_config_* 是 com.android.settings 的资源，本进程（com.xiaomi.bluetooth）
    // 查不到时会走 FALLBACK，这是预期行为，不是 bug。
    private const val RES_ANC_CYCLE = "miheadset_key_config_noise_control"
    private const val FALLBACK_ANC_CYCLE = "切换降噪"

    // ── 小米焦点通知 / 超级岛的 extra 键（键名与常量出处见 focusExtras 的 KDoc）──
    private const val EXTRA_FOCUS_PARAM = "miui.focus.param"
    private const val EXTRA_FOCUS_PICS = "miui.focus.pics"
    private const val EXTRA_MIUI_SHOW_ACTION = "miui.showAction"
    private const val EXTRA_MIUI_APP_ICON = "miui.appIcon"

    /**
     * `miui.focus.param` 外层 JSON 的 `type`：焦点通知模板工厂（`xzakota` 那套焦点通知库）。
     * 这**不是**协议字段，而是 HyperOS 焦点通知渲染器认的模板标识 —— 值来自 dev 分支
     * （同一作者的另一个仓库 `huimeyc/HyperPods`）在 **HyperOS 4 真机**上 dumpsys 出来的原文。
     */
    private const val FOCUS_TEMPLATE_V3 =
        "com.xzakota.hyper.notification.focus.FocusNotification.FocusTemplateFactory.V3"

    /** 焦点通知/超级岛用的图标 key（真机原文 `"pic":"key_headset"`）。 */
    private const val FOCUS_ICON_KEY = "key_headset"

    /** `textButton` 的动作 key：由系统侧认，不是我们自己的 Intent action。 */
    private const val FOCUS_BUTTON_ANC_CYCLE = "key_anc_cycle"
    private const val FOCUS_BUTTON_DISCONNECT = "key_disconnect"

    private val hookedConstructors = LinkedHashSet<String>()

    /** 诊断用：invokeStatusBar 的 action 只记一次，避免刷屏。 */
    private val loggedStatusBarActions = LinkedHashSet<String>()

    /** 诊断用：updateParameters 的载荷类只记一次。 */
    private val loggedUpdateParamClasses = LinkedHashSet<String>()

    private var receiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null

    @Volatile
    private var processContext: Context? = null

    /**
     * 「通知栏显示」开关（HyperPodsPrefsKey.SHOW_NOTIFICATION，默认 true）最近一次取值。
     * null = 还没读过；非 null 时只在该值**发生变化**时记一条日志（应用每 ~3s 推一次电量，不能刷屏）。
     */
    @Volatile
    private var lastNotificationEnabled: Boolean? = null

    /** 「焦点显示」开关（SHOW_FOCUS_ISLAND，默认 true）**且**本机是 HyperOS 时的最终取值。 */
    @Volatile
    private var lastFocusIslandEnabled: Boolean? = null

    /** 「超级岛提示」开关（SHOW_STRONG_TOAST，默认 true）**且**本机是 HyperOS 时的最终取值。 */
    @Volatile
    private var lastStrongToastEnabled: Boolean? = null

    /** 本进程最近一次解析到的水月雨设备 —— EXTRA_DEVICE 缺失时的兜底（见 resolveNotificationDevice）。 */
    @Volatile
    private var lastMoondropDevice: BluetoothDevice? = null

    /** 「重启作用域」接收器用的进程 Context（通知构造 hook 偷到的那个，见 resolveContext）。 */
    override fun processContextOrNull(): Context? = processContext

    override fun onHook() {
        Log.d(TAG, "xiaomi bluetooth notification hook initializing (${RomProfile.summary()})")
        clsBtService = firstPresentClass(RomProfile.bluetoothHeadsetServiceClasses).orEmpty()
        clsNotification = firstPresentClass(RomProfile.bluetoothNotificationClasses).orEmpty()
        if (clsBtService.isEmpty()) {
            Log.w(TAG, "headset service missing; candidates=${RomProfile.bluetoothHeadsetServiceClasses}")
        }
        if (clsNotification.isEmpty()) {
            Log.w(TAG, "notification class missing; candidates=${RomProfile.bluetoothNotificationClasses}")
        }
        hookExactConstructor(Context::class.java, Looper::class.java)
        runCatching {
            hookExactConstructor(Looper::class.java, findClass(clsBtService))
        }.onFailure { Log.w(TAG, "$clsBtService unavailable for notification constructor hook", it) }
        runCatching {
            val constructor = findConstructorByParamCount(clsNotification, 2)
            if (hookedConstructors.add(constructor.toGenericString())) {
                hookConstructorAfter(constructor) { onNotificationCreated(instance, args) }
                Log.d(TAG, "hooked constructor by param-count fallback: ${describe(constructor.parameterTypes)}")
            }
        }.onFailure { Log.w(TAG, "2-arg notification constructor fallback skipped", it) }
        hookNotificationParameters()
        // 服务端 Binder 伪装（本进程才是小米耳机 AIDL 服务的实现者）——见 HeadsetServiceBinderHook 文件头。
        runCatching { HeadsetServiceBinderHook.install(this) }
            .onFailure { Log.w(TAG, "headset service binder hooks skipped", it) }
    }

    override fun onHotReloading() {
        runCatching { receiver?.let { r -> receiverContext?.unregisterReceiver(r) } }
            .onFailure { Log.w(TAG, "unregister notification receiver failed", it) }
        receiver = null
        receiverContext = null
        processContext = null
        lastMoondropDevice = null
        lastNotificationEnabled = null
        lastFocusIslandEnabled = null
        lastStrongToastEnabled = null
        hookedConstructors.clear()
        synchronized(loggedStatusBarActions) { loggedStatusBarActions.clear() }
        synchronized(loggedUpdateParamClasses) { loggedUpdateParamClasses.clear() }
        HeadsetServiceBinderHook.reset()
    }

    /**
     * 连接通知 / 状态栏文案的两处入口（本 ROM 实测存在）：
     *   MiuiBluetoothNotification.updateParameters(MiuiBluetoothNotification$c): V
     *   MiuiBluetoothNotification.invokeStatusBar(Context, String, Bundle): V
     *
     * 这里**只观察不改写**：载荷类 `MiuiBluetoothNotification$c` 是混淆类，Bundle 的键值语义
     * 无法从 DEX 静态确定，盲改文案会破坏原生通知。先把真实载荷记进 logcat，下一轮再按实测改。
     */
    private fun hookNotificationParameters() {
        runCatching {
            val method = findMethodOrNull(
                clsNotification, "invokeStatusBar",
                Context::class.java, String::class.java, Bundle::class.java
            )
            if (method != null) {
                hookBefore(method) {
                    val action = args.getOrNull(1) as? String
                    val extras = args.getOrNull(2) as? Bundle
                    val first = synchronized(loggedStatusBarActions) { loggedStatusBarActions.add(action ?: "null") }
                    if (first) {
                        Log.i(TAG, "invokeStatusBar action=$action extras=${extras?.keySet()?.joinToString()}")
                        // 诊断加强：把每个键的值也打出来。ROM 自己构造焦点通知时，焦点 JSON
                        // 就在这个 Bundle 里，下一轮按它校正 focusExtras()。
                        if (extras != null) {
                            extras.keySet().forEach { key ->
                                val value = extras.get(key)
                                Log.i(TAG, "  invokeStatusBar extra[$key]=${describeBundleValue(value)}")
                            }
                        }
                    }
                }
                Log.d(TAG, "hooked $clsNotification#invokeStatusBar (diagnostic only)")
            }
        }.onFailure { Log.w(TAG, "hook $clsNotification.invokeStatusBar skipped", it) }

        runCatching {
            // 按参数个数定位，避免在编译期引用混淆内部类 MiuiBluetoothNotification$c。
            val method = findMethodByParamCountOrNull(clsNotification, "updateParameters", 1)
            if (method != null) {
                hookBefore(method) {
                    val payload = args.getOrNull(0)
                    val cls = payload?.javaClass?.name ?: "null"
                    val first = synchronized(loggedUpdateParamClasses) { loggedUpdateParamClasses.add(cls) }
                    if (first) Log.i(TAG, "updateParameters payload=$cls fields=${describePayload(payload)}")
                }
                Log.d(TAG, "hooked $clsNotification#updateParameters (diagnostic only)")
            }
        }.onFailure { Log.w(TAG, "hook $clsNotification.updateParameters skipped", it) }
    }

    /** Bundle 里一个值的人类可读形式（只读；深一层 Bundle 递归一层就够）。 */
    private fun describeBundleValue(value: Any?): String = when (value) {
        null -> "null"
        is Bundle -> value.keySet().joinToString(prefix = "{", postfix = "}") { k ->
            "$k=${describeBundleValue(value.get(k))}"
        }
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> value.size.toString() + "B"
        else -> value.toString()
    }

    /** 把混淆载荷对象的字段名与可读值列出来（只读，失败即忽略）。 */
    private fun describePayload(payload: Any?): String {
        if (payload == null) return "null"
        return runCatching {
            payload.javaClass.declaredFields.joinToString(prefix = "{", postfix = "}") { field ->
                field.isAccessible = true
                val value = runCatching { field.get(payload) }.getOrNull()
                "${field.name}=${value ?: "null"}"
            }
        }.getOrDefault("<unreadable>")
    }

    private fun hookExactConstructor(vararg parameterTypes: Class<*>) {
        runCatching {
            val constructor = findConstructor(clsNotification, *parameterTypes)
            if (!hookedConstructors.add(constructor.toGenericString())) return
            hookConstructorAfter(constructor) { onNotificationCreated(instance, args) }
            Log.d(TAG, "hooked $clsNotification(${describe(constructor.parameterTypes)})")
        }.onFailure { Log.w(TAG, "$clsNotification(${describe(parameterTypes)}) not available", it) }
    }

    private fun describe(types: Array<out Class<*>>): String = types.joinToString { it.name }

    // ── 偷 Context 并注册广播接收器 ────────────────────────────────────────────

    private fun onNotificationCreated(instance: Any?, args: List<Any?>) {
        val context = resolveContext(instance, args) ?: run {
            Log.w(TAG, "no Context for notification hook; receivers not registered")
            return
        }
        processContext = context.applicationContext ?: context
        registerReceiver(context)
    }

    private fun resolveContext(instance: Any?, args: List<Any?>): Context? {
        (args.firstOrNull { it is Context } as? Context)?.let { return it }
        (runCatching { getObjectField(instance, "mContext") }.getOrNull() as? Context)?.let { return it }
        // (Looper, BluetoothHeadsetService) 形态：从 service / 其它参数上再找一层 Context。
        args.filterNotNull().forEach { arg ->
            (runCatching { getObjectField(arg, "mContext") }.getOrNull() as? Context)?.let { return it }
            (runCatching { getObjectField(arg, "mService") }.getOrNull() as? Context)?.let { return it }
            (runCatching { getObjectField(arg, "mBluetoothHeadsetService") }.getOrNull() as? Context)?.let { return it }
        }
        (runCatching { getObjectField(instance, "mService") }.getOrNull() as? Context)?.let { return it }
        (runCatching { getObjectField(instance, "mHeadsetService") }.getOrNull() as? Context)?.let { return it }
        return SystemApisUtils.currentApplication()
    }

    @Synchronized
    private fun registerReceiver(context: Context) {
        if (receiver != null) return
        val appContext = context.applicationContext ?: context
        val filter = IntentFilter().apply {
            addAction(HyperPodsAction.SEND_STRONG_TOAST)
            addAction(HyperPodsAction.UPDATE_PODS_NOTIFICATION)
            addAction(HyperPodsAction.CANCEL_PODS_NOTIFICATION)
        }
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val received = intent ?: return
                val action = received.action ?: return
                runCatching { handle(ctx ?: appContext, action, received) }
                    .onFailure { Log.e(TAG, "handle $action failed", it) }
            }
        }
        // 发送方是 com.android.bluetooth / 模块应用进程（不同应用），必须 RECEIVER_EXPORTED。
        val registered = runCatching { appContext.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "register notification receiver skipped", it) }
            .isSuccess
        if (!registered) return
        receiver = broadcastReceiver
        receiverContext = appContext
        Log.d(TAG, "notification receiver registered in $VENDOR_PKG")
    }

    private fun handle(context: Context, action: String, intent: Intent) {
        when (action) {
            // 「超级岛提示」关掉时，强提示这条广播直接不落地（普通通知仍照发）。
            HyperPodsAction.SEND_STRONG_TOAST -> {
                if (!notificationDisplayEnabled()) {
                    cancelNotification(context, "")
                    return
                }
                if (!strongToastEnabled()) {
                    Log.d(TAG, "SEND_STRONG_TOAST suppressed by pref ${HyperPodsPrefsKey.SHOW_STRONG_TOAST}=false")
                    return
                }
                postFromIntent(context, action, intent)
            }
            HyperPodsAction.UPDATE_PODS_NOTIFICATION -> {
                // 应用侧「通知栏显示」开关：关掉时连已经发出去的那条也撤掉，不只是不再发新的。
                if (!notificationDisplayEnabled()) {
                    cancelNotification(context, "")
                    return
                }
                postFromIntent(context, action, intent)
            }
            HyperPodsAction.CANCEL_PODS_NOTIFICATION -> {
                val device = SystemApisUtils.parcelableDevice(intent, HyperPodsAction.EXTRA_DEVICE)
                val address = SystemApisUtils.deviceAddress(device)
                    .ifEmpty { intent.getStringExtra(HyperPodsAction.EXTRA_MAC).orEmpty() }
                cancelNotification(context, address)
            }
        }
    }

    /** 解析设备后统一落地（两个入口共用：解析失败只记一条日志，不抛）。 */
    private fun postFromIntent(context: Context, action: String, intent: Intent) {
        val resolved = resolveNotificationDevice(context, intent)
        if (resolved == null) {
            Log.w(
                TAG,
                "$action without ${HyperPodsAction.EXTRA_DEVICE}/${HyperPodsAction.EXTRA_MAC} " +
                    "and no connected Moondrop device; notification skipped"
            )
            return
        }
        val battery = SystemApisUtils.readBatteryExtras(intent)
        val message = intent.getStringExtra(HyperPodsAction.EXTRA_MESSAGE)
        Log.d(
            TAG,
            "$action device resolved via=${resolved.second} " +
                "address=${SystemApisUtils.deviceAddress(resolved.first)}"
        )
        postNotification(context, resolved.first, battery, message)
    }

    // ── 通知开关 / 设备解析 ────────────────────────────────────────────────────

    /**
     * 是否显示耳机电量通知（[HyperPodsPrefsKey.SHOW_NOTIFICATION]，应用侧设置项，默认 true）。
     *
     * ⚠ 只影响**通知栏/强提示**这一个界面。写进系统蓝牙栈的电量是**另一个**界面：
     *   com.android.bluetooth 的 HeadsetStateDispatcher 收 UPDATE_SYSTEM_BATTERY
     *   → AdapterService.setBatteryLevel(device, level)，供系统蓝牙页 / 融合设备中心显示。
     *   两者互相独立：关掉通知**不会**也不应该停掉系统蓝牙页的电量。
     *
     * 读不到 prefs（null / 框架异常 / 类型不符）时按 true 处理 —— 宁可多显示，也不要因为
     * 读设置失败而静默不显示。每次取值变化只打一条日志（见文件头 TAG）。
     */
    private fun notificationDisplayEnabled(): Boolean {
        val enabled = prefBoolean(HyperPodsPrefsKey.SHOW_NOTIFICATION, true)
        if (lastNotificationEnabled != enabled) {
            lastNotificationEnabled = enabled
            if (enabled) {
                Log.i(
                    TAG,
                    "notification display enabled (pref ${HyperPodsPrefsKey.SHOW_NOTIFICATION}=true)"
                )
            } else {
                Log.i(
                    TAG,
                    "notification display disabled by pref " +
                        "(${HyperPodsPrefsKey.SHOW_NOTIFICATION}=false); earbud notification suppressed"
                )
            }
        }
        return enabled
    }

    /**
     * 「焦点显示」是否生效 = 本机是 HyperOS **且** 开关打开。
     *
     * RomProfile.isXiaomiRom 是**严格**判定（只看 HyperOS 自报的 ro.mi.os.version.* 属性，
     * SDK 启发式不算），所以 AOSP / 其它厂商 ROM 上这里恒为 false —— 不会往通知里塞
     * 无意义的 miui.focus.* extra。UI 那侧只是把开关置灰，兜底在这里。
     */
    private fun focusIslandEnabled(): Boolean {
        val xiaomi = RomProfile.isXiaomiRom
        val pref = prefBoolean(HyperPodsPrefsKey.SHOW_FOCUS_ISLAND, true)
        val enabled = xiaomi && pref
        if (lastFocusIslandEnabled != enabled) {
            lastFocusIslandEnabled = enabled
            Log.i(
                TAG,
                "focus display ${if (enabled) "enabled" else "disabled"} " +
                    "(xiaomiRom=$xiaomi, ${HyperPodsPrefsKey.SHOW_FOCUS_ISLAND}=$pref)"
            )
        }
        return enabled
    }

    /** 「超级岛提示」是否生效 = 本机是 HyperOS **且** 开关打开（判定同 [focusIslandEnabled]）。 */
    private fun strongToastEnabled(): Boolean {
        val xiaomi = RomProfile.isXiaomiRom
        val pref = prefBoolean(HyperPodsPrefsKey.SHOW_STRONG_TOAST, true)
        val enabled = xiaomi && pref
        if (lastStrongToastEnabled != enabled) {
            lastStrongToastEnabled = enabled
            Log.i(
                TAG,
                "super island ${if (enabled) "enabled" else "disabled"} " +
                    "(xiaomiRom=$xiaomi, ${HyperPodsPrefsKey.SHOW_STRONG_TOAST}=$pref)"
            )
        }
        return enabled
    }

    /**
     * 解析这条通知该贴到哪个设备上，返回 (设备, 用的是哪条证据)；全失败返回 null。
     *
     * ⚠ 真机实测（2026-09-14）：应用进程 ControlBridge 的 UPDATE_PODS_NOTIFICATION /
     *   SEND_STRONG_TOAST 里 `EXTRA_DEVICE` 是**可空的** —— 它的 `connectedDevice` 在
     *   「应用刚被拉起 / 还没收到 PODS_CONNECTED」时是 null，于是每条通知都命中
     *   `without device` 被丢弃（logcat 每 3s 一条 `HyperPods-MiBtToast: …without device`），
     *   表现就是用户报的「蓝牙设置中通知栏显示失效了」。这里按 1→2→3→4 兜底，
     *   不再把「发送方没给 parcelable」当成「没有设备」。
     *   1) EXTRA_DEVICE（正常情况，发送方给对了）
     *   2) EXTRA_MAC -> BluetoothAdapter.getRemoteDevice(mac)
     *   3) 本进程最近一次解析到的水月雨设备（缓存）
     *   4) 从系统里扫当前已连接的 A2DP/HFP 水月雨设备
     */
    private fun resolveNotificationDevice(context: Context, intent: Intent): Pair<BluetoothDevice, String>? {
        SystemApisUtils.parcelableDevice(intent, HyperPodsAction.EXTRA_DEVICE)?.let {
            lastMoondropDevice = it
            return it to "extra"
        }
        val mac = intent.getStringExtra(HyperPodsAction.EXTRA_MAC)
        if (!mac.isNullOrEmpty()) {
            remoteDevice(context, mac)?.let {
                lastMoondropDevice = it
                return it to "extra-mac"
            }
        }
        lastMoondropDevice?.let { return it to "cached" }
        connectedMoondropDevice(context)?.let {
            lastMoondropDevice = it
            return it to "connected-scan"
        }
        return null
    }

    /** MAC -> BluetoothDevice（BluetoothAdapter.getRemoteDevice；非法 MAC 直接当失败）。 */
    private fun remoteDevice(context: Context, mac: String): BluetoothDevice? = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        adapter?.getRemoteDevice(mac)
    }.getOrNull()

    /** 当前已连接的水月雨设备（A2DP / HFP 两个 profile 都看，按设备名匹配）。 */
    private fun connectedMoondropDevice(context: Context): BluetoothDevice? = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (manager == null) return null
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
            .asSequence()
            .flatMap { profile ->
                runCatching { manager.getConnectedDevices(profile).orEmpty().asSequence() }
                    .getOrElse { emptySequence() }
            }
            .distinctBy { SystemApisUtils.deviceAddress(it) }
            .firstOrNull { MoondropModels.match(SystemApisUtils.deviceName(it)) != null }
    }.getOrNull()

    // ── 通知构建 ───────────────────────────────────────────────────────────────

    private fun postNotification(context: Context, device: BluetoothDevice, battery: IntArray, message: String?) {
        // 兜底再查一次开关：将来若有别的调用方绕过 handle()，也不会漏掉这个设置。
        if (!notificationDisplayEnabled()) {
            cancelNotification(context, "")
            return
        }
        val address = SystemApisUtils.deviceAddress(device)
        if (address.isEmpty()) {
            Log.w(TAG, "postNotification without address")
            return
        }
        val title = SystemApisUtils.deviceName(device).ifEmpty { "MOONDROP" }
        val manager = SystemApisUtils.notificationManager(context) ?: run {
            Log.w(TAG, "NotificationManager unavailable")
            return
        }
        val tag = "$NOTIFICATION_TAG_PREFIX$address"
        // 正文只算一次：焦点通知的 JSON 与通知正文必须是同一份文案。
        val content = contentText(context, battery, message)
        runCatching {
            ensureChannel(manager, title)
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(HyperPodsAction.SHOW_POPUP).apply {
                    setPackage(PKG_APP)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(smallIcon(context))
                .setWhen(0L)
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
            deleteIntent(context, device)?.let { builder.setDeleteIntent(it) }
            disconnectAction(context, device)?.let { builder.addAction(it) }
            // miui.* / miui.focus.* 只在**真的**跑在小米 ROM 上时才写：
            // 非 HyperOS 上这些键没有任何消费者，写进去只是脏数据。
            if (RomProfile.isXiaomiRom) {
                runCatching { builder.setExtras(miuiExtras(context, device, title, content, batteryAodTitle(battery))) }
                    .onFailure { Log.d(TAG, "miui extras unsupported", it) }
            }
            accentColor(context)?.let { builder.setColor(it) }

            SystemApisUtils.notifyAsUser(manager, tag, NOTIFICATION_ID, builder.build(), SystemApisUtils.getUserAllUserHandle())
            Log.i(
                TAG,
                "notification posted tag=$tag level=${battery.joinToString(",")} " +
                    "focus=${focusIslandEnabled()} island=${strongToastEnabled()}"
            )
        }.onFailure { Log.e(TAG, "postNotification failed", it) }
    }

    private fun cancelNotification(context: Context, address: String) {
        val manager = SystemApisUtils.notificationManager(context) ?: return
        val user = SystemApisUtils.getUserAllUserHandle()
        if (address.isNotEmpty()) {
            SystemApisUtils.cancelAsUser(manager, "$NOTIFICATION_TAG_PREFIX$address", NOTIFICATION_ID, user)
            Log.i(TAG, "notification cancelled tag=$NOTIFICATION_TAG_PREFIX$address")
            return
        }
        // 没有具体地址时清掉本模块发出的全部耳机电量通知（同 id + tag 前缀）。
        runCatching {
            manager.activeNotifications
                .filter { it.id == NOTIFICATION_ID && it.tag?.startsWith(NOTIFICATION_TAG_PREFIX) == true }
                .mapNotNull { it.tag }
                .forEach { tag -> SystemApisUtils.cancelAsUser(manager, tag, NOTIFICATION_ID, user) }
        }.onFailure { Log.w(TAG, "cancel all pods notifications failed", it) }
    }

    /**
     * 建通知通道。
     *
     * 早期版本用的是 IMPORTANCE_MIN（只在通知栏列表里，状态栏不出图标、也没有提示），
     * 表现就是用户报的「通知栏里看不到」。通道的重要性**创建之后不可修改**，所以
     * 发现已有通道的重要性不是 IMPORTANCE_LOW 时，先删掉再按 LOW 建一次。
     */
    private fun ensureChannel(manager: NotificationManager, name: String) {
        runCatching {
            val existing = runCatching { manager.getNotificationChannel(CHANNEL_ID) }.getOrNull()
            if (existing != null && existing.importance != NotificationManager.IMPORTANCE_LOW) {
                Log.i(TAG, "recreate channel $CHANNEL_ID: importance ${existing.importance} -> IMPORTANCE_LOW")
                manager.deleteNotificationChannel(CHANNEL_ID)
            }
            if (runCatching { manager.getNotificationChannel(CHANNEL_ID) }.getOrNull() != null) return
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }.onFailure { Log.w(TAG, "createNotificationChannel($CHANNEL_ID) failed", it) }
    }

    private fun deleteIntent(context: Context, device: BluetoothDevice): PendingIntent? = runCatching {
        val intent = Intent("com.android.bluetooth.headset.notification.cancle")
        intent.putExtra("android.bluetooth.device.extra.DEVICE", device)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    private fun disconnectAction(context: Context, device: BluetoothDevice): Notification.Action? {
        val text = vendorString(context, RES_DISCONNECT) ?: FALLBACK_DISCONNECT
        return runCatching {
            val intent = Intent("com.android.bluetooth.headset.notification")
            intent.putExtra("btData", Bundle().apply { putParcelable("Device", device) })
            intent.putExtra("disconnect", "1")
            intent.setIdentifier("$NOTIFICATION_TAG_PREFIX${SystemApisUtils.deviceAddress(device)}")
            Notification.Action(
                vendorDrawable(context, RES_ICON),
                text,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }.getOrNull()
    }

    /**
     * 整条通知的 extra：小米蓝牙自己的两个键 + （HyperOS 且开关打开时的）焦点通知/超级岛。
     *
     * 一次性返回**一个** Bundle：Notification.Builder.setExtras() 是整体替换而不是合并，
     * 分两次调用会把前一次的键丢掉。
     */
    private fun miuiExtras(
        context: Context,
        device: BluetoothDevice,
        title: String,
        content: String,
        aodTitle: String
    ): Bundle = Bundle().apply {
        putBoolean(EXTRA_MIUI_SHOW_ACTION, true)
        val icon = vendorDrawable(context, RES_ICON)
        if (icon != 0) putParcelable(EXTRA_MIUI_APP_ICON, Icon.createWithResource(context, icon))
        if (focusIslandEnabled()) {
            focusExtras(context, device, title, content, aodTitle, strongToastEnabled())?.let { putAll(it) }
        }
    }

    /**
     * HyperOS 焦点通知（`miui.focus.param`）+ 图标（`miui.focus.pics`）的 extra。
     *
     * ── 格式是哪来的（**修正过一次，这里是实测过的**）────────────────────────────
     * 第一版是反汇编 `com.android.bluetooth.ble.app.MiuiBluetoothNotification` 猜的：
     * 把 `miui.focus.param` 当成 `Bundle{ param_v2 = JSON }`。**那是错的。**
     * 后来在同作者的另一个仓库（`huimeyc/HyperPods` 的 dev 分支，跑在同一台
     * HyperOS 4 平板上）里 `dumpsys notification --noredact` 直接读到了系统里
     * 真实存在的那条水月雨通知，原文是：
     *
     *   miui.focus.param = String ( {"type":"com.xzakota.hyper.notification.focus
     *                                  .FocusNotification.FocusTemplateFactory.V3",
     *                               "param_v2":{ "ticker":…, "updatable":true, "enableFloat":true,
     *                                            "iconTextInfo":{ "title":…, "content":…,
     *                                                             "animIconInfo":{"type":0,"src":"key_headset"} },
     *                                            "textButton":[ {"action":"key_anc_cycle","actionTitle":"切换降噪"},
     *                                                           {"action":"key_disconnect","actionTitle":"断开连接"} ],
     *                                            "param_island":{ "islandProperty":1,
     *                                                             "bigIslandArea":{ "imageTextInfoLeft":{…},
     *                                                                               "imageTextInfoRight":{…} } },
     *                                            "aodTitle":"L 0% | R 91%", "aodPic":"key_headset" } } )
     *   miui.focus.pics = Bundle
     *
     * 也就是说：外层 extra 是**字符串**，JSON 里 `type` 是模板工厂名（`xzakota` 那套焦点通知库），
     * 内容都在它下面的 `param_v2` 里。本函数按这份原文复刻，键名不做「看起来更合理」的改名。
     *
     * 写得不对最多是「焦点通知不显示」，不会影响下面那条原生通知 —— 本文件每条通知都打
     * `focus=/island=`，真机上一眼能看出走没走到。
     *
     * @param aodTitle 息屏/超级岛上的紧凑电量串（见 batteryAodTitle）
     * @param withIsland 「超级岛提示」开关：打开时才写 param_island 那一块
     */
    private fun focusExtras(
        context: Context,
        device: BluetoothDevice,
        title: String,
        content: String,
        aodTitle: String,
        withIsland: Boolean
    ): Bundle? = runCatching {
        // ── 内层 param_v2：内容（成对的 title/content、图标、按钮、超级岛区域）────────
        // 字段名照真机原文，不做「看起来更合理」的改名。
        val paramV2 = JSONObject().apply {
            put("ticker", title)
            put("updatable", true)
            put("enableFloat", true)
            put(
                "iconTextInfo",
                JSONObject().apply {
                    put("title", title)
                    put("content", content)
                    put(
                        "animIconInfo",
                        JSONObject().apply {
                            put("type", 0)
                            put("src", FOCUS_ICON_KEY)
                        }
                    )
                }
            )
            put(
                "textButton",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("action", FOCUS_BUTTON_ANC_CYCLE)
                            put("actionTitle", vendorString(context, RES_ANC_CYCLE) ?: FALLBACK_ANC_CYCLE)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("action", FOCUS_BUTTON_DISCONNECT)
                            put("actionTitle", vendorString(context, RES_DISCONNECT) ?: FALLBACK_DISCONNECT)
                        }
                    )
                }
            )
            if (withIsland) {
                put(
                    "param_island",
                    JSONObject().apply {
                        put("islandProperty", 1)
                        put(
                            "bigIslandArea",
                            JSONObject().apply {
                                put(
                                    "imageTextInfoLeft",
                                    JSONObject().apply {
                                        put("type", 1)
                                        put(
                                            "picInfo",
                                            JSONObject().apply {
                                                put("type", 1)
                                                put("pic", FOCUS_ICON_KEY)
                                            }
                                        )
                                    }
                                )
                                put(
                                    "imageTextInfoRight",
                                    JSONObject().apply {
                                        put("type", 2)
                                        put(
                                            "textInfo",
                                            JSONObject().apply {
                                                put("title", title)
                                                put("content", content)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
            put("aodTitle", aodTitle)
            put("aodPic", FOCUS_ICON_KEY)
        }
        // ── 外层：`miui.focus.param` 是**一个字符串**（JSON），里面放模板标识 + param_v2 ──
        val param = JSONObject().apply {
            put("type", FOCUS_TEMPLATE_V3)
            put("param_v2", paramV2)
        }
        val pics = Bundle().apply {
            putString("pic", FOCUS_ICON_KEY)
            putInt("type", 1)
        }
        Log.d(
            TAG,
            "focus extras built address=${SystemApisUtils.deviceAddress(device)} island=$withIsland " +
                "json=${param.toString().length}B"
        )
        Bundle().apply {
            // 注意 putString 而不是 putBundle：真机上这个 extra 就是字符串。
            putString(EXTRA_FOCUS_PARAM, param.toString())
            putBundle(EXTRA_FOCUS_PICS, pics)
        }
    }.onFailure { Log.w(TAG, "focus extras build failed", it) }.getOrNull()

    /**
     * 息屏 / 超级岛上的紧凑电量串（真机原文形如 `L 0% | R 91%`）。
     * 没有读数的一侧写 `--`，不写 0%（0 在协议里是「无读数」，见 batteryLine 的说明）。
     */
    private fun batteryAodTitle(battery: IntArray): String {
        fun part(prefix: String, raw: Int): String {
            val level = SystemApisUtils.decodeLevel(raw)
            return if (level <= 0) "$prefix --" else "$prefix $level%"
        }
        return part("L", battery.getOrElse(0) { 0 }) + " | " + part("R", battery.getOrElse(1) { 0 })
    }

    private fun accentColor(context: Context): Int? {
        val id = vendorResourceId(context, "color", RES_ACCENT_COLOR)
        if (id == 0) return null
        return runCatching { context.getColor(id) }.getOrNull()
    }

    private fun smallIcon(context: Context): Int =
        vendorDrawable(context, RES_ICON).takeIf { it != 0 } ?: android.R.drawable.stat_sys_data_bluetooth

    private fun vendorDrawable(context: Context, name: String): Int =
        vendorResourceId(context, "drawable", name)

    private fun vendorResourceId(context: Context, type: String, name: String): Int =
        runCatching { context.resources.getIdentifier(name, type, VENDOR_PKG) }.getOrDefault(0)

    private fun vendorString(context: Context, name: String): String? {
        val id = vendorResourceId(context, "string", name)
        if (id == 0) return null
        return runCatching { context.resources.getString(id) }.getOrNull()
    }

    private fun contentText(context: Context, battery: IntArray, message: String?): String {
        if (!message.isNullOrEmpty()) return message
        val lines = mutableListOf<String>()
        batteryLine(context, RES_BOX, FALLBACK_BOX, battery[2])?.let { lines += it }
        listOf(
            batteryLine(context, RES_LEFT, FALLBACK_LEFT, battery[0]),
            batteryLine(context, RES_RIGHT, FALLBACK_RIGHT, battery[1])
        ).filterNotNull().joinToString(" | ").takeIf { it.isNotEmpty() }?.let { lines += it }
        return lines.joinToString("\n").ifEmpty { "已连接" }
    }

    /**
     * 电量原始编码 -> "标签：xx %"；未知返回 null（该行不显示）。
     *
     * ⚠ 未连接/无数据（level <= 0）显示「离线」而不是 `0 %`：
     * 水月雨固件对未连接的一侧会上报 0x00 或 0xFF，两者都到达过这里，
     * 显示 0% 会让用户误以为电量耗尽。复用小米蓝牙自己的断开文案。
     */
    private fun batteryLine(context: Context, resName: String, fallback: String, raw: Int): String? {
        val level = SystemApisUtils.decodeLevel(raw)
        if (level < 0) return null
        val label = vendorString(context, resName) ?: fallback
        if (level == 0) {
            return "$label：" + (vendorString(context, RES_DISCONNECT) ?: FALLBACK_DISCONNECT)
        }
        val charging = if (SystemApisUtils.decodeCharging(raw)) " ⚡" else ""
        return "$label：$level %$charging"
    }
}
