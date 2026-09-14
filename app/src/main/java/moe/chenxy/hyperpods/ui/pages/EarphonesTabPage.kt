/*
 * HyperPods for Moondrop — 耳机页内容（已连接 = 设备详情，未连接 = 设备选择）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 形态对齐参考实现 moondrop-pods 的 ui/pages/EarphonesTabPage.kt:19-77：
 * 用 androidx.compose.animation.AnimatedContent 在「设备详情」与「设备选择」之间切换。
 *
 * 判据只有「是否已连上」：普通 App 收窄后不再有「从模块页强制打开已配对列表」这个入口
 * （那张「配对蓝牙」卡片属于模块页，已随模块一起删除），因此不再需要「强制打开已配对列表」这个开关。
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
 * @param onDeviceSelected 选中一台已配对设备；默认交给 MoondropLink.connect()
 */
@Composable
internal fun EarphonesTabPage(
    snapshot: PodSnapshot,
    onOpenGestures: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onDeviceSelected: (BluetoothDevice) -> Unit = { device -> MoondropLink.connect(device) },
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
                onOpenGestures = onOpenGestures,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            DevicePickerPage(
                snapshot = snapshot,
                onConnect = onDeviceSelected,
                contentPadding = contentPadding,
            )
        }
    }
}
