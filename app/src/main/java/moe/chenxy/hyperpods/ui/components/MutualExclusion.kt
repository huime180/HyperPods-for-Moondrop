/*
 * HyperPods for Moondrop — LHDC / 双设备连接互斥确认
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 用户实测（Moondrop PUDDING）：LHDC 与「双设备连接」在固件层互斥，不能同时开启。
 * 因此这个开关不是「直接下发」，而是：
 *   · 关（或另一项本来就没开）→ 直接下发，不打扰用户；
 *   · 开且另一项正开着 → 不下发，弹 Miuix 确认框，用户选「切换」后才：
 *       先关掉另一项 → 等一小段时间 → 再打开所选那一项（顺序不能反，也不能同时发）。
 *
 * 能力门控：只有 hasLhdc 与 hasDualConnection 同时为 true 的设备才做互斥判断，
 * 否则保持各自独立开关的语义。
 *
 * 弹框形态对齐参考实现 _refs/OppoPods/.../ui/ProfilesPage.kt:227-282（OverlayDialog +
 * 标题 + summary + 底部一排 TextButton）与 :300-321（主按钮用 textButtonColorsPrimary）。
 */
package moe.chenxy.hyperpods.ui.components

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** 用户这一次想打开的互斥项。 */
enum class MutualExclusionTarget {
    /** LHDC 高清音频 */
    LHDC,

    /** 双设备连接 */
    DUAL_CONNECTION,
}

/** 先关另一项、再开所选之间的间隔（毫秒）。 */
private const val SWITCH_GAP_MS = 300L

/**
 * 互斥确认框的可组合状态。
 *
 * 用法：把 [request] 当作开关的拦截器，把 [MutualExclusionDialog] 挂在同一页面里。
 */
@Stable
class MutualExclusionState {
    private val pendingState = mutableStateOf<MutualExclusionTarget?>(null)

    /** 非 null = 正在等用户确认的目标项。 */
    val pending: MutualExclusionTarget? get() = pendingState.value

    /** 用户选了「切换」或点掉弹框时收起确认框。 */
    fun dismiss() {
        pendingState.value = null
    }

    /**
     * 请求把 [target] 置为 [wanted]。
     *
     * @return true = 没有冲突，调用方直接下发命令；
     *         false = 已挂起确认框（此时**不要**下发），等用户选择。
     */
    fun request(snapshot: PodSnapshot, target: MutualExclusionTarget, wanted: Boolean): Boolean {
        if (!wanted) return true
        val capabilities = snapshot.capabilities
        // 能力门控：两者都支持才谈互斥
        if (!capabilities.hasLhdc || !capabilities.hasDualConnection) return true
        val otherOn = when (target) {
            MutualExclusionTarget.LHDC -> snapshot.dualConnectionOn == true
            MutualExclusionTarget.DUAL_CONNECTION -> snapshot.lhdcOn == true
        }
        if (!otherOn) return true
        pendingState.value = target
        return false
    }
}

@Composable
fun rememberMutualExclusionState(): MutualExclusionState = remember { MutualExclusionState() }

/**
 * 按用户选择顺序下发：先关另一项 → 等待 → 再开所选。
 *
 * 单独抽成挂起函数，是为了让两处入口（详情页 / 快速弹窗）用完全相同的顺序与间隔。
 */
suspend fun applyMutualExclusion(target: MutualExclusionTarget) {
    when (target) {
        MutualExclusionTarget.LHDC -> {
            MoondropLink.setDualConnection(false)
            delay(SWITCH_GAP_MS)
            MoondropLink.setLhdc(true)
        }

        MutualExclusionTarget.DUAL_CONNECTION -> {
            MoondropLink.setLhdc(false)
            delay(SWITCH_GAP_MS)
            MoondropLink.setDualConnection(true)
        }
    }
}

/**
 * 互斥确认框。必须放在 Miuix `Scaffold` 里（OverlayDialog 默认渲染到根 Scaffold 的弹层宿主）。
 *
 * @param state 由 [rememberMutualExclusionState] 创建，与开关拦截器共用同一个实例
 */
@Composable
fun MutualExclusionDialog(state: MutualExclusionState) {
    val scope = rememberCoroutineScope()
    val target = state.pending

    OverlayDialog(
        title = stringResource(R.string.conflict_lhdc_dual_title),
        summary = when (target) {
            MutualExclusionTarget.LHDC -> stringResource(R.string.conflict_lhdc_dual_message_lhdc)
            MutualExclusionTarget.DUAL_CONNECTION -> stringResource(R.string.conflict_lhdc_dual_message_dual)
            null -> ""
        },
        show = target != null,
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
                text = stringResource(R.string.conflict_switch),
                onClick = {
                    val selected = target
                    state.dismiss()
                    if (selected != null) {
                        scope.launch { applyMutualExclusion(selected) }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
