/*
 * HyperPods for Moondrop — libxposed API 102 Hook 基类
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 移植自 OppoPods `app/src/main/java/moe/chenxy/oppopods/hook/HookContext.kt`
 * （该文件与本项目无关，是通用封装），保留其 API 风格：
 *   · hookBefore / hookAfter / hookConstructorAfter + HookParam(chain, initialResult) 包装
 *   · findClass / findMethod / findMethodByParamCount / findConstructorByParamCount
 *   · API 102 稳定 hook id（module.hook(exec).setId(id)）与 onHotReloading() 支持
 *   · 顶层 getObjectField / setObjectField / callMethod / callStaticMethod 反射助手
 *
 * 本模块额外约束：**任何 hook 注册与反射失败都不得让被注入进程崩溃**。
 * 因此所有查找都提供 *OrNull / findAnyMethod 变体，调用方统一用
 * `runCatching { ... }.onFailure { Log.w(TAG, "hook X skipped", it) }` 包裹。
 */
package moe.chenxy.hyperpods.hook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey

abstract class HookContext {
    lateinit var module: XposedModule
    lateinit var appClassLoader: ClassLoader

    /**
     * 远程设置（LSPosed getRemotePreferences）。
     *
     * 与原版 OppoPods `HookContext` 的唯一差异：这里允许为 null（原版是 `lateinit var`）。
     * 原因：框架未实现远程首选项时 `getRemotePreferences` 会失败，`lateinit` 会在首次读取时抛
     * UninitializedPropertyAccessException；本模块的 prefs 只用于读「模块总开关」，
     * 因此改成可空 + 容错读取（读不到 = 保持开启），避免为一个开关引入崩溃点。
     */
    var prefs: SharedPreferences? = null
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()

    /** 注册本进程需要的全部 hook；调用方（XposedEntry / SystemUIPluginHook）负责整体 runCatching。 */
    abstract fun onHook()

    /** Releases resources retained by target-process objects before API 102 hot reload. */
    open fun onHotReloading() = Unit

    /**
     * 换目标 ClassLoader 再挂一次（SystemUI 的 miui.systemui.plugin 插件 ClassLoader 专用）。
     * 与普通 HookContext 的区别只是解析目标类用的 ClassLoader 不同。
     */
    fun bind(module: XposedModule, classLoader: ClassLoader, prefs: SharedPreferences?) {
        this.module = module
        this.appClassLoader = classLoader
        this.prefs = prefs
    }

    fun hookIds(): Set<String> {
        return if (module.getApiVersion() >= XposedInterface.API_102) {
            hookHandles.mapNotNullTo(linkedSetOf()) { it.id }
        } else {
            emptySet()
        }
    }

    /** 模块总开关（HyperPodsPrefsKey.ENABLE，默认 true；prefs 不可用时也不阻塞功能）。 */
    fun isEnabled(): Boolean = prefBoolean(HyperPodsPrefsKey.ENABLE, true)

    /**
     * 本进程当前可用的 Context —— 专供「重启作用域」接收器注册（hook/RestartScopeReceiver.kt）。
     *
     * 默认 null：XposedEntry 会退回 SystemApisUtils.currentApplication() 并延迟重试，
     * 因此只有「手上正好有一个确定可用的 Context」的 hook 才需要覆盖它。
     * 实现方必须容错：拿不到就返回 null，绝不允许抛异常。
     */
    open fun processContextOrNull(): Context? = null

    /** 容错读取布尔型首选项：prefs 为 null / 类型不符 / 框架异常都回落到 [def]。 */
    fun prefBoolean(key: String, def: Boolean): Boolean =
        runCatching { prefs?.getBoolean(key, def) ?: def }.getOrDefault(def)

    fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)

    fun findClassOrNull(name: String): Class<*>? = runCatching { findClass(name) }.getOrNull()

    /**
     * 候选类名里第一个能在本进程 ClassLoader 里加载的类名；都没有返回 null。
     *
     * 供 RomProfile 的「本代主档优先 + 其余代数兜底」候选表使用：hook 文件不再自己硬编码
     * 单一代 ROM 的类名，而是把候选表交给这里，按顺序挑第一个真实存在的。
     */
    fun firstPresentClass(candidates: List<String>): String? {
        for (name in candidates) {
            if (findClassOrNull(name) != null) return name
        }
        return null
    }

    fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method =
        findClass(className).getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }

    fun findMethodOrNull(className: String, methodName: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { findMethod(className, methodName, *parameterTypes) }.getOrNull()

    fun findConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*> =
        findClass(className).getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }

    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method =
        findClass(className).declaredMethods.first { it.name == methodName && it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }

    fun findMethodByParamCountOrNull(className: String, methodName: String, paramCount: Int): Method? =
        runCatching { findMethodByParamCount(className, methodName, paramCount) }.getOrNull()

    fun findConstructorByParamCount(className: String, paramCount: Int): Constructor<*> =
        findClass(className).declaredConstructors.first { it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }

    fun findConstructorByParamCountOrNull(className: String, paramCount: Int): Constructor<*>? =
        runCatching { findConstructorByParamCount(className, paramCount) }.getOrNull()

    /**
     * 沿继承链找方法（框架内部类常把方法声明在父类里，declaredMethods 找不到）。
     * [paramCount] 为 null 时只按名字匹配（取第一个）。
     */
    fun findAnyMethod(className: String, methodName: String, paramCount: Int? = null): Method {
        var cls: Class<*>? = findClass(className)
        while (cls != null && cls != Any::class.java) {
            val found = cls.declaredMethods.firstOrNull {
                it.name == methodName && (paramCount == null || it.parameterTypes.size == paramCount)
            }
            if (found != null) {
                found.isAccessible = true
                return found
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException("$className#$methodName/$paramCount")
    }

    fun hookAfter(method: Method, block: HookParam.() -> Unit) {
        registerHook(method, "after") { chain ->
            val result = chain.proceed()
            HookParam(chain, result).apply(block).result
        }
    }

    fun hookBefore(method: Method, block: HookParam.() -> Unit) {
        registerHook(method, "before") { chain ->
            val param = HookParam(chain, null).apply(block)
            if (param.hasResult) param.result else chain.proceed()
        }
    }

    fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit) {
        registerHook(constructor, "constructor-after") { chain ->
            chain.proceed().also { HookParam(chain, it).apply(block) }
        }
    }

    private fun registerHook(
        executable: Executable,
        phase: String,
        hooker: XposedInterface.Hooker
    ) {
        val id = "$phase:${executable.toGenericString()}"
        val builder = module.hook(executable)
        if (module.getApiVersion() >= XposedInterface.API_102) {
            builder.setId(id)
        }
        hookHandles += builder.intercept(hooker)
    }

    fun reloadRemotePrefs() {
        runCatching {
            val store = prefs ?: return
            store.javaClass.methods.firstOrNull {
                it.name == "reload" && it.parameterTypes.isEmpty()
            }?.invoke(store)
        }
    }
}

/** Hook 回调参数：args / instance 来自 chain，result 可写以短路原方法（hasResult = true）。 */
class HookParam(private val chain: XposedInterface.Chain, initialResult: Any?) {
    val args: List<Any?> = chain.args
    val instance: Any? = chain.thisObject
    var hasResult = false
        private set
    var result: Any? = initialResult
        set(value) {
            hasResult = true
            field = value
        }
}

fun getObjectField(instance: Any?, fieldName: String): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
    if (instance == null) return
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            cls.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
            return
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
            it.isAccessible = true
            return it.invoke(instance, *args)
        }
        cls = cls.superclass
    }
    throw NoSuchMethodException(methodName)
}

fun callStaticMethod(cls: Class<*>?, methodName: String, vararg args: Any?): Any? {
    if (cls == null) return null
    var c: Class<*>? = cls
    while (c != null && c != Any::class.java) {
        c.declaredMethods.firstOrNull {
            it.name == methodName && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == args.size
        }?.let {
            it.isAccessible = true
            return it.invoke(null, *args)
        }
        c = c.superclass
    }
    throw NoSuchMethodException("${cls.name}#$methodName")
}

fun getStaticObjectField(cls: Class<*>?, fieldName: String): Any? {
    if (cls == null) return null
    val field = cls.getDeclaredField(fieldName)
    field.isAccessible = true
    return field.get(null)
}
