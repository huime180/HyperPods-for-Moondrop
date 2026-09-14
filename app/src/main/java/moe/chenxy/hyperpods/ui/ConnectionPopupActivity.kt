/*
 * MiuixMoondrop — 连接弹窗（连上耳机时弹一次，显示三路电量后自动关闭）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 移植自参考实现 moondrop-pods 的 ConnectionPopupActivity.kt（429 行）：
 *   · 全透明窗口 + 底部居中的大圆角卡片（55dp 圆角，浅色底 #FBFBFB）；
 *   · 卡片内容自上而下：设备名 → 耳机盒位图（img_box）→ 左右耳/充电盒三行电量
 *     → 「完成」按钮；右上角还有一个圆形关闭按钮；
 *   · 电量图标是 10 档 PNG（drawable-nodpi 的 common_1..10 / charge_1..10，
 *     充电态用 charge_*，night 变体在 drawable-night-nodpi）；
 *   · 到点自动关闭（dismiss_seconds，默认 8s，只接受 3/5/8/10/15/30）；
 *   · 卡片在收到 PODS_DISCONNECTED 时立即关闭，收到 BATTERY_CHANGED 时刷新电量。
 *
 * ── 与参考实现的差异（本项目的进程/数据契约）────────────────────────────
 *   ① 电量载体：参考实现从 Intent 里取它的 Parcelable `BatteryParams`；
 *      本项目没有那个类，所以这里改用 **pods/ControlBridge.kt 的 Bundle 编码**
 *      （BatteryCodecWire）：
 *        "left" / "right" / "case" 三个 Int，低 7 位 = 0..100 电量，bit7 = 充电中，
 *        255 = 未知。
 *      这个 Bundle 就是 HyperPodsAction.EXTRA_BATTERY（"batteryParams"）的载荷。
 *   ② 没有读数显示「-」、读到 0 显示「离线」，绝不显示 0%
 *      （与 ui/components/PodStatus.kt 的「离线」口径一致）。
 *   ③ 文案全部走 res/values/strings.xml + values-zh-rCN/strings.xml
 *      （参考实现是写死的中文）。
 *   ④ 不在这里申请运行时权限：这是连接耳机时自动弹出的窗口，弹权限框会打断用户；
 *      权限由 MainActivity / PopupActivity 在用户主动进入时申请。
 *
 * ── 谁负责弹它、数据从哪来 ────────────────────────────────────────────────
 *   pods/ControlBridge.kt 在首次拿到有效电量的那一刻用**显式组件**启动本 Activity，
 *   并把设备名与电量直接作为 extra 带进来（EXTRA_DEVICE_NAME / EXTRA_STATUS）：
 *
 *     context.startActivity(
 *         Intent(context, ConnectionPopupActivity::class.java)
 *             .putExtra(ConnectionPopupActivity.EXTRA_STATUS, batteryBundle)
 *             .putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, name)
 *             .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
 *     )
 *
 *   因此弹窗首帧就有内容。
 *   也可以按 action 隐式拉起：chen.action.hyperpods.moondrop.show_connection_popup
 *   （= ConnectionPopupActivity.ACTION_SHOW_CONNECTION_POPUP，manifest 里有同名过滤器）。
 *
 * ── 为什么首帧之后还要再看一遍状态（本轮修的正是这里）──────────────────
 *   协议栈（pods/MoondropLink.kt）就在**本应用进程**里，弹窗要的实时状态不必绕任何广播。
 *   上一版把弹窗内容冻在启动那一刻的 extra 上、靠
 *   `PODS_CONNECTED / BATTERY_CHANGED / PODS_DISCONNECTED` 三条广播刷新 ——
 *   那三条是 Xposed 时代由被注入的系统进程发出的，本应用已不是模块，**没有任何发送方**，
 *   于是刷新路径是死的。真机实测（布丁，2026-09-15）：
 *   `已连接 左耳 2 %`（首批电量帧只带左耳）后 **286 ms** 就是 `左耳 59 % · 右耳 64 %`，
 *   而弹窗整段存活期都停在 `左耳 2 % / 右耳 - / 充电盒 -` —— 用户看到的是错的数。
 *   现在改成订阅进程内 [moe.chenxy.hyperpods.pods.PodEvent]（[rememberPodSnapshot]）：
 *     · extra 仍然带进首帧内容（启动方已经在手，零成本）；
 *     · 之后一律以进程内快照为准，首批帧一落定就自动纠正，断开即收窗。
 */
