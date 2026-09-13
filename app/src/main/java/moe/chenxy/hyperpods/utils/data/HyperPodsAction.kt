/*
 * HyperPods for Moondrop — 跨进程广播契约
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 模块跨 4 个进程工作，进程间只靠广播通信（不共享内存、不写系统设置）：
 *   com.android.bluetooth   —— 耳机连接状态感知 + 把电量写进系统蓝牙栈
 *   com.xiaomi.bluetooth    —— 通知 / 超级岛电量展示
 *   com.android.systemui    —— 融合设备中心耳机卡的点击接管
 *   com.android.settings    —— 伪装成小米原生耳机页，把系统页面的操作路由回本模块
 *   本应用进程               —— 协议客户端 + UI
 *
 * ⚠ 所有跨进程广播都必须 setPackage(...)：Android 14+ 会丢弃未指定包名的隐式广播。
 */
package moe.chenxy.hyperpods.utils.data

object HyperPodsAction {

    /** 打开本模块 UI（设备卡点击、通知点击都用它） */
    const val SHOW_UI = "chen.action.hyperpods.moondrop.show_ui"

    /**
     * 打开「快速弹窗」（PuddingPods 形态的主入口）：超级岛 / 设备卡 / 通知点击走这里，
     * 只展示电量 + 降噪 + 快捷控制，详情与设置再从弹窗进入 SHOW_UI 的详情页。
     */
    const val SHOW_POPUP = "chen.action.hyperpods.moondrop.show_popup"

    /** 设备卡点击时 SystemUI 广播索要当前耳机 MAC */
    const val GET_PODS_MAC = "chen.action.hyperpods.moondrop.get_pods_mac"
    /** 蓝牙进程回 MAC（与 GET_PODS_MAC 同名字符串，靠方向区分，与原 HyperPods 一致） */
    const val PODS_MAC_RECEIVED = "chen.action.hyperpods.moondrop.get_pods_mac"

    // ── 蓝牙进程 / 设置进程 → 应用 UI ────────────────────────────────────
    const val PODS_CONNECTED = "chen.action.hyperpods.moondrop.pods_connected"
    const val PODS_DISCONNECTED = "chen.action.hyperpods.moondrop.pods_disconnected"
    const val BATTERY_CHANGED = "chen.action.hyperpods.moondrop.battery_changed"
    const val ANC_CHANGED = "chen.action.hyperpods.moondrop.anc_changed"
    const val GAIN_CHANGED = "chen.action.hyperpods.moondrop.gain_changed"
    const val LED_CHANGED = "chen.action.hyperpods.moondrop.led_changed"
    const val PROMPT_TONE_CHANGED = "chen.action.hyperpods.moondrop.prompt_tone_changed"
    const val PROMPT_VOLUME_CHANGED = "chen.action.hyperpods.moondrop.prompt_volume_changed"
    const val LHDC_CHANGED = "chen.action.hyperpods.moondrop.lhdc_changed"
    const val DUAL_CONNECTION_CHANGED = "chen.action.hyperpods.moondrop.dual_connection_changed"
    /**
     * 手势配置变化（应用进程 → 设置进程）。载荷 = feature 22 TOUCHV2 的 **5 字节** 配置
     * （每字节高 4 位 = 左耳动作 id、低 4 位 = 右耳动作 id，见 core/Gaia.GestureConf）。
     * 设置进程用它把原生耳机页里我们自己托管的手势控件刷成真实值。
     */
    const val GESTURE_CHANGED = "chen.action.hyperpods.moondrop.gesture_changed"
    const val LOW_LATENCY_CHANGED = "chen.action.hyperpods.moondrop.low_latency_changed"
    /**
     * 蓝牙进程报告**系统实际协商出来的** A2DP 编码名（SBC / AAC / LDAC / LHDC…）。
     * 应用进程收到后交给 MoondropLink.onSystemCodecChanged()，详情页「当前编码」显示它。
     */
    const val CODEC_CHANGED = "chen.action.hyperpods.moondrop.codec_changed"
    const val CAPABILITIES_CHANGED = "chen.action.hyperpods.moondrop.capabilities_changed"
    const val DEBUG_LOG = "chen.action.hyperpods.moondrop.debug_log"

