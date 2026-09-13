/*
 * HyperPods for Moondrop — libxposed API 102 模块入口
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 架构：协议客户端跑在**本应用进程**，被注入的进程只做三件事：
 *   1) 感知连接（com.android.bluetooth      -> HeadsetStateDispatcher）
 *   2) 把电量写进系统蓝牙栈 / 汇报 MAC
 *   3) 渲染与注入 UI（SystemUI / 小米蓝牙 / 设置）
 * 进程之间只通过广播通信，全部 setPackage(...)（Android 14+ 丢弃隐式广播）。
 *
 * 入口类名必须与 app/src/main/resources/META-INF/xposed/java_init.list 中一致：
 *   moe.chenxy.hyperpods.hook.XposedEntry
 * 作用域见 scope.list：com.android.bluetooth / com.xiaomi.bluetooth / com.android.systemui / com.android.settings
 *
 * 每个进程只注册自己那一个 HookContext；任何注册失败都只打日志，绝不抛给被注入进程。
 */
package moe.chenxy.hyperpods.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import moe.chenxy.hyperpods.BuildConfig

class XposedEntry : XposedModule() {

    companion object {
        const val TAG = "HyperPods-Moondrop"

        /** 远程设置组名（LSPosed 远程首选项；读不到时用内存兜底）。 */
        const val PREFS_GROUP = "hyperpods_moondrop_settings"

        const val PKG_APP = "moe.chenxy.hyperpods.moondrop"
        const val PKG_BLUETOOTH = "com.android.bluetooth"
        const val PKG_XIAOMI_BLUETOOTH = "com.xiaomi.bluetooth"
        const val PKG_SYSTEMUI = "com.android.systemui"
        const val PKG_SETTINGS = "com.android.settings"
    }

    /** 本进程已经挂上的 hook（正常只有一个；SystemUI 会额外挂插件 hook）。 */
    private val hooks = LinkedHashMap<String, HookContext>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "onModuleLoaded process=${param.processName} api=${runCatching { getApiVersion() }.getOrDefault(-1)}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName.substringBefore(':')
        val classLoader = param.classLoader
        Log.d(TAG, "onPackageReady package=${param.packageName} classLoader=$classLoader")
        when (packageName) {
            PKG_BLUETOOTH -> load("HeadsetStateDispatcher", HeadsetStateDispatcher, classLoader, param.packageName)
            PKG_SYSTEMUI -> load("SystemUIPluginHook", SystemUIPluginHook, classLoader, param.packageName)
            PKG_XIAOMI_BLUETOOTH -> load("MiBluetoothToastHook", MiBluetoothToastHook, classLoader, param.packageName)
            PKG_SETTINGS -> load("SettingsHeadsetHook", SettingsHeadsetHook, classLoader, param.packageName)
            else -> Log.d(TAG, "no hook registered for $packageName")
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        hooks.forEach { (name, hook) ->
            runCatching { hook.onHotReloading() }
                .onFailure { Log.w(TAG, "onHotReloading($name) failed", it) }
        }
        hooks.clear()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val oldHooks = param.oldHookHandles
        val classLoader = oldHooks.firstOrNull()?.executable?.declaringClass?.classLoader
        if (classLoader == null) {
            Log.w(TAG, "hot reload skipped: no target class loader available")
            return
        }
        // HotReloadedParam 只有进程名（可能是 com.x:ui），而 hook 选择按包名，必须先归一化。
        val packageName = param.processName.substringBefore(':')
        Log.d(TAG, "hot reload package=$packageName process=${param.processName}")
        loadHookForPackage(packageName, classLoader, param.processName)

        val activeIds = hooks.values.flatMap { it.hookIds() }.toSet()
        oldHooks.filter { handle -> handle.id?.let { it !in activeIds } ?: true }
            .forEach { handle -> runCatching { handle.unhook() } }
    }

    private fun loadHookForPackage(packageName: String, classLoader: ClassLoader, processName: String) {
        when (packageName) {
            PKG_BLUETOOTH -> load("HeadsetStateDispatcher", HeadsetStateDispatcher, classLoader, processName)
            PKG_SYSTEMUI -> load("SystemUIPluginHook", SystemUIPluginHook, classLoader, processName)
            PKG_XIAOMI_BLUETOOTH -> load("MiBluetoothToastHook", MiBluetoothToastHook, classLoader, processName)
            PKG_SETTINGS -> load("SettingsHeadsetHook", SettingsHeadsetHook, classLoader, processName)
            else -> Log.d(TAG, "no hook for $packageName")
        }
    }

    /** 单个 HookContext 的装载：任何一步失败都只记日志（缺类不能让被注入进程崩溃）。 */
    private fun load(name: String, hook: HookContext, classLoader: ClassLoader, processName: String) {
        if (hooks.containsKey(name)) {
            Log.d(TAG, "$name already loaded in $processName")
            return
        }
        runCatching {
            hook.module = this
            hook.appClassLoader = classLoader
            hook.prefs = runCatching { getRemotePreferences(PREFS_GROUP) }.getOrElse { MemoryPrefs() }
            hook.onHook()
            hooks[name] = hook
            Log.i(TAG, "$name hooked in $processName (classLoader=$classLoader)")
        }.onFailure { Log.w(TAG, "hook $name skipped in $processName", it) }
    }
}

/**
 * getRemotePreferences 不可用时的内存兜底实现（例如框架未实现远程首选项）。
 * 只用于保证 `prefs.getXxx(key, default)` 不会抛异常；不参与持久化。
 */
private class MemoryPrefs : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, Any?> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = apply { removed += key }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            if (clearRequested) {
                values.clear()
                clearRequested = false
            }
            removed.forEach { values.remove(it) }
            removed.clear()
            values.putAll(pending)
            pending.clear()
        }
    }
}
