/*
 * HyperPods for Moondrop — 应用打开时的运行时权限申请
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 用户需求：应用打开时主动申请蓝牙权限（否则连不上耳机）。
 *   · 蓝牙：API 31+ 才需要运行时授权的 BLUETOOTH_CONNECT / BLUETOOTH_SCAN；
 *   · 通知：API 33+ 的 POST_NOTIFICATIONS（连接通知/强提示要用）；
 *   · 三个权限都已在 AndroidManifest.xml 里声明（无需改 manifest）。
 *
 * 只在「还没授权」时申请一次（LaunchedEffect(Unit) + checkSelfPermission），
 * 不会每帧打扰用户；MainActivity 与 PopupActivity 两个入口都挂这一个 composable。
 *
 * rememberLauncherForActivityResult 用的是 ActivityResultRegistry 里不带 LifecycleOwner 的
 * register(key, contract, callback) 重载（activity-compose 源码 ActivityResultRegistry.kt:105），
 * 没有「必须早于 STARTED 注册」的检查，因此在 setContent 的根组合里调用是安全的。
 */
package moe.chenxy.hyperpods.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** 本应用需要申请的运行时权限（按 SDK 版本裁剪）。 */
fun requiredRuntimePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/** 是否还有没授予的权限；全部授予时返回 false（用于避免重复弹框）。 */
fun hasMissingRuntimePermissions(context: Context): Boolean =
    requiredRuntimePermissions().any {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

/**
 * 组合进入时申请一次权限（每个 Activity 实例一次）。
 * 已经全部授予时什么都不做。
 */
@Composable
fun RequestRuntimePermissionsOnLaunch() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 结果不需要在这里处理：真正用到权限的代码各自做 checkSelfPermission 兜底
    }

    LaunchedEffect(Unit) {
        if (hasMissingRuntimePermissions(context)) {
            launcher.launch(requiredRuntimePermissions())
        }
    }
}
