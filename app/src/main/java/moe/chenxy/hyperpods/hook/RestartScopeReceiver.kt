/*
 * HyperPods for Moondrop — Tier 1「重启作用域」接收器（每个被注入进程一份）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 问题：模块的 hook 只在被注入进程启动时挂一次，改完设置 / 更新模块后必须让进程重启才生效。
 * LSPosed Manager 的「重启作用域」就是「杀掉作用域进程 → 系统重新拉起 → LSPosed 重新注入模块」。
 * 但普通应用**没有权限**杀别的应用，所以反过来做：让每个被注入进程**自己**注册一个
 * RESTART_SCOPE 接收器，应用 UI 显式广播过来 → 进程打日志后自杀 → 系统重新拉起。
 *
 *   UI（模块应用进程）  --RESTART_SCOPE + setPackage(pkg)-->  被注入进程
 *                                                              └─ Log.i + Process.killProcess(myPid())
 *
 * Tier 2（root 兜底，`su -c am force-stop`）不在这里，见 ui/components/RestartScope.kt。
 *
 * 注册时机（重要）：只在 XposedEntry.load() 里 **hook 挂载成功之后** 调用 install()，
 * 绝不在进程还没 hook 完时就能被「重启」掉；注册失败 / 暂时拿不到 Context 都只记日志，
 * 绝不因为「重启作用域」把被注入进程弄崩。
 */
package moe.chenxy.hyperpods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

internal object RestartScopeReceiver {

    /** 日志 tag 与 libxposed 注入日志（HyperPods-Moondrop）分开，方便按 tag 过滤重启链路。 */
    private const val TAG = "HyperPods-Restart"

    /**
     * Context 重试参数：部分进程（如 SystemUI 的 miui.systemui.plugin 插件 ClassLoader 阶段、
     * com.android.bluetooth 的早期阶段）onPackageReady 时 ActivityThread.currentApplication()
     * 还是 null，这里在主线程延迟重试（40 × 500ms = 20s 窗口），拿不到就放弃（只记日志）。
     */
    private const val RETRY_ATTEMPTS = 40
    private const val RETRY_DELAY_MS = 500L

    /** 每个进程只允许开始一次安装流程（真正的重试由内部 Runnable 负责）。 */
    private val installStarted = AtomicBoolean(false)

    /** 接收器是否已经真正注册成功（成功后再收到 install() 调用直接返回）。 */
    private val registered = AtomicBoolean(false)

    private val lock = Any()

    @Volatile
    private var processName: String = ""

    /**
     * 注册本进程的「重启作用域」接收器（幂等：每个进程只注册一次）。
     *
     * @param processName     进程名（只用于日志，例如 com.android.bluetooth）
     * @param contextProvider 取本进程可用的 Context：优先 hook 自己偷到的，兜底
     *                        SystemApisUtils.currentApplication()（ActivityThread）
     */
    fun install(processName: String, contextProvider: () -> Context?) {
        if (!installStarted.compareAndSet(false, true)) return
        this.processName = processName
        if (register(contextProvider())) return
        if (!scheduleRetry(contextProvider)) {
            Log.w(TAG, "restart-scope receiver skipped in $processName (no main looper)")
        }
    }

    /** 主线程延迟重试（不阻塞 hook 流程；进程活着就有机会注册上）。 */
    private fun scheduleRetry(contextProvider: () -> Context?): Boolean {
        val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull() ?: return false
        val attempt = object : Runnable {
            private var tries = 0
            override fun run() {
                if (registered.get()) return
                if (register(contextProvider())) return
                if (++tries >= RETRY_ATTEMPTS) {
                    Log.w(TAG, "restart-scope receiver not registered in $processName after $tries retries")
                    return
                }
                runCatching { handler.postDelayed(this, RETRY_DELAY_MS) }
            }
        }
        return runCatching { handler.postDelayed(attempt, RETRY_DELAY_MS) }.isSuccess
    }

    /**
     * 真正注册（失败返回 false，让调用方继续重试）。
     *
     * @Suppress UnspecifiedRegisterReceiverFlag：API < 33 的分支用两参 registerReceiver
     * （本模块 minSdk 35，该分支只为可读性保留），避免 lint 噪音。
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun register(context: Context?): Boolean {
        if (registered.get()) return true
        val appContext = context?.applicationContext ?: context ?: return false
        synchronized(lock) {
            if (registered.get()) return true
            val filter = IntentFilter(HyperPodsAction.RESTART_SCOPE)
            val restartReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != HyperPodsAction.RESTART_SCOPE) return
                    // 接收器由系统在主线程回调：任何异常都不许冒泡出 onReceive。
                    runCatching { exitForRestart() }
                        .onFailure { Log.w(TAG, "restart-scope exit failed in $processName", it) }
                }
            }
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // 发送方是模块应用进程（不同 uid），必须 RECEIVER_EXPORTED 才收得到。
                    appContext.registerReceiver(restartReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appContext.registerReceiver(restartReceiver, filter)
                }
            }.onFailure { Log.w(TAG, "register restart-scope receiver failed in $processName", it) }.isSuccess
            if (!ok) return false
            registered.set(true)
            Log.i(TAG, "restart-scope receiver registered in $processName")
            return true
        }
    }

    /**
     * 自杀退出：killProcess 优于 System.exit（System.exit 会跑 shutdown hook / 等 finalizer，
     * 在被注入进程里有可能卡住或触发别的业务清理）。系统会按需重新拉起进程，
     * LSPosed 随之重新注入本模块 —— 这正是「重启作用域」想要的效果。
     *
     * ⚠ com.android.systemui 被杀掉时状态栏/导航栏会短暂消失并自动恢复（界面闪一下），
     *   这与 LSPosed 自己的「重启作用域」行为一致，UI 文案里已向用户说明。
     */
    private fun exitForRestart() {
        Log.i(TAG, "restart scope requested in $processName, exiting")
        Process.killProcess(Process.myPid())
    }
}
