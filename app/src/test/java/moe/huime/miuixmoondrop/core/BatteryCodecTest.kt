/*
 * 「右耳电量不显示」修复的回归测试。
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.huime.miuixmoondrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryCodecTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ── 帧构造 ────────────────────────────────────────────────────────────

    @Test
    fun `cmd0 查询支持列表的帧与协议文档一致`() {
        // 文档：电量支持 BATTERY(0D) cmd0 → `00 1D 1A 00`
        assertEquals("00 1D 1A 00", Gaia.hex(BatteryCodec.buildSupportedQuery()))
    }

    @Test
    fun `cmd1 带类型列表的查询帧与协议文档一致`() {
        // 文档：电量值 cmd1 + 类型字节 → `00 1D 1A 01 01 02 03`
        val pkt = BatteryCodec.buildLevelsQuery(intArrayOf(1, 2, 3))
        assertEquals("00 1D 1A 01 01 02 03", Gaia.hex(pkt))
    }

    @Test
    fun `标准分体回包解析出左右耳与充电盒`() {
        val pairs = BatteryCodec.parse(bytes(1, 80, 2, 75, 3, 60))
        assertEquals(3, pairs.size)
        assertEquals(Gaia.BATTERY_LEFT, pairs[0][0]); assertEquals(80, pairs[0][1])
        assertEquals(Gaia.BATTERY_RIGHT, pairs[1][0]); assertEquals(75, pairs[1][1])
        assertEquals(Gaia.BATTERY_CASE, pairs[2][0]); assertEquals(60, pairs[2][1])
    }

    @Test
    fun `带数量前缀的回包不会整体错位`() {
        // [count=2][1,80][2,75]
        val pairs = BatteryCodec.parse(bytes(2, 1, 80, 2, 75))
        assertEquals(2, pairs.size)
        assertEquals(80, pairs[0][1])
        assertEquals(75, pairs[1][1])
    }

    @Test
    fun `未知类型只忽略不位移`() {
        // [7,99] 是未知 type，后面的 1/2 必须仍归位
        val pairs = BatteryCodec.parse(bytes(7, 99, 1, 80, 2, 75))
        assertEquals(2, pairs.size)
        assertEquals(Gaia.BATTERY_LEFT, pairs[0][0])
        assertEquals(Gaia.BATTERY_RIGHT, pairs[1][0])
    }

    @Test
    fun `无数据标记255被丢弃`() {
        val pairs = BatteryCodec.parse(bytes(1, 255, 2, 75))
        assertEquals(1, pairs.size)
        assertEquals(Gaia.BATTERY_RIGHT, pairs[0][0])
    }

    // ── 核心回归：右耳必须显示 ────────────────────────────────────────────

    @Test
    fun `单设备电量型机型左右耳都要显示 - 这是右耳不显示的根因`() {
        // 实测水月雨 EDGE 只回 type 0（单设备）
        val state = BatteryState()
        state.applyPairs(BatteryCodec.parse(bytes(0, 60)))

        assertTrue("左耳应显示", state.currentLeft.known)
        assertTrue("右耳也必须显示（修复点）", state.currentRight.known)
        assertEquals(60, state.currentLeft.level)
        assertEquals(60, state.currentRight.level)
        assertTrue(state.usesSingleDeviceBattery())
    }

    @Test
    fun `分体值优先于单设备值`() {
        val state = BatteryState()
        state.applyPairs(BatteryCodec.parse(bytes(0, 60)))          // 整机 60
        state.applyPairs(BatteryCodec.parse(bytes(1, 80, 2, 75)))   // 分体覆盖
        assertEquals(80, state.currentLeft.level)
        assertEquals(75, state.currentRight.level)
    }

    @Test
    fun `只收到左耳时右耳不会被清空`() {
        val state = BatteryState()
        state.applyPairs(BatteryCodec.parse(bytes(1, 80, 2, 75)))
        state.applyPairs(BatteryCodec.parse(bytes(1, 70)))  // 通知里只有左耳
        assertEquals(70, state.currentLeft.level)
        assertEquals("右耳应保留上一次的 75", 75, state.currentRight.level)
    }

    @Test
    fun `缺少右耳的单包不会让右耳变成未知`() {
        val state = BatteryState()
        state.applyPairs(BatteryCodec.parse(bytes(1, 80, 2, 75, 3, 60)))
        state.applyPairs(BatteryCodec.parse(bytes(3, 55)))  // 只有盒
        assertTrue(state.currentRight.known)
        assertEquals(75, state.currentRight.level)
        assertEquals(55, state.currentCase.level)
    }

    @Test
    fun `系统广播兜底必须左右都给 - 只兜左耳就是原来的 bug`() {
        val state = BatteryState()
        state.fallbackFromSystem(42)
        assertEquals(42, state.currentLeft.level)
        assertEquals("兜底也必须给右耳", 42, state.currentRight.level)
    }

    @Test
    fun `三路齐全后 allKnown`() {
        val state = BatteryState()
        state.applyPairs(BatteryCodec.parse(bytes(1, 80, 2, 75, 3, 60)))
        assertTrue(state.bothEarsKnown())
        assertEquals(60, state.currentCase.level)
    }

    @Test
    fun `系统单值取左右较小者`() {
        val state = BatteryState()
        state.applyPairs(BatteryCodec.parse(bytes(1, 80, 2, 75)))
        assertEquals(75, state.systemLevel())
    }

    @Test
    fun `非法电量被裁剪到0到100`() {
        val state = BatteryState()
        state.applyPairs(listOf(intArrayOf(Gaia.BATTERY_LEFT, 200)))
        // 200 不是合法电量，parse 会丢弃；直接喂 pair 时被裁剪
        assertEquals(100, state.currentLeft.level)
    }

    @Test
    fun `reset 清空全部`() {
        val state = BatteryState()
        state.applyPairs(BatteryCodec.parse(bytes(0, 60, 3, 10)))
        state.reset()
        assertFalse(state.currentLeft.known)
        assertFalse(state.currentRight.known)
        assertFalse(state.currentCase.known)
    }
}
