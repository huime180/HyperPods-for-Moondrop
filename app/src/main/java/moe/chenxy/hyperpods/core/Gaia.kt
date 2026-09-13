/*
 * HyperPods for Moondrop — GAIA 协议核心
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 水月雨（Moondrop）耳机使用高通 GAIA（QTiL）业务协议。本文件是该协议的权威定义，
 * 命令号 / 枚举逆向自水月雨官方 App 内嵌的 com.qualcomm.qti.gaiaclient 库。
 *
 * 两条传输链路共用同一份线格式（wire format）：
 *   · GAIA V3 —— BLE GATT（服务 00001100-d102-11e1-9b23-00025b00a5a5）
 *   · GAIA V4 —— Classic Bluetooth RFCOMM / SPP（UUID 00001101-0000-1000-8000-00805f9b34fb）
 *
 * 线格式：
 *   [vendor 2B BE][commandValue 2B BE][payload...]
 *   commandValue = (feature shl 9) or (type shl 7) or (command and 0x7F)
 *   type: 0=COMMAND, 1=NOTIFICATION, 2=RESPONSE
 *
 * 本文件不依赖任何 Android API，可直接单元测试。
 */
package moe.chenxy.hyperpods.core

object Gaia {

    // ============================================================
    // 线格式常量
    // ============================================================
    const val VENDOR_QUALCOMM = 0x001D

    const val TYPE_COMMAND = 0
    const val TYPE_NOTIFICATION = 1
    const val TYPE_RESPONSE = 2

    /** GAIA V4 over RFCOMM 的 SPP UUID（Classic Bluetooth） */
    const val SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"

    // GAIA V3 over BLE 的 GATT UUID
    const val GATT_SERVICE = "00001100-d102-11e1-9b23-00025b00a5a5"
    const val GATT_COMMAND = "00001101-d102-11e1-9b23-00025b00a5a5"
    const val GATT_RESPONSE = "00001102-d102-11e1-9b23-00025b00a5a5"
    const val GATT_DATA = "00001103-d102-11e1-9b23-00025b00a5a5"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    // ============================================================
    // Feature ID（QTiL）
    // ============================================================
    const val F_BASIC = 0
    const val F_EARBUD = 1
    const val F_ANC = 2                  // ANC V1
    const val F_VOICE_UI = 3
    const val F_DEBUG = 4
    const val F_MUSIC_PROCESSING = 5     // EQ
    const val F_UPGRADE = 6
    const val F_HANDSET_SERVICE = 7
    const val F_AUDIO_CURATION = 8       // 风噪 / 透传 / 自动透传 / 防啸叫 / 噪声识别
    const val F_EARBUD_FIT = 9
    const val F_VOICE_PROCESSING = 10
    const val F_GESTURE_CONFIGURATION = 11
    const val F_STATISTICS = 12
    const val F_BATTERY = 13
    const val F_VOICE = 14               // 提示音（voice prompt）
    const val F_DAC_GAIN = 15            // 增益
    const val F_CODEC_TYPE = 16          // 蓝牙编解码（AAC / LHDC / LDAC / LC3）
    const val F_LIGHT_SENSOR = 17
    const val F_SPATIAL_AUDIO = 18
    const val F_LED = 19                 // 指示灯
    const val F_ONEBRINGTWO = 20         // 一拖二 / 双设备连接
    const val F_BT_ADDRESS = 21
    const val F_TOUCHV2 = 22
    const val F_AUDIO_RESOURCE = 23
    const val F_POWER_CONTROL = 24
    const val F_POWER_TIMEOUT = 25
    const val F_TOUCHV3 = 26
    const val F_DYBASS = 27
    const val F_AUDIO_FILE_STORAGE = 29
    const val F_LR_CHANNEL = 30
    const val F_ANC_V2 = 32

    // ============================================================
    // BASIC(0)
    // ============================================================
    const val C_BASIC_GET_GAIA_VERSION = 0
    const val C_BASIC_GET_SUPPORTED_FEATURES = 1
    const val C_BASIC_GET_SUPPORTED_FEATURES_NEXT = 2
    const val C_BASIC_GET_SERIAL_NUMBER = 3
    const val C_BASIC_GET_VARIANT = 4
    const val C_BASIC_GET_APPLICATION_VERSION = 5
    const val C_BASIC_REGISTER_NOTIFICATION = 7
    const val C_BASIC_CANCEL_NOTIFICATION = 8
    const val C_BASIC_GET_EARBUD_COLOR = 18
    const val C_BASIC_GET_EARBUD_LANG = 19
    const val C_BASIC_GET_EARBUD_SN_L = 20
    const val C_BASIC_GET_EARBUD_SN_R = 21
    const val C_BASIC_GET_TWS_CONNECTION_STATUS = 22

    // ============================================================
    // ANC V1(2)
    // ============================================================
    const val C_ANC1_GET_ANC_STATE = 1
    const val C_ANC1_SET_ANC_STATE = 2
    const val C_ANC1_GET_NUM_ANC_MODES = 3
    const val C_ANC1_GET_CURRENT_ANC_MODE = 4
    const val C_ANC1_SET_ANC_MODE = 5
    const val C_ANC1_GET_CONFIGURED_LEAKTHROUGH_GAIN = 6
    const val C_ANC1_SET_LEAKTHROUGH_GAIN = 7

