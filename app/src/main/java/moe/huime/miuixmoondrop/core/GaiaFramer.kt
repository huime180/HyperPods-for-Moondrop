/*
 * MiuixMoondrop — RFCOMM/SPP 流式 GAIA 切帧器
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * GAIA V4 设备（布丁 PUDDING 等）在 Classic Bluetooth RFCOMM/SPP 上存在两种封装：
 *
 *  1) 传输帧（官方 TransportProtocol.Rfcomm 封装，SOF = 0xFF）：
 *     FF | Version(1B) | Flags(1B) | Length(1B 或 2B) | PDU(Length+4) | [Checksum(1B)]
 *       Flags bit0 = CHECKSUM        PDU 之后随附 1 字节校验和
 *       Flags bit1 = LENGTH_EXTENSION  Length 为 2 字节（仅 Version >= 4 有效）
 *       Length = PDU 的 payload 字节数；PDU 总长 = Length + 4（vendor 2B + cmdValue 2B）
 *
 *  2) 裸 PDU（部分固件直发，vendor 头 00 1D 开头）：
 *     00 1D | cmdValue(2B) | payload...
 *     无长度字段，无法精确定界；只能靠流中下一个帧起始（FF 或 00 1D）或 burst 结束来切分。
 *
 * 本类为流式状态机：feed() 喂入一段字节，返回切出的完整 PDU 列表。
 * 半截帧保留在内部缓冲等待后续字节，绝不丢弃。
 *
 * 局限（裸 PDU 固有）：若 payload 内部恰好出现 FF 或 00 1D，会被误判为下一帧起始。
 * 实测水月雨设备单 burst 单帧且 payload 很短，不受影响。
 */
package moe.huime.miuixmoondrop.core

class GaiaFramer {

    private val buf = ArrayList<Byte>(160)

    /** 已缓存的字节数（诊断用） */
    val pendingSize: Int get() = buf.size

    /**
     * 喂入一段字节并切帧。
     *
     * @param chunk 本次读到的字节
     * @param burstEnd 本次 burst 是否结束（读流 available()==0）。
     *                 裸 PDU 只有在 burst 结束时才能确定边界并交付。
     * @return 切出的完整 PDU 列表（顺序即到达顺序）
     */
    fun feed(chunk: ByteArray, burstEnd: Boolean): List<ByteArray> {
        for (b in chunk) buf.add(b)
        val out = ArrayList<ByteArray>(2)

        while (true) {
            if (buf.size < 2) break
            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF

            if (b0 == SOF) {
                if (buf.size < 4) break
                val version = b1
                val flags = buf[2].toInt() and 0xFF
                val hasLenExt = version >= VERSION_WITH_LENGTH_EXTENSION &&
                    (flags and FLAG_LENGTH_EXTENSION) != 0
                val headerLen = if (hasLenExt) 5 else 4
                if (buf.size < headerLen) break
                val length = if (hasLenExt) {
                    ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                } else {
                    buf[3].toInt() and 0xFF
                }
                val hasChecksum = (flags and FLAG_CHECKSUM) != 0
                val pduLen = length + PDU_HEADER_SIZE
                val total = headerLen + pduLen + (if (hasChecksum) 1 else 0)
                if (total < headerLen + PDU_HEADER_SIZE) { consume(1); continue }
                if (buf.size < total) break // 半截帧，等待后续 burst
                val pdu = ByteArray(pduLen)
                for (i in 0 until pduLen) pdu[i] = buf[headerLen + i]
                consume(total)
                out.add(pdu)
                continue
            }

            if (b0 == VENDOR_HI && b1 == VENDOR_LO) {
                var cut = -1
                var i = 4
                while (i < buf.size) {
                    val v = buf[i].toInt() and 0xFF
                    if (v == SOF || (v == VENDOR_HI && i + 1 < buf.size &&
                                (buf[i + 1].toInt() and 0xFF) == VENDOR_LO)
                    ) {
                        cut = i
                        break
                    }
                    i++
                }
                if (cut > 0) {
                    val pdu = ByteArray(cut)
                    for (j in 0 until cut) pdu[j] = buf[j]
                    consume(cut)
                    out.add(pdu)
                    continue
                }
                if (burstEnd) {
                    val pdu = ByteArray(buf.size)
                    for (j in pdu.indices) pdu[j] = buf[j]
                    consume(buf.size)
                    out.add(pdu)
                }
                break
            }

            // 垃圾字节：跳过 1 字节继续扫描
            consume(1)
        }
        return out
    }

    private fun consume(n: Int) {
        repeat(n) { if (buf.isNotEmpty()) buf.removeAt(0) }
    }

    fun reset() = buf.clear()

    companion object {
        private const val SOF = 0xFF
        private const val VENDOR_HI = 0x00
        private const val VENDOR_LO = 0x1D
        private const val FLAG_CHECKSUM = 0x01
        private const val FLAG_LENGTH_EXTENSION = 0x02
        private const val VERSION_WITH_LENGTH_EXTENSION = 4
        private const val PDU_HEADER_SIZE = 4
    }
}