package moe.chenxy.hyperpods.ui

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.BatterySnapshot
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** 单路电量读数：level 不在 0..100 即未知（显示「-」，不显示 0%）。 */
private data class PopupBatteryLine(val level: Int, val charging: Boolean) {
    val known: Boolean get() = level in 0..100
}

/** 连接弹窗自己的电量模型（三路）。 */
private data class PopupBattery(
    val left: PopupBatteryLine = PopupBatteryLine(-1, false),
    val right: PopupBatteryLine = PopupBatteryLine(-1, false),
    val case: PopupBatteryLine = PopupBatteryLine(-1, false),
)

class ConnectionPopupActivity : ComponentActivity() {

    companion object {
        /**
         * 隐式启动本弹窗用的 action（manifest 里有同名过滤器）。
         * 外部按 action 拉起本类时可以直接照抄这个字符串值：
         * `chen.action.hyperpods.moondrop.show_connection_popup`
         */
        const val ACTION_SHOW_CONNECTION_POPUP =
            "chen.action.hyperpods.moondrop.show_connection_popup"

        /** 自动关闭秒数 extra（只接受 3 / 5 / 8 / 10 / 15 / 30，其余回落默认值）。 */
        const val EXTRA_DISMISS_SECONDS = "dismiss_seconds"

        /** 电量 Bundle extra（与参考实现同名）；同时也接受 HyperPodsAction.EXTRA_BATTERY。 */
        const val EXTRA_STATUS = "status"

        /**
         * 弹窗是否真的显示过 / 此刻是否可见。
         *
         * 为什么需要：耳机连上时本弹窗是从**后台**启动的，而 Android 10 起系统可以**静默**拦掉
         * 后台启动 Activity（BAL，见 ui/Permissions.kt）—— 不抛异常、也没有任何回调。
         * pods/ControlBridge.kt 就在启动后回头确认这两个标记，没落地就重试（同进程，静态标记即可）。
         */
        @Volatile internal var lastShownAt: Long = 0L

        @Volatile internal var visible: Boolean = false

        private val DISMISS_SECOND_OPTIONS = listOf(3, 5, 8, 10, 15, 30)
        private const val DEFAULT_DISMISS_SECONDS = 8
    }

    override fun onResume() {
        super.onResume()
        visible = true
        lastShownAt = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        super.onPause()
        visible = false
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全透明宿主：卡片自己带底色，卡片外点不到（与参考实现一致）
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val initialBattery = decodeBattery(
            intent.getBundleExtra(EXTRA_STATUS)
                ?: intent.getBundleExtra(HyperPodsAction.EXTRA_BATTERY)
        )
        val initialDeviceName = intent.getStringExtra(HyperPodsAction.EXTRA_DEVICE_NAME).orEmpty()
        val autoDismissSeconds = intent.getIntExtra(
            EXTRA_DISMISS_SECONDS,
            DEFAULT_DISMISS_SECONDS
        ).takeIf { it in DISMISS_SECOND_OPTIONS }
            ?: DEFAULT_DISMISS_SECONDS

        // 应用内主题（跟随系统 / 浅色 / 深色）与主界面同一口径
        val colorSchemeMode = when (loadThemeMode(this)) {
            1 -> ColorSchemeMode.Light
            2 -> ColorSchemeMode.Dark
            else -> ColorSchemeMode.System
        }

        setContent {
            AppTheme(colorSchemeMode = colorSchemeMode) {
                ConnectionPopupContent(
                    initialDeviceName = initialDeviceName,
                    initialBattery = initialBattery,
                    autoDismissSeconds = autoDismissSeconds,
                    onDismiss = { finish() },
                )
            }
        }
    }
}

/**
 * 解码 ControlBridge 的 Bundle 电量编码（低 7 位电量 + bit7 充电 + 255 未知）。
 * 同时兼容显式布尔 extra（BatteryCodecWire 两个都写）。
 */
private fun decodeBattery(bundle: Bundle?): PopupBattery {
    if (bundle == null) return PopupBattery()
    return PopupBattery(
        left = decodeLine(bundle, "left", "left_charging"),
        right = decodeLine(bundle, "right", "right_charging"),
        case = decodeLine(bundle, "case", "case_charging"),
    )
}