    // ── 应用 UI → 蓝牙进程 ───────────────────────────────────────────────
    /** 请求全量状态重放（UI 打开时调用） */
    const val UI_INIT = "chen.action.hyperpods.moondrop.ui_init"
    const val ANC_SELECT = "chen.action.hyperpods.moondrop.anc_select"
    /**
     * 手势槽位选择（设置页 → 应用进程）：用户在原生耳机页托管的手势控件里改了一个半字节。
     * 应用进程按 (EXTRA_GESTURE_SLOT, EXTRA_GESTURE_EAR, EXTRA_STATUS) 调 MoondropLink.setGesture。
     */
    const val GESTURE_SELECT = "chen.action.hyperpods.moondrop.gesture_select"
    const val GAIN_SELECT = "chen.action.hyperpods.moondrop.gain_select"
    const val LED_SELECT = "chen.action.hyperpods.moondrop.led_select"
    const val PROMPT_TONE_SELECT = "chen.action.hyperpods.moondrop.prompt_tone_select"
    const val PROMPT_VOLUME_SELECT = "chen.action.hyperpods.moondrop.prompt_volume_select"
    const val LHDC_SELECT = "chen.action.hyperpods.moondrop.lhdc_select"
    const val DUAL_CONNECTION_SELECT = "chen.action.hyperpods.moondrop.dual_connection_select"
    const val LOW_LATENCY_SELECT = "chen.action.hyperpods.moondrop.low_latency_select"
    /** 请求重新探测能力 */
    const val REQUEST_CAPABILITIES = "chen.action.hyperpods.moondrop.request_capabilities"
    /** 请求重新读取三路电量 */
    const val REQUEST_BATTERY = "chen.action.hyperpods.moondrop.request_battery"
    /** 请求重放一次当前手势配置（设置进程打开原生耳机页时调用） */
    const val REQUEST_GESTURE = "chen.action.hyperpods.moondrop.request_gesture"

    // ── 蓝牙进程 → 小米蓝牙进程（通知 / 超级岛） ─────────────────────────
    const val SEND_STRONG_TOAST = "chen.action.hyperpods.moondrop.sendstrongtoast"
    const val UPDATE_PODS_NOTIFICATION = "chen.action.hyperpods.moondrop.updatepodsnotification"
    const val CANCEL_PODS_NOTIFICATION = "chen.action.hyperpods.moondrop.cancelpodsnotification"

    // ── 蓝牙进程 → 系统蓝牙栈（把电量写进 AdapterService） ───────────────
    /** 由蓝牙进程内的 hook 接收，调用 AdapterService.setBatteryLevel */
    const val UPDATE_SYSTEM_BATTERY = "chen.action.hyperpods.moondrop.update_system_battery"

    // ── 应用 UI → 全部被注入进程（「重启作用域」） ─────────────────────────
    /**
     * 「重启作用域」Tier 1：应用 UI 显式广播（逐个 setPackage 作用域包名）给 5 个被注入进程，
     * 进程内的 hook.RestartScopeReceiver 收到后打日志并 killProcess(myPid()) 自杀，
     * 系统重新拉起进程时 LSPosed 会重新注入本模块 —— 等效于 LSPosed Manager 的「重启作用域」，
     * 不需要重启手机、不需要 root（Tier 2 的 root 兜底见 ui/components/RestartScope.kt）。
     */
    const val RESTART_SCOPE = "chen.action.hyperpods.moondrop.restart_scope"

    // extras
    const val EXTRA_STATUS = "status"
    const val EXTRA_BATTERY = "batteryParams"
    const val EXTRA_DEVICE = "device"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_MAC = "mac"
    const val EXTRA_LEVEL = "level"
    const val EXTRA_PROMPT_VOLUME_RAW = "prompt_volume_raw"
    const val EXTRA_ENABLED = "enabled"
    /** CODEC_CHANGED 携带的系统编码名（String，如 "AAC" / "LHDCv5" / "LDAC" / "SBC"）。 */
    const val EXTRA_CODEC = "codec"
    const val EXTRA_MESSAGE = "message"

    // ── 手势（TOUCHV2）extras ────────────────────────────────────────────────
    /** GESTURE_CHANGED 携带的 5 字节配置（ByteArray，高 4 位 = 左耳 / 低 4 位 = 右耳）。 */
    const val EXTRA_GESTURE_PAYLOAD = "gesture_payload"
    /** 手势槽位下标（Int，0 单击 / 1 双击 / 2 三击 / 3 长按1秒 / 4 长按3秒）。 */
    const val EXTRA_GESTURE_SLOT = "gesture_slot"
    /** 耳朵（Int，0 = 左耳 / 1 = 右耳）。 */
    const val EXTRA_GESTURE_EAR = "gesture_ear"
}
