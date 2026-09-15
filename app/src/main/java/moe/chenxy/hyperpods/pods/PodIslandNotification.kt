/*
 * MiuixMoondrop — 超级岛（只在连接 / 断开那一刻显示一小会儿）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么单独一份通知：超级岛要「只出现一会」，而 HyperOS 只要常驻通知上带着 `param_island`
 * 就会把岛一直挂着。所以岛走这条独立的、临时的通知：
 *   · `isShowNotification = false`：只在岛区域浮现，不在通知栏里多出一条（也不会闪一下）；
 *   · `timeout`：交给系统按时收起；另外再用协程兜一层延时撤掉（个别 ROM 不认 timeout）；
 *   · 内容只在连接那一刻（电量）与断开那一刻（「已断开」）各发一次，之后不再刷新。
 *
 * 触发点见 pods/ControlBridge.kt：连接用「本次会话没发过」做边沿判定（PodEvent.Connected 是
 * 全量状态事件，每次电量变化都会来一条，不能直接拿它当「刚连上」）。
 */
package moe.chenxy.hyperpods.pods

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.hyperpods.R

object PodIslandNotification {
    private const val TAG = "MiuixMoondropIsland"
    private const val CHANNEL_ID = "hyperpods_moondrop_island"
    private const val NOTIFICATION_ID = 10005

    /** 岛显示多久（秒）。用户要求「只显示一会」——5 秒接近系统级提示的体感。 */
    private const val ISLAND_TIMEOUT_SECONDS = 5

    /** timeout 之外再兜一点时间才撤（避免刚好卡在系统收起的瞬间）。 */
    private const val HIDE_EXTRA_SECONDS = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 上一次「延时收起」的协程：新的岛来了就取消它，免得把新岛一起撤掉。 */
    private var pendingHide: Job? = null

    /** 连接时：岛上显示设备名 + 电量。 */
    fun showConnected(context: Context, deviceName: String, batteryText: String, boxBitmap: Bitmap?) {
        show(context, deviceName, batteryText, boxBitmap)
    }

    /** 断开时：岛上显示设备名 + 「已断开」。 */
    fun showDisconnected(context: Context, deviceName: String, boxBitmap: Bitmap?) {
        show(context, deviceName, context.getString(R.string.island_disconnected), boxBitmap)
    }

    private fun show(context: Context, deviceName: String, contentText: String, boxBitmap: Bitmap?) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                ensureChannel(app)
                val extras = PodFocusNotification.buildExtras(
                    context = app,
                    titleText = deviceName.ifBlank { app.getString(R.string.unknown_model) },
                    contentText = contentText,
                    // 岛是临时的，不写 AOD（息屏那行留给常驻通知那条焦点通知）
                    aodText = "",
                    boxBitmap = boxBitmap,
                    withIsland = true,
                    timeoutSeconds = ISLAND_TIMEOUT_SECONDS,
                    showInShade = false,
                ) ?: return@launch
                val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(
                    NOTIFICATION_ID,
                    Notification.Builder(app, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle(deviceName)
                        .setContentText(contentText)
                        .addExtras(extras)
                        .build(),
                )
                Log.i(TAG, "island shown: $deviceName / $contentText")
                pendingHide?.cancel()
                pendingHide = scope.launch {
                    delay((ISLAND_TIMEOUT_SECONDS + HIDE_EXTRA_SECONDS) * 1000L)
                    runCatching { manager.cancel(NOTIFICATION_ID) }
                }
            }.onFailure { Log.w(TAG, "island show failed", it) }
        }
    }

    /**
     * 岛用的通道：与常驻通知那条分开，这样用户在系统通知设置里能单独关掉岛。
     * DEFAULT + 关声音/震动（与常驻通知同口径：要进焦点通知渲染路径就不能用 LOW）。
     */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (runCatching { manager.getNotificationChannel(CHANNEL_ID) }.getOrNull() != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_island),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }
}
