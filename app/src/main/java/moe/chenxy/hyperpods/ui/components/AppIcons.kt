/*
 * MiuixMoondrop — 底部导航用的矢量图标
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 图标本体照搬参考实现 moondrop-pods 的 ui/components/AppIcons.kt:12-60（Home / Headphones），
 * 只保留本项目底部导航真正用到的那一个：普通 App 收窄后只剩「耳机」页签，
 * Home 图标随「模块」页签一起删除，Contacts / RemoveContact 本来就未使用。
 * 纯 Compose ImageVector，不新增资源、不新增依赖。
 */
package moe.chenxy.hyperpods.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {
    val Headphones: ImageVector = ImageVector.Builder(
        name = "Headphones",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 13f)
            curveTo(4f, 7.5f, 7.8f, 4f, 12f, 4f)
            curveTo(16.2f, 4f, 20f, 7.5f, 20f, 13f)
            moveTo(7.2f, 13f)
            curveTo(5.8f, 13f, 5f, 14f, 5f, 15.4f)
            verticalLineTo(17f)
            curveTo(5f, 18.4f, 5.9f, 19.5f, 7.2f, 19.5f)
            horizontalLineTo(8.6f)
            verticalLineTo(13f)
            horizontalLineTo(7.2f)
            moveTo(16.8f, 13f)
            curveTo(18.2f, 13f, 19f, 14f, 19f, 15.4f)
            verticalLineTo(17f)
            curveTo(19f, 18.4f, 18.1f, 19.5f, 16.8f, 19.5f)
            horizontalLineTo(15.4f)
            verticalLineTo(13f)
            horizontalLineTo(16.8f)
        }
    }.build()
}
