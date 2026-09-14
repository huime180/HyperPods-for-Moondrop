/*
 * HyperPods for Moondrop — LSPosed 服务连接状态（应用进程侧）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 背景：模块页那张「LSPosed 是否已激活 / 作用域是否齐全」的状态卡需要
 * io.github.libxposed.service 的 XposedService。以前本项目只依赖 compileOnly 的
 * io.github.libxposed:api（模块侧注入用），应用进程拿不到该服务，所以那张卡一直没做；
 * 现在补上 io.github.libxposed:service（与 api 同为 102.0.0），应用进程就能绑定框架服务。
 *
 * 服务怎么来的：libxposed service 的 AAR 自带一个 ContentProvider
 * （io.github.libxposed.service.XposedProvider，authorities = ${applicationId}.XposedService，
 * 由 manifest 合并进本应用），框架通过它把 Binder 递进来；XposedServiceHelper 负责缓存/分发。
 *
 * 与参考实现的差异：参考实现在 Application.onCreate 里注册 listener（它有 MoondropPodsApp）。
 * 本项目**没有** Application 子类，且本轮不允许改 manifest 的 android:name，因此这里把注册
 * 做成「首次被 UI 订阅时惰性注册一次」，全局单例持有，UI 侧只订阅 / 退订。
 * 效果等价：只要模块页被打开过，状态就与参考实现一致；差别只是绑定时机晚到「第一次进 UI」。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import moe.chenxy.hyperpods.R

/**
 * 模块需要的作用域（包名 → 勾选框里显示的名字），
 * 顺序与 app/src/main/resources/META-INF/xposed/scope.list 完全一致。
 *
 * 注意：这张表与 ui/components/RestartScope.kt 的 SCOPE_ENTRIES 是同一份数据，
 * 但那边是 private（文件私有）且本轮不改动已验收的组件，所以这里另存一份；
 * 两处必须保持同步（包名与顺序见 META-INF/xposed/scope.list）。
 */
internal val REQUIRED_SCOPES: List<Pair<String, Int>> = listOf(
    "com.android.bluetooth" to R.string.scope_bluetooth,
    "com.milink.service" to R.string.scope_milink,
    "com.xiaomi.bluetooth" to R.string.scope_xiaomi_bluetooth,
    "com.android.systemui" to R.string.scope_systemui,
    "com.android.settings" to R.string.scope_settings,
)

/** 作用域检查结果：服务是否连上 + 缺哪几个作用域（存显示名的资源 id）。 */
internal data class LsposedScopeState(
    val serviceConnected: Boolean,
    val missingScopes: List<Int>,
) {
    /** 已激活 = 服务已连上，且 5 个作用域一个不缺。 */
    val active: Boolean get() = serviceConnected && missingScopes.isEmpty()
}

/**
 * 读一次框架服务里已勾选的作用域并与 [REQUIRED_SCOPES] 比对。
 *
 * service.scope 是跨进程调用，任何异常（服务刚死、权限异常）都按「没连上」处理，
 * 绝不显示一个乐观的「已激活」。
 */
internal fun lsposedScopeState(service: XposedService?): LsposedScopeState {
    if (service == null) return LsposedScopeState(serviceConnected = false, missingScopes = emptyList())
    val granted = runCatching { service.scope ?: emptyList<String>() }.getOrNull()
        ?: return LsposedScopeState(serviceConnected = false, missingScopes = emptyList())
    val missing = REQUIRED_SCOPES.filter { it.first !in granted }.map { it.second }
    return LsposedScopeState(serviceConnected = true, missingScopes = missing)
}

/** 框架版本一行文案；没有服务时由调用方回落成「未知」。 */
internal fun lsposedVersionText(service: XposedService?): String {
    if (service == null) return ""
    return runCatching {
        "${service.frameworkName} ${service.frameworkVersion} (${service.frameworkVersionCode})" +
            " · API ${service.apiVersion}"
    }.getOrDefault("")
}

/**
 * LSPosed 服务桥：进程内只向 XposedServiceHelper 注册一次，UI 侧多消费者各自订阅。
 * 用 CopyOnWriteArraySet 是因为 onServiceBind / onServiceDied 可能在别的线程回来。
 */
private object XposedServiceBridge : XposedServiceHelper.OnServiceListener {

    @Volatile
    private var registered = false

    @Volatile
    private var service: XposedService? = null

    private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

    /** 幂等：只注册一次全局 listener。 */
    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
        }
        runCatching { XposedServiceHelper.registerListener(this) }
            .onFailure { registered = false }
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service === service) {
            this.service = null
            listeners.forEach { it(null) }
        }
    }

    /** 订阅并立刻回放当前状态（可能是 null）。 */
    fun subscribe(listener: (XposedService?) -> Unit): XposedService? {
        listeners.add(listener)
        return service
    }

    fun unsubscribe(listener: (XposedService?) -> Unit) {
        listeners.remove(listener)
    }
}

/**
 * 订阅 LSPosed 服务；返回 null 表示服务还没连上（或框架没装 / 模块未激活）。
 * 与 ui/PodState.kt 的 rememberPodSnapshot 同一套「订阅 + 回放一次当前值」的写法。
 */
@Composable
fun rememberXposedService(): XposedService? {
    var service by remember { mutableStateOf<XposedService?>(null) }

    DisposableEffect(Unit) {
        val listener: (XposedService?) -> Unit = { service = it }
        XposedServiceBridge.ensureRegistered()
        service = XposedServiceBridge.subscribe(listener)
        onDispose { XposedServiceBridge.unsubscribe(listener) }
    }

    return service
}
