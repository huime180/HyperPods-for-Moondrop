/*
 * HyperPods for Moondrop — 「手势操作」页
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 一行 = 一个手势槽位（单击 / 双击 / 三击 / 长按1秒 / 长按3秒），点开选动作 → 立即下发
 * **完整 5 字节**配置（协议见 core/Gaia.kt 的 TOUCHV2 段落，下发见 pods/MoondropLink.kt 的 setGesture）。
 *
 * 组件词汇与参考实现一致（_refs/OppoPods/.../ui/PodDetailPage.kt:146,184 与 PodDetailPage.kt:175）：
 *   androidx LazyColumn（与本项目 PodDetailPage.kt 同一写法）+ basic.SmallTitle +
 *   basic.Card + preference.OverlayDropdownPreference(title / items / selectedIndex /
 *   onSelectedIndexChange) + basic.BasicComponent(title / summary)。
 *
 * ⚠ 这一页刻意「不做什么」，全部因为协议证据不足（页面上也如实写给用户看）：
 *   1) **没有左右耳选择器**。观测到的读写帧来自官方 App 的**左耳**页面；右耳是否另有一帧
 *      （多一个参数/索引、独立命令，或按触控板区分）尚未找到证据。宁可不做，也不摆一个
 *      没有协议支撑的开关（要加时：确认帧格式后再加，并回来补这里的选择器）。
 *   2) **动作表是部分表**：只收录真机逐字节对齐过的 5 个 id（Gaia.TouchActions.ALL）。
 *      未映射的 id 不会被静默吞掉或回落成「无」，而是单列一条 `未知(0x..)` 选项
 *      （Gaia.TouchActions.matchOrUnknown）。
 *   3) **没有「重置」按钮**：没有已知的重置命令，就不发明一个（宁缺毋滥）。
 *   4) 长按1秒 / 长按3秒**不互斥**：实测回包就是 5 个独立槽位；用户反馈官方 App 看似互斥，
 *      但未经证实，因此不替用户禁掉一种可能合法的组合（见 Gaia.GestureSlot 的 KDoc）。
 */
package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.core.Gaia
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 卡片/区块之间的统一间距（与 PodDetailPage / AboutPage 的 12dp 一致）。 */
private val GESTURE_CARD_GAP = 12.dp

/**
 * 动作 id → 本地化文案资源。
 *
 * 表外的 id 由 [actionLabel] 回落到 [Gaia.TouchActions] 里的中文标签；两者都没有时
 * 走 [Gaia.TouchActions.matchOrUnknown] 显示 `未知(0x..)` —— 任何取值都不会变成空白。
 * 新增动作时：Gaia.TouchActions.ALL 加一行 + 两份 strings.xml 加同名键 + 这里加一行映射。
 */
private val ACTION_LABEL_RES: Map<Int, Int> = mapOf(
    Gaia.TouchActions.NONE to R.string.gesture_action_none,
    Gaia.TouchActions.PLAY_PAUSE to R.string.gesture_action_play_pause,
    Gaia.TouchActions.PREVIOUS_TRACK to R.string.gesture_action_previous_track,
    Gaia.TouchActions.VOICE_ASSISTANT to R.string.gesture_action_voice_assistant,
    Gaia.TouchActions.ANC_SWITCH to R.string.gesture_action_anc_switch,
)

/**
 * 手势操作页。
 *
 * @param snapshot 来自 [rememberPodSnapshot]：`gestureConf == null` 表示还没读到配置
 *                 （未支持、未连接或读取超时），此时给出说明卡片而不是空白。
 */
