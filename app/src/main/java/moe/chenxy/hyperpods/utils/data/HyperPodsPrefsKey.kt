/*
 * HyperPods for Moondrop — 配置键
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 普通 App 收窄后只剩两个偏好键，都是应用自己发的东西：
 *   · 通知栏显示 —— 控制 pods/PodNotification.kt 那条应用自己发的耳机状态通知；
 *   · 连接时自动唤出弹窗 —— 控制 pods/ControlBridge.kt 在耳机连上后自动弹出的连接弹窗。
 * 原来的模块总开关 / 型号伪装 / 焦点显示 / 超级岛 / 调试日志 / ANC 映射覆盖 /
 * 提示音命令号覆盖都只对 hook 侧有意义，随 hook 一起删除。
 */
package moe.chenxy.hyperpods.utils.data

object HyperPodsPrefsKey {
    /** 应用侧耳机电量通知开关（设置页「通知栏显示」，默认开） */
    const val SHOW_NOTIFICATION = "show_notification"

    /** 耳机连上后自动弹出连接弹窗（设置页「连接时自动唤出弹窗」，默认开） */
    const val AUTO_POPUP_ON_CONNECT = "auto_popup_on_connect"
}
