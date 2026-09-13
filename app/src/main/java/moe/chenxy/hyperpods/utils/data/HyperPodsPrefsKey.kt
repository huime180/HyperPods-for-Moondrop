/*
 * HyperPods for Moondrop — 配置键
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.chenxy.hyperpods.utils.data

object HyperPodsPrefsKey {
    /** 模块总开关（关闭后不接管任何设备） */
    const val ENABLE = "enable"

    /** 手动指定型号 id；空 = 自动识别（见 MoondropModels.match） */
    const val MODEL_ID = "model_id"
    const val MODEL_MODE = "model_mode" // auto | manual

    /** 用户覆盖的 ANC 设备码映射（"ui0,ui1,..."），空 = 用型号档案 */
    const val ANC_MAP_CUSTOM = "anc_map_custom"

    /** 提示音开关的命令号覆盖（-1 = 用型号档案默认） */
    const val VOICE_CMD_GET_ENABLE = "voice_cmd_get_enable"
    const val VOICE_CMD_SET_ENABLE = "voice_cmd_set_enable"
    const val VOICE_CMD_GET_VOLUME = "voice_cmd_get_volume"
    const val VOICE_CMD_SET_VOLUME = "voice_cmd_set_volume"
    const val VOICE_VOLUME_MAX = "voice_volume_max"

    /** 显示选项 */
    const val SHOW_NOTIFICATION = "show_notification"
    const val SHOW_STRONG_TOAST = "show_strong_toast"
    const val SHOW_FOCUS_ISLAND = "show_focus_island"

    /** 调试：把 GAIA 收发帧写进 logcat 与调试页 */
    const val DEBUG_LOG = "debug_log"
}
