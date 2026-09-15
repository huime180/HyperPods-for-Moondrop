/*
 * TOUCHV2（feature 22）手势协议的回归测试。
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 全部断言都锚定在**真机实测 + 官方 App 字节码**这一份数据上：
 *
 *   READ   TX 00 1D 2C 02
 *          RX 00 1D 2D 02 11 23 66 77 00   ← 本次用例的已知明文
 *   WRITE  TX 00 1D 2C 03 <5 bytes>
 *          RX echo 00 1D 2D 03 <same 5 bytes>
 *
 * 语义（`TouchNewInfo.<init>`：每种手势一对 L/R int 字段，只取 data[0..4]）：
 *   **1 字节 = 1 种手势**，顺序 b0=单击 b1=双击 b2=三击 b3=长按1秒 b4=长按3秒；
 *   **高 4 位 = 左耳动作 id，低 4 位 = 右耳动作 id**（单击字节用 shr 4 / and 15 拆开）。
 *
 * 已知明文的双耳解码（本文件多处用它当锚点）：
 *   11 → L=1 R=1   23 → L=2 R=3   66 → L=6 R=6   77 → L=7 R=7   00 → L=0 R=0
 */
package moe.huime.miuixmoondrop.core

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

    /** 用户已知配置对应的 5 个「双耳打包」字节（与 measuredReadBack 同一份）。 */
    private val knownSlots = intArrayOf(0x11, 0x23, 0x66, 0x77, 0x00)

    /** IntArray 没有 toByteArray()（Kotlin stdlib 不提供）—— CI 曾因此报 Unresolved reference。 */
    private fun IntArray.toBytes(): ByteArray = ByteArray(size) { this[it].toByte() }

    private fun confOf(vararg bytes: Int): Gaia.GestureConf =
        Gaia.GestureConf(IntArray(bytes.size) { bytes[it] })

    // ── 1. 命令构造（线格式逐字节） ─────────────────────────────────────────

    @Test
    fun `读手势配置的帧与实测一致`() {
        assertEquals("00 1D 2C 02", Gaia.hex(Gaia.touchV2GetConf()))
        // 顺便把「feature 22 / COMMAND / cmd 2」的运算关系钉死：0x2C02 == 22<<9 | 2
        val f = Gaia.parse(Gaia.touchV2GetConf())!!
        assertEquals(Gaia.F_TOUCHV2, f.feature)
        assertEquals(Gaia.TYPE_COMMAND, f.type)
        assertEquals(Gaia.C_TOUCHV2_GET_ACTION_CONF, f.command)
        // 字节码里 GET_DEFAULT_ACTION=1 确实存在，但官方 App 从不发送 → 本项目也不发送
        assertEquals(1, Gaia.C_TOUCHV2_GET_DEFAULT_ACTION)
        assertFalse(Gaia.hex(Gaia.touchV2GetConf()).contains("2C 01"))
    }

    @Test
    fun `写手势配置的帧与实测一致`() {
        val pkt = Gaia.touchV2SetConf(byteArrayOf(0x11, 0x23, 0x66, 0x77, 0x00))
        assertEquals("00 1D 2C 03 11 23 66 77 00", Gaia.hex(pkt))
        val f = Gaia.parse(pkt)!!
        assertEquals(Gaia.F_TOUCHV2, f.feature)
        assertEquals(Gaia.TYPE_COMMAND, f.type)
        assertEquals(Gaia.C_TOUCHV2_SET_ACTION_CONF, f.command)
        // payload 必须**原样**是那 5 个字节（顺序 = 手势顺序，不允许被重排）
        assertTrue(knownSlots.toBytes().contentEquals(f.payload))
    }

    @Test
    fun `响应帧的字节布局与实测一致（RESPONSE 类型）`() {
        // 用 packet() 反推回包：feature 22 / RESPONSE / cmd 2 → 0x2D02
        val back = Gaia.packet(
            Gaia.VENDOR_QUALCOMM,
            Gaia.F_TOUCHV2,
            Gaia.TYPE_RESPONSE,
            Gaia.C_TOUCHV2_GET_ACTION_CONF,
            knownSlots.toBytes(),
        )
        assertEquals("00 1D 2D 02 11 23 66 77 00", Gaia.hex(back))
        assertTrue(back.contentEquals(measuredReadBack))
        assertEquals(
            "00 1D 2D 03 11 23 66 77 00",
            Gaia.hex(
                Gaia.packet(
                    Gaia.VENDOR_QUALCOMM,
                    Gaia.F_TOUCHV2,
                    Gaia.TYPE_RESPONSE,
                    Gaia.C_TOUCHV2_SET_ACTION_CONF,
                    knownSlots.toBytes(),
                )
            ),
        )
    }

    @Test
    fun `SPP 封装与字节码参考一致`() {
        // 字节码参考：读 `FF 04 00 00 | 00 1D 2C 02`，写 `FF 04 00 05 | 00 1D 2C 03 ...`
        // 桩头长度 = PDU 中 4 字节 GAIA 头之后的 payload 字节数（读 0、写 5）
        assertEquals(
            "FF 04 00 00 00 1D 2C 02",
            Gaia.hex(Gaia.wrapRfcomm(Gaia.touchV2GetConf())),
        )
        assertEquals(
            "FF 04 00 05 00 1D 2C 03 11 23 66 77 00",
            Gaia.hex(
                Gaia.wrapRfcomm(
                    Gaia.touchV2SetConf(byteArrayOf(0x11, 0x23, 0x66, 0x77, 0x00))
                )
            ),
        )
    }

    @Test
    fun `写入长度不是 5 字节时拒绝下发`() {
        assertRejectsIae { Gaia.touchV2SetConf(ByteArray(0)) }
        assertRejectsIae { Gaia.touchV2SetConf(byteArrayOf(0x11, 0x23, 0x66, 0x77)) }
        assertRejectsIae { Gaia.touchV2SetConf(byteArrayOf(0x11, 0x23, 0x66, 0x77, 0x00, 0x01)) }
        // 恰好 5 字节则放行
        assertEquals(5, Gaia.touchV2SetConf(ByteArray(5)).size - 4)
    }

    // ── 2. 已知明文解析 + 双耳半字节解码 ───────────────────────────────────

    @Test
    fun `解析实测回包得到 5 个字节`() {
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
    fun `实测字节按双耳半字节解码`() {
        val conf = Gaia.parseGestureConf(measuredReadBack.copyOfRange(4, 9))!!

        // 11 23 66 77 00 → 逐字节拆半字节（高 4 位左耳 / 低 4 位右耳）
        //   0x11: L=(0x11 shr 4)=1   R=(0x11 and 0x0F)=1
        //   0x23: L=2                R=3           ← 唯一不对称，正是「双击=上/下一首」
        //   0x66: L=6                R=6
        //   0x77: L=7                R=7
        //   0x00: L=0                R=0
        assertEquals(1, conf.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT))
        assertEquals(1, conf.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT))

        assertEquals(2, conf.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.LEFT))
        assertEquals(3, conf.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.RIGHT))

        assertEquals(6, conf.action(Gaia.GestureSlot.TRIPLE_TAP, Gaia.Ear.LEFT))
        assertEquals(6, conf.action(Gaia.GestureSlot.TRIPLE_TAP, Gaia.Ear.RIGHT))

        assertEquals(7, conf.action(Gaia.GestureSlot.LONG_PRESS_1S, Gaia.Ear.LEFT))
        assertEquals(7, conf.action(Gaia.GestureSlot.LONG_PRESS_1S, Gaia.Ear.RIGHT))

        assertEquals(0, conf.action(Gaia.GestureSlot.LONG_PRESS_3S, Gaia.Ear.LEFT))
        assertEquals(0, conf.action(Gaia.GestureSlot.LONG_PRESS_3S, Gaia.Ear.RIGHT))

        // 动作 id ↔ 已观测的动作表（1:1）
        assertEquals(Gaia.TouchActions.PLAY_PAUSE, conf.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT))
        assertEquals(Gaia.TouchActions.PREVIOUS_TRACK, conf.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.LEFT))
        assertEquals(Gaia.TouchActions.NEXT_TRACK, conf.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.RIGHT))
        assertEquals(Gaia.TouchActions.VOICE_ASSISTANT, conf.action(Gaia.GestureSlot.TRIPLE_TAP, Gaia.Ear.LEFT))
        assertEquals(Gaia.TouchActions.ANC_SWITCH, conf.action(Gaia.GestureSlot.LONG_PRESS_1S, Gaia.Ear.LEFT))
        assertEquals(Gaia.TouchActions.NONE, conf.action(Gaia.GestureSlot.LONG_PRESS_3S, Gaia.Ear.LEFT))

        // 整字节取值（双耳一起）也对得上
        assertEquals(0x11, conf[Gaia.GestureSlot.SINGLE_TAP])
        assertEquals(0x23, conf[Gaia.GestureSlot.DOUBLE_TAP])
        assertEquals(0x66, conf[Gaia.GestureSlot.TRIPLE_TAP])
        assertEquals(0x77, conf[Gaia.GestureSlot.LONG_PRESS_1S])
        assertEquals(0x00, conf[Gaia.GestureSlot.LONG_PRESS_3S])
    }

    @Test
    fun `耳朵方向等于高四位左低四位右`() {
        // 0x23 → 左 2 / 右 3；把同样的半字节反过来（0x32）应当得到左 3 / 右 2
        val a = confOf(0x23, 0x00, 0x00, 0x00, 0x00)
        assertEquals(2, a.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT))
        assertEquals(3, a.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT))

        val b = confOf(0x32, 0x00, 0x00, 0x00, 0x00)
        assertEquals(3, b.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT))
        assertEquals(2, b.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT))

        // 枚举顺序也是「先左后右」（UI 的行顺序依赖它）
        assertEquals(listOf(Gaia.Ear.LEFT, Gaia.Ear.RIGHT), Gaia.Ear.entries.toList())
    }

    // ── 3. 半字节读改写（with） ────────────────────────────────────────────

    @Test
    fun `半字节读改写只动目标耳朵`() {
        val base = confOf(0x11, 0x23, 0x66, 0x77, 0x00)

        // 双击字节 0x23：左半 = 2、右半 = 3
        // 改左耳为 3：merged = (0x23 and 0x0F) or (3 shl 4) = 0x03 or 0x30 = 0x33
        val l3 = base.with(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.LEFT, 3)
        assertEquals("11 33 66 77 00", Gaia.hex(l3.toPayload()))
        assertEquals(3, l3.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.LEFT))
        assertEquals(3, l3.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.RIGHT)) // 右耳原本就是 3，没被动
        // 其余 4 个字节一字未改
        assertEquals(0x11, l3[Gaia.GestureSlot.SINGLE_TAP])
        assertEquals(0x66, l3[Gaia.GestureSlot.TRIPLE_TAP])
        assertEquals(0x77, l3[Gaia.GestureSlot.LONG_PRESS_1S])
        assertEquals(0x00, l3[Gaia.GestureSlot.LONG_PRESS_3S])

        // 右耳写回同一个值 3：merged = (0x23 and 0xF0) or 3 = 0x23 → 幂等，不产生变化
        assertEquals(
            "11 23 66 77 00",
            Gaia.hex(base.with(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.RIGHT, 3).toPayload()),
        )

        // 改右耳为 5：merged = (0x23 and 0xF0) or 5 = 0x20 or 0x05 = 0x25（左耳的 2 保留）
        val r5 = base.with(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.RIGHT, 5)
        assertEquals("11 25 66 77 00", Gaia.hex(r5.toPayload()))
        assertEquals(2, r5.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.LEFT))
        assertEquals(5, r5.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.RIGHT))

        // 清左耳（0）：merged = (0x11 and 0x0F) or (0 shl 4) = 0x01 → 右耳的 1 保留
        assertEquals(
            "01 23 66 77 00",
            Gaia.hex(base.with(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT, 0).toPayload()),
        )
        // 清右耳（0）：merged = (0x11 and 0xF0) or 0 = 0x10 → 左耳的 1 保留
        assertEquals(
            "10 23 66 77 00",
            Gaia.hex(base.with(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT, 0).toPayload()),
        )

        // 原对象没有被就地改动（IntArray 是可变引用，必须 copyOf）
        assertEquals("11 23 66 77 00", Gaia.hex(base.toPayload()))
        assertEquals(2, base.action(Gaia.GestureSlot.DOUBLE_TAP, Gaia.Ear.LEFT))

        // 下发用的正是「完整 5 字节 + 只有那一个半字节变了」
        assertEquals(
            "00 1D 2C 03 11 33 66 77 00",
            Gaia.hex(Gaia.touchV2SetConf(l3.toPayload())),
        )
    }

    @Test
    fun `半字节掩码对 0 到 15 全部成立`() {
        val base = confOf(0x11, 0x23, 0x66, 0x77, 0x00)
        for (v in 0..15) {
            // 左耳写 v：merged = 1 or (v shl 4)，右耳恒为 1
            val left = base.with(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT, v)
            assertEquals(v, left.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT))
            assertEquals(1, left.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT))
            // 右耳写 v：merged = 0x10 or v，左耳恒为 1
            val right = base.with(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT, v)
            assertEquals(v, right.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT))
            assertEquals(1, right.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT))
        }

        // 越界取值被归一到一个半字节（不会溢出到相邻字节 / 相邻耳朵）
        //   0x1F and 0x0F = 0x0F → 左耳 15；只影响左耳
        val big = base.with(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT, 0x1F)
        assertEquals("F1 23 66 77 00", Gaia.hex(big.toPayload()))
        assertEquals(15, big.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT))
        assertEquals(1, big.action(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT))
        //   0x11 and 0x0F = 1 → 右耳 1（高 4 位仍属于左耳，不会被右耳值污染）
        assertEquals(
            "11 23 66 77 00",
            Gaia.hex(base.with(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.RIGHT, 0x11).toPayload()),
        )
    }

    @Test
    fun `长按两档是两个独立字节可以同时配置`() {
        // 字节码里 onesL/onesR 与 threesL/threesR 是两组独立字段：
        // 长按3秒（b4）配不同动作时，长按1秒（b3）不受影响。
        val conf = confOf(0x11, 0x23, 0x66, 0x77, 0x00)
            .with(Gaia.GestureSlot.LONG_PRESS_3S, Gaia.Ear.LEFT, Gaia.TouchActions.ANC_SWITCH)
        // 长按3S 左耳写 7：merged = 0x00 or (7 shl 4) = 0x70
        assertEquals("11 23 66 77 70", Gaia.hex(conf.toPayload()))
        assertEquals(7, conf.action(Gaia.GestureSlot.LONG_PRESS_1S, Gaia.Ear.LEFT)) // 1 秒那档没变
        assertEquals(7, conf.action(Gaia.GestureSlot.LONG_PRESS_3S, Gaia.Ear.LEFT))
        assertEquals(0, conf.action(Gaia.GestureSlot.LONG_PRESS_3S, Gaia.Ear.RIGHT))
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
        assertNotNull(Gaia.parseGestureConf(knownSlots.toBytes()))
    }

    @Test
    fun `回包多于 5 字节时只取前 5（额外字节没有对应字段）`() {
        val longer = knownSlots.toBytes() + byteArrayOf(0x7F, 0x01)
        val conf = Gaia.parseGestureConf(longer)!!
        assertEquals(Gaia.TOUCHV2_CONF_SIZE, conf.size)
        assertTrue(knownSlots.contentEquals(conf.slots))
    }

    @Test
    fun `配置按内容比较而不是按引用`() {
        val a = Gaia.GestureConf(intArrayOf(0x11, 0x23, 0x66, 0x77, 0x00))
        val b = Gaia.GestureConf(intArrayOf(0x11, 0x23, 0x66, 0x77, 0x00))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals("GestureConf(11 23 66 77 00)", a.toString())
        assertFalse(a == a.with(Gaia.GestureSlot.SINGLE_TAP, Gaia.Ear.LEFT, Gaia.TouchActions.NONE))
    }

    // ── 4. 动作表（半字节 0..15；已观测 6 条 + 推断 2 条） ──────────────────

    @Test
    fun `动作表覆盖 0 到 7 且只有音量正负标为推断`() {
        val all = Gaia.TouchActions.ALL
        // id 必须是半字节且不重复
        assertTrue(all.all { it.id in 0..15 })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        // 已观测的 6 条 + 推断的 2 条都在
        assertTrue(all.map { it.id }.containsAll(listOf(0, 1, 2, 3, 4, 5, 6, 7)))

        // 「推断」只允许出现在 4/5 上（不能让未证实的 id 冒充已确认）
        assertEquals(
            setOf(Gaia.TouchActions.VOLUME_UP, Gaia.TouchActions.VOLUME_DOWN),
            all.filter { it.inferred }.map { it.id }.toSet(),
        )
        // 已观测的 6 条一个都不许标「推断」
        for (id in listOf(
            Gaia.TouchActions.NONE,
            Gaia.TouchActions.PLAY_PAUSE,
            Gaia.TouchActions.PREVIOUS_TRACK,
            Gaia.TouchActions.NEXT_TRACK,
            Gaia.TouchActions.VOICE_ASSISTANT,
            Gaia.TouchActions.ANC_SWITCH,
        )) {
            assertFalse(Gaia.TouchActions.byId(id)!!.inferred)
        }

        // 双语标签非空，且 labelOf 与表内一致（不锁死具体字面量）
        for (action in all) {
            assertTrue(action.labelZh.isNotBlank())
            assertTrue(action.labelEn.isNotBlank())
            assertEquals(action.labelZh, Gaia.TouchActions.labelOf(action.id))
        }
    }

    @Test
    fun `未映射的动作 id 显示为未知而不是空白`() {
        // 8..15 未观测到：动态挑一个当前不在表里的半字节（表若被补全也不会误伤）
        val unmappedId = (0..15).firstOrNull { Gaia.TouchActions.byId(it) == null }
        if (unmappedId != null) {
            assertEquals("未知(0x%X)".format(unmappedId), Gaia.TouchActions.matchOrUnknown(unmappedId))
            assertNull(Gaia.TouchActions.labelOf(unmappedId))
        } else {
            // 表已覆盖整个半字节空间 → 不存在未知值，这条测试的前提自动成立
            assertEquals((0..15).toList(), Gaia.TouchActions.ALL.map { it.id }.sorted())
        }

        // 传入的 id 只看低 4 位：0x12 → 2（上一曲），高 4 位不会干扰查表
        assertEquals(Gaia.TouchActions.labelOf(2), Gaia.TouchActions.matchOrUnknown(0x12))
        assertNotNull(Gaia.TouchActions.labelOf(2))
        // 掩码常量本身就是半字节
        assertEquals(0x0F, Gaia.TOUCH_ACTION_MASK)
    }

    @Test
    fun `动作表里的每个动作都能按 id 取回`() {
        for (action in Gaia.TouchActions.ALL) {
            assertEquals(action, Gaia.TouchActions.byId(action.id))
            assertEquals(action.labelZh, Gaia.TouchActions.labelOf(action.id))
            // 半字节掩码不改变表内 id
            assertEquals(action, Gaia.TouchActions.byId(action.id or 0x10))
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
