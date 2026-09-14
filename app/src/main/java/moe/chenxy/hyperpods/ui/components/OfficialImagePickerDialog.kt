/*
 * MiuixMoondrop — 「机型图片」选择框（水月雨官方产品目录）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式沿用本仓库既有的弹框写法（见 ui/components/MutualExclusion.kt）：Miuix OverlayDialog +
 * 底部等宽 TextButton；必须放在 Miuix Scaffold 里（详情页就是）。
 *
 * 两处与「列表直接铺满」不同的取舍：
 *   · 官方目录有 105 款，一次拉 105 张缩略图不现实（每张 160~230 KB），所以列表行用默认
 *     占位图，**只为当前选中项**下载一张预览；
 *   · 进来自动选中与当前设备名最接近的那款（pods/PodImageStore.bestMatch），对不上就选第一款，
 *     用户仍可在列表里改。
 *
 * 不引新组件（进度指示器用文案，不用 Miuix 的 spinner）：本文件是新增 UI，
 * 能少依赖一个 API 就少一个编译风险。
 */
package moe.chenxy.hyperpods.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.MoondropOfficialImages
import moe.chenxy.hyperpods.pods.MoondropOfficialProduct
import moe.chenxy.hyperpods.pods.PodImageStore
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 机型图选择框。
 *
 * @param currentAddress 当前设备地址 —— 图片按它落盘（同一型号的两副耳机可以各自一张）
 * @param deviceName     用来预选官方目录里的机型
 */
@Composable
fun OfficialImagePickerDialog(
    show: Boolean,
    currentAddress: String,
    deviceName: String,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var products by remember(show) { mutableStateOf<List<MoondropOfficialProduct>>(emptyList()) }
    var selected by remember(show) { mutableStateOf<MoondropOfficialProduct?>(null) }
    var preview by remember(show) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(show) { mutableStateOf(false) }
    var applying by remember(show) { mutableStateOf(false) }
    // 「重试」用：改一下就重新触发下面的 LaunchedEffect
    var reloadKey by remember(show) { mutableStateOf(0) }

    LaunchedEffect(show, reloadKey) {
        if (!show) return@LaunchedEffect
        loading = true
        val list = withContext(Dispatchers.IO) { MoondropOfficialImages.fetchProducts() }
        products = list
        selected = withContext(Dispatchers.IO) { PodImageStore.bestMatch(list, deviceName) } ?: list.firstOrNull()
        loading = false
    }

    // 只为选中项下一个预览（105 款全下太重）
    LaunchedEffect(selected?.uuid) {
        preview = null
        val path = selected?.boxPath() ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            MoondropOfficialImages.downloadImage(path)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }

    OverlayDialog(
        title = stringResource(R.string.pod_image_title),
        summary = stringResource(R.string.official_image_dialog_summary),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = stringResource(R.string.official_image_hint),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

        when {
            loading -> Text(
                text = stringResource(R.string.official_image_loading),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )

            products.isEmpty() -> Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.official_image_error),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                TextButton(
                    text = stringResource(R.string.official_image_retry),
                    onClick = { reloadKey += 1 },
                )
            }

            else -> {
                preview?.let { bitmap ->
                    Image(
                        painter = BitmapPainter(bitmap.asImageBitmap()),
                        contentDescription = selected?.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(products, key = { it.uuid }) { product ->
                        OfficialProductRow(
                            product = product,
                            selected = product.uuid == selected?.uuid,
                            onClick = { selected = product },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (PodImageStore.hasImage(context, currentAddress)) {
                TextButton(
                    text = stringResource(R.string.official_image_reset),
                    onClick = {
                        PodImageStore.clear(context, currentAddress)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
            }
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                text = stringResource(R.string.official_image_apply),
                onClick = {
                    val product = selected ?: return@TextButton
                    if (applying) return@TextButton
                    applying = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            PodImageStore.applyProduct(context, currentAddress, product)
                        }
                        applying = false
                        if (ok) onDismissRequest()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun OfficialProductRow(
    product: MoondropOfficialProduct,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.img_box),
            contentDescription = product.displayName,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.displayName,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
        }
    }
}
