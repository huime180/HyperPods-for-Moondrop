/*
 * MiuixMoondrop — 通知里「断开连接」按钮的落点
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 谁触发它：pods/PodNotification.kt 那条常驻通知上的动作按钮（AndroidManifest.xml 里静态声明，
 *   android:exported="false"）。通知的 PendingIntent 由本应用创建，投递时同样以本应用身份执行，
 *   所以不需要导出、别的应用也调不到它。
 *
 * 它能断到哪一步（写清楚，免得误以为是系统蓝牙那条「断开连接」）：
 *   · 断掉的是**本应用**与耳机之间的那条链路（MoondropLink：GAIA over GATT / RFCOMM）。
 *     MoondropLink.disconnect() 会 emit PodEvent.Disconnected，pods/ControlBridge.kt 据此
 *     撤掉通知并刷新界面状态 —— 按下去立刻能看到结果。
 *   · A2DP / HFP 那条**音频**链路归系统蓝牙管：普通应用没有 BLUETOOTH_PRIVILEGED，
 *     平台也没有给第三方应用开放 profile 的 disconnect API，所以本应用无法代用户断开它。
 *     要在系统层面断连，走系统蓝牙设备页（本应用在设置页/弹窗里提供了入口）。
 */
package moe.huime.miuixmoondrop.pods

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.huime.miuixmoondrop.utils.data.HyperPodsAction

private const val TAG = "HyperPods-PodDisconnect"

/** 通知动作按钮 → 断开本应用与耳机的那条链路。见文件头说明。 */
class PodDisconnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HyperPodsAction.POD_DISCONNECT) return
        // disconnect() 只关 socket / GATT 并取消轮询协程，不阻塞；onReceive 里直接调即可。
        runCatching { MoondropLink.disconnect() }
            .onFailure { Log.w(TAG, "disconnect from notification action failed", it) }
    }
}
