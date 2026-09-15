/*
 * MiuixMoondrop — HyperOS 焦点通知 / 超级岛的 extra 构造
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么用第三方库而不是自己拼 JSON：MIUI 焦点通知的 extra 结构（`miui.focus.param` 的
 * JSON 模板、`miui.focus.pics` / `miui.focus.actions` 两袋 Parcelable）在 PROTOCOL.md §10.5
 * 里已经从真机 `dumpsys notification --noredact` 原文核对过，但**手拼那两袋的键与类型**
 * 仍然只能靠反汇编试错。`com.xzakota.hyper.notification:focus-api`（Maven Central，Apache-2.0）
 * 正是那套模板的官方实现，HyperPods（dev）分支在**同一台 HyperOS 4 平板**上用的就是它，
 * 所以这里直接复用同一个库、同一套 DSL 调用，不再自行发明字段。
 *
 * 普通 ROM 的行为：这些 extra 只是 Bundle 里的几个键，不会被识别的系统当作未知数据忽略 ——
 * 通知本身仍然是一条正常通知，所以带上它们对非 HyperOS 设备无害（这也是 dev 侧一直在做的）。
 *
 * 图片：`key_headset`（PROTOCOL.md §10.5.2 的真机原文就是这个 key，不是资源名）。
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
import org.json.JSONObject

object PodFocusNotification {
    private const val TAG = "MiuixMoondropFocus"
    /** 焦点通知里图片的键名（真机 dumpsys 原文，见 PROTOCOL.md §10.5.2）。 */
    private const val PIC_KEY = "key_headset"
    /** `miui.focus.param` 这个 extra 的键（库也是写它）。 */
    private const val PARAM_KEY = "miui.focus.param"

    /**
     * 构造 V3 焦点通知的 extras；拿不到图片 / 库抛异常时返回 null（调用方就不带 extras，
     * 通知仍按普通通知发出去 —— 焦点通知是锦上添花，不该让整条通知发不出来）。
     *
     * @param titleText   设备名（岛与普通通知的标题同一份文案）
     * @param contentText 连接状态 + 三路电量（与普通通知正文同一份文案）
     * @param aodText     息屏显示用的紧凑电量行，例如 `L 59% | R 64%`（空串则不写 AOD 字段）
     * @param boxBitmap   机型图；为 null 时回落仓库自带的 img_box
     */
    fun buildExtras(
        context: Context,
        titleText: String,
        contentText: String,
        aodText: String,
        boxBitmap: Bitmap?,
    ): Bundle? = runCatching {
        val bitmap = boxBitmap
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.img_box)
            ?: return@runCatching null
        val picture = Icon.createWithBitmap(bitmap)
        val extras = FocusNotification.buildV3 {
            val logo = createPicture(PIC_KEY, picture)
            // enableFloat：允许在状态栏/岛里浮动显示；updatable：允许后续再发一次更新它
            // （电量每 30s 变一次，不能更新就只能等它自己消失再出现）
            enableFloat = true
            updatable = true
            ticker = titleText
            iconTextInfo {
                animIconInfo {
                    type = 0
                    src = logo
                }
                // 注意：这里必须用外层参数（titleText/contentText），
                // 直接写 title = title 会被解析成接收者自己的属性（自己赋给自己）
                title = titleText
                content = contentText
            }
            island {
                islandProperty = 1
                bigIslandArea {
                    imageTextInfoLeft {
                        type = 1
                        picInfo {
                            type = 1
                            pic = logo
                        }
                    }
                    imageTextInfoRight {
                        type = 2
                        textInfo {
                            title = titleText
                            content = contentText
                        }
                    }
                }
            }
        } ?: return@runCatching null
        // AOD（息屏显示）：库的 DSL 里没有对应字段，按 dev 侧真机验证过的做法，
        // 把它补进 miui.focus.param 的 JSON 里（param_v2 下加 aodTitle / aodPic）。
        if (aodText.isNotBlank()) {
            runCatching {
                val json = JSONObject(extras.getString(PARAM_KEY) ?: "{}")
                val paramV2 = json.optJSONObject("param_v2") ?: JSONObject()
                paramV2.put("aodTitle", aodText)
                paramV2.put("aodPic", PIC_KEY)
                json.put("param_v2", paramV2)
                extras.putString(PARAM_KEY, json.toString())
            }.onFailure { Log.w(TAG, "AOD 字段注入失败（不影响岛与通知）", it) }
        }
        extras
    }.onFailure { Log.w(TAG, "buildExtras failed", it) }.getOrNull()
}
