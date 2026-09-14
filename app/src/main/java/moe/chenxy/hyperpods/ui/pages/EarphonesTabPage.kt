/*
 * HyperPods for Moondrop — 耳机页内容（已连接 = 设备详情，未连接 = 设备选择）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 形态对齐参考实现 moondrop-pods 的 ui/pages/EarphonesTabPage.kt:19-77：
 * 用 androidx.compose.animation.AnimatedContent 在「设备详情」与「设备选择」之间切换，
 * 判据是同一个「是否已连接」的真值（本项目的 PodSnapshot.connected）。
 *
 * 详情的具体行仍由本项目原有的 ui/PodDetailPage.kt 负责（版式与能力门控都已验证）；
 * 本页只做「两张页面二选一」的容器，不重复实现任何控制逻辑。
 */
package moe.chenxy.hyperpods.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.PodDetailPage

@Composable
internal fun EarphonesTabPage(
    snapshot: PodSnapshot,
    onOpenAbout: () -> Unit,
    onOpenGestures: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = snapshot.connected,
        modifier = modifier.fillMaxSize(),
        label = "EarphonesTabPage",
    ) { connected ->
        if (connected) {
            PodDetailPage(
                contentPadding = contentPadding,
                snapshot = snapshot,
                onOpenAbout = onOpenAbout,
                onOpenGestures = onOpenGestures,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            DevicePickerPage(
                snapshot = snapshot,
                onConnect = { device -> MoondropLink.connect(device) },
                contentPadding = contentPadding,
            )
        }
    }
}