@Composable
fun GesturePage(
    snapshot: PodSnapshot,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val conf = snapshot.gestureConf
    // 能力位为真但配置还没读回来 → 与「机型不支持（能力位为假）」区分开提示，不要一律说「不支持」
    val supported = snapshot.capabilities.hasGestures

    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + GESTURE_CARD_GAP,
            bottom = contentPadding.calculateBottomPadding() + GESTURE_CARD_GAP,
            start = 12.dp,
            end = 12.dp,
        ),
        overscrollEffect = null,
    ) {
        item { SmallTitle(text = stringResource(R.string.gesture_title)) }

        // 三种状态：读到配置（gesturesKnown 且 conf 非空）/ 支持但还没读回来 / 机型不支持
        val current = if (snapshot.gesturesKnown) conf else null

        if (current != null) {
            // 取一个非空局部量：lambda 里不再依赖「智能转换能否穿透闭包」这类细节
            val rows = current
            item {
                Card {
                    Gaia.GestureSlot.entries.forEach { slot ->
                        GestureSlotRow(
                            slot = slot,
                            currentId = rows[slot],
                            onSelect = { actionId -> MoondropLink.setGesture(slot, actionId) },
                        )
                    }
                }
            }
        } else {
            item {
                Card {
                    BasicComponent(
                        title = stringResource(
                            if (supported) {
                                R.string.gesture_reading_title
                            } else {
                                R.string.gesture_unavailable_title
                            }
                        ),
                        summary = stringResource(
                            if (supported) {
                                R.string.gesture_reading_summary
                            } else {
                                R.string.gesture_unavailable_summary
                            }
                        ),
                    )
                }
            }
        }

        // 如实说明（左右耳未确认 / 动作表不完整 / 长按两档未证实互斥）
        item {
            Card(modifier = Modifier.padding(top = GESTURE_CARD_GAP)) {
                BasicComponent(
                    title = stringResource(R.string.gesture_note_title),
                    summary = stringResource(R.string.gesture_note_body),
                )
            }
        }
    }
}

/**
 * 一个槽位的下拉行。
 *
 * 当前值不在动作表里时（固件给了我们还没映射的 id），列表首项插入一条 `未知(0x..)`
 * 并选中它 —— 这条占位项**不可被选中下发**（选中它没有意义，会被 `getOrNull(-1)` 丢掉）。
 */
@Composable
private fun GestureSlotRow(
    slot: Gaia.GestureSlot,
    currentId: Int,
    onSelect: (Int) -> Unit,
) {
    val known = Gaia.TouchActions.ALL
    val unmapped = Gaia.TouchActions.byId(currentId) == null
    val labels = known.map { actionLabel(it) }
    val items = if (unmapped) {
        listOf(Gaia.TouchActions.matchOrUnknown(currentId)) + labels
    } else {
        labels
    }
    val selectedIndex = if (unmapped) {
        0
    } else {
        known.indexOfFirst { it.id == (currentId and 0xFF) }.coerceAtLeast(0)
    }

    OverlayDropdownPreference(
        title = stringResource(slotTitleRes(slot)),
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            val offset = if (unmapped) 1 else 0
            known.getOrNull(index - offset)?.let { action -> onSelect(action.id) }
        },
    )
}

/** 动作 → 本地化文案；无本地化条目时回落表里的中文标签。 */
@Composable
private fun actionLabel(action: Gaia.TouchAction): String =
    ACTION_LABEL_RES[action.id]?.let { stringResource(it) } ?: action.labelZh

/** 槽位 → 标题资源（单击 / 双击 / 三击 / 长按1秒 / 长按3秒）。 */
private fun slotTitleRes(slot: Gaia.GestureSlot): Int = when (slot) {
    Gaia.GestureSlot.SINGLE_TAP -> R.string.gesture_slot_single_tap
    Gaia.GestureSlot.DOUBLE_TAP -> R.string.gesture_slot_double_tap
    Gaia.GestureSlot.TRIPLE_TAP -> R.string.gesture_slot_triple_tap
    Gaia.GestureSlot.LONG_PRESS_1S -> R.string.gesture_slot_long_press_1s
    Gaia.GestureSlot.LONG_PRESS_3S -> R.string.gesture_slot_long_press_3s
}
