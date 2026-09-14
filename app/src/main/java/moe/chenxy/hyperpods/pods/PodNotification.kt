/*
 * HyperPods for Moondrop — 应用进程自己发的耳机状态通知
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么需要它：
 *   以前这条通知只由 hook 侧发（hook/MiBluetoothToastHook.kt 在 com.xiaomi.bluetooth 进程里
 *   post：tag = BTHeadset + MAC、id = 10003、channel = hyperpods_moondrop_bt_headset）。
 *   模块没在 LSPosed 激活时，那个进程里根本没有本模块的代码，通知就永远不会出现 —— 即使本应用
 *   已经自己连上耳机、也已经拿到三路电量（协议栈在应用进程，见 pods/MoondropLink.kt）。
 *   本文件让**应用进程自己**发一条等价通知：不需要 root，也不需要模块激活。
 *
 * 去重策略（判定写在 [hookWillNotify]，取舍写在下面）：
 *   **只在 hook 不会发的时候由应用发**。
 *   为什么不做「应用一直发、激活时由应用压制自己那条」：hook 那条通知是**另一个应用**
 *   （com.xiaomi.bluetooth）发的，应用进程既查不到也撤不掉它（NotificationManager 只能看/撤
 *   自己应用的通知）；要压制它只能让 hook 侧留一个「我发过了」的回执，而 hook/ 不在本轮改动
 *   范围（激活时它那条必须照旧有用）。所以两边严格互斥：判定 hook 会发时，应用撤销自己那条，
 *   屏幕上只留 hook 那条（它另外还有焦点通知 / 超级岛）。
 *
 * 与 hook 那条完全区分（两边同时活着也不会互相覆盖或误撤）：
 *   channel = hyperpods_moondrop_app_status   （hook：hyperpods_moondrop_bt_headset）
 *   tag     = HyperPodsAppState               （hook：BTHeadset + MAC）
 *   id      = 10004                           （hook：10003）
 *
 * 开关：读设置页写的那份 hyperpods_moondrop_settings（与 hook 侧同一个组名）：
 *   · SHOW_NOTIFICATION（「通知栏显示」，默认 true）—— 这条通知的总开关；
 *   · ENABLE（模块总开关，默认 true）—— 跟随设置页把「通知栏显示」开关的 enabled 绑在总开关上的
 *     口径（ui/pages/SettingsPage.kt：enabled = settings.enabled），总开关关掉时也不发。
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
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.MODULE_PREFS_GROUP
import moe.chenxy.hyperpods.ui.currentXposedService
import moe.chenxy.hyperpods.ui.hookNotificationScopeActive
import moe.chenxy.hyperpods.ui.primeXposedServiceRegistration
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey

private const val TAG = "HyperPods-PodNotify"

object PodNotification {

    /** 通知通道：IMPORTANCE_LOW，状态栏有图标、不响铃不打扰（与 hook 侧同档）。 */
    private const val CHANNEL_ID = "hyperpods_moondrop_app_status"

    /** 与 hook 侧的 BTHeadset + MAC 区分开，两边的 cancel 不会互相误伤。 */
    private const val NOTIFICATION_TAG = "HyperPodsAppState"

    /** 与 hook 侧的 10003 区分开（同一应用内 id 唯一，跨应用其实不冲突，区分是为了排查时一眼可辨）。 */
    private const val NOTIFICATION_ID = 10004

    /**
     * 「框架服务还没绑上来」的宽限窗口：应用刚起来时 XposedService 的绑定是异步的，这段时间里
     * [currentXposedService] 为 null，不能立刻当成「模块未激活」—— 否则模块真的激活时会多发
     * 一条重复通知。窗口从 [prime] 那次注册算起，只影响进程刚起来的那一小段。
     */
    private const val SERVICE_BIND_GRACE_MS = 2_000L

    /** 宽限窗口内两次判定的间隔。 */
    private const val SERVICE_BIND_POLL_MS = 200L

    /** 电量各组件之间的分隔符（与 hook 侧 contentText 的左右耳分隔同一形态，紧凑一行）。 */
    private const val PART_SEPARATOR = " · "

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 判定 + 落地整段串行化：并发事件不会交叉出两条状态不一致的通知。 */
    private val lock = Mutex()

    /** 本进程第一次被要求关注模块激活状态的时刻；0 = 还没 prime 过（宽限窗口的起点）。 */
    @Volatile private var primedAtMs = 0L

    /**
     * 上一条已落地的通知内容（标题 + 正文）。完全相同就不再 notify：电量是 30s 一轮的轮询，
     * 不判重会把同一条通知反复重发（虽然系统不会响，但会平白刷新时间戳）。
     */
    @Volatile private var lastRendered: String? = null

    /** 当前是否有一条本应用发出的通知（只用于日志去重，不参与任何判定）。 */
    @Volatile private var posted = false

    // ── 入口 ────────────────────────────────────────────────────────────────

    /**
     * 应用进程初始化时调用一次（ControlBridge.ensureInit，主线程）：
     *   ① 提前触发 libxposed 的框架服务绑定 —— 这样「连上耳机后该不该由自己发」是在服务状态
     *      已经确定之后判的，而不是在进程刚起来那一瞬间（见 [SERVICE_BIND_GRACE_MS]）；
     *   ② 按当前快照对一次账：进程重启后把自己那条重画（耳机没连就撤掉，不留旧通知）。
     *
     * 幂等：重复调用只重跑对账，宽限窗口的起点以第一次为准。
     */
    fun prime(context: Context) {
        if (primedAtMs == 0L) primedAtMs = SystemClock.elapsedRealtime()
        runCatching { primeXposedServiceRegistration() }
            .onFailure { Log.w(TAG, "prime XposedService registration failed: ${it.message}") }
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

    /** 断开连接时调用：撤掉自己那条（hook 侧那条由 ControlBridge 的广播路径撤）。 */
    fun cancel(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { lock.withLock { cancelNow(app, "disconnected") } }
                .onFailure { Log.w(TAG, "cancel failed: ${it.message}") }
        }
    }

    /**
     * 设置页改了「通知栏显示」/「模块总开关」后立即生效（不必等下一次电量轮询）：
     * 打开就按当前快照重画一条，关掉就把已经发出去的那条撤掉。
     */
    fun refreshFromSnapshot(context: Context) = onSnapshot(context, MoondropLink.snapshot())

    // ── 判定 + 落地 ──────────────────────────────────────────────────────────

    /** 一次完整决策：不该发就撤销，该发就（按需）落地。整段在 [lock] 内串行。 */
    private suspend fun sync(app: Context, snapshot: PodSnapshot) = lock.withLock {
        when {
            !snapshot.connected -> cancelNow(app, "not connected")
            !notificationsEnabled(app) -> cancelNow(app, "disabled by settings")
            hookWillNotify() -> cancelNow(app, "hook side will post; app notification suppressed")
            else -> post(app, snapshot)
        }
    }

    /**
     * hook 侧（com.xiaomi.bluetooth 进程里的 MiBluetoothToastHook）这次会不会自己发一条。
     * 返回 true = 本应用**不发**。
     *
     * 依据全部来自既有信号，没有新造：
     *   · `currentXposedService() != null` —— libxposed 的框架服务绑上了（就是
     *     ui/XposedServiceState.kt 那份缓存，模块页那张状态卡读的是同一条）。真机实测：
     *     在 LSPosed 里把本模块关掉（modules_state.enabled=0）时它一直是 null，模块页显示
     *     「LSPosed 未激活」；
     *   · 服务报出来的作用域里有 com.xiaomi.bluetooth —— 那正是 MiBluetoothToastHook 被注入
     *     该进程的前提（作用域见 META-INF/xposed/scope.list）。
     * 服务还没绑上来且仍在宽限窗口内 → 等一下再判，避免把「还没绑」误判成「没激活」而多发一条。
     */
    private suspend fun hookWillNotify(): Boolean {
        val deadline = primedAtMs + SERVICE_BIND_GRACE_MS
        // 有界等待：最多等到宽限窗口用完，每 SERVICE_BIND_POLL_MS 重判一次（不无限循环）
        val maxTries = (SERVICE_BIND_GRACE_MS / SERVICE_BIND_POLL_MS).toInt() + 1
        repeat(maxTries) {
            val service = currentXposedService()
            if (service != null) {
                val active = hookNotificationScopeActive(service)
                Log.i(
                    TAG,
                    "XposedService bound; com.xiaomi.bluetooth scope=$active -> " +
                        "app notification ${if (active) "suppressed" else "used"}",
                )
                return active
            }
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) return false
            delay(minOf(SERVICE_BIND_POLL_MS, left))
        }
        Log.i(TAG, "no XposedService bound after grace; module not activated -> app notification used")
        return false
    }

    /**
     * 「通知栏显示」+「模块总开关」是否允许应用自己发。
     *
     * 读不到 prefs（文件不存在 / 读抛异常）时按 true 处理 —— 与 hook 侧 notificationDisplayEnabled
     * 同一取舍：宁可多显示一条，也不要因为读设置失败而静默不显示。
     */
    private fun notificationsEnabled(context: Context): Boolean {
        val prefs = runCatching {
            context.getSharedPreferences(MODULE_PREFS_GROUP, Context.MODE_PRIVATE)
        }.getOrNull() ?: return true
        val enabled = runCatching { prefs.getBoolean(HyperPodsPrefsKey.ENABLE, true) }.getOrDefault(true)
        val show = runCatching { prefs.getBoolean(HyperPodsPrefsKey.SHOW_NOTIFICATION, true) }
            .getOrDefault(true)
        if (!enabled || !show) {
            Log.i(TAG, "app notification off (enable=$enabled, show_notification=$show)")
        }
        return enabled && show
    }

    // ── 通知本体 ─────────────────────────────────────────────────────────────

    /** 建/校正通道，然后 notify。内容与上一条相同则跳过（见 [lastRendered]）。 */
    private fun post(context: Context, snapshot: PodSnapshot) {
        val title = titleOf(context, snapshot)
        val content = contentOf(context, snapshot)
        val rendered = "$title\n$content"
        if (posted && rendered == lastRendered) return
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
                .setOngoing(false)
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build())
            lastRendered = rendered
            posted = true
            Log.i(TAG, "app notification posted: $title / ${content.replace('\n', ' ')}")
        }.onFailure { Log.e(TAG, "post app notification failed", it) }
    }

    /** 撤掉自己那条；[reason] 只进日志。始终调用 cancel（幂等），日志只在状态翻转时打一条。 */
    private fun cancelNow(context: Context, reason: String) {
        lastRendered = null
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
     * 通道的重要性**创建之后不可修改**，所以发现同名通道的档位不是 IMPORTANCE_LOW 时先删掉再
     * 按 LOW 建一次（hook 侧 ensureChannel 同一写法）。通道名/描述只在创建那一刻生效。
     */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        runCatching {
            val existing = runCatching { manager.getNotificationChannel(CHANNEL_ID) }.getOrNull()
            if (existing != null && existing.importance != NotificationManager.IMPORTANCE_LOW) {
                Log.i(TAG, "recreate channel $CHANNEL_ID: importance ${existing.importance} -> IMPORTANCE_LOW")
                manager.deleteNotificationChannel(CHANNEL_ID)
            }
            if (runCatching { manager.getNotificationChannel(CHANNEL_ID) }.getOrNull() != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_status),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = context.getString(R.string.notification_channel_status_desc)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "channel created: $CHANNEL_ID")
        }.onFailure { Log.w(TAG, "createNotificationChannel($CHANNEL_ID) failed", it) }
    }

    /**
     * 点击进本应用：与 hook 那条通知同一个 action（HyperPodsAction.SHOW_POPUP → ui/PopupActivity，
     * 见 AndroidManifest.xml 的 intent-filter），所以两种情况下点通知的落点完全一致。
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
