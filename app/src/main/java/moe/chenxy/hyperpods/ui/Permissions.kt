/*
 * MiuixMoondrop — 应用打开时的运行时权限申请
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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

/**
 * 应用**没有可见界面**时能不能启动 Activity —— 也就是连接弹窗能不能自动弹出来。
 *
 * 为什么需要它：连接弹窗由 pods/BluetoothConnectReceiver.kt 在耳机连上时唤醒应用进程后弹出，
 * 那一刻应用处于后台，而 Android 10 起禁止后台启动 Activity（BAL）；不依赖厂商 ROM 的豁免
 * 只有「显示在其他应用上层」（SYSTEM_ALERT_WINDOW，见 AndroidManifest.xml）。
 * 没有这个权限时系统的行为是**静默拦掉**（不报错、不弹窗），所以判断结果要显式记日志。
 */
fun canStartActivityFromBackground(context: Context): Boolean =
    runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

/**
 * 跳到能开「后台弹出」权限的系统页面。
 *
 * 依次尝试，成功即返回：
 *   ① HyperOS/MIUI 的权限编辑页 —— MIUI 自己的「后台弹出界面」开关在这一页；
 *   ② 通用的「显示在其他应用上层」页；
 *   ③ 应用详情页（最后的兜底，用户至少能找到权限入口）。
 * 三步都用 runCatching：拿不到就返回 false，调用方只打日志，绝不因为跳不过去而崩。
 */
fun openBackgroundPopupPermissionSettings(context: Context): Boolean {
    val miuiEditor = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
        setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity",
        )
        putExtra("extra_pkgname", context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val overlay = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return listOf(miuiEditor, overlay, details).any { intent ->
        runCatching { context.startActivity(intent) }.isSuccess
    }
}
