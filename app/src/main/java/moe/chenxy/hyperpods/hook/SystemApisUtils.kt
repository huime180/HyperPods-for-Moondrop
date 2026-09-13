/*
 * HyperPods for Moondrop — 模块内共用的小工具（全部只用反射碰系统内部 API）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 参考 HyperPods `utils/SystemApisUtils.kt`（YukiHookAPI 版）里的：
 *   isHyperOS / getUserAllUserHandle / setIconVisibility / BATTERY_LEVEL_UNKNOWN
 * 这里改为 libxposed API 102 风格：不引用任何框架内部编译期符号，全部走反射，失败即降级。
 *
 * ── 三路电量 extra 约定（跨进程契约，应用进程负责发送） ────────────────────────
 * HyperPodsAction 只声明了 EXTRA_BATTERY("batteryParams") / EXTRA_LEVEL("level")，
 * 没有左右耳/充电盒的键名，因此这里固定一套键名，应用进程必须按此发送：
 *   1) 首选：EXTRA_BATTERY 放 android.os.Bundle，键为 left / right / case（Int），
 *      可选 left_charging / right_charging / case_charging（Boolean）；
 *      电量值 255 或负数 = 未知；充电位单独给布尔值，本层统一编码成 `value or 128`。
 *   2) 兜底：EXTRA_BATTERY 放一个 Parcelable（如 pods/PodSnapshot 风格的对象），
 *      反射读 getLeft/getRight/getCase -> getBattery|getLevel + isCharging。
 *   3) 再兜底：EXTRA_LEVEL（Int，单路电量）同时套用到三路（如 EDGE 只上报单设备电量）。
 * 对外统一返回 **原始** HyperOS 编码：0..100 有效值、255 未知、`value or 128` 表示充电中。
 */
package moe.chenxy.hyperpods.hook

import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

object SystemApisUtils {
    private const val TAG = "HyperPods-SysApi"

    /** 与 HyperPods 参考实现一致：-1 = 未知电量。 */
    const val BATTERY_LEVEL_UNKNOWN: Int = -1

    /** MIUI/HyperOS 电量负载里的「未知」。 */
    const val BATTERY_RAW_UNKNOWN: Int = 255

    // 三路电量 Bundle 键名（见文件头约定）
    const val KEY_LEFT = "left"
    const val KEY_RIGHT = "right"
    const val KEY_CASE = "case"
    const val KEY_LEFT_CHARGING = "left_charging"
    const val KEY_RIGHT_CHARGING = "right_charging"
    const val KEY_CASE_CHARGING = "case_charging"

    /** HyperOS 判定：ro.mi.os.version.code 非空（优先 SystemProperties 反射，失败回退 getprop）。 */
    fun isHyperOS(): Boolean = systemProperty("ro.mi.os.version.code").isNotEmpty()

