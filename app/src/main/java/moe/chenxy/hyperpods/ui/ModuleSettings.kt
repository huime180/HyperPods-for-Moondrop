/*
 * HyperPods for Moondrop — 模块设置（设置页读写的一层薄封装）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 键与默认值的唯一来源是 utils/data/HyperPodsPrefsKey.kt；组名必须与
 * hook/XposedEntry.kt 的 PREFS_GROUP 一致，否则设置页写下的值 hook 侧读不到。
 *
 * 注意：本文件只负责「读写 + 可组合状态」，不重复实现任何 hook 逻辑。
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
 * 模块首选项组名，与 hook/XposedEntry.kt 的 PREFS_GROUP 完全一致。
 * internal：pods/PodNotification.kt（应用自己那条状态通知的开关）读的是同一组，
 * 不在这里再抄一份字符串。
 */
internal const val MODULE_PREFS_GROUP = "hyperpods_moondrop_settings"

/** HyperPodsPrefsKey.MODEL_MODE 的两个取值。 */
private const val MODEL_MODE_AUTO = "auto"
private const val MODEL_MODE_MANUAL = "manual"

@Stable
class ModuleSettingsState internal constructor(private val prefs: SharedPreferences) {

    private val enabledState = mutableStateOf(prefs.getBoolean(HyperPodsPrefsKey.ENABLE, true))
    private val notificationState = mutableStateOf(prefs.getBoolean(HyperPodsPrefsKey.SHOW_NOTIFICATION, true))
    private val strongToastState = mutableStateOf(prefs.getBoolean(HyperPodsPrefsKey.SHOW_STRONG_TOAST, true))
    private val focusIslandState = mutableStateOf(prefs.getBoolean(HyperPodsPrefsKey.SHOW_FOCUS_ISLAND, true))
    private val debugLogState = mutableStateOf(prefs.getBoolean(HyperPodsPrefsKey.DEBUG_LOG, false))
    private val modelModeState = mutableStateOf(
        prefs.getString(HyperPodsPrefsKey.MODEL_MODE, MODEL_MODE_AUTO) ?: MODEL_MODE_AUTO
    )
    private val modelIdState = mutableStateOf(prefs.getString(HyperPodsPrefsKey.MODEL_ID, "").orEmpty())

    val enabled: Boolean get() = enabledState.value
    val showNotification: Boolean get() = notificationState.value
    val showStrongToast: Boolean get() = strongToastState.value
    val showFocusIsland: Boolean get() = focusIslandState.value
    val debugLog: Boolean get() = debugLogState.value

    /** true = 自动按蓝牙名识别型号（默认），false = 用户手动指定。 */
    val modelAuto: Boolean get() = modelModeState.value == MODEL_MODE_AUTO

    /** 手动指定的型号 id；空 = 未指定。 */
    val modelId: String get() = modelIdState.value

    fun setEnabled(value: Boolean) {
        enabledState.value = value
        prefs.edit().putBoolean(HyperPodsPrefsKey.ENABLE, value).apply()
    }

    fun setShowNotification(value: Boolean) {
        notificationState.value = value
        prefs.edit().putBoolean(HyperPodsPrefsKey.SHOW_NOTIFICATION, value).apply()
    }

    fun setShowStrongToast(value: Boolean) {
        strongToastState.value = value
        prefs.edit().putBoolean(HyperPodsPrefsKey.SHOW_STRONG_TOAST, value).apply()
    }

    fun setShowFocusIsland(value: Boolean) {
        focusIslandState.value = value
        prefs.edit().putBoolean(HyperPodsPrefsKey.SHOW_FOCUS_ISLAND, value).apply()
    }

    fun setDebugLog(value: Boolean) {
        debugLogState.value = value
        prefs.edit().putBoolean(HyperPodsPrefsKey.DEBUG_LOG, value).apply()
    }

    fun setModelAuto(value: Boolean) {
        val mode = if (value) MODEL_MODE_AUTO else MODEL_MODE_MANUAL
        modelModeState.value = mode
        prefs.edit().putString(HyperPodsPrefsKey.MODEL_MODE, mode).apply()
    }

    fun setModelId(value: String) {
        modelIdState.value = value
        prefs.edit().putString(HyperPodsPrefsKey.MODEL_ID, value).apply()
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
