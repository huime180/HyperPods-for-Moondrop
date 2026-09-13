/*
 * HyperPods for Moondrop — 「重启作用域」（等效 LSPosed 的「重启作用域」，不需要重启手机）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 背景：模块的 hook 只在被注入进程启动时挂一次。改完设置 / 更新模块后，要让 hook 立刻生效，
 * 就得像 LSPosed Manager 的「重启作用域」那样——杀掉 5 个作用域进程，让系统重新拉起并重新注入。
 * 但**普通应用没有权限杀别的应用**，所以做成两级机制：
 *
 *   Tier 1（主路径，不需要 root）
 *     模块应用进程逐个作用域包发**显式广播**（setPackage(pkg)，Android 8+ 隐式广播限制）；
 *     每个被注入进程里的 hook.RestartScopeReceiver 收到后打日志并 killProcess(myPid()) 自杀；
 *     系统按需重新拉起进程 → LSPosed 重新注入本模块。
 *
 *   Tier 2（兜底，需要 root）
 *     先用 `su -c id`（runCatching + 短超时）探测 root；失败就**静默跳过**。
 *     探测成功则再对 5 个作用域各跑一次 `su -c "am force-stop <pkg>"`，
 *     作为 Tier 1 未响应时的兜底（就是 LSPosed 重启作用域在无 root 时做不到的那一步）。
 *     注意：force-stop 会把包标记为 stopped（系统应用/常驻应用会被系统重新拉起，
 *     普通应用如 com.android.settings 需要用户再点开一次）——所以它只是兜底，不是主路径。
 *
 * 线程：Tier 2 的阻塞操作（`su -c id` 探测、`am force-stop`）全部在 Dispatchers.IO 上跑；
 *   UI 线程只做「弹确认框 → 点重启 → 弹结果 Toast」，绝不会被 su 卡住。
 *
 * ⚠ com.android.systemui 退出会导致状态栏/导航栏短暂消失再恢复（界面闪一下）——
 *   这是 LSPosed「重启作用域」本来就有的现象，确认框文案里已明确告知用户。
 */
package moe.chenxy.hyperpods.ui.components

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

private const val TAG = "HyperPods-Restart"

/** 5 个作用域包：与 app/src/main/resources/META-INF/xposed/scope.list 一一对应。 */
private val SCOPE_PACKAGES = listOf(
    "com.android.bluetooth",
    "com.milink.service",
    "com.xiaomi.bluetooth",
    "com.android.systemui",
    "com.android.settings",
)

/** Tier 1 广播与 Tier 2 兜底之间的间隔：留出「进程自杀 + 系统重新拉起」的时间。 */
private const val SECOND_TIER_DELAY_MS = 800L

/** `su -c id` 的最长等待；超时 / 异常一律当作「没有 root」，Tier 2 静默跳过（含 Magisk 弹窗未授权）。 */
private const val ROOT_TIMEOUT_MS = 1_200L

/** 单条 `su -c "am force-stop pkg"` 的最长等待（正常几十毫秒就返回；上限只为防止极少数卡死拖住 UI）。 */
private const val FORCE_STOP_TIMEOUT_MS = 1_500L

/** 「重启作用域」的结果，用于给用户一句可读反馈。 */
data class RestartScopeResult(
    /** Tier 1 成功发出的广播条数（= 被通知的作用域进程数）。 */
    val notified: Int,
    /** Tier 2 真正 force-stop 成功的包数（没有 root 时恒为 0）。 */
    val forceStopped: Int,
    /** 是否探测到 root；false = Tier 2 被静默跳过。 */
    val rootAvailable: Boolean,
)

/**
 * 执行「重启作用域」：先 Tier 1（广播），再 Tier 2（root 兜底）。
 * 挂起函数，内部整个切到 [Dispatchers.IO]；调用方（UI）直接 launch 即可。
 */
suspend fun restartScopeProcesses(context: Context): RestartScopeResult = withContext(Dispatchers.IO) {
    // ── Tier 1：显式广播给每个作用域包（接收器是 RECEIVER_EXPORTED，发送方 uid 不同） ──
    val notified = SCOPE_PACKAGES.count { pkg -> sendRestartBroadcast(context, pkg) }

    // 让收到广播的进程先自杀（broadcast 是异步的）；然后再决定要不要走 root 兜底。
    delay(SECOND_TIER_DELAY_MS)

    // ── Tier 2：root 兜底（探测失败/超时 = 静默跳过） ──
    val rootAvailable = hasRootAccess()
    val forceStopped = if (rootAvailable) {
        SCOPE_PACKAGES.count { pkg -> forceStopWithRoot(pkg) }
    } else {
        0
    }

    Log.i(
        TAG,
        "restart scope: notified=$notified/${SCOPE_PACKAGES.size} " +
            "forceStopped=$forceStopped root=$rootAvailable"
    )
    RestartScopeResult(notified = notified, forceStopped = forceStopped, rootAvailable = rootAvailable)
}