    // ============================================================
    // AUDIO_CURATION(8)
    // ============================================================
    const val C_AC_GET_AC_STATE = 0
    const val C_AC_SET_AC_STATE = 1
    const val C_AC_GET_MODES_COUNT = 2
    const val C_AC_GET_CURRENT_MODE = 3
    const val C_AC_SET_MODE = 4
    const val C_AC_GET_GAIN = 5
    const val C_AC_SET_GAIN = 6
    const val C_AC_GET_TOGGLE_CONFIGURATION_COUNT = 7
    const val C_AC_GET_TOGGLE_CONFIGURATION = 8
    const val C_AC_SET_TOGGLE_CONFIGURATION = 9
    const val C_AC_GET_SCENARIO_CONFIGURATION = 10
    const val C_AC_SET_SCENARIO_CONFIGURATION = 11
    const val C_AC_GET_DEMO_SUPPORT = 12
    const val C_AC_GET_DEMO_STATE = 13
    const val C_AC_SET_DEMO_STATE = 14
    const val C_AC_GET_ADAPTATION_STATE = 15
    const val C_AC_SET_ADAPTATION = 16
    const val C_AC_GET_LEAKTHROUGH_GAIN_CONFIGURATION = 17
    const val C_AC_GET_LEAKTHROUGH_GAIN_STEP = 18
    const val C_AC_SET_LEAKTHROUGH_GAIN_STEP = 19
    const val C_AC_GET_LEFT_RIGHT_BALANCE = 20
    const val C_AC_SET_LEFT_RIGHT_BALANCE = 21
    const val C_AC_GET_WIND_NOISE_REDUCTION_SUPPORT = 22
    const val C_AC_GET_WIND_NOISE_DETECTION_STATE = 23
    const val C_AC_SET_WIND_NOISE_DETECTION_STATE = 24
    const val C_AC_GET_AUTO_TRANSPARENCY_SUPPORT = 25
    const val C_AC_GET_AUTO_TRANSPARENCY_STATE = 26
    const val C_AC_SET_AUTO_TRANSPARENCY_STATE = 27
    const val C_AC_GET_AUTO_TRANSPARENCY_RELEASE_TIME = 28
    const val C_AC_SET_AUTO_TRANSPARENCY_RELEASE_TIME = 29
    const val C_AC_GET_HOWLING_DETECTION_SUPPORT = 30
    const val C_AC_GET_HOWLING_DETECTION_STATE = 31
    const val C_AC_SET_HOWLING_DETECTION_STATE = 32
    const val C_AC_GET_FEEDBACK_GAIN = 33
    const val C_AC_GET_NOISE_ID_SUPPORT = 34
    const val C_AC_GET_NOISE_ID_STATE = 35
    const val C_AC_SET_NOISE_ID_STATE = 36
    const val C_AC_GET_NOISE_ID_CATEGORY = 37
    const val C_AC_GET_ADVERSE_ACOUSTIC_HANDLER_SUPPORT = 38
    const val C_AC_GET_ADVERSE_ACOUSTIC_HANDLER_STATE = 39
    const val C_AC_SET_ADVERSE_ACOUSTIC_HANDLER_STATE = 40
    const val C_AC_GET_CURRENT_ANC_SWITCH_CONF = 41
    const val C_AC_SET_ANC_SWITCH_CONF = 42

    // ============================================================
    // BATTERY(13)
    // ============================================================
    /** 老固件（GAIA V3 / GA2 等）查询电量用 cmd=1 */
    const val C_BATT_GET_BATTERY_LEVELS = 1
    /** 布丁 PUDDING 等 GAIA V4 固件用 cmd=0（`00 1D 1A 00`） */
    const val C_BATT_GET_BATTERY_LEVELS_V4 = 0
    const val C_BATT_GET_SUPPORTED_BATTERIES = 0

    /**
     * 电池类型 ID。
     *
     * ⚠ 水月雨固件存在两种上报形态：
     *   · 分体上报：1=左耳、2=右耳、3=充电盒
     *   · **单设备上报：0=单设备（整机电量）** —— 实测 EDGE 等机型只回 type 0
     * 只认 1/2 的解析器在这种机型上会出现「某一侧不显示」。
     */
    const val BATTERY_SINGLE = 0
    const val BATTERY_LEFT = 1
    const val BATTERY_RIGHT = 2
    const val BATTERY_CASE = 3
    /** 固件表示「该组件无数据」。 */
    const val BATTERY_LEVEL_UNKNOWN = 255

    // ============================================================
    // CODEC_TYPE(16)：蓝牙编解码
    // ============================================================
    const val C_CODEC_GET_LC3_STATE = 1
    const val C_CODEC_GET_LDAC_STATE = 2
    const val C_CODEC_SET_LC3_STATE = 3
    const val C_CODEC_SET_LDAC_STATE = 4
    const val C_CODEC_GET_LHDC_STATE = 5
    const val C_CODEC_SET_LHDC_STATE = 6

    // ============================================================
    // TOUCHV2(22)：触控手势（feature 11 GESTURE_CONFIGURATION 本耳机不使用）
    //
    // ✅ 真机实测（MOONDROP Pudding）：读写都是**一份 5 字节配置**，
    //    字节顺序 = 槽位顺序：b0=单击 b1=双击 b2=三击 b3=长按1秒 b4=长按3秒。
    //
    //      READ   TX 00 1D 2C 02
    //             RX 00 1D 2D 02 <b0> <b1> <b2> <b3> <b4>
    //      WRITE  TX 00 1D 2C 03 <b0> <b1> <b2> <b3> <b4>
    //             RX echo 00 1D 2D 03 <同样 5 字节>
    //
    //    ⚠ **写入必须带全部 5 字节**：固件把 5 个槽位当作一份完整配置，
    //      只发一个槽位会把其余槽位写坏（与提示音 VoiceConf 同一个坑）。
    //
    //    ⚠ 观测到的帧来自官方 App 的**左耳**页面。是否存在右耳对应帧
    //      （多一个参数/索引、独立命令，或按触控板区分）**尚未确认**；
    //      在没有证据之前 UI 不提供左右耳选择器（见 ui/GesturePage.kt）。
    // ============================================================
    /** 读手势配置（cmd 2），回包 payload = 5 个槽位 */
    const val C_TOUCHV2_GET_ACTION_CONF = 2
    /** 写手势配置（cmd 3），payload = 5 个槽位（必须全量） */
    const val C_TOUCHV2_SET_ACTION_CONF = 3