private fun decodeLine(bundle: Bundle, key: String, chargingKey: String): PopupBatteryLine {
    if (!bundle.containsKey(key)) return PopupBatteryLine(-1, false)
    val raw = bundle.getInt(key, -1)
    if (raw < 0) return PopupBatteryLine(-1, false)
    val level = raw and 0x7F
    if (level !in 0..100) return PopupBatteryLine(-1, false)
    val charging = (raw and 0x80) != 0 || bundle.getBoolean(chargingKey, false)
    return PopupBatteryLine(level, charging)
}

/**
 * 进程内快照 → 弹窗自己的三路模型（[PopupBattery]）。
 *
 * 与 [decodeBattery] 的区别只是数据来源：这里来自 pods/MoondropLink.kt 的实时快照，
 * 那里来自启动方塞进 intent 的 Bundle（首帧兜底）。判定口径完全一致：
 * `*Known == false`（[moe.chenxy.hyperpods.core.BATTERY_UNKNOWN]）＝ 没有读数。
 */
private fun BatterySnapshot.toPopupBattery(): PopupBattery = PopupBattery(
    left = PopupBatteryLine(if (leftKnown) left else -1, leftCharging),
    right = PopupBatteryLine(if (rightKnown) right else -1, rightCharging),
    case = PopupBatteryLine(if (caseKnown) case else -1, caseCharging),
)

@Composable
private fun ConnectionPopupContent(
    initialDeviceName: String,
    initialBattery: PopupBattery,
    autoDismissSeconds: Int,
    onDismiss: () -> Unit,
) {
    // 状态源：**进程内**的 [PodEvent]（协议栈与弹窗同一个进程）。
    // 不使用任何广播：hook 时代那三条跨进程广播在本应用里没有发送方（见文件头）。
    val snapshot = rememberPodSnapshot()

    // 首帧优先用启动方带进来的 extra（进程内快照还没来得及更新时也绝不空白）；
    // 只要进程内快照已经有读数，就以它为准 —— 它总是比 extra 新。
    val battery = if (snapshot.battery.anyKnown) snapshot.battery.toPopupBattery() else initialBattery
    val deviceName = snapshot.deviceName.ifBlank { initialDeviceName }

    // 断开就收窗：耳机拔了 / 关了之后卡片上的读数已经没有意义。
    // 必须区分「从没连上过」与「连上又断开」—— 按 action 隐式拉起时本来就没连接，
    // 那时不能立刻把自己关掉（用户是主动打开它看状态的）。
    var everConnected by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.connected) {
        if (snapshot.connected) {
            everConnected = true
        } else if (everConnected) {
            onDismiss()
        }
    }

    LaunchedEffect(autoDismissSeconds) {
        delay(autoDismissSeconds * 1000L)
        onDismiss()
    }

    ConnectionPopupCard(
        deviceName = deviceName.ifEmpty { stringResource(R.string.app_name) },
        battery = battery,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ConnectionPopupCard(
    deviceName: String,
    battery: PopupBattery,
    onDismiss: () -> Unit,
) {
    // 卡片固定浅色（同参考实现）：官方 App 的连接弹窗是固定浅色的产品图卡片，
    // 因此文字/底色不跟随系统深浅色，避免深色下白底黑字/黑底黑字互相冲突。
    val containerColor = Color(0xFFFBFBFB)
    val textColor = Color(0xFF111111)
    val secondaryTextColor = Color(0xFF333333)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .clip(RoundedCornerShape(55.dp))
                .background(containerColor),
        ) {
            CloseButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 22.dp, end = 22.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 29.dp, bottom = 31.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    text = deviceName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 75.dp),
                    style = TextStyle(
                        color = textColor,
                        fontSize = 19.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(38.dp))
                ConnectionPodImage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(960f / 312f),
                )
                Spacer(modifier = Modifier.height(22.dp))

                BatterySummary(battery = battery, textColor = secondaryTextColor)

                Spacer(modifier = Modifier.height(52.dp))
                DoneButton(
                    onClick = onDismiss,
                    textColor = textColor,
                    modifier = Modifier.padding(horizontal = 30.dp),
                )
            }
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(27.dp)
            .clip(CircleShape)
            .background(Color(0xFFF1F1F1))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(9.dp)) {
            val strokeWidth = 2.8.dp.toPx()
            drawLine(
                color = Color(0xFF8B8B8B),
                start = Offset(1.dp.toPx(), 1.dp.toPx()),
                end = Offset(size.width - 1.dp.toPx(), size.height - 1.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFF8B8B8B),
                start = Offset(size.width - 1.dp.toPx(), 1.dp.toPx()),
                end = Offset(1.dp.toPx(), size.height - 1.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ConnectionPodImage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFFFBFBFB)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.img_box),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 8.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/** 左右耳两行（左列）+ 充电盒一行（右列），位置与参考实现相同的 25% / 75% 分栏。 */
@Composable
private fun BatterySummary(battery: PopupBattery, textColor: Color) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnWidth = 110.dp
        val leftCenter = maxWidth * 0.25f
        val caseCenter = maxWidth * 0.75f

        Column(
            modifier = Modifier
                .width(columnWidth)
                .offset(x = leftCenter - columnWidth / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BatteryLine(
                label = stringResource(R.string.batt_left),
                line = battery.left,
                textColor = textColor,
            )
            BatteryLine(
                label = stringResource(R.string.batt_right),
                line = battery.right,
                textColor = textColor,
            )
        }
        Box(
            modifier = Modifier
                .width(columnWidth)
                .offset(x = caseCenter - columnWidth / 2),
            contentAlignment = Alignment.TopCenter,
        ) {
            BatteryLine(
                label = stringResource(R.string.batt_case),
                line = battery.case,
                textColor = textColor,
            )
        }
    }
}

