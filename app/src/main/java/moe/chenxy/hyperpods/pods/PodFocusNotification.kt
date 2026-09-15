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
 * 单独的、`isShowNotification = false` + `timeout` 的 extras 在连接/断开那一刻临时发一次。
 *
 * 普通 ROM 会忽略这些 extra，通知照常显示，因此带上它们没有兼容性代价。
 */
package moe.chenxy.hyperpods.pods

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
     * @param timeoutSeconds 岛显示多久（秒）后自动收起；null = 不限制（模板基类的 `timeout` 字段）
     * @param showInShade    是否在通知栏也留一条通知。临时岛传 false —— 只借岛显示一下，
     *                       不在通知栏里多出/闪出一条（库的 `isShowNotification`）
     */
    fun buildExtras(
        context: Context,
        titleText: String,
        contentText: String,
        aodText: String,
        boxBitmap: Bitmap?,
        withIsland: Boolean,
        timeoutSeconds: Int? = null,
        showInShade: Boolean = true,
    ): Bundle? = runCatching {
        val bitmap = boxBitmap
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.img_box)
            ?: return@runCatching null
        val picture = Icon.createWithBitmap(bitmap)
        FocusNotification.buildV3 {
            val logo = createPicture(PIC_KEY, picture)
            // enableFloat：允许在状态栏/岛区域浮动显示；updatable：允许后续再发一次更新它
            enableFloat = true
            updatable = true
            ticker = titleText
            // 临时岛：不要在通知栏留痕
            if (!showInShade) isShowNotification = false
            // AOD（息屏显示）文案：模板基类自带 aodTitle 字段，直接写即可
            //（不需要像 dev 当初那样再往 miui.focus.param 的 JSON 里塞 param_v2.aodTitle）
            if (aodText.isNotBlank()) aodTitle = aodText
            timeoutSeconds?.let { timeout = it }
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
            if (withIsland) {
                island {
                    islandProperty = 1
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
        }
    }.onFailure { Log.w(TAG, "buildExtras failed", it) }.getOrNull()
}