    /** 手势配置 payload 长度：单击/双击/三击/长按1秒/长按3秒 = 5 */
    const val TOUCHV2_CONF_SIZE = 5

    /** 槽位数量（= [GestureSlot] 的条目数，就是 payload 的字节数） */
    const val GESTURE_SLOT_COUNT = TOUCHV2_CONF_SIZE

    /**
     * 动作 id `0x00` = 无 / 未定义。
     *
     * 真机读写 1:1 对齐确认的动作 id **只有这 5 条**（见 [TouchActions.ALL]）：
     * `0x00` 无 / 未定义、`0x11` 播放 / 暂停、`0x23` 上一曲、
     * `0x66` 语音助手、`0x77` 降噪切换。
     * 完整动作表仍在从官方 App 的 dex（Qualcomm `TouchNewInfo` / TouchV2 枚举）提取，
     * 因此这里**只播种已确认的 id，不臆造任何新 id**。
     */
    const val TOUCH_ACTION_NONE = 0x00

    // ============================================================
    // VOICE(14)：提示音（voice prompt）
    //
    // ✅ 命令号与 payload 已由**官方 App 自身 gaiaclient 的日志**确认
    //    （2026-09-14 真机抓取，Xiaomi Pad 8 Pro）：
    //
    //      V3VoicePlugin: fetchVoiceConf
    //      [V3VoicePlugin->onResponse] command=1, data=[1, 20, 1], size=3
    //      VoiceRepositoryData: updateV2VoiceConf: enabled=true, volume=20, index=1
    //      V3VoicePlugin: setVoiceConf
    //      [V3VoicePlugin->onResponse] command=2, data=[0, 82, 1], size=3
    //
    //    → GET = cmd 1，SET = cmd 2
    //    → payload(V2, size>=3) = [enabled(0/1)][volume(0..100)][index]
    //    → ⚠ **没有**独立的音量命令：音量是同一份配置里的一个字段。
    //      之前按「GET=1/SET=2 开关 + GET=3/SET=4 音量」的猜测是错的。
    // ============================================================
    const val C_VOICE_GET_CONF = 1
    const val C_VOICE_SET_CONF = 2

    // 兼容旧命名（都指向同一对命令）
    const val C_VOICE_GET_ENABLE = C_VOICE_GET_CONF
    const val C_VOICE_SET_ENABLE = C_VOICE_SET_CONF
    const val C_VOICE_GET_VOLUME = C_VOICE_GET_CONF
    const val C_VOICE_SET_VOLUME = C_VOICE_SET_CONF

    /** V2 配置 payload 长度：`[enabled][volume][index]` */
    const val VOICE_CONF_V2_SIZE = 3

    /** 提示音音量的取值范围（百分比）。 */
    const val VOICE_VOLUME_MAX = 100

    // ============================================================
    // ONEBRINGTWO(20)：双设备连接 / 一拖二
    //
    // 命令号已由 MegaSuite/moondrop-link-desktop 在真机（水月雨 EDGE）上确认，
    // 与官方 App 内嵌 gaiaclient 的 V3OnebringtwoPlugin 一致：
    //   读状态 `00 1D 28 01`，写状态 `00 1D 28 02 <0|1>`
    // ============================================================
    /** 读双设备连接开关 */
    const val C_OBT_GET_STATE = 1
    /** 写双设备连接开关，payload = [0|1] */
    const val C_OBT_SET_STATE = 2
    /** 读「暂停回连」超时秒数 */
    const val C_OBT_GET_TIMEOUT = 3
    /** 写「暂停回连」超时秒数，payload = [seconds] */
    const val C_OBT_SET_TIMEOUT = 4
    /** 读已连接设备列表（首页） */
    const val C_OBT_GET_DEVICES = 5
    /** 读已连接设备列表（翻页） */
    const val C_OBT_GET_DEVICES_NEXT = 6
    /** 断开某个已连接设备，payload = [num:1][addr:6][name utf8] */
    const val C_OBT_DISCONNECT = 7

    /** 兼容别名（旧命名） */
    const val C_OBT_GET_ENABLE = C_OBT_GET_STATE
    const val C_OBT_SET_ENABLE = C_OBT_SET_STATE

    // ============================================================
    // 其它功能
    // ============================================================
    const val C_DAC_GET_GAIN = 1
    const val C_DAC_SET_GAIN = 2
    const val C_SPATIAL_GET_STATE = 1
    const val C_SPATIAL_SET_STATE = 2
    const val C_SPATIAL_GET_HEAD_TRACKING = 3
    const val C_SPATIAL_SET_HEAD_TRACKING = 4
    const val C_LED_GET_STATE = 1
    const val C_LED_SET_STATE = 2
    const val C_DYBASS_GET_STATE = 1
    const val C_DYBASS_SET_STATE = 2
    const val C_LR_GET_REVERSED = 1
    const val C_LR_SET_REVERSED = 2
    const val C_POWER_SET_PWR_OFF = 1

    // ANC V2(32)
    const val C_ANC2_GET_CURRENT_MODE = 3
    const val C_ANC2_SET_CURRENT_MODE = 4
    const val C_ANC2_GET_CURRENT_ANC_SWITCH_CONF = 41
    const val C_ANC2_SET_ANC_SWITCH_CONF = 42

    // ANC V2 模式枚举（官方 AncV2Handler）
    const val ANC2_OFF = 0
    const val ANC2_ON = 1
    const val ANC2_TRANSPARENT = 2
    const val ANC2_ANTI_WIND = 3
    const val ANC2_ADAPTIVE = 4
    const val ANC2_LIVE = 5

