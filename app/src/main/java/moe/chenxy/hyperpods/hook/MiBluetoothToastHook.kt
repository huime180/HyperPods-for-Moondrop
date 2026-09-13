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
 */
package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Looper
import android.util.Log
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {
    private const val TAG = "HyperPods-MiBtToast"
    private const val PKG_APP = BuildConfig.APPLICATION_ID
    private const val CLS_BT_SERVICE = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
    private const val CLS_NOTIFICATION = "com.android.bluetooth.ble.app.MiuiBluetoothNotification"
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

    private var receiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null

    @Volatile
    private var processContext: Context? = null

    override fun onHook() {
        hookExactConstructor(Context::class.java, Looper::class.java)
        runCatching {
            hookExactConstructor(Looper::class.java, findClass(CLS_BT_SERVICE))
        }.onFailure { Log.w(TAG, "$CLS_BT_SERVICE unavailable for notification constructor hook", it) }
        runCatching {
            val constructor = findConstructorByParamCount(CLS_NOTIFICATION, 2)
            if (hookedConstructors.add(constructor.toGenericString())) {
                hookConstructorAfter(constructor) { onNotificationCreated(instance, args) }
                Log.d(TAG, "hooked constructor by param-count fallback: ${describe(constructor.parameterTypes)}")
            }
        }.onFailure { Log.w(TAG, "2-arg notification constructor fallback skipped", it) }
    }

    override fun onHotReloading() {
        runCatching { receiver?.let { r -> receiverContext?.unregisterReceiver(r) } }
            .onFailure { Log.w(TAG, "unregister notification receiver failed", it) }
        receiver = null
        receiverContext = null
        processContext = null
    }

    private fun hookExactConstructor(vararg parameterTypes: Class<*>) {
        runCatching {
            val constructor = findConstructor(CLS_NOTIFICATION, *parameterTypes)
            if (!hookedConstructors.add(constructor.toGenericString())) return
            hookConstructorAfter(constructor) { onNotificationCreated(instance, args) }
            Log.d(TAG, "hooked $CLS_NOTIFICATION(${describe(constructor.parameterTypes)})")
        }.onFailure { Log.w(TAG, "$CLS_NOTIFICATION(${describe(parameterTypes)}) not available", it) }
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
                val action = intent?.action ?: return
                runCatching { handle(ctx ?: appContext, action, intent) }
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
                val device = SystemApisUtils.parcelableDevice(intent, HyperPodsAction.EXTRA_DEVICE)
                if (device == null) {
                    Log.w(TAG, "$action without ${HyperPodsAction.EXTRA_DEVICE}")
                    return
                }
                val battery = SystemApisUtils.readBatteryExtras(intent)
                val message = intent.getStringExtra(HyperPodsAction.EXTRA_MESSAGE)
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

    // ── 通知构建 ───────────────────────────────────────────────────────────────

    private fun postNotification(context: Context, device: BluetoothDevice, battery: IntArray, message: String?) {
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
                Intent(HyperPodsAction.SHOW_UI).apply {
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

    /** 电量原始编码 -> "标签：xx %"；未知返回 null（该行不显示）。 */
    private fun batteryLine(context: Context, resName: String, fallback: String, raw: Int): String? {
        val level = SystemApisUtils.decodeLevel(raw)
        if (level < 0) return null
        val label = vendorString(context, resName) ?: fallback
        val charging = if (SystemApisUtils.decodeCharging(raw)) " ⚡" else ""
        return "$label：$level %$charging"
    }
}