    fun systemProperty(name: String): String {
        val viaReflection = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getDeclaredMethod("get", String::class.java)
            method.isAccessible = true
            method.invoke(null, name) as? String
        }.getOrNull()
        if (!viaReflection.isNullOrEmpty()) return viaReflection
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
            val line = BufferedReader(InputStreamReader(process.inputStream), 1024).use { it.readLine() }
            process.waitFor()
            line ?: ""
        }.getOrDefault("")
    }

    /** UserHandle.ALL（反射 UserHandle.ALL，失败再试 Process.myUserHandle）。 */
    fun getUserAllUserHandle(): UserHandle? {
        runCatching {
            val handle = getStaticObjectField(Class.forName("android.os.UserHandle"), "ALL") as? UserHandle
            if (handle != null) return handle
        }
        return runCatching {
            val cls = Class.forName("android.os.Process")
            val method = cls.getDeclaredMethod("myUserHandle")
            method.isAccessible = true
            method.invoke(null) as? UserHandle
        }.getOrNull()
    }

    fun statusBarManager(context: Context?): Any? =
        runCatching { context?.getSystemService("statusbar") }.getOrNull()

    /**
     * 反射调用 StatusBarManager#setIconVisibility(String, boolean)。
     * 「wireless_headset」图标名由调用方给出（本模块固定用该名字）。
     */
    fun setIconVisibility(statusBarManager: Any?, name: String, visible: Boolean): Boolean {
        val ok = runCatching { callMethod(statusBarManager, "setIconVisibility", name, visible) }.isSuccess
        if (!ok) Log.w(TAG, "setIconVisibility($name, $visible) unavailable")
        return ok
    }

    fun notificationManager(context: Context?): NotificationManager? =
        runCatching { context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager }.getOrNull()

    fun notifyAsUser(
        manager: NotificationManager?,
        tag: String,
        id: Int,
        notification: Notification,
        user: UserHandle?
    ) {
        if (manager == null) return
        if (user != null) {
            val ok = runCatching { callMethod(manager, "notifyAsUser", tag, id, notification, user) }.isSuccess
            if (ok) return
        }
        runCatching { manager.notify(tag, id, notification) }
            .onFailure { Log.w(TAG, "notify($tag, $id) failed", it) }
    }

    fun cancelAsUser(manager: NotificationManager?, tag: String, id: Int, user: UserHandle?) {
        if (manager == null) return
        if (user != null) {
            val ok = runCatching { callMethod(manager, "cancelAsUser", tag, id, user) }.isSuccess
            if (ok) return
        }
        runCatching { manager.cancel(tag, id) }
            .onFailure { Log.w(TAG, "cancel($tag, $id) failed", it) }
    }

    /** SystemUI 进程在 PluginInstance 阶段还没有 Context，用 ActivityThread.currentApplication() 取。 */
    fun currentApplication(): Context? = runCatching {
        val cls = Class.forName("android.app.ActivityThread")
        val method = cls.getDeclaredMethod("currentApplication")
        method.isAccessible = true
        method.invoke(null) as? Context
    }.getOrNull()

    fun deviceName(device: BluetoothDevice?): String =
        runCatching { device?.name ?: device?.alias }.getOrNull().orEmpty()

    fun deviceAddress(device: BluetoothDevice?): String =
        runCatching { device?.address }.getOrNull().orEmpty()

    @Suppress("DEPRECATION")
    fun parcelableDevice(intent: Intent?, key: String): BluetoothDevice? {
        if (intent == null) return null
        return runCatching { intent.getParcelableExtra(key, BluetoothDevice::class.java) }.getOrNull()
            ?: runCatching { intent.getParcelableExtra<BluetoothDevice>(key) }.getOrNull()
    }

    /** 三路电量 extra 读取（约定见文件头）。返回 [左, 右, 充电盒] 的原始 HyperOS 编码。 */
    fun readBatteryExtras(intent: Intent?): IntArray {
        val out = intArrayOf(BATTERY_RAW_UNKNOWN, BATTERY_RAW_UNKNOWN, BATTERY_RAW_UNKNOWN)
        if (intent == null) return out

        val bundle = runCatching { intent.getBundleExtra(HyperPodsAction.EXTRA_BATTERY) }.getOrNull()
        if (bundle != null && readFromBundle(bundle, out)) return out

        val parcelable = runCatching { intent.getParcelableExtra<Any>(HyperPodsAction.EXTRA_BATTERY) }.getOrNull()
        if (parcelable != null && readFromParcelable(parcelable, out)) return out

        val level = intent.getIntExtra(HyperPodsAction.EXTRA_LEVEL, BATTERY_LEVEL_UNKNOWN)
        if (level in 0..100) {
            out[0] = level
            out[1] = level
            out[2] = level
        }
        return out
    }

    private fun readFromBundle(bundle: Bundle, out: IntArray): Boolean {
        val any = bundle.containsKey(KEY_LEFT) || bundle.containsKey(KEY_RIGHT) || bundle.containsKey(KEY_CASE)
        if (!any) return false
        out[0] = encode(bundle.getInt(KEY_LEFT, BATTERY_LEVEL_UNKNOWN), bundle.getBoolean(KEY_LEFT_CHARGING, false))
        out[1] = encode(bundle.getInt(KEY_RIGHT, BATTERY_LEVEL_UNKNOWN), bundle.getBoolean(KEY_RIGHT_CHARGING, false))
        out[2] = encode(bundle.getInt(KEY_CASE, BATTERY_LEVEL_UNKNOWN), bundle.getBoolean(KEY_CASE_CHARGING, false))
        return true
    }

    /** BatteryParams 风格对象：getLeft/getRight/getCase -> getBattery|getLevel + isCharging。 */
    private fun readFromParcelable(payload: Any, out: IntArray): Boolean {
        var found = false
        listOf("getLeft" to 0, "getRight" to 1, "getCase" to 2).forEach { (getter, index) ->
            val part = runCatching { callMethod(payload, getter) }.getOrNull() ?: return@forEach
            found = true
            if (part is Int) {
                out[index] = encode(part, false)
                return@forEach
            }
            val level = listOf("getBattery", "getLevel", "getBatteryLevel")
                .firstNotNullOfOrNull { name -> runCatching { callMethod(part, name) as? Int }.getOrNull() }
            val charging = listOf("isCharging", "getCharging")
                .firstNotNullOfOrNull { name -> runCatching { callMethod(part, name) as? Boolean }.getOrNull() }
                ?: false
            out[index] = encode(level ?: BATTERY_LEVEL_UNKNOWN, charging)
        }
        return found
    }

    /** 拼成 HyperOS 负载里的电量字段：未知 = 255，充电 = value or 128。 */
    fun encode(level: Int, charging: Boolean): Int {
        if (level < 0 || level > 100) return BATTERY_RAW_UNKNOWN
        return if (charging) level or 128 else level
    }

    /** 原始编码 -> 真实电量（0..100），未知返回 BATTERY_LEVEL_UNKNOWN(-1)。 */
    fun decodeLevel(raw: Int): Int = when {
        raw < 0 -> BATTERY_LEVEL_UNKNOWN
        raw in 128..228 -> raw and 0x7F
        raw in 0..100 -> raw
        else -> BATTERY_LEVEL_UNKNOWN
    }

    fun decodeCharging(raw: Int): Boolean = raw in 128..228
}
