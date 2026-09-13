/*
 * HyperPods for Moondrop — SystemUI 进程：把融合设备中心耳机卡的 hook 挂进 MIUI 插件 ClassLoader
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 技术路线与 HyperPods `hook/SystemUIPluginHook.kt` 完全一致（YukiHookAPI -> libxposed API 102）：
 *   hook com.android.systemui.shared.plugins.PluginInstance#loadPlugin，
 *   当 getPackage() == "miui.systemui.plugin" 时取出插件 ClassLoader：
 *       mPluginFactory -> mClassLoaderFactory -> get()
 *   然后用 **该插件 ClassLoader** 解析 miui.systemui.devicecenter.* 类并挂 DeviceCardHook。
 *
 * 为什么必须这样：DeviceInfoWrapper / MainPanelController 只存在于 miui.systemui.plugin 里，
 * 用 SystemUI 自己的 ClassLoader（param.classLoader）Class.forName 永远是 ClassNotFound。
 */
package moe.chenxy.hyperpods.hook

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

object SystemUIPluginHook : HookContext() {
    private const val TAG = "HyperPods-SystemUI"
    private const val PLUGIN_PACKAGE = "miui.systemui.plugin"
    private const val CLS_PLUGIN_INSTANCE = "com.android.systemui.shared.plugins.PluginInstance"

    private val lock = Any()

    /**
     * 「重启作用域」接收器用的进程 Context：优先用 DeviceCardHook 偷到的，
     * 兜底 ActivityThread.currentApplication()（插件加载阶段可能还是空的，接收器会重试）。
     */
    override fun processContextOrNull(): Context? =
        DeviceCardHook.processContextOrNull() ?: SystemApisUtils.currentApplication()

    @Volatile
    private var hookedClassLoader: ClassLoader? = null

    override fun onHook() {
        runCatching {
            val method = resolveLoadPlugin()
            hookAfter(method) {
                runCatching { onPluginLoaded(instance) }
                    .onFailure { Log.w(TAG, "loadPlugin handling failed", it) }
            }
            Log.d(TAG, "hooked ${method.declaringClass.name}#${method.name}/${method.parameterTypes.size}")
        }.onFailure { Log.w(TAG, "hook $CLS_PLUGIN_INSTANCE.loadPlugin skipped", it) }
    }

    override fun onHotReloading() {
        runCatching { DeviceCardHook.onHotReloading() }
            .onFailure { Log.w(TAG, "DeviceCardHook.onHotReloading failed", it) }
        hookedClassLoader = null
    }

    /** loadPlugin() 在 AOSP/小米上有无参和带参两种形态，逐个尝试，再退化到「只按名字找」。 */
    private fun resolveLoadPlugin(): Method =
        findMethodByParamCountOrNull(CLS_PLUGIN_INSTANCE, "loadPlugin", 0)
            ?: findMethodByParamCountOrNull(CLS_PLUGIN_INSTANCE, "loadPlugin", 1)
            ?: findAnyMethod(CLS_PLUGIN_INSTANCE, "loadPlugin")

    private fun onPluginLoaded(instance: Any?) {
        val packageName = runCatching { callMethod(instance, "getPackage") as? String }.getOrNull()
        if (packageName != PLUGIN_PACKAGE) {
            Log.d(TAG, "loadPlugin package=$packageName (ignored)")
            return
        }
        val factory = runCatching { getObjectField(instance, "mPluginFactory") }.getOrNull()
        val classLoaderFactory = runCatching { getObjectField(factory, "mClassLoaderFactory") }.getOrNull()
        val classLoader = runCatching { callMethod(classLoaderFactory, "get") as? ClassLoader }.getOrNull()
        if (classLoader == null) {
            Log.w(TAG, "loadPlugin($PLUGIN_PACKAGE): plugin ClassLoader unavailable")
            return
        }
        hookDeviceCard(classLoader)
    }

    private fun hookDeviceCard(classLoader: ClassLoader) {
        synchronized(lock) {
            if (hookedClassLoader === classLoader) return
            runCatching {
                DeviceCardHook.bind(module, classLoader, prefs)
                DeviceCardHook.onHook()
                hookedClassLoader = classLoader
                Log.i(TAG, "DeviceCardHook hooked into $PLUGIN_PACKAGE classLoader=$classLoader")
            }.onFailure { Log.w(TAG, "DeviceCardHook hook skipped", it) }
        }
    }
}
