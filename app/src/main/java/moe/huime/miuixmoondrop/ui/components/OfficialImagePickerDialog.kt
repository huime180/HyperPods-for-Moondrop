/*
 * MiuixMoondrop — 「机型图片」选择框（水月雨官方产品目录）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 版式沿用本仓库既有的弹框写法（见 ui/components/MutualExclusion.kt）：Miuix OverlayDialog +
 * 底部等宽 TextButton；必须放在 Miuix Scaffold 里（详情页就是）。
 *
 * 三处与「列表直接铺满」不同的取舍：
 *   · 官方目录过滤后有 49 款，一次拉 49 张缩略图不现实（每张 160~230 KB），所以列表行**懒加载**：
 *     每行的缩略图由这一行自己的 LaunchedEffect 下载（LazyColumn 只为真正组合出来的那几行
 *     跑），下载 + 解码全部在 Dispatchers.IO，结果进 [OfficialThumbCache] 内存缓存；
 *     下载中 / 失败显示仓库既有的 img_box 占位图；
 *   · 选中项的**大预览**仍是 [MoondropOfficialProduct.boxPath] 那张透明渲染图（与英雄图同一张），
 *     两者用途不同：大图叠底色要透明图，缩略图要构图合适的方形图；
 *   · 进来自动选中与当前设备名最接近的那款（pods/PodImageStore.bestMatch）并把它**置顶**，
 *     对不上就按官方目录顺序，用户仍可在列表里改。
 *
 * 不引新组件（进度指示器用文案，不用 Miuix 的 spinner）：本文件是新增 UI，
 * 能少依赖一个 API 就少一个编译风险。
 */
package moe.huime.miuixmoondrop.ui.components

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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.huime.miuixmoondrop.R
import moe.huime.miuixmoondrop.pods.MoondropOfficialImages
import moe.huime.miuixmoondrop.pods.MoondropOfficialProduct
import moe.huime.miuixmoondrop.pods.PodImageStore
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
    /** 与当前设备名最接近的那款的 uuid（置顶行 + 「当前机型」小字都看它）。 */
    var currentUuid by remember(show) { mutableStateOf<String?>(null) }
    var preview by remember(show) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(show) { mutableStateOf(false) }
    var applying by remember(show) { mutableStateOf(false) }
    // 「重试」用：改一下就重新触发下面的 LaunchedEffect
    var reloadKey by remember(show) { mutableStateOf(0) }

    LaunchedEffect(show, reloadKey) {
        if (!show) return@LaunchedEffect
        loading = true
        val list = withContext(Dispatchers.IO) { MoondropOfficialImages.fetchProducts() }
        // 当前机型置顶：其余保持官方目录原来的名称顺序（列表自身不再重排第二次）
        val match = withContext(Dispatchers.IO) { PodImageStore.bestMatch(list, deviceName) }
        products = if (match == null) list else listOf(match) + list.filter { it.uuid != match.uuid }
        currentUuid = match?.uuid
        selected = match ?: list.firstOrNull()
        loading = false
    }

    // 只为选中项下一个预览（49 款全下太重）
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
                            isCurrent = product.uuid == currentUuid,
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

/**
 * 列表里的一行：左侧缩略图 + 机型名（置顶的当前机型再多一行小字）。
 *
 * 缩略图在**这一行自己的 LaunchedEffect** 里加载 —— LazyColumn 只为真正组合出来的行跑它，
 * 所以滑到哪一行才下哪一张（不会一进列表就下 49 张）。
 */
@Composable
private fun OfficialProductRow(
    product: MoondropOfficialProduct,
    selected: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    // 按 uuid remember：列表重排（当前机型置顶）时不会把上一行的下载结果串到下一行
    val thumbnailPath = remember(product.uuid) { product.thumbnailPath() }
    var thumbnail by remember(product.uuid) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(product.uuid, thumbnailPath) {
        if (thumbnailPath == null) return@LaunchedEffect
        thumbnail = withContext(Dispatchers.IO) { OfficialThumbCache.load(thumbnailPath) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnailModifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = product.displayName,
                modifier = thumbnailModifier,
                contentScale = ContentScale.Fit,
            )
        } else {
            // 下载中 / 下载失败 / 这张图解不出来：显示仓库既有的静态产品图
            Image(
                painter = painterResource(R.drawable.img_box),
                contentDescription = product.displayName,
                modifier = thumbnailModifier,
                contentScale = ContentScale.Fit,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.displayName,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
            if (isCurrent) {
                // 为什么置顶：这一条是按设备名匹配出来的当前机型，给小字说明免得用户以为排序乱了
                Text(
                    text = stringResource(R.string.official_image_current),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 列表缩略图的内存缓存（下载 + 采样解码）。
 *
 * 为什么单独放一个对象：
 *   · 缓存要跨行、跨滚动存活 —— 放进 composable 的 remember 里，行一滑出组合就被回收，
 *     来回滚动会反复下载同一张图；
 *   · 解码必须采样：官方原图 1125×597，整张 ARGB 位图约 2.6 MB，49 行全尺寸留在内存里
 *     是几百 MB。缩略图只有 48dp，这里用 `inJustDecodeBounds` 量一次尺寸、再算 inSampleSize，
 *     把长边压到 200~300 px 量级（1125×597 → inSampleSize=4，约 170 KB/张）。
 *
 * 缓存值允许是 null（这张图下载失败 / 不是 PNG）：失败也记下来，避免每次滚回来都重试一遍
 * CDN；换图（用户手动选另一款）走的是另一个 path，不受影响。
 * 最坏情况下同一 path 会被并发下载两次（两处同时组合到同一行），代价可接受，不做额外的
 * 「in-flight」去重。
 */
private object OfficialThumbCache {

    /** 缩略图目标边长（px）：列表里的图只有 48dp，128 足够覆盖高密度屏（原图 1125×597 → inSampleSize=4）。 */
    private const val TARGET_PX = 128

    /**
     * 缓存上限（项数）。官方目录过滤后只有 49 款蓝牙耳机，128 足以装下整份列表。
     *
     * 位图按 [TARGET_PX] 量级采样后每张约 170 KB（281×149×4B），装满也就 20 MB 上下；
     * 不设上限的话，反复开关对话框会把 49 张全尺寸图（每张 2.6 MB）慢慢攒在内存里。
     */
    private const val MAX_ENTRIES = 128

    private val cache = LinkedHashMap<String, Bitmap?>()

    /** 取缩略图（**阻塞 IO**，调用方必须在 Dispatchers.IO 里调）；null = 拿不到，用占位图。 */
    fun load(path: String): Bitmap? {
        synchronized(cache) {
            if (cache.containsKey(path)) return cache[path]
        }
        val bitmap = decode(download(path))
        synchronized(cache) {
            if (cache.size >= MAX_ENTRIES) {
                // 简单 FIFO 淘汰：LinkedHashMap 保持插入顺序，丢掉最早的那一项
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            cache[path] = bitmap
        }
        return bitmap
    }

    private fun download(path: String): ByteArray? = MoondropOfficialImages.downloadImage(path)

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        // 宽高都还至少是目标的两倍时才继续降采样（原图 1125×597 → inSampleSize = 2）
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
