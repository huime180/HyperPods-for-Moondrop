/*
 * HyperPods for Moondrop — 电量解析（「右耳电量不显示」的修复核心）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * ────────────────────────────────────────────────────────────────────────────
 * 问题现象：左耳电量显示正常，右耳电量不显示。
 *
 * GAIA 电量协议（feature 0x0D，真机确认）：
 *
 *   ① 查询支持哪些电池      TX `00 1D 1A 00`      → RX payload = [type...]
 *   ② 查询这些电池的电量    TX `00 1D 1A 01 00 01 02 03`
 *                          （payload = 要查的 type 列表）→ RX payload = [type, level, type, level, ...]
 *
 *   电池 type：0=单设备(整机)、1=左耳、2=右耳、3=充电盒；255 = 无数据。
 *
 * ── 根因（按可能性排序，本文件全部覆盖）────────────────────────────────────
 *
 *  ① **固件上报的是 type 0「单设备」而不是分体 1/2。**
 *     实测水月雨 EDGE 只回 type 0。只把 1→左耳、2→右耳 写死的解析器在这种机型上
 *     左右耳都拿不到值；而如果代码给左耳兜底了系统广播的单值电量、却没有给右耳兜底，
 *     就会精确表现为「左耳正常、右耳空白」。
 *     → 修复：type 0 视为整机电量，**同时**充填左耳与右耳；一旦收到显式的 1/2 分体值，
 *             分体值优先于单设备值（见 BatteryState.currentLeft/currentRight）。
 *     ⚠ 这是**机型相关**的成因：实测布丁 PUDDING 的受支持类型恰好是 `01 02`
 *       （只有左、右耳，从来没有 type 0），回包 `01 62 02 64 03 FF` 里左右耳都在
 *       （L=98 R=100），第三对是充电盒的 `0xFF`（UI 显示"离线"）。
 *       所以在布丁上「右耳不显示」必须另有原因 —— 见 ②③⑤。
 *
 *  ② **查询时没有把要查的 type 列全。**
 *     设备只回被问到的 type。写死 `[1]` 就永远只有左耳。
 *     → 修复：先 cmd 0 问出设备实际支持的 type，再按该集合去查（BatteryCodec.buildSupportedQuery / buildLevelsQuery）。
 *
 *  ③ **按位置解析而不是按 type 解析。**
 *     回包是自描述的 (type, level) 对；某组件缺失时字节数会变，位置解析会把充电盒读成右耳，
 *     或直接丢掉最后一对。
 *     → 修复：parse() 严格按 type 归位，未知 type 只忽略、绝不位移。
 *
 *  ④ **只保留本次回包里的组件。**
 *     设备常在通知里只推变化的单只耳；若整体覆盖，另一只耳会被反复清空。
 *     → 修复：每个组件保留最近一次有效值，单包缺项不清零。
 *
 *  ⑤ **遇到 0xFF（无数据）就停止解析。**
 *     布丁回包 `[01 62][02 64][03 FF]` 的第三对是充电盒 0xFF。若解析器把 0xFF 当成
 *     "数据到此为止"而 `break`，在"充电盒排在左右耳之前"的固件上右耳会被一起丢掉。
 *     → 修复：非法电量或非法 type 只 `continue` 跳过该对，**绝不 break**。
 * ────────────────────────────────────────────────────────────────────────────
 */
package moe.chenxy.hyperpods.core

/** 电量为未知/无该组件时的哨兵值。 */
const val BATTERY_UNKNOWN = -1

/** 单个组件的电量读数。 */
data class BatterySlot(
    /** 0..100；BATTERY_UNKNOWN 表示未知 */
    val level: Int = BATTERY_UNKNOWN,
    /** 是否正在充电 */
    val charging: Boolean = false,
    /** 该组件本轮是否出现过有效读数 */
    val present: Boolean = false,
) {
    val known: Boolean get() = level != BATTERY_UNKNOWN

    companion object {
        val UNKNOWN = BatterySlot()
    }
}

/**
 * 三路电量状态：按组件分别保留最近一次有效值，并正确处理「单设备电量」机型。
 *
 * 读取语义（**这是修复的关键**）：
 *   · [currentLeft] / [currentRight] 会优先返回显式分体值（type 1 / 2）；
 *     没有分体值时回退到整机值（type 0）。因此只上报 type 0 的机型，
 *     左右耳**都会**显示，不会再出现「只有左耳」。
 *   · [rawLeft] / [rawRight] 只反映显式分体值（调试/诊断用）。
 */
class BatteryState {

