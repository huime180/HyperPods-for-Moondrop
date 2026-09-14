/*
 * MiuixMoondrop — 关于页
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式对齐参考实现 moondrop-pods 的 ui/pages/AboutPage.kt:39-77：
 *   · LazyColumn + 左右 12dp、块间 12dp；
 *   · 图标/名称/版本居中的页头 + miuix Card 里的只读行；
 *   · 可点的行用 basic.BasicComponent(title, summary, onClick) 直接开浏览器（同参考实现的
 *     项目主页跳转），只读的行不传 onClick。
 * 本项目自己的图标与版本号沿用改造前的写法（R.drawable.ic_launcher_foreground + BuildConfig）。
 *
 * 背景动效（ui/effect）：与参考实现同一处理——整页正文包在 BgEffectBackground 里，
 * 自动开关（RuntimeShader 可用 + Android 版本够新），关掉时退化为普通 Box。
 */
package moe.chenxy.hyperpods.ui.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.effect.BgEffectBackground
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 卡片/区块之间的统一间距。 */
private val SECTION_GAP = 12.dp

/** 关于页：图标 + 版本 + 致谢/许可 + 项目主页（可点跳浏览器）。 */
@Composable
fun AboutPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current

    // OS3 动态背景：需要 RuntimeShader 支持 + Android 16 (SDK 36) 及以上，纯自动启用
    // （同参考实现 AboutPage.kt:28-33 的判定；不支持时 BgEffectBackground 退化成普通 Box）
    val effectBackground = remember {
        runCatching { isRuntimeShaderSupported() }.getOrDefault(false) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    }

    BgEffectBackground(
        dynamicBackground = effectBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().scrollEndHaptic(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + SECTION_GAP,
                bottom = contentPadding.calculateBottomPadding() + SECTION_GAP,
                start = 12.dp,
                end = 12.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = SECTION_GAP),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(72.dp),
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.padding(top = 8.dp),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                    )
                    Text(
                        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        modifier = Modifier.padding(top = 2.dp),
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        fontSize = 13.sp,
                    )
                }
            }

            item { SmallTitle(text = stringResource(R.string.about)) }

            item {
                Card {
                    BasicComponent(
                        title = stringResource(R.string.about_credits_title),
                        summary = stringResource(R.string.about_credits_summary),
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_license_title),
                        summary = stringResource(R.string.about_license_value),
                    )
                }
            }

            item {
                Card(modifier = Modifier.padding(top = SECTION_GAP)) {
                    BasicComponent(
                        title = stringResource(R.string.about_repo_title),
                        summary = stringResource(R.string.about_repo_url),
                        onClick = {
                            openUrl(context, context.getString(R.string.about_repo_url))
                        },
                    )
                }
            }
        }
    }
}

/** 打开外部浏览器；失败只记日志（与设备页打开系统设置同一处理方式）。 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
