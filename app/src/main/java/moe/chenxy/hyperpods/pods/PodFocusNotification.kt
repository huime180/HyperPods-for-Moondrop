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
 * 岛**不挂在常驻通知上**：常驻通知一旦带 `param_island`，HyperOS 会把岛一直挂着（用户实测）。
 * 所以常驻通知只带 `iconTextInfo`（焦点通知那条小条），岛由 [PodIslandNotification] 用一份
 * 单独的、`isShowNotification = false` + `islandTimeout` 的 extras 在连接/断开那一刻临时发一次。
 * 临时岛那份 extras 里**只写 `bigIslandArea`，不写 `smallIslandArea`**（摘要态）：dev 仓库蓝牙
 * 常驻通知里的岛就是这么写的，实测不会被系统一直挂在岛区；写了 smallIslandArea 反而会被当成
 * 「这条通知有摘要态」，连接后岛一直挂在岛区（用户反馈「常驻焦点通知还是有超级岛」）。
 *
 * 普通 ROM 会忽略这些 extra，通知照常显示，因此带上它们没有兼容性代价。
 */
package moe.chenxy.hyperpods.pods

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import com.xzakota.hyper.notification.focus.FocusNotification
import moe.chenxy.hyperpods.R

object PodFocusNotification {
    private const val TAG = "MiuixMoondropFocus"
    /** 焦点通知里图片的键名（真机 dumpsys 原文，见 PROTOCOL.md §10.5.2）。 */
    private const val PIC_KEY = "key_headset"

    /**
     * 构造 V3 焦点通知的 extras；拿不到图片 / 库抛异常时返回 null（调用方就不带 extras，
     * 通知仍按普通通知发出去 —— 焦点通知是锦上添花，不该让整条通知发不出来）。
     *
     * @param titleText      设备名（岛左栏与焦点通知标题同一份文案）
     * @param contentText    岛右栏要显示的内容：连接时是电量，断开时是「已断开」
     * @param aodText        息屏显示用的紧凑电量行（`L 59% | R 64%`）；空串则不写 AOD 字段
     * @param boxBitmap      机型图；为 null 时回落仓库自带的 img_box
     * @param withIsland     是否带上「超级岛」那一段。**常驻通知必须传 false**（否则岛一直挂着）
     * @param islandTimeoutSeconds 岛显示多久后由系统自动收起（岛的 `islandTimeout` 字段，**单位：秒**，
     *                       系统默认 3600 s）；null = 用系统默认。注意别和模板基类的 `timeout` 混：
     *                       那个单位是**分钟**，管的是整条通知的存活时间，不是岛的。
     * @param showInShade    是否在通知栏也留一条通知。临时岛传 false —— 只借岛显示一下，
     *                       不在通知栏里多出/闪出一条（库的 `isShowNotification`）
     * @param floating       是否让它「浮」成岛。**常驻通知必须传 false**：焦点通知本身带着
     *                       `param_island`，再叠上 enableFloat 就会被系统渲染成一个岛
     *                       （用户实测：常驻那条也一直带岛）。只有临时的连接/断开提示才浮。
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
        withIsland: Boolean,
        islandTimeoutSeconds: Int? = null,
        showInShade: Boolean = true,
        floating: Boolean = true,
        disconnectIntent: PendingIntent? = null,
        disconnectLabel: String = "",
    ): Bundle? = runCatching {
        val bitmap = boxBitmap
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.img_box)
            ?: return@runCatching null
        val picture = Icon.createWithBitmap(bitmap)
        FocusNotification.buildV3 {
            val logo = createPicture(PIC_KEY, picture)
            // enableFloat：允许在状态栏/岛区域浮动显示。常驻通知传 false —— 它不带岛，
            // 也不该浮起来；只有临时的连接/断开提示才浮。
            // islandFirstFloat 一起关掉：库默认会让焦点通知「先浮一下」，那也是岛。
            enableFloat = floating
            if (!floating) islandFirstFloat = false
            updatable = true
            ticker = titleText
            // 临时岛：不要在通知栏留痕
            if (!showInShade) isShowNotification = false
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
            if (!withIsland) {
                // 关键：库默认会带一个空的 IslandTemplate（序列化到 miui.focus.param 的
                // param_island），系统只要看到这个字段就会给通知渲染一个小岛 —— 光是不写
                // island {} 块并不够（用户实测「常驻的焦点通知也会一直显示超级岛」）。
                // 属性名是 island（@SerialName("param_island")），可空，显式置空才真的没有岛。
                island = null
            }
            if (withIsland) {
                island {
                    islandProperty = 1
                    // 岛自己多久收起：**秒**（岛模板字段 islandTimeout，系统默认 3600 s）。
                    // 早先这里把 5 打到了模板基类的 timeout 上 —— 那个字段单位是**分钟**，
                    // 等于给岛留了 5 分钟寿命，也是「岛不走」的一个帮凶。
                    islandTimeoutSeconds?.let { islandTimeout = it }
                    // 刻意**不写 smallIslandArea**（摘要态那块，只能放图片）：dev 仓库蓝牙常驻通知
                    // 的岛就只有 bigIslandArea，实测不会被系统一直挂在岛区；写了它反而会被当成
                    // 「有摘要态」，连接后岛一直挂在岛区。收起态照样画得出来 —— 系统用
                    // bigIslandArea 的「左图 + 右 title」渲染未展开态（真机实测），所以下面这套
                    // 左图右文同时就是小岛的形态。
                    bigIslandArea {
                        // 布局（用户要求）：左 = 图片 + 设备名，右 = 内容（电量 / 已断开）。
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
    }.onFailure { Log.w(TAG, "buildExtras failed", it) }.getOrNull()
}
