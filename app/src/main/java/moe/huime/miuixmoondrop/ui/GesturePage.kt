/*
 * MiuixMoondrop — 「手势操作」页
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 协议（真机实测 + 官方 App 字节码，见 core/Gaia.kt 的 TOUCHV2 段落）：
 *   feature 22 的配置是 **5 个字节**，1 字节 = 1 种手势（单击/双击/三击/长按1秒/长按3秒），
 *   **每个字节打包双耳**：高 4 位 = 左耳动作 id，低 4 位 = 右耳动作 id。
 *   所以本页是 **5 × 2 = 10 行**（一行 = 一种手势的一只耳朵），改一行只下发那一个半字节
 *   （读-改-写整份 5 字节，见 pods/MoondropLink.kt 的 setGesture）。
 *
 * 组件词汇与参考实现一致（_refs/OppoPods/.../ui/PodDetailPage.kt:146,184）：
 *   androidx LazyColumn（与本项目 PodDetailPage.kt 同一写法）+ basic.SmallTitle +
 *   basic.Card + preference.OverlayDropdownPreference(title / items / selectedIndex /
 *   onSelectedIndexChange) + basic.BasicComponent(title / summary)。
 *
 * 事实标注：
 *   1) 动作表（播放/暂停、上一曲、下一曲、语音助手、降噪切换、音量±）来自官方 App 的
 *      字节码校验与真机实测，8 个动作在两份 strings.xml 里都有本地化字符串。
 *   2) `8..15` 未观测到：这种值不会被静默吞掉或回落成「无」，而是单列一条 `未知(0xN)` 选项
 *      （Gaia.TouchActions.matchOrUnknown）。
 *   3) 长按1秒 / 长按3秒是协议上**两个独立字节**（字节码里 onesL/onesR 与 threesL/threesR
 *      两组独立字段），设备行为上**同侧**互斥：把某一侧的长按1秒设为「无」以外的动作时，
 *      只把**同一只耳**的长按3秒清空为「无」（另一只耳不受影响），反之亦然。
 *      该规则已在 pods/MoondropLink.kt 的 setGesture 里实现。
 *   4) **没有「重置」按钮**：没有已知的重置命令，就不发明一个（宁缺毋滥）。
 */
package moe.huime.miuixmoondrop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.huime.miuixmoondrop.R
import moe.huime.miuixmoondrop.core.Gaia
import moe.huime.miuixmoondrop.pods.MoondropLink
import moe.huime.miuixmoondrop.pods.PodSnapshot
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
 * 走 [Gaia.TouchActions.matchOrUnknown] 显示 `未知(0xN)` —— 任何取值都不会变成空白。
 * 新增动作时：Gaia.TouchActions.ALL 加一行 + 两份 strings.xml 加同名键 + 这里加一行映射。
 */
private val ACTION_LABEL_RES: Map<Int, Int> = mapOf(
    Gaia.TouchActions.NONE to R.string.gesture_action_none,
    Gaia.TouchActions.PLAY_PAUSE to R.string.gesture_action_play_pause,
    Gaia.TouchActions.PREVIOUS_TRACK to R.string.gesture_action_previous_track,
    Gaia.TouchActions.NEXT_TRACK to R.string.gesture_action_next_track,
    Gaia.TouchActions.VOLUME_UP to R.string.gesture_action_volume_up,
    Gaia.TouchActions.VOLUME_DOWN to R.string.gesture_action_volume_down,
    Gaia.TouchActions.VOICE_ASSISTANT to R.string.gesture_action_voice_assistant,
    Gaia.TouchActions.ANC_SWITCH to R.string.gesture_action_anc_switch,
)

/**
 * 手势操作页：10 行 = 5 种手势 × 2 只耳朵，行标题形如「单击 · 左耳」。
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
                    // 5 种手势 × 2 只耳朵 = 10 行；顺序：手势优先，每只手势先左后右
                    Gaia.GestureSlot.entries.forEach { slot ->
                        Gaia.Ear.entries.forEach { ear ->
                            GestureRow(
                                slot = slot,
                                ear = ear,
                                currentId = rows.action(slot, ear),
                                onSelect = { actionId ->
                                    MoondropLink.setGesture(slot, ear, actionId)
                                },
                            )
                        }
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

        // 协议说明（双耳打包 / 长按两档互斥的规则）
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
 * 一行 = 一种手势 + 一只耳朵。
 *
 * 当前值不在动作表里时（固件给了我们还没映射的半字节，例如 `8..15`），列表首项插入一条
 * `未知(0xN)` 并选中它 —— 这条占位项**不可被选中下发**（选中它没有意义，会被
 * `getOrNull(-1)` 丢掉），因此不会把未知值悄悄改成别的动作。
 */
@Composable
private fun GestureRow(
    slot: Gaia.GestureSlot,
    ear: Gaia.Ear,
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
        known.indexOfFirst { it.id == (currentId and Gaia.TOUCH_ACTION_MASK) }.coerceAtLeast(0)
    }

    OverlayDropdownPreference(
        // 「单击 · 左耳」：耳朵必须在标题里可见 —— 10 行同列，不做左右耳切换开关，
        // 这样任何时刻都能看清自己在改哪只耳朵的哪个手势。
        title = stringResource(
            R.string.gesture_slot_ear,
            stringResource(slotTitleRes(slot)),
            stringResource(earRes(ear)),
        ),
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            val offset = if (unmapped) 1 else 0
            known.getOrNull(index - offset)?.let { action -> onSelect(action.id) }
        },
    )
}

/**
 * 动作 → 本地化文案。
 *
 * 表里 8 个已知动作都有本地化条目（见 ACTION_LABEL_RES），所以正常路径永远取 strings.xml；
 * 未命中时才回落到 Gaia.TouchActions 自带的中文标签，未知取值走 matchOrUnknown。
 */
@Composable
private fun actionLabel(action: Gaia.TouchAction): String =
    ACTION_LABEL_RES[action.id]?.let { stringResource(it) } ?: action.labelZh

/** 手势种类 → 标题资源（单击 / 双击 / 三击 / 长按1秒 / 长按3秒）。 */
private fun slotTitleRes(slot: Gaia.GestureSlot): Int = when (slot) {
    Gaia.GestureSlot.SINGLE_TAP -> R.string.gesture_slot_single_tap
    Gaia.GestureSlot.DOUBLE_TAP -> R.string.gesture_slot_double_tap
    Gaia.GestureSlot.TRIPLE_TAP -> R.string.gesture_slot_triple_tap
    Gaia.GestureSlot.LONG_PRESS_1S -> R.string.gesture_slot_long_press_1s
    Gaia.GestureSlot.LONG_PRESS_3S -> R.string.gesture_slot_long_press_3s
}

/** 耳朵 → 标题资源（左耳 / 右耳）。 */
private fun earRes(ear: Gaia.Ear): Int = when (ear) {
    Gaia.Ear.LEFT -> R.string.gesture_ear_left
    Gaia.Ear.RIGHT -> R.string.gesture_ear_right
}
