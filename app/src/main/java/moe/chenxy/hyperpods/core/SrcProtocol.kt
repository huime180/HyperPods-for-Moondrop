/*
 * HyperPods for Moondrop — 中科蓝讯私有协议（BleSourceSwitch / 9ECA0000）
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 部分水月雨耳机（猫饼 NEKOCAKE / 太空漫游2 / 音乐胶囊 等）主控为中科蓝讯
 * （Bluetrum），在提供高通 GAIA 服务的同时还暴露一条 Moondrop 私有服务
 * `9eca0000-7f3a-4f32-9a38-a91b2c6e0100`，用于音源切换 / EQ / MIC 增益 / SN。
 * 两条协议可在同一个 GATT 连接里并存，互不干扰。
 *
 * 帧格式：`[0xA5][0x01][frameType][commandId][seq][payloadLen][payload ≤ 14B]`
 *   frameType: 1=COMMAND 2=RESPONSE 3=NOTIFICATION
 */
package moe.chenxy.hyperpods.core

object SrcProtocol {

    // ── GATT ────────────────────────────────────────────────────────────────
    const val SERVICE = "9eca0000-7f3a-4f32-9a38-a91b2c6e0100"
    const val CH_COMMAND = "9eca0001-7f3a-4f32-9a38-a91b2c6e0100"
    const val CH_RESPONSE = "9eca0002-7f3a-4f32-9a38-a91b2c6e0100"
    const val CH_NOTIFICATION = "9eca0003-7f3a-4f32-9a38-a91b2c6e0100"
    const val CH_CAPABILITY = "9eca0004-7f3a-4f32-9a38-a91b2c6e0100"
    const val CH_FW_INFO = "9eca0005-7f3a-4f32-9a38-a91b2c6e0100"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    // ── 帧 ──────────────────────────────────────────────────────────────────
    const val MAGIC = 0xA5
    const val VERSION = 0x01
    const val FRAME_COMMAND = 1
    const val FRAME_RESPONSE = 2
    const val FRAME_NOTIFICATION = 3
    const val MAX_PAYLOAD = 14

    // ── 命令 ────────────────────────────────────────────────────────────────
    const val CMD_GET_AUDIO_SOURCE = 1
    const val CMD_SET_AUDIO_SOURCE = 2
    const val CMD_GET_CAPABILITY = 3
    const val CMD_GET_FW_VERSION = 4
    const val CMD_GET_DEVICE_VOLUME = 5
    const val CMD_SET_DEVICE_VOLUME = 6
    const val CMD_GET_PRESET_EQ = 7
    const val CMD_SET_PRESET_EQ = 8
    const val CMD_GET_PEQ_CONFIG = 9
    const val CMD_SET_PEQ_PREGAIN = 10
    const val CMD_GET_PEQ_POINT = 11
    const val CMD_SET_PEQ_POINT = 12
    const val CMD_COMMIT_PEQ = 13
    const val CMD_GET_MIC_GAIN = 14
    const val CMD_SET_MIC_GAIN = 15
    const val CMD_PING = 127

    // ── 通知 ────────────────────────────────────────────────────────────────
    const val NOTIF_AUDIO_SOURCE_CHANGED = 129
    const val NOTIF_SWITCH_STATE_CHANGED = 130
    const val NOTIF_CAPABILITY_CHANGED = 131
    const val NOTIF_VOLUME_CHANGED = 133
    const val NOTIF_PRESET_EQ_CHANGED = 134
    const val NOTIF_PEQ_COMMITTED = 135
    const val NOTIF_MIC_GAIN_CHANGED = 136

    // ── 音源 ────────────────────────────────────────────────────────────────
    const val SRC_BLUETOOTH = 0
    const val SRC_USB_AUDIO = 1
    const val SRC_WIRELESS_2_4G = 2
    const val SRC_AUX_LINE_IN = 3
    const val SRC_OPTICAL_SPDIF = 4
    const val SRC_COAXIAL = 5
    const val SRC_HDMI_ARC = 6
    const val SRC_LOCAL_PLAYER = 7
    const val SRC_AUTO_SELECT = 127
    const val SRC_NO_SIGNAL = 254