/** 显式广播「请求退出」到单个作用域包（失败只记日志）。 */
private fun sendRestartBroadcast(context: Context, pkg: String): Boolean = runCatching {
    context.sendBroadcast(
        Intent(HyperPodsAction.RESTART_SCOPE).apply {
            setPackage(pkg)
            // 前台广播：让接收方尽快被调度（要的就是「尽快退出」）
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
    )
    true
}.onFailure { Log.w(TAG, "RESTART_SCOPE broadcast to $pkg failed", it) }.getOrDefault(false)

/**
 * root 探测：`su -c id`，短超时；任何异常 / 超时 / 非 0 退出码都返回 false（Tier 2 静默跳过）。
 *
 * 阻塞调用，只在 [Dispatchers.IO] 上使用。
 */
private fun hasRootAccess(): Boolean = runCatching {
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
    val finished = runCatching { process.waitFor(ROOT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    if (!finished) {
        runCatching { process.destroy() }
        return@runCatching false
    }
    if (process.exitValue() != 0) return@runCatching false
    // 真 root 的 su 输出形如 "uid=0(root) gid=0(root) ..."
    val output = runCatching {
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrDefault("")
    output.contains("uid=0")
}.getOrDefault(false)

/** 单包 root 兜底：`su -c "am force-stop <pkg>"`（阻塞，只在 [Dispatchers.IO] 上使用）。 */
private fun forceStopWithRoot(pkg: String): Boolean = runCatching {
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg"))
    val finished = runCatching { process.waitFor(FORCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    if (!finished) {
        runCatching { process.destroy() }
        return@runCatching false
    }
    val ok = process.exitValue() == 0
    if (ok) Log.i(TAG, "force-stop $pkg via root ok") else Log.w(TAG, "force-stop $pkg via root failed")
    ok
}.getOrDefault(false)

/** 「重启作用域」的 UI 状态：顶栏动作只负责 [request]（弹确认框），执行要用户再确认一次。 */
@Stable
class RestartScopeState {
    private val visibleState = mutableStateOf(false)

    /** true = 确认框正在显示。 */
    val visible: Boolean get() = visibleState.value

    /** 顶栏动作点一下：弹确认框。 */
    fun request() {
        visibleState.value = true
    }

    /** 取消 / 点掉弹框 / 执行完：收起确认框。 */
    fun dismiss() {
        visibleState.value = false
    }
}

@Composable
fun rememberRestartScopeState(): RestartScopeState = remember { RestartScopeState() }

/**
 * 确认框 + 执行入口。形态对齐 ui/components/MutualExclusion.kt:120-158（OverlayDialog +
 * summary 说明 + 底部一排 TextButton，主按钮用 textButtonColorsPrimary）。
 *
 * 必须挂在 Miuix `Scaffold` 里（OverlayDialog 默认渲染到根 Scaffold 的弹层宿主），
 * 因此在 MainUI 的 Home entry 里与页面内容并列放置。
 */
@Composable
fun RestartScopeDialog(state: RestartScopeState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    OverlayDialog(
        title = stringResource(R.string.restart_scope),
        summary = stringResource(R.string.restart_scope_summary),
        show = state.visible,
        onDismissRequest = { state.dismiss() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { state.dismiss() },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.restart_scope_confirm),
                onClick = {
                    state.dismiss()
                    scope.launch {
                        // restartScopeProcesses 内部切 Dispatchers.IO，UI 线程不会被 su 阻塞。
                        val result = try {
                            restartScopeProcesses(context)
                        } catch (cancelled: CancellationException) {
                            throw cancelled // 页面/作用域销毁：正常取消，什么都不做
                        } catch (failure: Throwable) {
                            // 兜底：任何意外都只记日志，绝不让模块 UI 进程崩溃
                            Log.w(TAG, "restart scope failed", failure)
                            null
                        }
                        val text = if (result != null && result.notified > 0) {
                            context.getString(R.string.restart_scope_done, result.notified)
                        } else {
                            context.getString(R.string.restart_scope_denied)
                        }
                        runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