    // 布丁 PUDDING（GAIA V4）专用 ANC V2 枚举：0=关 1=自适应 2=通透 3=抗风 4=基础降噪
    const val PUDDING_ANC_OFF = 0
    const val PUDDING_ANC_ADAPTIVE = 1
    const val PUDDING_ANC_TRANSPARENT = 2
    const val PUDDING_ANC_ANTI_WIND = 3
    const val PUDDING_ANC_BASIC_NC = 4

    // ============================================================
    // ANC 路径
    // ============================================================
    const val ANC_PATH_UNKNOWN = -1
    const val ANC_PATH_ANC_V1 = 2
    const val ANC_PATH_AUDIO_CURATION = 8
    const val ANC_PATH_ANC_V2 = 32

    // ============================================================
    // 帧编解码
    // ============================================================

    /** 构造 GAIA 命令帧。payload 为 null 表示无 payload。 */
    fun command(feature: Int, command: Int, payload: ByteArray? = null): ByteArray =
        packet(VENDOR_QUALCOMM, feature, TYPE_COMMAND, command, payload)

    /** 构造任意类型的 GAIA 帧。 */
    fun packet(
        vendor: Int,
        feature: Int,
        type: Int,
        command: Int,
        payload: ByteArray? = null,
    ): ByteArray {
        val cmdValue = (feature shl 9) or (type shl 7) or (command and 0x7F)
        val p = payload ?: EMPTY
        val out = ByteArray(4 + p.size)
        out[0] = ((vendor shr 8) and 0xFF).toByte()
        out[1] = (vendor and 0xFF).toByte()
        out[2] = ((cmdValue shr 8) and 0xFF).toByte()
        out[3] = (cmdValue and 0xFF).toByte()
        if (p.isNotEmpty()) System.arraycopy(p, 0, out, 4, p.size)
        return out
    }

    /** 解析后的 GAIA 帧。 */
    data class Frame(
        val vendor: Int,
        val feature: Int,
        val type: Int,
        val command: Int,
        val payload: ByteArray,
    ) {
        val isResponse: Boolean get() = type == TYPE_RESPONSE
        val isNotification: Boolean get() = type == TYPE_NOTIFICATION

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return vendor == other.vendor && feature == other.feature &&
                type == other.type && command == other.command &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var r = vendor
            r = 31 * r + feature
            r = 31 * r + type
            r = 31 * r + command
            r = 31 * r + payload.contentHashCode()
            return r
        }

