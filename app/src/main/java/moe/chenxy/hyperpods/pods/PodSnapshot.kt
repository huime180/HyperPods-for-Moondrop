/*
 * MiuixMoondrop — 运行时状态快照
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.chenxy.hyperpods.pods

import moe.chenxy.hyperpods.core.AncMode
import moe.chenxy.hyperpods.core.BATTERY_UNKNOWN
import moe.chenxy.hyperpods.core.Gaia

/** 连接后探测到的能力。凡是从位图/真实回包得到的信息都放这里。 */
data class PodCapabilities(
    /** GET_SUPPORTED_FEATURES 位图（可含多页） */
    val features: Set<Int> = emptySet(),
    /** 实际使用的 ANC 路径（探测结果优先于型号档案） */
    val ancPath: Int = Gaia.ANC_PATH_UNKNOWN,
    /** 设备真实上报的电池类型（cmd 0 的结果） */
    val batteryTypes: List<Int> = emptyList(),
    val hasGain: Boolean = false,
    val hasLed: Boolean = false,
    val hasSpatial: Boolean = false,
    val hasHeadTracking: Boolean = false,
    /** 提示音：能力位图含 F_VOICE 或收到过响应 */
    val hasPromptTone: Boolean = false,
    val hasPromptVolume: Boolean = false,
    /** LHDC 开关：位图含 F_CODEC_TYPE */
    val hasLhdc: Boolean = false,
    /** 双设备连接：位图含 F_ONEBRINGTWO */
    val hasDualConnection: Boolean = false,
    /**
     * 手势操作（feature 22 TOUCHV2）：位图含 F_TOUCHV2。
     *
     * ⚠ 只由**能力位图**决定（本机型不使用 feature 11 GESTURE_CONFIGURATION，
     * 也不做型号档案标记）。
     */
    val hasGestures: Boolean = false,
    /**
     * 低延迟模式。
     *
     * 这是 **HyperOS 系统侧功能**（系统蓝牙设备详情页的「低延迟」开关），
     * 不是 GAIA 命令；因此不依赖能力位图，而是由系统 A2DP 会话是否存在低延迟
     * 配置能力决定（见 LowLatencyBridge）。
     */
    val hasLowLatency: Boolean = false,
    /** 探测是否已经完成（避免 UI 在探测中途闪来闪去） */
    val probed: Boolean = false,
) {
    fun supports(feature: Int): Boolean = features.contains(feature)

    override fun equals(other: Any?): Boolean {
        if (other !is PodCapabilities) return false
        return features == other.features && ancPath == other.ancPath &&
            batteryTypes == other.batteryTypes && hasGain == other.hasGain &&
            hasLed == other.hasLed && hasSpatial == other.hasSpatial &&
            hasHeadTracking == other.hasHeadTracking && hasPromptTone == other.hasPromptTone &&
            hasPromptVolume == other.hasPromptVolume && hasLhdc == other.hasLhdc &&
            hasDualConnection == other.hasDualConnection && hasGestures == other.hasGestures &&
            hasLowLatency == other.hasLowLatency &&
            probed == other.probed
    }

    override fun hashCode(): Int = features.hashCode() * 31 + ancPath
}

/** 三路电量的可序列化快照（BatteryState 是可变累加器，对外只暴露快照）。 */
data class BatterySnapshot(
    val left: Int = BATTERY_UNKNOWN,
    val right: Int = BATTERY_UNKNOWN,
    val case: Int = BATTERY_UNKNOWN,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    /** 设备是否采用「单设备电量」形态（type 0） */
    val singleDevice: Boolean = false,
) {
    val leftKnown: Boolean get() = left != BATTERY_UNKNOWN
    val rightKnown: Boolean get() = right != BATTERY_UNKNOWN
    val caseKnown: Boolean get() = case != BATTERY_UNKNOWN
    val anyKnown: Boolean get() = leftKnown || rightKnown || caseKnown
    val allKnown: Boolean get() = leftKnown && rightKnown && caseKnown
}

/** UI 与系统集成层消费的唯一状态对象。 */
data class PodSnapshot(
    val deviceName: String = "",
    val deviceAddress: String = "",
    val modelId: String = "",
    val modelName: String = "",
    val modelVerified: Boolean = false,
    val connected: Boolean = false,
    /** 当前使用的传输层 */
    val transport: String = "",
    val battery: BatterySnapshot = BatterySnapshot(),
    /** 当前降噪档位在 ancModes 里的下标；-1 = 未知 */
    val ancIndex: Int = -1,
    val ancModes: List<AncMode> = emptyList(),
    /** 增益档位（UI 下标）；-1 = 未知 */
    val gainIndex: Int = -1,
    val gainLabels: List<String> = emptyList(),
    /** 指示灯；null = 未知 */
    val ledOn: Boolean? = null,
    /** 提示音开关；null = 未知 */
    val promptToneOn: Boolean? = null,
    /** 提示音音量：**0..100 百分比**（官方 App 日志实测单位）；-1 = 未知 */
    val promptVolumeRaw: Int = -1,
    val promptVolumeMax: Int = 100,
    /** 提示音索引（语言/主题） */
    val promptIndex: Int = 0,
    /** LHDC 开关；null = 未知。false 时按 AAC/LDAC 等基础编码工作。 */
    val lhdcOn: Boolean? = null,
    /** 当前系统 A2DP 实际协商到的编解码名（来自系统，用于与 LHDC 开关对照） */
    val activeCodec: String = "",
    /** 双设备连接；null = 未知 */
    val dualConnectionOn: Boolean? = null,
    /** 低延迟模式（HyperOS 系统侧）；null = 未知 */
    val lowLatencyOn: Boolean? = null,
    /**
     * 手势配置（TOUCHV2，5 个字节，顺序见 [Gaia.GestureSlot]；**每字节高 4 位 = 左耳动作、
     * 低 4 位 = 右耳动作**）；null = 尚未读到。
     *
     * 用不可变的 [Gaia.GestureConf]（内部按内容 equals）而不是裸 IntArray：
     * 裸数组的 equals 是按引用的，会让状态比较永远「不相等」。
     */
    val gestureConf: Gaia.GestureConf? = null,
    val capabilities: PodCapabilities = PodCapabilities(),
) {
    /** 手势配置是否已读到（UI 用它区分「读不到」与「配置为全无」）。 */
    val gesturesKnown: Boolean get() = gestureConf != null

    companion object {
        val DISCONNECTED = PodSnapshot()
    }
}

/** 状态变化的类型化事件，UI / 通知层按需消费。 */
sealed interface PodEvent {
    data class Connected(val snapshot: PodSnapshot) : PodEvent
    data object Disconnected : PodEvent
    data class BatteryChanged(val battery: BatterySnapshot) : PodEvent
    data class AncChanged(val index: Int, val mode: AncMode) : PodEvent
    data class GainChanged(val index: Int) : PodEvent
    data class LedChanged(val on: Boolean) : PodEvent
    data class PromptToneChanged(val on: Boolean) : PodEvent
    data class PromptVolumeChanged(val raw: Int) : PodEvent
    data class LhdcChanged(val on: Boolean) : PodEvent
    data class DualConnectionChanged(val on: Boolean) : PodEvent
    /** 手势配置变化（读回到新的 5 槽位配置）。 */
    data class GestureChanged(val conf: Gaia.GestureConf) : PodEvent
    data class LowLatencyChanged(val on: Boolean) : PodEvent
    data class CapabilitiesChanged(val capabilities: PodCapabilities) : PodEvent
    data class Frame(val direction: String, val hex: String, val decoded: String) : PodEvent
}
