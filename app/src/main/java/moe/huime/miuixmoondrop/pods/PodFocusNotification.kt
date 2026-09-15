/*
 * MiuixMoondrop — HyperOS 焦点通知 / 超级岛的 extra 构造
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么用第三方库而不是自己拼 JSON：MIUI 焦点通知的 extra 结构（`miui.focus.param` 的
 * JSON 模板、`miui.focus.pics` / `miui.focus.actions` 两袋 Parcelable）在 PROTOCOL.md §10.5
 * 里已经从真机 `dumpsys notification --noredact` 原文核对过，但**手拼那两袋的键与类型**
 * 仍然只能靠反汇编试错。`com.xzakota.hyper.notification:focus-api`（Maven Central）正是那套
 * 模板的实现，HyperPods（dev）分支在同一台 HyperOS 4 平板上用的就是它，所以这里复用同一个库、
 * 同一套 DSL 调用，不自行发明字段。
 *
 * **超级岛挂在常驻通知上**（2026-09 起）：本应用不再单独发「连接 / 断开」的临时岛通知
 * （原 pods/PodIslandNotification.kt 与设置页「超级岛」开关一并删除），岛跟着
 * pods/PodNotification.kt 这条常驻焦点通知一起出现（连接、以及电量变化的那一刻），
 * 几秒内自己收掉。下面四条是踩过三次坑之后留下的结论，别再走回头路：
 *   1. **把 `island` 置空不管用**：库的 `Json` 配了 `explicitNulls = false`，null 值根本不会写进
 *      JSON（「显式置空」在代码上早已走到底）；而焦点通知在 HyperOS 上自带摘要态，不给
 *      `param_island` 时系统会用默认形态补一个**常驻摘要胶囊** —— 这就是最早「岛一直挂着」的来源；
 *   2. **不要写 `islandFirstFloat = false`**：该字段含义是「通知第一次出现时的档位」，false = **摘要态**
 *      —— 摘要态正是岛区小胶囊的地盘，常驻通知被强制成摘要态就会一直挂着；
 *   3. 岛模板**只写 `bigIslandArea`、不写 `smallIslandArea`**：摘要态没有自己的内容，岛不会长住
 *      （dev 仓库蓝牙常驻通知就是这个形态）；
 *   4. 再显式 `dismissIsland = true`（官方字段：摘要态是否消失，true = 消失）＋ `islandTimeout`
 *      （**秒**）让它几秒内自己退场。真正的判定在设备侧：抓 `MiuixMoondropFocus` 的
 *      `focus param:` 日志可以看到这段 JSON。
 *
 * 普通 ROM 会忽略这些 extra，通知照常显示，因此带上它们没有兼容性代价。
 */
package moe.huime.miuixmoondrop.pods

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import com.xzakota.hyper.notification.focus.FocusNotification
import moe.huime.miuixmoondrop.R

object PodFocusNotification {
    private const val TAG = "MiuixMoondropFocus"
    /** 焦点通知里图片的键名（真机 dumpsys 原文，见 PROTOCOL.md §10.5.2）。 */
    private const val PIC_KEY = "key_headset"

    /**
     * 岛在岛区露多久（**秒**）——岛模板字段 `islandTimeout`，由系统到点收起。
     * 别和模板基类的 `timeout` 混：那个单位是**分钟**，管的是整条通知的存活时间。
     */
    private const val ISLAND_TIMEOUT_SECONDS = 5

