/*
 * HyperPods for Moondrop — 系统蓝牙广播：耳机连上就把应用进程拉起来
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么需要它（这是「模块未激活也要有通知」的最后一块拼图）：
 *   应用侧原先把进程拉起来的路径只有两条，且都依赖模块激活：
 *     · com.android.bluetooth 里的 hook 发 PODS_CONNECTED（见 pods/ControlBridge.kt 的 ControlReceiver）；
 *     · 用户自己打开本应用（ui/PodState.kt 的冷启动兜底发现已配对设备后自己连）。
 *   模块没在 LSPosed 激活时第一条不存在，于是最常见的用法「从未打开过 App + 在系统蓝牙里连上耳机」
 *   下进程根本不会被唤醒，pods/PodNotification.kt 也就没有机会发出那条通知。
 *   本接收器监听**系统蓝牙进程**在 A2DP 连接状态变化时发出的系统广播（manifest 静态注册，
 *   见 AndroidManifest.xml），把进程拉起来，再用既有的应用侧入口连一次耳机 —— 不新增连接路径、
 *   不重复实现电量解析、不持有唤醒锁、不起常驻服务。
 *
 * 判据与取舍：
 *   · 只在 A2DP STATE_CONNECTED 上动作；其它状态 / 缺 device extra 一律静默返回；
 *   · 「是不是水月雨」优先用设备名（[MoondropModels.isMoondrop]，与 ControlBridge.onConnected 同一套
 *     别名匹配），名字拿不到（缺 BLUETOOTH_CONNECT / 名字为空）才回落到 App 已存的「上次连接地址」
 *     （ui/PodState.kt 写的那份偏好）；两条都拿不到就 fail-closed 返回 —— 不硬编码任何地址，
 *     也不因为「不确定」去连一台来路不明的设备；
 *   · hook 已激活（复用同一个 hookNotificationScopeActive 判定）→ 直接返回不连：那条链路归
 *     com.xiaomi.bluetooth 进程所有，通知也由 hook 发（见 pods/PodNotification.kt 的去重策略）。
 */
package moe.chenxy.hyperpods.pods

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.chenxy.hyperpods.core.MoondropModels
import moe.chenxy.hyperpods.ui.UI_PREFS_GROUP
import moe.chenxy.hyperpods.ui.UI_PREFS_KEY_LAST_ADDRESS
import moe.chenxy.hyperpods.ui.currentXposedService
import moe.chenxy.hyperpods.ui.hookNotificationScopeActive

private const val TAG = "HyperPods-BtConnect"

/**
 * 由 AndroidManifest.xml 静态声明的接收器：耳机在系统蓝牙里连上时把本应用进程拉起来。
 *
 * ⚠ 这个类会被**每一条** A2DP 连接状态广播唤醒（任何蓝牙设备），所以「不是水月雨」这条分支
 *   必须做到零副作用：在 [handle] 里，设备判定排在所有初始化/连接动作之前。
 */
class BluetoothConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching { handle(context, intent) }
            .onFailure { Log.w(TAG, "onReceive failed for ${intent.action}", it) }
    }

    /**
     * 只在「A2DP 连上 + 是水月雨设备 + hook 未激活」时做一件事：用应用侧入口连一次耳机。
     * 其余情况（其它状态、没有 device extra、不是水月雨、hook 已激活）一律静默返回，无副作用。
     */
    private fun handle(context: Context, intent: Intent) {
        // action 由 manifest 的 intent-filter 保证，这里再兜一次（防御性，防显式广播直接投递）
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
        val state = intent.getIntExtra(
            BluetoothProfile.EXTRA_STATE,
            BluetoothProfile.STATE_DISCONNECTED,
        )
        if (state != BluetoothProfile.STATE_CONNECTED) return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (device == null) {
            Log.i(TAG, "${BluetoothDevice.EXTRA_DEVICE} missing in ${intent.action}; skipped")
            return
        }
        // 设备判定最先做：绝大多数广播都不是水月雨耳机，这一步之前不做任何初始化
        if (!isMoondropDevice(context, device)) {
            Log.i(TAG, "not a Moondrop device (name/address unavailable or unmatched); skipped")
            return
        }
        // 应用侧入口初始化：appContext + 事件转发器 + 框架服务绑定（与 ui/PodState.kt 的冷启动
        // 兜底同一条路；不发它的话 MoondropLink 没有 appContext，connect 会直接返回）
        ControlBridge.ensureInit(context.applicationContext)
        if (hookActive()) {
            Log.i(TAG, "module activated: link belongs to com.xiaomi.bluetooth; app does not connect")
            return
        }
        Log.i(TAG, "A2DP connected -> app-side connect (module not activated)")
        MoondropLink.connect(device)
    }

    /**
     * 这台是不是水月雨设备。
     *
     * ① 优先设备名：[MoondropModels.isMoondrop]（型号别名 contains 匹配 + MOONDROP / 水月雨 兜底，
     *    与 pods/ControlBridge.kt 的 onConnected 同一套判据）。读名字需要 BLUETOOTH_CONNECT，
     *    没授权会抛 SecurityException —— 这里按「名字拿不到」处理，而不是当成「不是水月雨」。
     * ② 名字拿不到时回落到 App 已存的「上次连接过的耳机地址」（ui/PodState.kt 冷启动兜底写的同一个键）；
     *    这份偏好只在用户以前打开过本应用并成功连过时才存在，没有就是 null，不做任何猜测。
     * ③ 两条都拿不到 → false。宁可这次不连（用户下次打开应用仍会走冷启动兜底），
     *    也绝不因为「不确定」就去连一台来路不明的设备。
     */
    private fun isMoondropDevice(context: Context, device: BluetoothDevice): Boolean {
        val name = runCatching { device.name }.getOrNull()
        if (!name.isNullOrBlank()) return MoondropModels.isMoondrop(name)
        val stored = storedAddress(context)
        val address = runCatching { device.address }.getOrNull()
        Log.i(TAG, "device name unavailable (BLUETOOTH_CONNECT?); stored=$stored device=$address")
        if (stored.isNullOrEmpty() || address.isNullOrEmpty()) return false
        return stored.equals(address, ignoreCase = true)
    }

    /** App 已存的「上次连接过的耳机地址」；从来没有连过（或读失败）返回 null。 */
    private fun storedAddress(context: Context): String? = runCatching {
        context.getSharedPreferences(UI_PREFS_GROUP, Context.MODE_PRIVATE)
            .getString(UI_PREFS_KEY_LAST_ADDRESS, null)
    }.getOrNull()

    /**
     * hook 侧是否已经接管（与 pods/PodNotification.hookWillNotify 同源的既有信号）：
     * libxposed 的 XposedService 绑上了 **且** 它报出来的作用域里有 com.xiaomi.bluetooth。
     *
     * 这里是**同步**判定：BroadcastReceiver.onReceive 没有异步窗口，也不为此引入 goAsync /
     * 唤醒锁（本接收器只做「连一下」这一件事）。进程刚起来时绑定可能还没回来 → 判为未激活 →
     * 由 App 连；这不冲突：模块真激活时 hook 那条 PODS_CONNECTED 也会唤醒同一个应用进程，
     * 两边最终走的是同一个 MoondropLink.connect（同地址幂等，见它自己的 entry 守卫），
     * 不会产生两条链路；服务绑上之后 PodNotification 会把 App 自己那条通知撤掉、交回 hook。
     */
    private fun hookActive(): Boolean {
        val service = currentXposedService() ?: return false
        return hookNotificationScopeActive(service)
    }
}
