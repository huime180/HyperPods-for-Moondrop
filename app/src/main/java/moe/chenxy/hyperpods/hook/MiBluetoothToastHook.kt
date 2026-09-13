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
 *   FileProvider 交给 SystemUI 播放）。本模块不携带任何 .mp4 / base64 资产，
 *   SEND_STRONG_TOAST 降级为同一套普通耳机电量通知。
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
            HyperPodsAction.SEND_STRONG_TOAST,
            HyperPodsAction.UPDATE_PODS_NOTIFICATION -> {
                // 应用侧「通知栏显示」开关：关掉时连已经发出去的那条也撤掉，不只是不再发新的。
                if (!notificationDisplayEnabled()) {
                    cancelNotification(context, "")
                    return
                }
                val resolved = resolveNotificationDevice(context, intent)
                if (resolved == null) {
                    Log.w(
                        TAG,
                        "$action without ${HyperPodsAction.EXTRA_DEVICE}/${HyperPodsAction.EXTRA_MAC} " +
                            "and no connected Moondrop device; notification skipped"
                    )
                    return
                }
                val device = resolved.first
                val via = resolved.second
                val battery = SystemApisUtils.readBatteryExtras(intent)
                val message = intent.getStringExtra(HyperPodsAction.EXTRA_MESSAGE)
                Log.d(
                    TAG,
                    "$action device resolved via=$via address=${SystemApisUtils.deviceAddress(device)}"
                )
                postNotification(context, device, battery, message)
            }
            HyperPodsAction.CANCEL_PODS_NOTIFICATION -> {
                val device = SystemApisUtils.parcelableDevice(intent, HyperPodsAction.EXTRA_DEVICE)
                val address = SystemApisUtils.deviceAddress(device)
                    .ifEmpty { intent.getStringExtra(HyperPodsAction.EXTRA_MAC).orEmpty() }
                cancelNotification(context, address)
            }
        }
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
                .setContentText(contentText(context, battery, message))
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
            deleteIntent(context, device)?.let { builder.setDeleteIntent(it) }
            disconnectAction(context, device)?.let { builder.addAction(it) }
            runCatching { builder.setExtras(miuiExtras(context)) }
                .onFailure { Log.d(TAG, "miui extras unsupported", it) }
            accentColor(context)?.let { builder.setColor(it) }

            SystemApisUtils.notifyAsUser(manager, tag, NOTIFICATION_ID, builder.build(), SystemApisUtils.getUserAllUserHandle())
            Log.i(TAG, "notification posted tag=$tag level=${battery.joinToString(",")}")
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

    private fun ensureChannel(manager: NotificationManager, name: String) {
        runCatching {
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_MIN)
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

    private fun miuiExtras(context: Context): Bundle = Bundle().apply {
        putBoolean("miui.showAction", true)
        val icon = vendorDrawable(context, RES_ICON)
        if (icon != 0) putParcelable("miui.appIcon", Icon.createWithResource(context, icon))
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