    /** type 0：整机（单设备）电量 */
    var single: BatterySlot = BatterySlot.UNKNOWN
        private set

    /** type 1 / 2 / 3 的显式分体值 */
    var rawLeft: BatterySlot = BatterySlot.UNKNOWN
        private set
    var rawRight: BatterySlot = BatterySlot.UNKNOWN
        private set
    var rawCase: BatterySlot = BatterySlot.UNKNOWN
        private set

    var lastUpdateAt: Long = 0L
        private set

    /** 左耳显示值：显式左耳值优先，否则回退整机值。 */
    val currentLeft: BatterySlot
        get() = if (rawLeft.known) rawLeft else single

    /** 右耳显示值：显式右耳值优先，否则回退整机值。**右耳不显示就是在这里修好的。** */
    val currentRight: BatterySlot
        get() = if (rawRight.known) rawRight else single

    val currentCase: BatterySlot get() = rawCase

    fun slotOf(component: Int): BatterySlot = when (component) {
        Gaia.BATTERY_SINGLE -> single
        Gaia.BATTERY_LEFT -> rawLeft
        Gaia.BATTERY_RIGHT -> rawRight
        Gaia.BATTERY_CASE -> rawCase
        else -> BatterySlot.UNKNOWN
    }

    /**
     * 归并一组解析出的 (type, level) 对。
     * @return 是否有组件被更新
     */
    fun applyPairs(
        pairs: List<IntArray>,
        now: Long = System.currentTimeMillis(),
        charging: Boolean = false,
    ): Boolean {
        var changed = false
        for (p in pairs) {
            if (p.size < 2) continue
            val type = p[0]
            val raw = p[1]
            if (raw < 0 || raw == Gaia.BATTERY_LEVEL_UNKNOWN) continue
            val level = raw.coerceIn(0, 100)
            when (type) {
                Gaia.BATTERY_SINGLE -> {
                    if (single.level != level || single.charging != charging) changed = true
                    single = BatterySlot(level, charging, true)
                }
                Gaia.BATTERY_LEFT -> {
                    if (rawLeft.level != level || rawLeft.charging != charging) changed = true
                    rawLeft = BatterySlot(level, charging, true)
                }
                Gaia.BATTERY_RIGHT -> {
                    if (rawRight.level != level || rawRight.charging != charging) changed = true
                    rawRight = BatterySlot(level, charging, true)
                }
                Gaia.BATTERY_CASE -> {
                    if (rawCase.level != level || rawCase.charging != charging) changed = true
                    rawCase = BatterySlot(level, charging, true)
                }
                else -> Unit // 未知 type：忽略，绝不影响其它组件归位
            }
        }
        if (changed) lastUpdateAt = now
        return changed
    }

    /** 充电状态独立更新（部分固件把充电位放在单独通知里）。 */
    fun setCharging(component: Int, charging: Boolean) {
        when (component) {
            Gaia.BATTERY_SINGLE -> single = single.copy(charging = charging)
            Gaia.BATTERY_LEFT -> rawLeft = rawLeft.copy(charging = charging)
            Gaia.BATTERY_RIGHT -> rawRight = rawRight.copy(charging = charging)
            Gaia.BATTERY_CASE -> rawCase = rawCase.copy(charging = charging)
        }
    }

    /**
     * 供系统蓝牙栈显示的单值电量：左右耳取较小者。
     * 与 AOSP `AdapterService.setBatteryLevel(device, level, false)` 语义一致。
     */
    fun systemLevel(): Int {
        val l = currentLeft.level
        val r = currentRight.level
        return when {
            l != BATTERY_UNKNOWN && r != BATTERY_UNKNOWN -> minOf(l, r)
            l != BATTERY_UNKNOWN -> l
            r != BATTERY_UNKNOWN -> r
            else -> BATTERY_UNKNOWN
        }
    }

    /** 左右耳是否都已经可以显示（分体值或整机值皆可）。 */
    fun bothEarsKnown(): Boolean = currentLeft.known && currentRight.known

    /** 设备是否采用了「单设备电量」上报形态。 */
    fun usesSingleDeviceBattery(): Boolean =
        single.known && !rawLeft.known && !rawRight.known

    /**
     * 系统广播带回的单值电量兜底。
     * 注意：**必须左右耳都兜底**，只兜左耳就是「右耳不显示」的成因之一。
     */
    fun fallbackFromSystem(level: Int, charging: Boolean = false) {
        if (level < 0) return
        val v = level.coerceIn(0, 100)
        if (!single.known) single = BatterySlot(v, charging, true)
        if (!rawLeft.known) rawLeft = BatterySlot(v, charging, true)
        if (!rawRight.known) rawRight = BatterySlot(v, charging, true)
    }

