/*
 * HyperPods for Moondrop — 耳机页内容（已连接 = 设备详情，未连接 = 设备选择）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 形态对齐参考实现 moondrop-pods 的 ui/pages/EarphonesTabPage.kt:19-77：
 * 用 androidx.compose.animation.AnimatedContent 在「设备详情」与「设备选择」之间切换。
 *
 * 与参考实现的取法一致：判据是「是否显示设备选择页」，而它 = 没连上 或 用户明确要求看已配对列表
 * （参考实现是 canShowDetailPage && !showDevicePicker；本项目的「明确要求」来自模块页那张
 * 「配对蓝牙」卡片，状态在 MainUI 里，同参考 MainUI 的 showDevicePicker）。
 *
 * 详情的具体行仍由本项目原有的 ui/PodDetailPage.kt 负责（版式与能力门控都已验证）；
 * 本页只做「两张页面二选一」的容器，不重复实现任何控制逻辑。
 */
package moe.chenxy.hyperpods.ui.pages

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.chenxy.hyperpods.pods.MoondropLink
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.PodDetailPage

/**
 * @param showPicker 用户从「配对蓝牙」卡片进来时强制显示设备选择页（即使耳机已经连着）
 * @param onDeviceSelected 选中一台已配对设备；默认交给 MoondropLink.connect()
 */
@Composable
internal fun EarphonesTabPage(
    snapshot: PodSnapshot,
    onOpenGestures: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    showPicker: Boolean = false,
    onDeviceSelected: (BluetoothDevice) -> Unit = { device -> MoondropLink.connect(device) },
) {
    val picker = showPicker || !snapshot.connected

    AnimatedContent(
        targetState = picker,
        modifier = modifier.fillMaxSize(),
        label = "EarphonesTabPage",
    ) { showDevicePicker ->
        if (showDevicePicker) {
            DevicePickerPage(
                snapshot = snapshot,
                onConnect = onDeviceSelected,
                contentPadding = contentPadding,
            )
        } else {
            PodDetailPage(
                contentPadding = contentPadding,
                snapshot = snapshot,
                onOpenGestures = onOpenGestures,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