/**
 * 一行电量：文案 + 电池图标 + 百分比。
 * 未知（未连接 / 没有读数）显示「-」，**绝不显示 0%**。
 */
@Composable
private fun BatteryLine(label: String, line: PopupBatteryLine, textColor: Color) {
    // 没有读数（-1）显示「-」；读到 0 显示「离线」—— 固件对未连接的一侧回 0x00，
    // 一个真能上报的耳机不可能是真的 0 %（与 ui/components/PodStatus.kt 同一口径）。
    val offline = !line.known || line.level <= 0
    val levelText = when {
        !line.known -> "-"
        offline -> stringResource(R.string.battery_offline)
        else -> "${line.level}%"
    }
    val charging = line.charging && !offline

    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = label,
            style = TextStyle(
                color = textColor,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
            ),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Image(
            painter = painterResource(getBatteryIconRes(if (offline) 0 else line.level, charging)),
            contentDescription = "$label $levelText",
            modifier = Modifier.size(width = 28.dp, height = 17.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        BasicText(
            text = levelText,
            style = TextStyle(
                color = textColor,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit, textColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF2F2F2))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(R.string.done),
            style = TextStyle(
                color = textColor,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** 电量档 → 电池图标资源（10 档，充电态用 charge_*）。逐字对齐参考实现的取档逻辑。 */
private fun getBatteryIconRes(level: Int, isCharging: Boolean): Int {
    val index = when {
        level <= 10 -> 1
        level <= 20 -> 2
        level <= 30 -> 3
        level <= 40 -> 4
        level <= 50 -> 5
        level <= 60 -> 6
        level <= 70 -> 7
        level <= 80 -> 8
        level <= 90 -> 9
        else -> 10
    }
    return if (isCharging) {
        when (index) {
            1 -> R.drawable.charge_1
            2 -> R.drawable.charge_2
            3 -> R.drawable.charge_3
            4 -> R.drawable.charge_4
            5 -> R.drawable.charge_5
            6 -> R.drawable.charge_6
            7 -> R.drawable.charge_7
            8 -> R.drawable.charge_8
            9 -> R.drawable.charge_9
            else -> R.drawable.charge_10
        }
    } else {
        when (index) {
            1 -> R.drawable.common_1
            2 -> R.drawable.common_2
            3 -> R.drawable.common_3
            4 -> R.drawable.common_4
            5 -> R.drawable.common_5
            6 -> R.drawable.common_6
            7 -> R.drawable.common_7
            8 -> R.drawable.common_8
            9 -> R.drawable.common_9
            else -> R.drawable.common_10
        }
    }
}
