/*
 * MiuixMoondrop — 应用内 intent 常量
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 普通 App 收窄后只剩两组「应用自己给自己用」的 intent 常量：
 *   · 点通知的落点（SHOW_POPUP → ui/PopupActivity，见 AndroidManifest 的 intent-filter）；
 *   · 连接弹窗（ui/ConnectionPopupActivity）读取的设备名 / 电量 extra，
 *     以及它仍然监听的连接状态动作（pods/ControlBridge.kt 启动它时会把这两个 extra 一起带上）。
 *
 * 原来的跨进程广播契约（蓝牙进程 / 小米蓝牙 / SystemUI / 设置页四个被注入进程之间的
 * ANC、电量、手势、低延迟、重启作用域等动作）随 hook 一起删除：本应用已不是模块，没有对端了。
 */
package moe.chenxy.hyperpods.utils.data

object HyperPodsAction {

    /** 点通知的落点：打开「快速弹窗」（ui/PopupActivity，只展示电量 + 降噪 + 快捷控制）。 */
    const val SHOW_POPUP = "chen.action.hyperpods.moondrop.show_popup"

    // ── 连接状态（ui/ConnectionPopupActivity 用它决定刷新与收窗） ────────────
    const val PODS_CONNECTED = "chen.action.hyperpods.moondrop.pods_connected"
    const val PODS_DISCONNECTED = "chen.action.hyperpods.moondrop.pods_disconnected"
    /** 电量变化；载荷是 [EXTRA_BATTERY]。 */
    const val BATTERY_CHANGED = "chen.action.hyperpods.moondrop.battery_changed"

    /**
     * 通知里「断开连接」按钮的 action。
     *
     * 落点是本应用自己的 pods/PodDisconnectReceiver（通知的 action 用
     * PendingIntent.getBroadcast 指过去；PendingIntent 以本应用身份投递，receiver 不必导出）。
     */
    const val POD_DISCONNECT = "chen.action.hyperpods.moondrop.pod_disconnect"

    // ── extras ───────────────────────────────────────────────────────────────
    /**
     * 电量 Bundle extra（低 7 位电量 + bit7 充电 + 255 未知，
     * 编码见 pods/ControlBridge.kt 的 BatteryCodecWire）。
     */
    const val EXTRA_BATTERY = "batteryParams"
    const val EXTRA_DEVICE_NAME = "device_name"
}
