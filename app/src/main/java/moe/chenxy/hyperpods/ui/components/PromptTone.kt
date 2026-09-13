/*
 * HyperPods for Moondrop — 提示音开关 + 提示音音量（合并成一行）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 用户需求：提示音与提示音音量原来分成两行，现在合并成同一个 Miuix 组件。
 * 形态：SwitchPreference（标题 + 摘要 + 右侧开关）的 bottomAction 里嵌 SliderPreference ——
 *       Miuix 0.9.3 的 BasicComponent.bottomAction 就是「标题行下方再挂定制内容」的槽位
 *       （SwitchPreference 0.9.3 源码里 `bottomAction = bottomAction`，见
 *        v0.9.3 miuix-preference/.../SwitchPreference.kt:55 与 :86；BasicComponent 在
 *        Column 末尾渲染它，见 v0.9.3 miuix-ui/.../basic/Component.kt:255-258），
 *        因此开关与滑条落在同一个组件、同一份 insideMargin 里，视觉上就是同一行/同一栏。
 *
 * 能力门控：整行由 hasPromptTone || hasPromptVolume 打开；
 *          只有 hasPromptTone 时只显示开关，只有 hasPromptVolume 时只显示滑条。
 * 音量单位：设备侧与 UI 侧同为 0..promptVolumeMax（=100 百分比），恒等映射；
 *          拖动只改本地显示，松手 onValueChangeFinished 才下发。
 * 内层 SliderPreference 的 insideMargin 归零：外层 BasicComponent 已经统一给了 16dp，
 * 不归零的话滑条会比标题多缩进一层。
 */
package moe.chenxy.hyperpods.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 提示音一行：开关（若支持）与音量滑条（若支持）合并展示。
 * 两个能力都没有时什么都不渲染（调用方可以无条件放进 Card）。
 */
@Composable
fun PromptToneSection(snapshot: PodSnapshot, modifier: Modifier = Modifier) {
    val capabilities = snapshot.capabilities
    val hasTone = capabilities.hasPromptTone
    val hasVolume = capabilities.hasPromptVolume
    if (!hasTone && !hasVolume) return

    if (hasTone) {
        // 音量滑条挂在开关组件的 bottomAction 里 —— 同一个组件 = 同一行
        val volumeSlot: (@Composable () -> Unit)? = if (hasVolume) {
            { PromptVolumePreference(snapshot = snapshot, insideMargin = PaddingValues(horizontal = 0.dp)) }
        } else {
            null
        }
        SwitchPreference(
            modifier = modifier,
            title = stringResource(R.string.prompt_tone_title),
            summary = stringResource(R.string.prompt_tone_summary),
            checked = snapshot.promptToneOn ?: false,
            onCheckedChange = { on -> MoondropLink.setPromptTone(on) },
            bottomAction = volumeSlot,
        )
    } else {
        // 设备只有音量能力：整行就是滑条，保持 Miuix 默认 insideMargin
        PromptVolumePreference(snapshot = snapshot, modifier = modifier)
    }
}

/**
 * 提示音音量行（SliderPreference：Miuix 原生滑条行）。
 *
 * UI 值 0..promptVolumeMax 与设备值**同一单位**（0..100 百分比）——官方 App 日志实测
 * `updateV2VoiceConf: enabled=true, volume=20, index=1`，不是 0..255 的原始字节，因此恒等映射。
 *
 * @param insideMargin null = 用 Miuix 默认（BasicComponentDefaults.InsideMargin，16dp）；
 *                     嵌在开关组件里时传 0 水平内边距，避免与外层叠加缩进。
 */
@Composable
private fun PromptVolumePreference(
    snapshot: PodSnapshot,
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues? = null,
) {
    val max = snapshot.promptVolumeMax.coerceAtLeast(1)
    val raw = snapshot.promptVolumeRaw
    val uiValue = if (raw < 0) 0 else raw.coerceIn(0, max)
    var localValue by remember(raw, max) { mutableIntStateOf(uiValue) }

    SliderPreference(
        modifier = modifier,
        title = stringResource(R.string.prompt_volume_title),
        value = localValue.toFloat(),
        onValueChange = { value -> localValue = value.roundToInt().coerceIn(0, max) },
        valueRange = 0f..max.toFloat(),
        valueText = "$localValue / $max",
        onValueChangeFinished = { MoondropLink.setPromptVolumeRaw(localValue) },
        insideMargin = insideMargin ?: BasicComponentDefaults.InsideMargin,
    )
}
