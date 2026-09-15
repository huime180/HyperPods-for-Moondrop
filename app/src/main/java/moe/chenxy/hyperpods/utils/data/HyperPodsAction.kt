/*
 * MiuixMoondrop — 应用内 intent 常量
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 普通 App 收窄后只剩「应用自己给自己用」的两个 intent 常量：
 *   · 点通知的落点（SHOW_POPUP → ui/PopupActivity，见 AndroidManifest 的 intent-filter）；
 *   · 通知动作「断开连接」（POD_DISCONNECT → pods/PodDisconnectReceiver）。
 *
 * 原来的跨进程广播契约（蓝牙进程 / 小米蓝牙 / SystemUI / 设置页四个被注入进程之间的 ANC、
 * 电量、手势、低延迟、重启作用域等动作）随 hook 一起删除：本应用已不是模块，没有对端了。
 * 连接弹窗时代那组「连接状态 + 电量载荷」的常量（PODS_CONNECTED / PODS_DISCONNECTED /
 * BATTERY_CHANGED / EXTRA_BATTERY / EXTRA_DEVICE_NAME）也随 ConnectionPopupActivity 的删除
 * 一起去掉 —— 弹窗统一成点通知那个之后，界面状态全部走**进程内快照**
 * （ui/PodState.kt 的 rememberPodSnapshot），不再需要任何 Intent / Bundle 载荷。
 */
package moe.chenxy.hyperpods.utils.data

object HyperPodsAction {

    /** 点通知的落点：打开「快速弹窗」（ui/PopupActivity，电量 / 降噪 / 快捷控制）。 */
    const val SHOW_POPUP = "chen.action.hyperpods.moondrop.show_popup"

    /**
     * 通知里「断开连接」按钮的 action。
     *
     * 落点是本应用自己的 pods/PodDisconnectReceiver（通知的 action 用
     * PendingIntent.getBroadcast 指过去；PendingIntent 以本应用身份投递，receiver 不必导出）。
     */
    const val POD_DISCONNECT = "chen.action.hyperpods.moondrop.pod_disconnect"
}