    /** 切换选项位掩码 */
    const val OPT_PERSIST_DEFAULT = 1
    const val OPT_MUTE_DURING_SWITCH = 4
    const val OPT_NO_BT_AUTO_RESUME = 8

    /** 构造命令帧。 */
    fun command(cmd: Int, seq: Int, payload: ByteArray? = null): ByteArray {
        val p = payload ?: Gaia.EMPTY
        require(p.size <= MAX_PAYLOAD) { "9ECA payload 不得超过 $MAX_PAYLOAD 字节，实际 ${p.size}" }
        val out = ByteArray(6 + p.size)
        out[0] = MAGIC.toByte()
        out[1] = VERSION.toByte()
        out[2] = FRAME_COMMAND.toByte()
        out[3] = cmd.toByte()
        out[4] = seq.toByte()
        out[5] = p.size.toByte()
        if (p.isNotEmpty()) System.arraycopy(p, 0, out, 6, p.size)
        return out
    }

    data class Frame(
        val frameType: Int,
        val command: Int,
        val seq: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Frame && frameType == other.frameType && command == other.command &&
                seq == other.seq && payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            ((frameType * 31 + command) * 31 + seq) * 31 + payload.contentHashCode()
    }

    /** 解析一帧；不是 9ECA 帧返回 null。 */
    fun parse(data: ByteArray?): Frame? {
        val d = data ?: return null
        if (d.size < 6) return null
        if ((d[0].toInt() and 0xFF) != MAGIC) return null
        if ((d[1].toInt() and 0xFF) != VERSION) return null
        val len = d[5].toInt() and 0xFF
        val avail = minOf(d.size - 6, len)
        return Frame(
            frameType = d[2].toInt() and 0xFF,
            command = d[3].toInt() and 0xFF,
            seq = d[4].toInt() and 0xFF,
            payload = if (avail > 0) d.copyOfRange(6, 6 + avail) else Gaia.EMPTY,
        )
    }

    // ── 高层构造 ────────────────────────────────────────────────────────────
    fun getAudioSource(seq: Int) = command(CMD_GET_AUDIO_SOURCE, seq)
    fun setAudioSource(seq: Int, sourceId: Int, options: Int = 0, fadeSec: Int = 5) =
        command(CMD_SET_AUDIO_SOURCE, seq, byteArrayOf(
            sourceId.toByte(), options.toByte(), fadeSec.coerceIn(0, 60).toByte(),
        ))
    fun getCapability(seq: Int, page: Int) = command(CMD_GET_CAPABILITY, seq, byteArrayOf(page.toByte()))
    fun getFirmwareVersion(seq: Int) = command(CMD_GET_FW_VERSION, seq)
    fun getDeviceVolume(seq: Int) = command(CMD_GET_DEVICE_VOLUME, seq)
    fun setDeviceVolume(seq: Int, left: Int, right: Int, mute: Int) =
        command(CMD_SET_DEVICE_VOLUME, seq, byteArrayOf(left.toByte(), right.toByte(), mute.toByte()))
    fun getPresetEq(seq: Int) = command(CMD_GET_PRESET_EQ, seq)
    fun setPresetEq(seq: Int, presetIndex: Int) =
        command(CMD_SET_PRESET_EQ, seq, byteArrayOf(presetIndex.toByte()))
    fun getMicGain(seq: Int) = command(CMD_GET_MIC_GAIN, seq)
    fun setMicGain(seq: Int, gain: Int) = command(CMD_SET_MIC_GAIN, seq, byteArrayOf(gain.toByte()))
    fun ping(seq: Int) = command(CMD_PING, seq)
}
