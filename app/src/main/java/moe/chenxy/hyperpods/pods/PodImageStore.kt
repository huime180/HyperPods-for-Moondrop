/*
 * MiuixMoondrop — 机型图仓库（按设备地址落盘，并给 UI 一个可观察的版本号）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 设计取舍：
 *   · 图片存文件（`filesDir/pod_images/<MAC>_box.img`）而不是 SharedPreferences：图是二进制、
 *     单张 160~230 KB，塞进 XML 偏好会拖慢每次读写；
 *   · 键用**设备地址**：同一型号可以有两副耳机，用户也可能给某副单独换图；
 *   · 不给「有没有图」单独维护一份元数据 —— 文件存在且非空就是有图，少一处能不同步的状态；
 *   · [revision] 是给 Compose 的：拉图是在 IO 线程完成的，UI 靠它重新读一次位图。
 */
package moe.chenxy.hyperpods.pods

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

object PodImageStore {
    private const val TAG = "MoondropPodImage"
    private const val DIR = "pod_images"
    private const val SUFFIX = "box.img"

    /** 图片版本号：落盘或清除后 +1，UI 观察它来重读位图（跨线程写、组合里读）。 */
    var revision by mutableStateOf(0)
        private set

    /** 某台设备的机型图文件（不保证存在）。地址会做文件名安全化处理。 */
    fun fileFor(context: Context, address: String): File {
        val safe = address.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.filesDir, DIR).apply { mkdirs() }, "${safe}_$SUFFIX")
    }

    /** 这台设备有没有自己的机型图（文件存在且非空即算有）。 */
    fun hasImage(context: Context, address: String): Boolean {
        if (address.isBlank()) return false
        val file = fileFor(context, address)
        return file.isFile && file.length() > 0
    }

    /** 读这台设备的机型图；没有 / 解不出来返回 null（调用方回落静态图）。 */
    fun loadBitmap(context: Context, address: String): Bitmap? {
        if (address.isBlank()) return null
        return runCatching {
            val file = fileFor(context, address)
            if (file.isFile && file.length() > 0) BitmapFactory.decodeFile(file.absolutePath) else null
        }.getOrNull()
    }

    /** 落盘（阻塞 IO）。成功返回 true，并推一次 [revision]。 */
    fun saveImage(context: Context, address: String, bytes: ByteArray): Boolean {
        if (address.isBlank() || bytes.isEmpty()) return false
        return runCatching {
            fileFor(context, address).writeBytes(bytes)
            revision += 1
            true
        }.getOrElse {
            Log.w(TAG, "saveImage failed for $address", it)
            false
        }
    }

    /** 清掉这台设备的机型图（回到静态图）。 */
    fun clear(context: Context, address: String) {
        if (address.isBlank()) return
        runCatching {
            val file = fileFor(context, address)
            if (file.isFile && file.delete()) revision += 1
        }.onFailure { Log.w(TAG, "clear failed for $address", it) }
    }

    /**
     * 把某款官方产品的机型图套用到这台设备上（阻塞 IO）。
     *
     * 只取机型图：本应用目前只有一处展示机型图（详情页英雄图 / 连接弹窗），
     * 官方那两张左右耳图没有落盘的必要。
     */
    fun applyProduct(context: Context, address: String, product: MoondropOfficialProduct): Boolean {
        val path = product.boxPath() ?: return false
        val bytes = MoondropOfficialImages.downloadImage(path) ?: return false
        return saveImage(context, address, bytes)
    }

    /**
     * 连接后自动取图：按设备名在官方目录里找对应机型，找到就拉一张落盘（阻塞 IO）。
     *
     * 认不出来就**不猜**（返回 false），让用户在详情页「机型图片」里手动选 ——
     * 官方目录 105 款、本应用的型号档案 17 款，名称对不上是常态。
     */
    fun fetchOfficialImage(context: Context, address: String, deviceName: String?): Boolean {
        if (address.isBlank()) return false
        val products = MoondropOfficialImages.fetchProducts()
        if (products.isEmpty()) {
            Log.w(TAG, "fetchOfficialImage: 官方目录拉取失败")
            return false
        }
        val product = bestMatch(products, deviceName) ?: run {
            Log.i(TAG, "fetchOfficialImage: 目录里没有匹配 $deviceName 的机型，交给用户手选")
            return false
        }
        val ok = applyProduct(context, address, product)
        Log.i(TAG, "fetchOfficialImage: ${product.displayName} -> $address, ok=$ok")
        return ok
    }

    /**
     * 设备名 / 型号名 → 官方目录条目。
     *
     * 归一化（小写、去掉 MOONDROP / 水月雨 这类通用词与所有非字母数字）后互相包含即可，
     * 取最长命中：官方目录写的是 `MOONDROP Pudding`，设备名往往也是同一串；
     * 取最长是为了让 `Pudding` 不会被更短的同类条目抢走。
     */
    fun bestMatch(
        products: List<MoondropOfficialProduct>,
        deviceName: String?,
    ): MoondropOfficialProduct? {
        val key = normalizeName(deviceName)
        if (key.length < 3) return null
        return products
            .map { product -> product to normalizeName(product.displayName) }
            .filter { (_, name) -> name.isNotEmpty() && (name.contains(key) || key.contains(name)) }
            .maxByOrNull { (_, name) -> name.length }
            ?.first
    }

    private fun normalizeName(raw: String?): String = (raw ?: "")
        .lowercase()
        .replace("moondrop", "")
        .replace("水月雨", "")
        .replace(Regex("[^a-z0-9]"), "")
}