    /**
     * 构造 V3 焦点通知的 extras；拿不到图片 / 库抛异常时返回 null（调用方就不带 extras，
     * 通知仍按普通通知发出去 —— 焦点通知是锦上添花，不该让整条通知发不出来）。
     *
     * @param titleText      设备名（岛左栏与焦点通知标题同一份文案）
     * @param contentText    岛右栏要显示的内容：连接时是电量，断开时是「已断开」
     * @param aodText        息屏显示用的紧凑电量行（`L 59% | R 64%`）；空串则不写 AOD 字段
     * @param boxBitmap      机型图；为 null 时回落仓库自带的 img_box
     * @param disconnectIntent 通知动作「断开连接」的落点（PendingIntent.getBroadcast 指向
     *                       pods/PodDisconnectReceiver）；null = 不带这个按钮（临时岛就不带）
     * @param disconnectLabel 按钮文案（取本应用的 R.string.notification_disconnect）
     */
    fun buildExtras(
        context: Context,
        titleText: String,
        contentText: String,
        aodText: String,
        boxBitmap: Bitmap?,
        disconnectIntent: PendingIntent? = null,
        disconnectLabel: String = "",
    ): Bundle? = runCatching {
        val bitmap = boxBitmap
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.img_box)
            ?: return@runCatching null
        val picture = Icon.createWithBitmap(bitmap)
        val extras = FocusNotification.buildV3 {
            val logo = createPicture(PIC_KEY, picture)
            // enableFloat 恒为 false：通知更新时不再自动展开岛 —— 岛跟着这条常驻通知出现一次、
            // 几秒内自己收掉就够了，不该每 30s 一轮的电量更新都再展开一次。
            //
            // 注意：**不要写 islandFirstFloat = false**。它的含义是「通知第一次出现时的档位」，
            // false = 摘要态 —— 而摘要态正是岛区那个「一直挂着的小胶囊」的地盘。常驻通知一旦
            // 被强制成摘要态，就变成「常驻焦点通知始终有超级岛」（用户实测，三次都复现）。
            enableFloat = false
            updatable = true
            ticker = titleText
            // AOD（息屏显示）文案：模板基类自带 aodTitle 字段，直接写即可
            //（不需要像 dev 当初那样再往 miui.focus.param 的 JSON 里塞 param_v2.aodTitle）
            if (aodText.isNotBlank()) aodTitle = aodText
            iconTextInfo {
                animIconInfo {
                    type = 0
                    src = logo
                }
                // 这里必须用外层参数名（titleText/contentText）：直接写 title = title 会被解析成
                // 接收者自己的属性（自己赋给自己）
                title = titleText
                content = contentText
            }
            // 岛：常驻通知自己带（2026-09 起不再有单独的临时岛通知）。形态照 dev 仓库蓝牙
            // 常驻通知那份 —— 只有 bigIslandArea。
            //
            // 只写 bigIslandArea、**不写 smallIslandArea**：摘要态没有自己的内容，岛不会长住
            // （dev 那条「没有超级岛」就是这个形态）。再显式 dismissIsland = true（官方字段：
            // 摘要态是否消失，true = 消失）＋ islandTimeout（**秒**）让它在几秒内自己退场。
            island {
                islandProperty = 1
                dismissIsland = true
                islandTimeout = ISLAND_TIMEOUT_SECONDS
                bigIslandArea {
                    // 布局（用户要求）：左 = 图片 + 设备名，右 = 内容（电量 / 已连接 / 已断开）。
                    // 右栏刻意写成 title 而不是 content：实测右栏只渲染 title —— 原来把电量放在
                    // content 里时，岛上只看得到设备名、看不到电量。
                    imageTextInfoLeft {
                        type = 1
                        picInfo {
                            type = 1
                            pic = logo
                        }
                        textInfo {
                            title = titleText
                        }
                    }
                    imageTextInfoRight {
                        type = 2
                        textInfo {
                            title = contentText
                        }
                    }
                }
            }
            // 「断开连接」：挂在**模板动作栏**（param_v2.actions）上，与 dev 仓库蓝牙通知里那个
            // 按钮同一套写法 —— 不用 textButton（那是内容下方独立一整行的胶囊区，只剩一个按钮时
            // 会被系统拉满整行）。type = 2 是「文字按钮」（focus-api 的 ActionInfo 注释：
            // 0 圆形 / 1 进度 / 2 文字；其 getType() 规则同为「无图标 + 有标题 = 2」），
            // 文案照常显示，不会退化成只有一个图标。
            // 点击链路：createAction 把 Notification.Action 放进 miui.focus.actions 那一袋
            // Parcelable，再让 ActionInfo.action 用 key 引用它（MIUI 官方文档「Action 数据参数」）。
            if (disconnectIntent != null) {
                actions {
                    addActionInfo {
                        type = 2
                        actionTitle = disconnectLabel
                        val actionParcel = Notification.Action.Builder(
                            Icon.createWithResource(context, android.R.drawable.ic_delete),
                            disconnectLabel,
                            disconnectIntent,
                        ).build()
                        action = createAction("key_disconnect", actionParcel)
                    }
                }
            }
        }
        // 诊断用：把真正发出去的 miui.focus.param 原文打一条 debug 日志。
        // 「常驻通知还挂不挂岛」这类问题只能看这段 JSON（param_island / islandFirstFloat /
        // dismissIsland 到底写进去没有），现场抓 logcat 过滤 MiuixMoondropFocus 即可。
        Log.d(TAG, "focus param: ${extras.getString("miui.focus.param")}")
        extras
    }.onFailure { Log.w(TAG, "buildExtras failed", it) }.getOrNull()
}
