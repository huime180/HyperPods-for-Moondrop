/*
 * HyperPods for Moondrop — 应用首选项（设置页读写的一层薄封装）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 键与默认值的唯一来源是 utils/data/HyperPodsPrefsKey.kt。
 * 本应用已不再是 Xposed 模块，因此这里只剩两个应用自己的偏好（通知栏显示 / 连接时自动唤出弹窗）：
 * 原来的总开关 / 焦点显示 / 超级岛 / 型号伪装 / 调试日志都要 hook 侧一起工作，
 * 随 hook 一起删除，对应的键也不在 HyperPodsPrefsKey 里了。
 *
 * 组名保持 "hyperpods_moondrop_settings" 不变 —— 老用户已经写进这个文件的
 * 「通知栏显示」值要能被读出来；pods/PodNotification.kt 读的是同一组。
 *
 * 注意：本文件只负责「读写 + 可组合状态」，不重复实现任何业务逻辑。
 */
package moe.chenxy.hyperpods.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey

/**
 * 首选项组名。internal：pods/PodNotification.kt（应用自己那条状态通知的开关）读的是同一组，
 * 不在这里再抄一份字符串。
 */
internal const val MODULE_PREFS_GROUP = "hyperpods_moondrop_settings"

@Stable
class ModuleSettingsState internal constructor(private val prefs: SharedPreferences) {

    private val notificationState = mutableStateOf(prefs.getBoolean(HyperPodsPrefsKey.SHOW_NOTIFICATION, true))
    private val autoPopupState =
        mutableStateOf(prefs.getBoolean(HyperPodsPrefsKey.AUTO_POPUP_ON_CONNECT, true))

    val showNotification: Boolean get() = notificationState.value

    fun setShowNotification(value: Boolean) {
        notificationState.value = value
        prefs.edit().putBoolean(HyperPodsPrefsKey.SHOW_NOTIFICATION, value).apply()
    }

    /** 「连接时自动唤出弹窗」：耳机连上后是否自动弹出连接弹窗（pods/ControlBridge.kt 读同一个键）。 */
    val autoPopupOnConnect: Boolean get() = autoPopupState.value

    fun setAutoPopupOnConnect(value: Boolean) {
        autoPopupState.value = value
        prefs.edit().putBoolean(HyperPodsPrefsKey.AUTO_POPUP_ON_CONNECT, value).apply()
    }
}

@Composable
fun rememberModuleSettings(): ModuleSettingsState {
    val context = LocalContext.current
    return remember(context) {
        ModuleSettingsState(
            context.applicationContext.getSharedPreferences(MODULE_PREFS_GROUP, Context.MODE_PRIVATE)
        )
    }
}

/*
 * 主题模式用的偏好文件就是 ui/PodState.kt 那份 UI 侧偏好（同一个文件，避免 UI 偏好分散成两份），
 * 因此不再在这里另存一份文件名字符串：直接复用同包内的 UI_PREFS_GROUP（见 PodState.kt）。
 */

/** 主题模式：0 跟随系统 / 1 浅色 / 2 深色（与 ui/App.kt 的映射一致）。 */
const val THEME_MODE_KEY = "theme_mode"

fun loadThemeMode(context: Context): Int =
    context.getSharedPreferences(UI_PREFS_GROUP, Context.MODE_PRIVATE).getInt(THEME_MODE_KEY, 0)

fun saveThemeMode(context: Context, mode: Int) {
    context.getSharedPreferences(UI_PREFS_GROUP, Context.MODE_PRIVATE)
        .edit()
        .putInt(THEME_MODE_KEY, mode)
        .apply()
}
