/*
 * TOUCHV2（feature 22）手势协议的回归测试。
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 全部断言都锚定在**真机实测**的字节上（README/PROTOCOL 同一份数据）：
 *
 *   READ   TX 00 1D 2C 02
 *          RX 00 1D 2D 02 11 23 66 77 00   ← 本次用例的已知明文
 *   WRITE  TX 00 1D 2C 03 <5 bytes>
 *          RX echo 00 1D 2D 03 <same 5 bytes>
 *
 * 槽位顺序：b0=单击 b1=双击 b2=三击 b3=长按1秒 b4=长按3秒。
 */
package moe.chenxy.hyperpods.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchV2Test {

    /** 卡上量到的「读回」整帧（response：type=2 → 命令字 0x2D02）。 */
    private val measuredReadBack = byteArrayOf(
        0x00, 0x1D, 0x2D, 0x02,
        0x11, 0x23, 0x66, 0x77, 0x00,
    )

    /** 用户已知配置对应的 5 个动作 id（与 measuredReadBack 同一份）。 */
    private val knownSlots = intArrayOf(0x11, 0x23, 0x66, 0x77, 0x00)

    // ── 1. 命令构造（线格式逐字节） ─────────────────────────────────────────

    @Test
    fun `读手势配置的帧与实测一致`() {
        assertEquals("00 1D 2C 02", Gaia.hex(Gaia.touchV2GetConf()))
        // 顺便把「feature 22 / COMMAND / cmd 2」的运算关系钉死：0x2C02 == 22<<9 | 2
        val f = Gaia.parse(Gaia.touchV2GetConf())!!
        assertEquals(Gaia.F_TOUCHV2, f.feature)
        assertEquals(Gaia.TYPE_COMMAND, f.type)
        assertEquals(Gaia.C_TOUCHV2_GET_ACTION_CONF, f.command)
    }

    @Test
    fun `写手势配置的帧与实测一致`() {
        val pkt = Gaia.touchV2SetConf(byteArrayOf(0x11, 0x23, 0x66, 0x77, 0x00))
        assertEquals("00 1D 2C 03 11 23 66 77 00", Gaia.hex(pkt))
        val f = Gaia.parse(pkt)!!
        assertEquals(Gaia.F_TOUCHV2, f.feature)
        assertEquals(Gaia.TYPE_COMMAND, f.type)
        assertEquals(Gaia.C_TOUCHV2_SET_ACTION_CONF, f.command)
        // payload 必须**原样**是那 5 个字节（顺序就是槽位顺序，不允许被重排）
        assertTrue(knownSlots.toByteArray().contentEquals(f.payload))
    }

    @Test
    fun `响应帧的字节布局与实测一致（RESPONSE 类型）`() {
        // 用 packet() 反推回包：feature 22 / RESPONSE / cmd 2 → 0x2D02
        val back = Gaia.packet(
            Gaia.VENDOR_QUALCOMM,
            Gaia.F_TOUCHV2,
            Gaia.TYPE_RESPONSE,
            Gaia.C_TOUCHV2_GET_ACTION_CONF,
            knownSlots.toByteArray(),
        )
        assertEquals("00 1D 2D 02 11 23 66 77 00", Gaia.hex(back))
        assertTrue(back.contentEquals(measuredReadBack))
        assertEquals("00 1D 2D 03 11 23 66 77 00", Gaia.hex(
            Gaia.packet(
                Gaia.VENDOR_QUALCOMM,
                Gaia.F_TOUCHV2,
                Gaia.TYPE_RESPONSE,
                Gaia.C_TOUCHV2_SET_ACTION_CONF,
                knownSlots.toByteArray(),
            )
        ))
    }

    @Test
    fun `写入长度不是 5 字节时拒绝下发`() {
        assertRejectsIae { Gaia.touchV2SetConf(ByteArray(0)) }
        assertRejectsIae { Gaia.touchV2SetConf(byteArrayOf(0x11, 0x23, 0x66, 0x77)) }
        assertRejectsIae { Gaia.touchV2SetConf(byteArrayOf(0x11, 0x23, 0x66, 0x77, 0x00, 0x01)) }
        // 恰好 5 字节则放行
        assertEquals(5, Gaia.touchV2SetConf(ByteArray(5)).size - 4)
    }

    // ── 2. 已知明文解析（parseGestureConf） ────────────────────────────────

    @Test
    fun `解析实测回包得到 5 个槽位`() {
        val frame = Gaia.parse(measuredReadBack)!!
        assertEquals(Gaia.F_TOUCHV2, frame.feature)
        assertEquals(Gaia.TYPE_RESPONSE, frame.type)
        assertEquals(Gaia.C_TOUCHV2_GET_ACTION_CONF, frame.command)
        assertEquals(5, frame.payload.size)

        val conf = Gaia.parseGestureConf(frame.payload)!!
        assertTrue(knownSlots.contentEquals(conf.slots))
        assertEquals(Gaia.TOUCHV2_CONF_SIZE, conf.size)
    }

    @Test
    fun `已知明文按槽位语义逐条对齐`() {
        val conf = Gaia.parseGestureConf(measuredReadBack.copyOfRange(4, 9))!!

        // 槽位 ↔ 字节位置：b0=单击 … b4=长按3秒
        assertEquals(0x11, conf[Gaia.GestureSlot.SINGLE_TAP])
        assertEquals(0x23, conf[Gaia.GestureSlot.DOUBLE_TAP])
        assertEquals(0x66, conf[Gaia.GestureSlot.TRIPLE_TAP])
        assertEquals(0x77, conf[Gaia.GestureSlot.LONG_PRESS_1S])
        assertEquals(0x00, conf[Gaia.GestureSlot.LONG_PRESS_3S])

        // 动作 id ↔ 已确认的动作表（1:1）
        assertEquals(Gaia.TouchActions.PLAY_PAUSE, conf[Gaia.GestureSlot.SINGLE_TAP])
        assertEquals(Gaia.TouchActions.PREVIOUS_TRACK, conf[Gaia.GestureSlot.DOUBLE_TAP])
        assertEquals(Gaia.TouchActions.VOICE_ASSISTANT, conf[Gaia.GestureSlot.TRIPLE_TAP])
        assertEquals(Gaia.TouchActions.ANC_SWITCH, conf[Gaia.GestureSlot.LONG_PRESS_1S])
        assertEquals(Gaia.TouchActions.NONE, conf[Gaia.GestureSlot.LONG_PRESS_3S])

        // 长按两档是**两个独立槽位**，本模块不做互斥：
        // 读回里长按3秒 = 0x00，但把它改成「降噪切换」在协议上是完全合法的 5 字节组合。
        assertFalse(conf[Gaia.GestureSlot.LONG_PRESS_1S] == conf[Gaia.GestureSlot.LONG_PRESS_3S])
    }

    @Test
    fun `槽位顺序即字节顺序且共 5 个`() {
        val slots = Gaia.GestureSlot.entries
        assertEquals(5, slots.size)
        assertEquals(Gaia.TOUCHV2_CONF_SIZE, slots.size)
        assertEquals(Gaia.GESTURE_SLOT_COUNT, slots.size)
        slots.forEachIndexed { i, slot ->
            assertEquals(i, slot.index)
        }
        assertEquals(Gaia.GestureSlot.SINGLE_TAP, slots[0])
        assertEquals(Gaia.GestureSlot.DOUBLE_TAP, slots[1])
        assertEquals(Gaia.GestureSlot.TRIPLE_TAP, slots[2])
        assertEquals(Gaia.GestureSlot.LONG_PRESS_1S, slots[3])
        assertEquals(Gaia.GestureSlot.LONG_PRESS_3S, slots[4])
    }

    @Test
    fun `回包长度不足时返回 null`() {
        assertNull(Gaia.parseGestureConf(null))
        assertNull(Gaia.parseGestureConf(ByteArray(0)))
        assertNull(Gaia.parseGestureConf(byteArrayOf(0x11, 0x23, 0x66, 0x77)))
        assertNotNull(Gaia.parseGestureConf(knownSlots.toByteArray()))
    }

    @Test
    fun `回包多于 5 字节时只取前 5（多出的字节语义未确认）`() {
        val longer = knownSlots.toByteArray() + byteArrayOf(0x7F, 0x01)
        val conf = Gaia.parseGestureConf(longer)!!
        assertEquals(Gaia.TOUCHV2_CONF_SIZE, conf.size)
        assertTrue(knownSlots.contentEquals(conf.slots))
    }

    // ── 3. GestureConf：单槽位替换 / 内容相等 ──────────────────────────────

    @Test
    fun `替换一个槽位时其余槽位原样保留`() {
        val base = Gaia.parseGestureConf(knownSlots.toByteArray())!!
        val next = base.with(Gaia.GestureSlot.LONG_PRESS_3S, Gaia.TouchActions.ANC_SWITCH)

        // 下发用的正是完整 5 字节，且只有目标槽位变化
        assertEquals("11 23 66 77 77", Gaia.hex(next.toPayload()))
        assertEquals(5, next.toPayload().size)
        assertEquals(
            "00 1D 2C 03 11 23 66 77 77",
            Gaia.hex(Gaia.touchV2SetConf(next.toPayload())),
        )
        // 原对象没被就地改动（IntArray 是可变引用，必须 copy）
        assertEquals("11 23 66 77 00", Gaia.hex(base.toPayload()))
        assertEquals(Gaia.TouchActions.NONE, base[Gaia.GestureSlot.LONG_PRESS_3S])
    }

    @Test
    fun `配置按内容比较而不是按引用`() {
        val a = Gaia.GestureConf(intArrayOf(0x11, 0x23, 0x66, 0x77, 0x00))
        val b = Gaia.GestureConf(intArrayOf(0x11, 0x23, 0x66, 0x77, 0x00))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals("GestureConf(11 23 66 77 00)", a.toString())
        assertFalse(a == a.with(Gaia.GestureSlot.SINGLE_TAP, Gaia.TouchActions.NONE))
    }

    // ── 4. 动作表（数据驱动、部分表、不臆造 id） ───────────────────────────

    @Test
    fun `动作表播种了已确认的 5 个 id`() {
        val all = Gaia.TouchActions.ALL
        // 这里是**部分表**：只断言「真机确认过的这 5 条都在」，刻意不锁死条目总数 ——
        // 完整枚举会由 dex 提取任务补进 ALL，锁死数字会误伤那份工作。
        assertTrue(all.size >= 5)
        assertEquals(all.size, all.map { it.id }.toSet().size) // id 不重复
        assertTrue(all.map { it.id }.containsAll(listOf(0x00, 0x11, 0x23, 0x66, 0x77)))

        // 5 条已确认动作都能取到非空双语标签，且 labelOf 与表内一致
        // （不锁死具体字面量：动作表后续会被 dex 提取任务补全/润色）
        for (id in listOf(0x00, 0x11, 0x23, 0x66, 0x77)) {
            val action = Gaia.TouchActions.byId(id)
            assertTrue("动作表应含已确认的 id 0x%02X".format(id), action != null)
            if (action != null) {
                assertEquals(action.labelZh, Gaia.TouchActions.labelOf(id))
                assertTrue(action.labelZh.isNotBlank())
                assertTrue(action.labelEn.isNotBlank())
            }
        }

        // 未确认的 id 一律不猜：查不到就是 null（动态挑一个**当前**不在表里的 id，
        // 这样将来动作表被补全也不会让这条断言失效）
        val unmappedId = (0x01..0xFF).first { Gaia.TouchActions.byId(it) == null }
        assertNull(Gaia.TouchActions.labelOf(unmappedId))
    }

    @Test
    fun `未映射的动作 id 显示为未知而不是空白`() {
        // 动态挑一个当前不在动作表里的 id（表会被后续任务补全，写死 0x12 会误伤）
        val unmappedId = (0x01..0xFF).first { Gaia.TouchActions.byId(it) == null }
        val expected = "未知(0x%02X)".format(unmappedId)

        assertEquals(expected, Gaia.TouchActions.matchOrUnknown(unmappedId))
        // id 只取低 8 位（固件是一个字节）：0x0100|id 应当仍解析成同一个未知 id
        assertEquals(expected, Gaia.TouchActions.matchOrUnknown(unmappedId or 0x0100))
        // 已映射的照常给标签（等于表内 labelZh，不写死字面量）
        assertNotNull(Gaia.TouchActions.labelOf(0x77))
        assertEquals(Gaia.TouchActions.labelOf(0x77), Gaia.TouchActions.matchOrUnknown(0x77))
        assertNotNull(Gaia.TouchActions.labelOf(0x00))
        assertEquals(Gaia.TouchActions.labelOf(0x00), Gaia.TouchActions.matchOrUnknown(0x00))
    }

    @Test
    fun `动作表里的每个动作都能按 id 取回`() {
        for (action in Gaia.TouchActions.ALL) {
            assertEquals(action, Gaia.TouchActions.byId(action.id))
            assertEquals(action.labelZh, Gaia.TouchActions.labelOf(action.id))
        }
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    /** 断言会抛 IllegalArgumentException（不依赖 assertThrows，兼容老 junit）。 */
    private fun assertRejectsIae(block: () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("应当抛 IllegalArgumentException，实际：$thrown", thrown is IllegalArgumentException)
    }
}
