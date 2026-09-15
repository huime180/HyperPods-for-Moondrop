/*
 * MiuixMoondrop — 应用进程自己发的耳机状态通知
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么需要它：
 *   协议栈本来就在应用进程（pods/MoondropLink.kt 自己 connectGatt / 建 RFCOMM），
 *   所以「连上耳机 → 拿到三路电量 → 发一条状态通知」全程不需要 root，也不需要任何 hook。
 *   本应用已不再是 Xposed 模块：本文件就是它唯一的通知来源。
 *
 * 通知身份（与其它应用发的通知区分开，撤销时不会互相误伤）：
 *   channel = hyperpods_moondrop_app_status
 *   tag     = HyperPodsAppState
 *   id      = 10004
 *
 * 开关：读设置页写的那份 hyperpods_moondrop_settings：
 *   · SHOW_NOTIFICATION（「通知栏显示」，默认 true）—— 这条通知的总开关。
 * 另外还有一道**系统级**门槛：Android 13+ 的 POST_NOTIFICATIONS 运行时权限。没有它 notify() 会被
 *   系统静默丢弃，本文件只能如实打一条日志（见 [post]），并刻意不做「内容相同就跳过」的去重 ——
 *   用户一授予权限，下一次状态事件立刻就能把它发出来。全新安装后需要打开过一次应用（或手动在
 *   系统设置里授予）才有这条权限，这是平台规则，任何应用都绕不过。
 *
 * 线程与容错：所有入口都能从任意线程调用（ControlBridge 的事件在任意线程回来）；判定 + 落地
 *   整段在单一协程里串行化（[lock]），所有系统调用都 runCatching 兜住 —— 通知失败只打日志，
 *   绝不能让 App 崩（与仓库既有写法一致）。
 */
package moe.chenxy.hyperpods.pods

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.MODULE_PREFS_GROUP
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey

private const val TAG = "HyperPods-PodNotify"

object PodNotification {

    /**
     * 通知通道：**IMPORTANCE_DEFAULT + 主动关掉声音与震动**。
     *
     * 为什么不是 LOW：LOW 档的通知在 HyperOS 上不会走焦点通知 / 超级岛那条渲染路径
     * （同行项目 dev 分支在同一台 HyperOS 4 平板上验证过：焦点通知那条用的是 DEFAULT 通道）。
     * 关掉 sound / vibration 后仍然「不响铃、不震动」，只是不再被系统归到「静默」最低档。
     * 通道重要性创建后不可修改，所以 [ensureChannel] 发现同名通道档位不对时会删掉重建。
    private const val CHANNEL_ID = "hyperpods_moondrop_app_status"

    private const val NOTIFICATION_TAG = "HyperPodsAppState"

    private const val NOTIFICATION_ID = 10004

    /** 通道档位：见 [CHANNEL_ID] 的说明（要进 HyperOS 焦点通知路径就不能用 LOW）。 */
    private const val CHANNEL_IMPORTANCE = NotificationManager.IMPORTANCE_DEFAULT

    /** 电量各组件之间的分隔符（紧凑一行）。 */
    private const val PART_SEPARATOR = " · "

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 判定 + 落地整段串行化：并发事件不会交叉出两条状态不一致的通知。 */
    private val lock = Mutex()

    /**
     * 上一条已落地的通知内容（标题 + 正文）。完全相同就不再 notify：电量是 30s 一轮的轮询，
     * 不判重会把同一条通知反复重发（虽然系统不会响，但会平白刷新时间戳）。
     */
    @Volatile private var lastRendered: String? = null

    /**
     * 上一条通知带的大图（键 = 地址 + 图片版本号）。
     *
     * 判重要一起看它：机型图是异步拉回来的，图到位时标题正文可能一个字都没变，
     * 只比 [lastRendered] 的话那条通知要等到下一次电量变化才带上图。
     */
    private data class LargeIcon(val key: String, val bitmap: Bitmap)

    @Volatile private var lastIcon: LargeIcon? = null

    /** 当前是否有一条本应用发出的通知（只用于日志去重，不参与任何判定）。 */
    @Volatile private var posted = false

    // ── 入口 ────────────────────────────────────────────────────────────────

    /**
     * 应用进程初始化时调用一次（ControlBridge.ensureInit，主线程）：
     * 按当前快照对一次账 —— 进程重启后把自己那条重画（耳机没连就撤掉，不留旧通知）。
     */
    fun prime(context: Context) {
        refreshFromSnapshot(context)
    }