        override fun toString(): String =
            "Frame(vendor=0x${vendor.toString(16)}, feature=$feature, " +
                "type=$type, cmd=0x${command.toString(16)}, payload=${hex(payload)})"
    }

    /**
     * 解析一个完整的 GAIA PDU。
     * @return null 表示不是 GAIA 帧（vendor 不匹配或长度不足）
     */
    fun parse(pdu: ByteArray?): Frame? {
        if (pdu == null || pdu.size < 4) return null
        val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
        if (vendor != VENDOR_QUALCOMM) return null
        val cmdValue = ((pdu[2].toInt() and 0xFF) shl 8) or (pdu[3].toInt() and 0xFF)
        val payload = if (pdu.size > 4) pdu.copyOfRange(4, pdu.size) else EMPTY
        return Frame(
            vendor = vendor,
            feature = (cmdValue shr 9) and 0x7F,
            type = (cmdValue shr 7) and 0x03,
            command = cmdValue and 0x7F,
            payload = payload,
        )
    }

    val EMPTY: ByteArray = ByteArray(0)

    // ============================================================
    // RFCOMM / SPP 传输封装
    //
    // 实测（布丁 PUDDING，官方 App logcat 2026-09-14）：App→耳机 的 GAIA 帧外面还有一层
    // 官方 TransportProtocol 的 RFCOMM 传输头：
    //     FF | Version(0x04) | Flags(0x00) | Length
    //   Length = PDU 中 **4 字节 GAIA 头之后的 payload 字节数**，即 pdu.size - 4。
    //   实例：`SendingThread: sendData: bytes = [-1,4,0,3, 0,29,28,2,1,20,1]`
    //        = FF 04 00 03 | 00 1D 1C 02 01 14 01   （GAIA 帧 7 字节 = 4 + 3）
    //
    // 另一方面 FxxkMoondrop 对布丁是**直接发裸 PDU** 且可工作，设备对响应还会"裸 PDU + FF 帧"双发。
    // 因此这里两种都支持，由 MoondropLink 在连接时探测并记忆（见 probeRfcommFraming）。
    // ============================================================
    const val RFCOMM_SOF = 0xFF
    const val RFCOMM_VERSION = 0x04
    const val RFCOMM_HEADER_SIZE = 4

    /** 给 GAIA PDU 套上官方 RFCOMM 传输头：`FF 04 00 <len>`。 */
    fun wrapRfcomm(pdu: ByteArray): ByteArray {
        val len = (pdu.size - RFCOMM_HEADER_SIZE).coerceAtLeast(0)
        val out = ByteArray(RFCOMM_HEADER_SIZE + pdu.size)
        out[0] = RFCOMM_SOF.toByte()
        out[1] = RFCOMM_VERSION.toByte()
        out[2] = 0x00
        out[3] = (len and 0xFF).toByte()
        System.arraycopy(pdu, 0, out, RFCOMM_HEADER_SIZE, pdu.size)
        return out
    }

    fun hex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    // ============================================================
    // 命令构造：噪声控制
    // ============================================================

    /** 查询 ANC V2 当前模式（feature 32 / cmd 3） */
    fun ancV2GetMode(): ByteArray = command(F_ANC_V2, C_ANC2_GET_CURRENT_MODE)

    /** 设置 ANC V2 模式（feature 32 / cmd 4） */
    fun ancV2SetMode(deviceMode: Int): ByteArray =
        command(F_ANC_V2, C_ANC2_SET_CURRENT_MODE, byteArrayOf(deviceMode.toByte()))

    /** 查询 AudioCuration 当前模式（feature 8 / cmd 3） */
    fun audioCurationGetMode(): ByteArray = command(F_AUDIO_CURATION, C_AC_GET_CURRENT_MODE)

    /** 设置 AudioCuration 模式（feature 8 / cmd 4） */
    fun audioCurationSetMode(deviceMode: Int): ByteArray =
        command(F_AUDIO_CURATION, C_AC_SET_MODE, byteArrayOf(deviceMode.toByte()))

    /** 查询 ANC V1 开关状态（feature 2 / cmd 1） */
    fun ancV1GetState(): ByteArray = command(F_ANC, C_ANC1_GET_ANC_STATE)

    /** 设置 ANC V1 开关（feature 2 / cmd 2） */
    fun ancV1SetState(state: Int): ByteArray =
        command(F_ANC, C_ANC1_SET_ANC_STATE, byteArrayOf(state.toByte()))

    // ============================================================
    // 命令构造：电量
    // ============================================================

    /**
     * 查询电量（列出要查询的电池 ID）。
     *
     * 关键：**必须把左耳、右耳、充电盒一起列进 payload**。
     * 只查询左耳时固件只会回左耳，这正是「右耳电量不显示」的一种成因。
     */
    fun batteryGet(ids: IntArray = intArrayOf(BATTERY_LEFT, BATTERY_RIGHT, BATTERY_CASE)): ByteArray =
        command(F_BATTERY, C_BATT_GET_BATTERY_LEVELS, ByteArray(ids.size) { ids[it].toByte() })

    /** 老固件（不列 ID）的电量查询：cmd=0，无 payload —— 布丁 PUDDING 的 `00 1D 1A 00`。 */
    fun batteryGetAllV4(): ByteArray = command(F_BATTERY, C_BATT_GET_BATTERY_LEVELS_V4)

    /** 老固件（不列 ID）的电量查询：cmd=1，无 payload —— GA2 等 GAIA V3 设备。 */
    fun batteryGetAll(): ByteArray = command(F_BATTERY, C_BATT_GET_BATTERY_LEVELS)

    // ============================================================
    // 命令构造：增益 / 指示灯 / 空间音频
    // ============================================================
    fun gainGet(): ByteArray = command(F_DAC_GAIN, C_DAC_GET_GAIN)
    fun gainSet(deviceLevel: Int): ByteArray =
        command(F_DAC_GAIN, C_DAC_SET_GAIN, byteArrayOf(deviceLevel.toByte()))

    fun ledGet(): ByteArray = command(F_LED, C_LED_GET_STATE)
    fun ledSet(state: Int): ByteArray = command(F_LED, C_LED_SET_STATE, byteArrayOf(state.toByte()))

    fun spatialGet(): ByteArray = command(F_SPATIAL_AUDIO, C_SPATIAL_GET_STATE)
    fun spatialSet(state: Int): ByteArray =
        command(F_SPATIAL_AUDIO, C_SPATIAL_SET_STATE, byteArrayOf(state.toByte()))

    fun headTrackingGet(): ByteArray = command(F_SPATIAL_AUDIO, C_SPATIAL_GET_HEAD_TRACKING)
    fun headTrackingSet(state: Int): ByteArray =
        command(F_SPATIAL_AUDIO, C_SPATIAL_SET_HEAD_TRACKING, byteArrayOf(state.toByte()))

    // ============================================================
    // 命令构造：蓝牙编解码 / LHDC
    // ============================================================

    fun lhdcGet(): ByteArray = command(F_CODEC_TYPE, C_CODEC_GET_LHDC_STATE)
    fun lhdcSet(enabled: Boolean): ByteArray =
        command(F_CODEC_TYPE, C_CODEC_SET_LHDC_STATE, byteArrayOf(if (enabled) 1 else 0))

    fun ldacGet(): ByteArray = command(F_CODEC_TYPE, C_CODEC_GET_LDAC_STATE)
    fun ldacSet(enabled: Boolean): ByteArray =
        command(F_CODEC_TYPE, C_CODEC_SET_LDAC_STATE, byteArrayOf(if (enabled) 1 else 0))

    fun lc3Get(): ByteArray = command(F_CODEC_TYPE, C_CODEC_GET_LC3_STATE)
    fun lc3Set(enabled: Boolean): ByteArray =
        command(F_CODEC_TYPE, C_CODEC_SET_LC3_STATE, byteArrayOf(if (enabled) 1 else 0))

    // ============================================================
    // 命令构造：提示音 / 双设备连接（命令号可被型号档案覆盖）
    // ============================================================

    /** 读提示音配置（cmd 1）→ 回包 payload = `[enabled, volume, index]` */
    fun voiceGetConf(cmdGet: Int = C_VOICE_GET_CONF): ByteArray = command(F_VOICE, cmdGet)

    /**
     * 写提示音配置（cmd 2），payload = `[enabled, volume, index]`（V2 格式）。
     *
     * 必须一次把三个字段都发出去：固件把开关/音量/索引当作**一份完整配置**，
     * 只发一个字节会把另外两个字段写坏（这正是"改音量把开关关了"类问题的来源）。
     */
    fun voiceSetConf(
        enabled: Boolean,
        volumePercent: Int,
        index: Int,
        cmdSet: Int = C_VOICE_SET_CONF,
    ): ByteArray = command(
        F_VOICE, cmdSet,
        byteArrayOf(
            (if (enabled) 1 else 0).toByte(),
            volumePercent.coerceIn(0, VOICE_VOLUME_MAX).toByte(),
            (index and 0xFF).toByte(),
        ),
    )

    /** 提示音配置。 */
    data class VoiceConf(
        val enabled: Boolean,
        /** 0..100 */
        val volume: Int,
        /** 提示音索引（语言/主题） */
        val index: Int,
        /** 是否为 V2 三字节格式 */
        val v2: Boolean,
    )

    /** 解析提示音配置回包；兼容 V2(3B) / 2B / 1B 三种长度。 */
    fun parseVoiceConf(payload: ByteArray?): VoiceConf? {
        val p = payload ?: return null
        return when {
            p.size >= VOICE_CONF_V2_SIZE -> VoiceConf(
                (p[0].toInt() and 0xFF) != 0,
                (p[1].toInt() and 0xFF).coerceIn(0, VOICE_VOLUME_MAX),
                p[2].toInt() and 0xFF,
                true,
            )
            p.size == 2 -> VoiceConf((p[0].toInt() and 0xFF) != 0, p[1].toInt() and 0xFF, 0, false)
            p.size == 1 -> VoiceConf((p[0].toInt() and 0xFF) != 0, 0, 0, false)
            else -> null
        }
    }

    fun dualConnectionGet(cmdGet: Int = C_OBT_GET_STATE): ByteArray =
        command(F_ONEBRINGTWO, cmdGet)

    /** 写双设备连接开关 */
    fun dualConnectionSet(enabled: Boolean, cmdSet: Int = C_OBT_SET_STATE): ByteArray =
        command(F_ONEBRINGTWO, cmdSet, byteArrayOf(if (enabled) 1 else 0))

    /** 读已连接设备列表（首页 / 翻页） */
    fun dualConnectionGetDevices(next: Boolean = false): ByteArray =
        command(F_ONEBRINGTWO, if (next) C_OBT_GET_DEVICES_NEXT else C_OBT_GET_DEVICES)

    /** 断开某个已连接设备。@param address "AA:BB:CC:DD:EE:FF" */
    fun dualConnectionDisconnect(num: Int, address: String, name: String = ""): ByteArray? {
        val parts = address.split(":")
        if (parts.size != 6) return null
        val addr = ByteArray(6)
        for (i in 0 until 6) {
            addr[i] = (parts[i].toIntOrNull(16) ?: return null).toByte()
        }
        val nb = name.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + 6 + nb.size)
        payload[0] = num.toByte()
        System.arraycopy(addr, 0, payload, 1, 6)
        System.arraycopy(nb, 0, payload, 7, nb.size)
        return command(F_ONEBRINGTWO, C_OBT_DISCONNECT, payload)
    }

    /**
     * 解析已连接设备条目：`[num:1][addr:6][name utf8...]`
     * @return Triple(num, "AA:BB:..", name)，长度不足返回 null
     */
    fun parseLinkedDevice(payload: ByteArray?): Triple<Int, String, String>? {
        val p = payload ?: return null
        if (p.size < 7) return null
        val num = p[0].toInt() and 0xFF
        val addr = (1..6).joinToString(":") { "%02X".format(p[it].toInt() and 0xFF) }
        val name = String(p, 7, p.size - 7, Charsets.UTF_8)
        return Triple(num, addr, name)
    }

    // ============================================================
    // 命令构造：手势（TOUCHV2）
    // ============================================================

    /** 读手势配置（feature 22 / cmd 2）→ 回包 payload = `[单击,双击,三击,长按1秒,长按3秒]` */
    fun touchV2GetConf(): ByteArray = command(F_TOUCHV2, C_TOUCHV2_GET_ACTION_CONF)

    /**
     * 写手势配置（feature 22 / cmd 3）。**必须是完整 5 字节**（见 TOUCHV2 段落）。
     *
     * 长度不符时直接抛 [IllegalArgumentException]，而不是补齐/截断：静默补 0
     * 会把用户没动过的槽位写成「无」，属于破坏性写入。调用方在不确定其余槽位时
     * 应当先 [touchV2GetConf] 读回来（见 MoondropLink.setGesture）。
     */
    fun touchV2SetConf(bytes: ByteArray): ByteArray {
        require(bytes.size == TOUCHV2_CONF_SIZE) {
            "TOUCHV2 配置必须恰好 $TOUCHV2_CONF_SIZE 字节（收到 ${bytes.size}）：${hex(bytes)}"
        }
        return command(F_TOUCHV2, C_TOUCHV2_SET_ACTION_CONF, bytes)
    }

    /**
     * 手势槽位。**index 即 payload 里的字节位置**（顺序 = 实测字节顺序）。
     *
     * ⚠ 「长按1秒」与「长按3秒」是两个**独立**槽位（实测 payload 就是 5 个独立字节，
     *   读回时长按3秒是 `0x00` = 无）。用户反馈官方 App UI 里两者看似互斥
     *   （「冲突的，我手动修改了一次」），但**本模块不硬编码互斥**：
     *   没有证据证明固件禁止两者同时配置，就不能替用户禁掉一种可能合法的组合。
     */
    enum class GestureSlot(val index: Int, val labelZh: String, val labelEn: String) {
        SINGLE_TAP(0, "单击", "Single tap"),
        DOUBLE_TAP(1, "双击", "Double tap"),
        TRIPLE_TAP(2, "三击", "Triple tap"),
        LONG_PRESS_1S(3, "长按1秒", "Long press 1s"),
        LONG_PRESS_3S(4, "长按3秒", "Long press 3s"),
    }

    /** 一个手势动作：id + 双语标签 + 可选 i18n 资源名（UI 侧取 strings.xml 用）。 */
    data class TouchAction(
        val id: Int,
        val labelZh: String,
        val labelEn: String,
        /** strings.xml 里的资源名；null = 暂无本地化条目（UI 回落到 [labelZh]）。 */
        val i18nKey: String? = null,
    )

    /**
     * 手势动作表 —— **数据驱动的唯一来源**，新增动作只在这里加一行。
     *
     * ⚠ **这是部分表（partial）**：只收录真机对齐过的 id。未映射的 id 由
     *   [matchOrUnknown] 显示为 `未知(0x..)`，不会被静默吞掉变成空白。
     *   完整枚举由兄弟任务从官方 App dex 提取（Qualcomm TouchNewInfo / TouchV2）。
     */
    object TouchActions {

        /** 无 / 未定义（该槽位不响应任何手势） */
        const val NONE = TOUCH_ACTION_NONE
        /** 播放 / 暂停 */
        const val PLAY_PAUSE = 0x11
        /** 上一曲 */
        const val PREVIOUS_TRACK = 0x23
        /** 语音助手 */
        const val VOICE_ASSISTANT = 0x66
        /** 降噪切换 */
        const val ANC_SWITCH = 0x77

        /** 顺序即 UI 下拉列表顺序；[NONE] 放最前（= 不响应）。 */
        val ALL: List<TouchAction> = listOf(
            TouchAction(NONE, "无 / 未定义", "None / undefined", "gesture_action_none"),
            TouchAction(PLAY_PAUSE, "播放 / 暂停", "Play / Pause", "gesture_action_play_pause"),
            TouchAction(
                PREVIOUS_TRACK, "上一曲", "Previous track", "gesture_action_previous_track",
            ),
            TouchAction(
                VOICE_ASSISTANT, "语音助手", "Voice assistant", "gesture_action_voice_assistant",
            ),
            TouchAction(ANC_SWITCH, "降噪切换", "Noise control switch", "gesture_action_anc_switch"),
        )

        /** 按 id 取动作；未映射返回 null。 */
        fun byId(id: Int): TouchAction? {
            val v = id and 0xFF
            return ALL.firstOrNull { it.id == v }
        }

        /** 已映射动作的中文标签；未映射返回 null。 */
        fun labelOf(id: Int): String? = byId(id)?.labelZh

        /** 未映射的 id 也照实显示，避免「空白 = 看起来没配置」的误导。 */
        fun matchOrUnknown(id: Int): String {
            val a = byId(id)
            if (a != null) return a.labelZh
            return "未知(0x%02X)".format(id and 0xFF)
        }
    }

    /**
     * 手势配置：5 个槽位，顺序同 [GestureSlot]。
     *
     * IntArray 的 equals/hashCode 是按**引用**的，这里覆写成按**内容**比较：
     * 否则两份取值相同的配置在 State/事件比较里会被当成「变了」，UI 会反复重组。
     */
    data class GestureConf(val slots: IntArray) {

        val size: Int get() = slots.size

        /** 取某个槽位的动作 id。 */
        operator fun get(slot: GestureSlot): Int = slots[slot.index]

        /** 返回「替换了一个槽位」的新配置（不改自身）。 */
        fun with(slot: GestureSlot, actionId: Int): GestureConf {
            val next = slots.copyOf()
            next[slot.index] = actionId and 0xFF
            return GestureConf(next)
        }

        /** 转成下发用的 payload（长度 = [TOUCHV2_CONF_SIZE]）。 */
        fun toPayload(): ByteArray = ByteArray(slots.size) { slots[it].toByte() }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GestureConf) return false
            return slots.contentEquals(other.slots)
        }

        override fun hashCode(): Int = slots.contentHashCode()

        override fun toString(): String =
            "GestureConf(${slots.joinToString(" ") { "%02X".format(it and 0xFF) }})"
    }

    /**
     * 解析手势配置回包。
     *
     * 长度不足 [TOUCHV2_CONF_SIZE] 返回 null；**超过 5 字节时取前 5 字节**：
     * 多出来的字节目前无法解释（固件版本差异？左右耳索引？——未确认），
     * 因此不据此推断任何语义，也不用它去改槽位映射。
     */
    fun parseGestureConf(payload: ByteArray?): GestureConf? {
        val p = payload ?: return null
        if (p.size < TOUCHV2_CONF_SIZE) return null
        return GestureConf(IntArray(TOUCHV2_CONF_SIZE) { p[it].toInt() and 0xFF })
    }

    // ============================================================
    // 命令构造：其它
    // ============================================================
    fun basicGetSupportedFeatures(): ByteArray = command(F_BASIC, C_BASIC_GET_SUPPORTED_FEATURES)
    fun basicGetSupportedFeaturesNext(): ByteArray = command(F_BASIC, C_BASIC_GET_SUPPORTED_FEATURES_NEXT)
    fun basicGetVariant(): ByteArray = command(F_BASIC, C_BASIC_GET_VARIANT)
    fun basicGetAppVersion(): ByteArray = command(F_BASIC, C_BASIC_GET_APPLICATION_VERSION)
    fun basicGetGaiaVersion(): ByteArray = command(F_BASIC, C_BASIC_GET_GAIA_VERSION)
    fun basicGetSerialNumber(): ByteArray = command(F_BASIC, C_BASIC_GET_SERIAL_NUMBER)
    fun basicGetTwsStatus(): ByteArray = command(F_BASIC, C_BASIC_GET_TWS_CONNECTION_STATUS)
    fun basicGetEarbudLang(): ByteArray = command(F_BASIC, C_BASIC_GET_EARBUD_LANG)

    /** 注册/取消通知（GAIA V3：让设备主动推送电量等变化） */
    fun registerNotification(feature: Int, enabled: Boolean): ByteArray =
        command(
            F_BASIC,
            if (enabled) C_BASIC_REGISTER_NOTIFICATION else C_BASIC_CANCEL_NOTIFICATION,
            byteArrayOf(feature.toByte()),
        )

    fun powerOff(): ByteArray = command(F_POWER_CONTROL, C_POWER_SET_PWR_OFF)

    // ============================================================
    // GAIA 版本探测（V1/V2 包，vendor 0x000A）
    // 真机确认：先发 `00 0A 03 00`，若设备 GAIA 版本为 3，后续功能命令才用 vendor 0x001D。
    // ============================================================
    const val VENDOR_CSR = 0x000A
    const val CMD_GET_API_VERSION = 0x0300

    fun getApiVersion(): ByteArray = byteArrayOf(0x00, 0x0A, 0x03, 0x00)

    // ============================================================
    // AudioCuration SET_MODE 的位掩码语义（真机确认）
    //   1 -> 关闭降噪(off)，2 -> 主动降噪(anc)，4 -> 通透(transparent)
    // ============================================================
    val AC_SET_PAYLOAD = intArrayOf(0x01, 0x02, 0x04)

    /** 用 AC 状态索引（0=关/1=降噪/2=通透）构造 SET_MODE */
    fun audioCurationSetStateIndex(stateIndex: Int): ByteArray? {
        if (stateIndex !in AC_SET_PAYLOAD.indices) return null
        return command(F_AUDIO_CURATION, C_AC_SET_MODE, byteArrayOf(AC_SET_PAYLOAD[stateIndex].toByte()))
    }

    /**
     * ANC V2 的 5 字节开关配置：`[STATE][ANC_ON][ANC_OFF][TRANSPARENT][ORDER]`
     * 前 4 字节 0=禁用 / 1=启用；末字节为循环顺序。
     */
    fun ancV2SwitchConf(state: Int, ancOn: Int, ancOff: Int, transparent: Int, order: Int): ByteArray =
        command(F_ANC_V2, C_ANC2_SET_ANC_SWITCH_CONF,
            byteArrayOf(state.toByte(), ancOn.toByte(), ancOff.toByte(), transparent.toByte(), order.toByte()))

    fun ancV2GetSwitchConf(): ByteArray = command(F_ANC_V2, C_ANC2_GET_CURRENT_ANC_SWITCH_CONF)

    /**
     * 解析 `GET_SUPPORTED_FEATURES` 响应。
     *
     * ⚠ 上游两派对响应体的解读不一致，且**互斥**：
     *   · moondrop-link-desktop（真机跑通）：`[moreFlag:1][featureId:1][version:1]...`
     *   · FxxkMoondrop（官方 App 逆向）：32-bit word 位图，word i 覆盖 feature 32*i..32*i+31
     * 本实现**两种都试**：先按字节对解析，若得到的结果落在已知 feature 范围内则采用；
     * 否则回退位图。这样无论固件用哪种编码都能正确选择 ANC 路径。
     */
    data class FeatureEntry(val feature: Int, val version: Int)

    /** 按 `[more][featureId][version]...` 解析；长度不足以构成有序对时返回空。 */
    fun parseFeatureEntries(payload: ByteArray?): Pair<Boolean, List<FeatureEntry>> {
        val p = payload ?: return false to emptyList()
        if (p.size < 3) return false to emptyList()
        val more = (p[0].toInt() and 0x01) != 0
        val out = ArrayList<FeatureEntry>(8)
        var i = 1
        while (i + 1 < p.size) {
            val fid = p[i].toInt() and 0xFF
            val ver = p[i + 1].toInt() and 0xFF
            // feature id 落在 QTiL 已知范围内（0..63）才认为这是有效条目
            if (fid in 0..63) out.add(FeatureEntry(fid, ver)) else return false to emptyList()
            i += 2
        }
        return more to out
    }

    /**
     * 综合解析：优先字节对，失败回退位图。
     * @return 支持的能力集合
     */
    fun parseSupportedFeaturesSmart(payload: ByteArray?): Set<Int> {
        val (_, entries) = parseFeatureEntries(payload)
        if (entries.isNotEmpty()) return entries.map { it.feature }.toSet()
        return parseSupportedFeatures(payload)
    }

    /**
     * 解析 `GET_SUPPORTED_FEATURES` 的响应位图（备用解释）。
     * 响应 = 32-bit word 序列（大端），word i 覆盖 feature 32*i .. 32*i+31。
     */
    fun parseSupportedFeatures(payload: ByteArray?): Set<Int> {
        val p = payload ?: return emptySet()
        val features = HashSet<Int>()
        var wordIdx = 0
        var i = 0
        while (i + 3 < p.size) {
            val word = ((p[i].toInt() and 0xFF) shl 24) or
                ((p[i + 1].toInt() and 0xFF) shl 16) or
                ((p[i + 2].toInt() and 0xFF) shl 8) or
                (p[i + 3].toInt() and 0xFF)
            for (bit in 0 until 32) {
                if (word and (1 shl bit) != 0) features.add(wordIdx * 32 + bit)
            }
            wordIdx++
            i += 4
        }
        return features
    }

    /** 位图是否被截断（payload 长度不是 4 的倍数 → 末尾 feature word 丢失，不可据此判定能力）。 */
    fun isFeaturePayloadTruncated(payload: ByteArray?): Boolean =
        payload != null && payload.size % 4 != 0

    /** 由能力集合推导 ANC 路径（优先级：AudioCuration > ANC V2 > ANC V1）。 */
    fun ancPathFrom(features: Set<Int>): Int = when {
        F_AUDIO_CURATION in features -> ANC_PATH_AUDIO_CURATION
        F_ANC_V2 in features -> ANC_PATH_ANC_V2
        F_ANC in features -> ANC_PATH_ANC_V1
        else -> ANC_PATH_UNKNOWN
    }
}
