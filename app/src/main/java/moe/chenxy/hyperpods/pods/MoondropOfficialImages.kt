/*
 * MiuixMoondrop — 水月雨（MOONDROP）官方机型图来源
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么需要它：本应用原来只有一张静态产品图（`R.drawable.img_box`），和耳机型号无关。
 * 而水月雨官方 App（com.moondroplab.moondrop.moondrop_app）的机型图来自公开接口 ——
 * 产品目录在
 *   https://cdn-service.moondroplab.tech/api/v1/products/all
 * 每款产品带若干图片字段，图片实体在
 *   https://cdn.moondroplab.tech/<字段值>       （字段值形如 product-banner-img/xxxx.png）
 * 两个地址都是公开的（实测 200，不需要 UA / Referer），所以本应用直接按官方目录取图：
 * 不需要 root、不需要装官方 App、也不需要它连过一次。
 *
 * 字段与展示位置的对应（拿布丁的官方图逐张验过像素）：
 *   · 机型图 ← `bannerImgT`   1125×597，**85% 像素透明**的官方产品渲染（透明版 banner）；
 *              兜底 `bannerImgV2`（1388×640，0% 透明 —— 那是带底 banner）→ 方形图 → sellpic。
 *     英雄图与连接弹窗都要把图叠在别的底色上，只有透明图能用，所以顺序不能反。
 *   · `bannerImgLT` / `bannerImgRT` 是透明左/右耳图（88% 透明）。本应用目前只展示一张机型图，
 *     因此**只取机型图**，不落盘用不到的两张（宁缺毋滥）。
 *
 * 线程：本文件全是阻塞 IO，调用方必须在 Dispatchers.IO 里调。
 */
package moe.chenxy.hyperpods.pods

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** 官方产品目录里的一条（只声明用得到的字段，其余由 `ignoreUnknownKeys` 忽略）。 */
@Serializable
data class MoondropOfficialProduct(
    val uuid: String = "",
    val model: String = "",
    val name: String = "",
    val type: String = "",
    val bannerImg: String = "",
    val bannerImgNight: String = "",
    val bannerImgT: String = "",
    val bannerImgNightT: String = "",
    val bannerImgLT: String = "",
    val bannerImgRT: String = "",
    val bannerImgNightLT: String = "",
    val bannerImgNightRT: String = "",
    val bannerImgV2: String = "",
    val squareBannerImg: String = "",
    val squareBannerImgNight: String = "",
    val sellpic: String = "",
) {
    /** 展示名：优先 `model`，为空时用 `name`。 */
    val displayName: String get() = model.ifBlank { name }

    /**
     * 机型图路径。首选 [bannerImgT]（透明产品渲染），再退到带底 banner / 方形图 / sellpic。
     * 顺序不能反：卡片与弹窗要叠加在别的底色上，0% 透明的带底 banner 会把底透出来。
     */
    fun boxPath(): String? = listOf(
        bannerImgT, bannerImgNightT, bannerImgV2, squareBannerImg, sellpic, bannerImg, bannerImgNight,
    ).firstOrNull { it.isNotBlank() }

    /**
     * 列表**缩略图**路径（机型图选择列表用）。
     *
     * 顺序与 [boxPath] 不同，原因只有一条：缩略图是 48dp 的小格子，方形图的构图最适合它，
     * 所以方形图优先；官方目录里绝大多数机型没有方形图（布丁的 `squareBannerImg`/`sellpic`
     * 就是空的），那时退到带底 banner [bannerImgV2]。
     *
     * 这里允许取 0% 透明的带底 banner（[bannerImgV2] / [bannerImgNight]）：缩略图只是小格子里的
     * 展示，不会像英雄图那样叠在别的底色上，带底图在这里不会「透出底色」。
     */
    fun thumbnailPath(): String? = listOf(
        squareBannerImg, squareBannerImgNight, bannerImgV2, bannerImgNight,
    ).firstOrNull { it.isNotBlank() } ?: boxPath()
}

/** 目录接口的外层结构：`{"code":0,"desc":"success","data":[…]}`。 */
@Serializable
private data class MoondropProductsResponse(
    val code: Int = -1,
    val desc: String = "",
    val data: List<MoondropOfficialProduct> = emptyList(),
)

object MoondropOfficialImages {
    private const val TAG = "MoondropOfficialImg"
    private const val PRODUCTS_URL = "https://cdn-service.moondroplab.tech/api/v1/products/all"
    private const val FILE_BASE = "https://cdn.moondroplab.tech/"
    private const val TIMEOUT_MS = 15_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 拉官方产品目录（105 款、约 120 KB）。
     *
     * 只保留蓝牙耳机（`type = "BT"`）且拿得到机型图的条目，按名称排序。
     * 任何失败都返回**空列表**：调用方据此显示「拉取失败 / 手动选择」，不要把异常带进 Compose。
     */
    fun fetchProducts(): List<MoondropOfficialProduct> = runCatching {
        val body = httpGet(PRODUCTS_URL) ?: return@runCatching emptyList()
        json.decodeFromString(MoondropProductsResponse.serializer(), body.decodeToString())
            .data
            .filter { it.type.equals("BT", ignoreCase = true) && it.boxPath() != null }
            .sortedBy { it.displayName.lowercase() }
    }.onFailure { Log.w(TAG, "fetchProducts failed", it) }.getOrDefault(emptyList())

    /**
     * 下载一张官方图。
     *
     * 校验 PNG 魔数：CDN 出错时返回的是 JSON 文本（如 `{"error":"Document not found"}`），
     * 那种响应绝不能当图片写进用户的图片目录。
     */
    fun downloadImage(path: String): ByteArray? = runCatching {
        val bytes = httpGet(FILE_BASE + path) ?: return@runCatching null
        val isPng = bytes.size > 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
        if (isPng) {
            bytes
        } else {
            Log.w(TAG, "downloadImage: not a PNG ($path)")
            null
        }
    }.onFailure { Log.w(TAG, "downloadImage failed: $path", it) }.getOrNull()

    /** 极简 GET：失败 / 非 2xx 一律返回 null（调用方只看有没有数据）。 */
    private fun httpGet(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json,image/*")
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                null
            } else {
                connection.inputStream.use { it.readBytes() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GET failed: $url", t)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
