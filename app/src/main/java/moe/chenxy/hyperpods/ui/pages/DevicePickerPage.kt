/*
 * HyperPods for Moondrop — 设备选择页（耳机页签在「未连接」时的内容）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 结构对齐参考实现 moondrop-pods 的 ui/pages/DevicePickerPage.kt:66-266：
 *   · 顶部是「选择设备」标题，下面是已配对设备列表，每台一张 Card（名称 + MAC，整行可点）；
 *   · 已连接的那台用 primary 色标出并显示「已连接」；
 *   · 监听蓝牙开关 / 配对状态广播，变化就重读列表（同参考实现 :102-119）；
 *   · 没有 BLUETOOTH_CONNECT 时先给一条权限提示 + 一个申请按钮（同参考实现 :121-135）。
 *
 * 与参考实现的差异（本项目不做或没有的能力）：
 *   · 参考实现有「手动输入 MAC」对话框，本项目沿用原有语义：只在已配对设备里挑
 *     （与 ui/PodState.kt 的冷启动兜底同一套 MoondropModels.match 过滤），不新造连接路径；
 *   · 参考实现在每台设备行里放关闭按钮去断开连接，本项目不主动断开系统连接，
 *     因此行内不放断开按钮（系统蓝牙设置里可以断开）。
 *
 * 状态单一来源仍是 pods/MoondropLink.kt：本页只把选中的 BluetoothDevice 交给
 * MoondropLink.connect()，连接结果通过 ui/PodState.kt 的 rememberPodSnapshot() 回流。
 */
package moe.chenxy.hyperpods.ui.pages

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.pods.PodSnapshot
import moe.chenxy.hyperpods.ui.WaitingPodsPage
import moe.chenxy.hyperpods.ui.requiredRuntimePermissions
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 空状态（蓝牙开着但没有已配对的水月雨耳机）里转圈区域的固定高度。 */
private val WAITING_AREA_HEIGHT = 300.dp

/** 是否有读蓝牙设备列表的权限。 */
private fun hasConnectPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 设备选择页。
 *
 * @param onConnect 用户点了某一台设备 → 交给 MoondropLink.connect()
 */
@SuppressLint("MissingPermission")
@Composable
fun DevicePickerPage(
    snapshot: PodSnapshot,
    onConnect: (BluetoothDevice) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasConnectPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasPermission = granted[Manifest.permission.BLUETOOTH_CONNECT]
            ?: hasConnectPermission(context)
    }
    // 蓝牙开关 / 配对状态一变就重读列表
    var refreshToken by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshToken++
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
            Context.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val adapter = remember(context) {
        runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()
    }
    val bluetoothEnabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    val bondedPods = remember(hasPermission, bluetoothEnabled, refreshToken) {
        if (!hasPermission || !bluetoothEnabled) {
            emptyList()
        } else {
            // getOrDefault 在这里会推出 List<BluetoothDevice>?（adapter/bondedDevices 都可空），
            // 于是下面的 .filter 报 "nullable receiver" —— CI 实测错误。用 getOrNull().orEmpty() 归一。
            runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
                // 只列水月雨耳机（与 ui/PodState.kt 冷启动兜底同一套匹配）
                .filter { MoondropModels.match(it.name) != null }
                .sortedBy { it.name.orEmpty() }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
    ) {
        if (!hasPermission) {
            item {
                Card {
                    BasicComponent(
                        title = stringResource(R.string.bt_permission_required),
                        summary = stringResource(R.string.waiting_for_pod_hint),
                    )
                }
            }
            item {
                TextButton(
                    text = stringResource(R.string.grant_permission),
                    onClick = { permissionLauncher.launch(requiredRuntimePermissions()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
            return@LazyColumn
        }

        item {
            Text(
                text = stringResource(R.string.select_device),
                modifier = Modifier.padding(bottom = 8.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        if (!bluetoothEnabled) {
            item {
                Card {
                    BasicComponent(
                        title = stringResource(R.string.bluetooth_disabled),
                        summary = stringResource(R.string.waiting_for_pod_hint),
                    )
                }
            }
            return@LazyColumn
        }

        if (bondedPods.isEmpty()) {
            // 没有已配对的耳机：保留原来的「等待耳机连接」整页状态（转圈 + 提示）
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WAITING_AREA_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    WaitingPodsPage()
                }
            }
            return@LazyColumn
        }

        items(bondedPods, key = { it.address }) { device ->
            DeviceRow(
                name = device.name.orEmpty().ifBlank { stringResource(R.string.unknown_model) },
                address = device.address,
                connected = snapshot.connected && device.address == snapshot.deviceAddress,
                onClick = { onConnect(device) },
            )
        }
    }
}

/** 一台已配对耳机：名称 + MAC，整行可点；当前连接的那台用 primary 色并标注「已连接」。 */
@Composable
private fun DeviceRow(
    name: String,
    address: String,
    connected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (connected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            Text(
                text = address,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (connected) {
                Text(
                    text = stringResource(R.string.conn_connected),
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}