    /**
     * 耳机状态变化（连接 / 电量刷新）时调用。
     *
     * 这是 ControlBridge 事件转发器唯一的通知入口：拿到的快照就是 MoondropLink 的真实状态，
     * 不在这里二次判断「连接与否」以外的东西。
     */
    fun onSnapshot(context: Context, snapshot: PodSnapshot) {
        val app = context.applicationContext
        scope.launch {
            runCatching { sync(app, snapshot) }
                .onFailure { Log.w(TAG, "sync failed: ${it.message}") }
        }
    }

    /** 断开连接时调用：撤掉自己那条。 */
    fun cancel(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { lock.withLock { cancelNow(app, "disconnected") } }
                .onFailure { Log.w(TAG, "cancel failed: ${it.message}") }
        }
    }

    /**
     * 设置页改了「通知栏显示」后立即生效（不必等下一次电量轮询）：
     * 打开就按当前快照重画一条，关掉就把已经发出去的那条撤掉。
     */
    fun refreshFromSnapshot(context: Context) = onSnapshot(context, MoondropLink.snapshot())

    // ── 判定 + 落地 ──────────────────────────────────────────────────────────

    /** 一次完整决策：不该发就撤销，该发就（按需）落地。整段在 [lock] 内串行。 */
    private suspend fun sync(app: Context, snapshot: PodSnapshot) = lock.withLock {
        when {
            !snapshot.connected -> cancelNow(app, "not connected")
            !notificationsEnabled(app) -> cancelNow(app, "disabled by settings")
            else -> post(app, snapshot)
        }
    }

    /**
     * 「通知栏显示」是否允许应用自己发。
     *
     * 读不到 prefs（文件不存在 / 读抛异常）时按 true 处理：宁可多显示一条，
     * 也不要因为读设置失败而静默不显示。
     */
    private fun notificationsEnabled(context: Context): Boolean {
        val prefs = runCatching {
            context.getSharedPreferences(MODULE_PREFS_GROUP, Context.MODE_PRIVATE)
        }.getOrNull() ?: return true
        val show = runCatching { prefs.getBoolean(HyperPodsPrefsKey.SHOW_NOTIFICATION, true) }
            .getOrDefault(true)
        if (!show) Log.i(TAG, "app notification off (show_notification=$show)")
        return show
    }

    // ── 通知本体 ─────────────────────────────────────────────────────────────

    /** 建/校正通道，然后 notify。内容与大图都与上一条相同则跳过（见 [lastRendered] / [lastIcon]）。 */
    private suspend fun post(context: Context, snapshot: PodSnapshot) {
        val title = titleOf(context, snapshot)
        val content = contentOf(context, snapshot)
        val rendered = "$title\n$content"
        val icon = largeIconFor(context, snapshot)
        if (posted && rendered == lastRendered && icon === lastIcon) return
        val manager = notificationManager(context) ?: run {
            Log.w(TAG, "NotificationManager unavailable; app notification skipped")
            return
        }
        runCatching {
            ensureChannel(context, manager)
            // 系统级开关 / POST_NOTIFICATIONS 没授予时 notify() 会被系统静默丢弃（== 用户什么都看不到）。
            // 这时**不**记 lastRendered/posted：下一次状态事件再试一次，用户一授予权限立刻就出来
            // （否则「内容没变」的去重会让它一直不出来）。
            if (!manager.areNotificationsEnabled()) {
                Log.w(
                    TAG,
                    "notifications disabled for this app (POST_NOTIFICATIONS not granted?); " +
                        "app notification dropped",
                )
                return
            }
            val builder = Notification.Builder(context, CHANNEL_ID)
                // 状态栏小图必须保持应用图标：系统会把它渲染成**单色剪影**，
                // 塞一张产品照片只会糊成一团黑块。设备机型图走 largeIcon（右侧大图）。
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setWhen(0L)
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(content)
                // 正文有两行（连接状态 / 电量）：展开时按 BigText 完整显示，收起来时系统自己截断
                .setStyle(Notification.BigTextStyle().bigText(content))
                .setContentIntent(contentIntent(context))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                // 常驻：耳机连着的时候这条通知不该被划掉（划掉后要等下一次内容变化才会回来，
                // 用户会以为「通知坏了」）。断开连接时由 cancelNow 主动撤掉。
                .setOngoing(true)
            // 拿得到这台设备的机型图就带上；拿不到（没图 / 还没拉回来）就照常发，不因为没图不发通知
            if (icon != null) builder.setLargeIcon(icon.bitmap)
            // HyperOS 焦点通知 / 超级岛：带上 MIUI 认的那套 extra（普通 ROM 会忽略，通知照常）。
            // 图片与 largeIcon 用同一张机型图；拿不到图时 buildExtras 返回 null，这里就不带。
            PodFocusNotification.buildExtras(
                context = context,
                titleText = title,
                contentText = content.replace('\n', ' ').trim(),
                aodText = aodTitleOf(snapshot),
                boxBitmap = icon?.bitmap,
            )?.let { builder.addExtras(it) }
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build())
            lastRendered = rendered
            lastIcon = icon
            posted = true
            Log.i(TAG, "app notification posted: $title / ${content.replace('\n', ' ')}")
        }.onFailure { Log.e(TAG, "post app notification failed", it) }
    }

    /**
     * 息屏显示（AOD）用的紧凑电量行：`L 59% | R 64%`。
     *
     * 沿用正文那套「没有读数就不出现」的口径（见 [contentOf]）：未知 / 0% 的组件不写进
     * AOD 行，绝不显示成 0%。语言无关（L/R + 百分号），因为 AOD 那一行是系统画的。
     */
    private fun aodTitleOf(snapshot: PodSnapshot): String {
        val battery = snapshot.battery
        val left = if (battery.leftKnown && battery.left > 0) "L ${battery.left}%" else null
        val right = if (battery.rightKnown && battery.right > 0) "R ${battery.right}%" else null
        return listOfNotNull(left, right).joinToString(" | ")
    }

    /**
     * 通知大图 = 这台设备的机型图（pods/PodImageStore，按设备地址落盘）；没有就返回 null。
     *
     * 线程：解码是文件 IO，**绝不能落在主线程**。[onSnapshot] 的整段判定跑在
     * [scope]（Dispatchers.Default）里，这里再显式切一次 Dispatchers.IO，别把解码压在
     * 并发度有限的 Default 线程池上。
     *
     * 缓存：结果按「地址 + 图片版本号」记住（[PodImageStore.revision] 在换图/恢复默认时会 +1），
     * 所以每 30s 一轮的电量轮询不会重复解码同一张图；换图后 key 变了会重新读一次。
     * 这里用 [PodImageStore.loadBitmap]（与详情页英雄图同一张、全尺寸解码）：一台设备只留一张，
     * 且详情页本来也会持有同一张图，不额外做降采样。
     */
    private suspend fun largeIconFor(context: Context, snapshot: PodSnapshot): LargeIcon? {
        val address = snapshot.deviceAddress
        if (address.isBlank()) return null
        val key = "$address@${PodImageStore.revision}"
        lastIcon?.takeIf { it.key == key }?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { PodImageStore.loadBitmap(context, address) }.getOrNull()
        } ?: return null
        val icon = LargeIcon(key, bitmap)
        lastIcon = icon
        return icon
    }

    /** 撤掉自己那条；[reason] 只进日志。始终调用 cancel（幂等），日志只在状态翻转时打一条。 */
    private fun cancelNow(context: Context, reason: String) {
        lastRendered = null
        // lastIcon 刻意保留：它只用于「这一帧要不要重画」的比较（比的是对象身份），留着可以让
        // 重连同一台设备时直接复用那张已解码的位图（键没变就命中缓存）
        val wasPosted = posted
        posted = false
        notificationManager(context)?.let { manager ->
            runCatching { manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID) }
                .onFailure { Log.w(TAG, "cancel app notification failed: ${it.message}") }
        }
        if (wasPosted) Log.i(TAG, "app notification cancelled ($reason)")
    }

    private fun notificationManager(context: Context): NotificationManager? =
        runCatching {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.onFailure { Log.w(TAG, "NotificationManager unavailable: ${it.message}") }.getOrNull()

    /**
     * 建通道。
     *
     * 通道的重要性**创建之后不可修改**，所以发现同名通道的档位不是 [CHANNEL_IMPORTANCE] 时先删掉
     * 再按它建一次（老版本装过的用户从这里迁移到新档位）。通道名/描述只在创建那一刻生效。
     */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        runCatching {
            val existing = runCatching { manager.getNotificationChannel(CHANNEL_ID) }.getOrNull()
            if (existing != null && existing.importance != CHANNEL_IMPORTANCE) {
                Log.i(TAG, "recreate channel $CHANNEL_ID: importance ${existing.importance} -> $CHANNEL_IMPORTANCE")
                manager.deleteNotificationChannel(CHANNEL_ID)
            }
            if (runCatching { manager.getNotificationChannel(CHANNEL_ID) }.getOrNull() != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_status),
                CHANNEL_IMPORTANCE,
            )
            // 「不响铃不震动」由这两句保证（而不是靠最低档位），这样既能进焦点通知渲染路径，
            // 又不会打扰用户。enableVibration(false) 需要 API 26+（minSdk 35，安全）。
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.description = context.getString(R.string.notification_channel_status_desc)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "channel created: $CHANNEL_ID")
        }.onFailure { Log.w(TAG, "createNotificationChannel($CHANNEL_ID) failed", it) }
    }

    /**
     * 点击进本应用：走 HyperPodsAction.SHOW_POPUP → ui/PopupActivity
     * （见 AndroidManifest.xml 的 intent-filter），点通知直接落在快速弹窗上。
     * 必须 setPackage：命中本应用自己的组件，不受 Android 14+ 隐式 intent 限制。
     */
    private fun contentIntent(context: Context): PendingIntent? = runCatching {
        val intent = Intent(HyperPodsAction.SHOW_POPUP).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.onFailure { Log.w(TAG, "content intent build failed", it) }.getOrNull()

    /** 标题 = 设备名（取不到就型号名，再取不到用仓库既有的「水月雨耳机」占位串）。 */
    private fun titleOf(context: Context, snapshot: PodSnapshot): String =
        snapshot.deviceName.ifBlank { snapshot.modelName }
            .ifBlank { context.getString(R.string.unknown_model) }

    /**
     * 正文两行：① 连接状态 ② 三路电量。
     *
     * 电量按仓库既有的展示口径（ui/components/PodStatus.kt 的 isOffline）：
     *   · level 为 BATTERY_UNKNOWN（-1）＝ 本轮没有该组件的数据 → 该侧**整条不出现**，绝不当成 0%；
     *   · level == 0 ＝ 固件对未连接的一侧上报的 0x00（见 BatteryCodec / PodStatus 的说明）
     *     → 显示「离线」，同样不显示 0%；
     *   · 单设备机型（type 0）只显示一行「整机」；
     *   · 三路都没有读数时第二行回落成「正在读取电量…」，第一行的连接状态仍在。
     */
    private fun contentOf(context: Context, snapshot: PodSnapshot): String {
        val battery = snapshot.battery
        val state = context.getString(R.string.conn_connected)
        if (!battery.anyKnown) {
            return state + "\n" + context.getString(R.string.batt_unknown)
        }
        val parts = if (battery.singleDevice) {
            listOf(
                batteryPart(
                    context,
                    R.string.batt_whole,
                    if (battery.leftKnown) battery.left else battery.right,
                    battery.leftCharging || battery.rightCharging,
                )
            )
        } else {
            listOf(
                batteryPart(context, R.string.batt_left, battery.left, battery.leftCharging),
                batteryPart(context, R.string.batt_right, battery.right, battery.rightCharging),
                batteryPart(context, R.string.batt_case, battery.case, battery.caseCharging),
            )
        }
        val body = parts.filterNotNull().joinToString(PART_SEPARATOR)
            .ifEmpty { context.getString(R.string.batt_unknown) }
        return state + "\n" + body
    }

    /**
     * 一路电量：`标签 62 %` / `标签 62 % ⚡` / `标签 离线`；没有数据（[level] < 0）返回 null
     * （调用方整条丢掉）。
     *
     * @param level [BatterySnapshot] 的 left / right / case（-1 = 无数据）
     */
    private fun batteryPart(context: Context, labelRes: Int, level: Int, charging: Boolean): String? {
        val label = context.getString(labelRes)
        if (level < 0) return null
        if (level == 0) return "$label " + context.getString(R.string.battery_offline)
        val mark = if (charging) " ⚡" else ""
        return "$label $level %$mark"
    }
}
