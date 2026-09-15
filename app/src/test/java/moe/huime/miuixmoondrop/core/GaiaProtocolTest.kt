/*
 * GAIA 线格式与命令构造的回归测试（字节必须与协议文档逐字节一致）。
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.huime.miuixmoondrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaiaProtocolTest {

    @Test
    fun `电量查询命令与协议文档字节一致`() {
        assertEquals("00 1D 1A 00", Gaia.hex(Gaia.batteryGetAllV4()))
        assertEquals("00 1D 1A 01 01 02", Gaia.hex(Gaia.batteryGet(intArrayOf(1, 2))))
        assertEquals("00 1D 1A 01", Gaia.hex(Gaia.batteryGetAll()))
    }

    @Test
    fun `ANC V2 读写与文档一致`() {
        assertEquals("00 1D 40 03", Gaia.hex(Gaia.ancV2GetMode()))
        assertEquals("00 1D 40 04 02", Gaia.hex(Gaia.ancV2SetMode(2)))
    }

    @Test
    fun `双设备连接命令与 moondrop-link 真机结论一致`() {
        assertEquals("00 1D 28 01", Gaia.hex(Gaia.dualConnectionGet()))
        assertEquals("00 1D 28 02 01", Gaia.hex(Gaia.dualConnectionSet(true)))
        assertEquals("00 1D 28 02 00", Gaia.hex(Gaia.dualConnectionSet(false)))
        assertEquals("00 1D 28 05", Gaia.hex(Gaia.dualConnectionGetDevices(false)))
        assertEquals("00 1D 28 06", Gaia.hex(Gaia.dualConnectionGetDevices(true)))
    }

    @Test
    fun `断开已连接设备的 payload 为 num 加 6 字节地址加名字`() {
        val pkt = Gaia.dualConnectionDisconnect(1, "AA:BB:CC:DD:EE:FF", "Pad")
        assertNotNull(pkt)
        val f = Gaia.parse(pkt!!)!!
        assertEquals(Gaia.F_ONEBRINGTWO, f.feature)
        assertEquals(Gaia.C_OBT_DISCONNECT, f.command)
        assertEquals(1, f.payload[0].toInt())
        assertEquals("AA:BB:CC:DD:EE:FF", (1..6).joinToString(":") { "%02X".format(f.payload[it].toInt() and 0xFF) })
        assertEquals("Pad", String(f.payload, 7, f.payload.size - 7))
    }

    @Test
    fun `解析已连接设备条目`() {
        val payload = byteArrayOf(2, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()) +
            "MyPhone".toByteArray()
        val d = Gaia.parseLinkedDevice(payload)!!
        assertEquals(2, d.first)
        assertEquals("AA:BB:CC:DD:EE:FF", d.second)
        assertEquals("MyPhone", d.third)
        assertNull(Gaia.parseLinkedDevice(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `LHDC 编解码开关命令`() {
        assertEquals("00 1D 20 05", Gaia.hex(Gaia.lhdcGet()))
        assertEquals("00 1D 20 06 01", Gaia.hex(Gaia.lhdcSet(true)))
        assertEquals("00 1D 20 06 00", Gaia.hex(Gaia.lhdcSet(false)))
    }

    @Test
    fun `增益与指示灯命令`() {
        assertEquals("00 1D 1E 01", Gaia.hex(Gaia.gainGet()))
        assertEquals("00 1D 1E 02 00", Gaia.hex(Gaia.gainSet(0)))
        assertEquals("00 1D 26 01", Gaia.hex(Gaia.ledGet()))
        assertEquals("00 1D 26 02 01", Gaia.hex(Gaia.ledSet(1)))
    }

    @Test
    fun `提示音命令与官方 App 日志实测一致`() {
        // 【2026-09-14 真机证据】V3VoicePlugin: command=1 读 / command=2 写，
        // payload = [enabled, volume, index]（V2，size>=3）
        assertEquals("00 1D 1C 01", Gaia.hex(Gaia.voiceGetConf()))
        // 日志原样：data=[1, 20, 1]（开，音量 20，索引 1）
        assertEquals("00 1D 1C 02 01 14 01", Gaia.hex(Gaia.voiceSetConf(true, 20, 1)))
        // 日志原样：data=[0, 82, 1]（关，音量 82，索引 1）
        assertEquals("00 1D 1C 02 00 52 01", Gaia.hex(Gaia.voiceSetConf(false, 82, 1)))
        // 音量是百分比，越界被裁剪
        assertEquals("00 1D 1C 02 01 64 00", Gaia.hex(Gaia.voiceSetConf(true, 250, 0)))
    }

    @Test
    fun `提示音回包解析三个字段`() {
        val c = Gaia.parseVoiceConf(byteArrayOf(1, 20, 1))!!
        assertTrue(c.enabled)
        assertEquals(20, c.volume)
        assertEquals(1, c.index)
        assertTrue(c.v2)

        val off = Gaia.parseVoiceConf(byteArrayOf(0, 82, 1))!!
        assertFalse(off.enabled)
        assertEquals(82, off.volume)

        // 只有开关位的旧短格式
        val short = Gaia.parseVoiceConf(byteArrayOf(1))!!
        assertTrue(short.enabled)
        assertFalse(short.v2)

        assertNull(Gaia.parseVoiceConf(null))
    }

    @Test
    fun `版本探测帧与注册通知帧`() {
        assertEquals("00 0A 03 00", Gaia.hex(Gaia.getApiVersion()))
        assertEquals("00 1D 00 07 20", Gaia.hex(Gaia.registerNotification(Gaia.F_ANC_V2, true)))
    }

    @Test
    fun `parse 解析响应帧`() {
        val raw = byteArrayOf(0x00, 0x1D, 0x1B, 0x01, 2, 1, 80, 2, 75)
        val f = Gaia.parse(raw)!!
        assertEquals(0x001D, f.vendor)
        assertEquals(Gaia.F_BATTERY, f.feature)
        assertEquals(Gaia.TYPE_RESPONSE, f.type)
        assertEquals(1, f.command)
        assertEquals(5, f.payload.size)
        assertTrue(f.isResponse)
    }

    @Test
    fun `非 GAIA 帧返回 null`() {
        assertNull(Gaia.parse(byteArrayOf(0x04, 0x00, 0x04, 0x00)))
        assertNull(Gaia.parse(byteArrayOf(0x00, 0x1D)))
        assertNull(Gaia.parse(null))
    }

    @Test
    fun `能力位图解析与 ANC 路径选择`() {
        // word0：bit8 = AUDIO_CURATION(8)
        val feats = Gaia.parseSupportedFeatures(byteArrayOf(0, 0, 0x01, 0x00))
        assertTrue(feats.contains(Gaia.F_AUDIO_CURATION))
        assertEquals(Gaia.ANC_PATH_AUDIO_CURATION, Gaia.ancPathFrom(feats))

        // word0：bit2 = ANC_V1(2)
        val feats2 = Gaia.parseSupportedFeatures(byteArrayOf(0, 0, 0x00, 0x04))
        assertEquals(Gaia.ANC_PATH_ANC_V1, Gaia.ancPathFrom(feats2))

        // 无 ANC 能力
        assertEquals(Gaia.ANC_PATH_UNKNOWN, Gaia.ancPathFrom(emptySet()))
    }

    @Test
    fun `位图截断检测`() {
        assertTrue(Gaia.isFeaturePayloadTruncated(byteArrayOf(1, 2, 3)))
        assertEquals(false, Gaia.isFeaturePayloadTruncated(byteArrayOf(1, 2, 3, 4)))
    }

    private fun bytes(hex: String): ByteArray =
        hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