    fun reset() {
        single = BatterySlot.UNKNOWN
        rawLeft = BatterySlot.UNKNOWN
        rawRight = BatterySlot.UNKNOWN
        rawCase = BatterySlot.UNKNOWN
        lastUpdateAt = 0L
    }

    override fun toString(): String =
        "Battery(L=${currentLeft.level}${if (currentLeft.charging) "+" else ""}, " +
            "R=${currentRight.level}${if (currentRight.charging) "+" else ""}, " +
            "C=${currentCase.level}${if (currentCase.charging) "+" else ""}; " +
            "raw[single=${single.level} l=${rawLeft.level} r=${rawRight.level} c=${rawCase.level}])"
}

object BatteryCodec {

    /** 兜底查询集合：按 type 从小到大（单设备在前）。 */
    val FALLBACK_QUERY_IDS = intArrayOf(
        Gaia.BATTERY_SINGLE, Gaia.BATTERY_LEFT, Gaia.BATTERY_RIGHT, Gaia.BATTERY_CASE,
    )

    /**
     * 查询帧计划：
     *   1) cmd 0 无 payload —— 问设备「你有哪些电池」（`00 1D 1A 00`）
     *   2) 拿到支持的 type 后，用 cmd 1 + type 列表查询电量
     *   3) 若设备不回 cmd 0，回退用 FALLBACK_QUERY_IDS
     */
    fun buildSupportedQuery(): ByteArray = Gaia.batteryGetAllV4()

    fun buildLevelsQuery(ids: IntArray): ByteArray = Gaia.batteryGet(ids)

    /** 老固件（GAIA V3）用 cmd 1 无 payload 查全部。 */
    fun buildLegacyQuery(): ByteArray = Gaia.batteryGetAll()

    /**
     * 解析 `cmd 0`（GetSupported）的响应：payload 就是 type 列表。
     * 只保留已知 type，且保持设备给出的顺序。
     */
    fun parseSupported(payload: ByteArray?): IntArray {
        val p = payload ?: return IntArray(0)
        val out = ArrayList<Int>(4)
        for (b in p) {
            val t = b.toInt() and 0xFF
            if (isKnownBatteryId(t) && t !in out) out.add(t)
        }
        return out.toIntArray()
    }

    /**
     * 解析电量回包的 payload，返回 (type, level) 对列表。
     *
     * 兼容：
     *   A. `[type, level, type, level, ...]`            —— 标准
     *   B. `[count, type, level, ...]`                  —— 带数量前缀的变体
     *   C. `[level]`                                    —— 单值（配 hintType 使用）
     */
    fun parse(payload: ByteArray?, hintType: Int = -1): List<IntArray> {
        if (payload == null || payload.isEmpty()) return emptyList()

        if (payload.size == 1) {
            val v = payload[0].toInt() and 0xFF
            return if (hintType >= 0 && isPlausibleLevel(v)) listOf(intArrayOf(hintType, v)) else emptyList()
        }

        var offset = 0
        val first = payload[0].toInt() and 0xFF
        val remainingPairs = (payload.size - 1) / 2
        if ((payload.size - 1) % 2 == 0 && first == remainingPairs && first in 1..8) {
            offset = 1
        }

        val out = ArrayList<IntArray>(4)
        var i = offset
        while (i + 1 < payload.size) {
            val type = payload[i].toInt() and 0xFF
            val level = payload[i + 1].toInt() and 0xFF
            if (isKnownBatteryId(type) && isPlausibleLevel(level)) {
                out.add(intArrayOf(type, level))
            }
            i += 2
        }
        return out
    }

    /** 合法电池 type：0=单设备 1=左 2=右 3=盒 */
    fun isKnownBatteryId(id: Int): Boolean = id in Gaia.BATTERY_SINGLE..Gaia.BATTERY_CASE

    /** 合法电量：0..100。255（无数据）等标记被排除。 */
    fun isPlausibleLevel(level: Int): Boolean = level in 0..100

    /**
     * 充电位解析。不同固件把充电位放在：
     *   · 电量命令扩展 payload 的第 2 字节
     *   · BASIC 的组件状态
     * 仅在明显存在且取值合法时采用。
     */
    fun parseChargingFlag(payload: ByteArray?, index: Int = 1): Boolean? {
        val p = payload ?: return null
        if (index !in p.indices) return null
        return when (val v = p[index].toInt() and 0xFF) {
            0 -> false
            1 -> true
            else -> null
        }
    }
}
