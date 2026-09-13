# 机型适配指南（ADAPTATION）

> 本文说明如何把一款新的水月雨（MOONDROP）耳机接进本模块。
> 事实来源：`app/src/main/java/moe/chenxy/hyperpods/core/MoondropModels.kt`（档案库）、
> `pods/MoondropLink.kt`（能力探测与读写）、`core/Gaia.kt`（feature/命令），
> 以及 `_refs/` 下的上游实测材料。**凡未真机验证者，本文一律标注。**

---

## 一、设计原则：能力优先于型号

1. **型号档案只描述差异点**：ANC 档位顺序与设备码映射、增益映射、是否有指示灯、
   是否展示新功能、默认传输层。凡是不确定的，交给连接后的能力探测裁决。
2. **未命中档案也能用**：设备名含 `MOONDROP` / `水月雨` 但没命中任何档案时，回退
   `MoondropModels.FALLBACK`，用「可探测 + 保守」策略工作（不展示增益/指示灯/新功能开关）。
3. **不是水月雨设备就不接管**：`MoondropModels.match()` 返回 `null` 时模块不处理该设备。
4. **新增机型 = 加一条数据**，不需要改任何控制逻辑。

---

## 二、`MoondropModel` 字段逐项说明

```kotlin
data class MoondropModel(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val aliases: List<String>,
    val chipset: String,
    val transports: List<PodTransport>,
    val verified: Boolean,
    val anc: AncProfile?,
    val dc: DcProfile,
    val features: FeatureProfile,
    val singleDeviceBattery: Boolean = false,
    val note: String = "",
)
```

| 字段 | 含义 | 约定 / 注意 |
|---|---|---|
| `id` | 档案内部 id（`edge`、`pudding`、`golden_ages_2` …） | 也被 `HyperPodsPrefsKey.MODEL_ID` 用于手动指定型号 |
| `nameZh` / `nameEn` | UI 展示名（中/英） | `MoondropLink.snapshot()` 的 `modelName` 取 `nameZh` |
| `aliases` | 设备名匹配关键字 | 匹配时**统一转大写并 `contains`**，所以写普通子串即可；`match()` 按**别名长度降序**遍历，保证 `EDGE2` 不被 `EDGE` 抢先、`SPACE TRAVEL 2` 不被 `SPACE TRAVEL` 抢先 |
| `chipset` | 主控与协议栈的人类可读描述 | 仅用于文档/调试展示 |
| `transports` | 该机型可能使用的传输层列表 | `connect()` 只看**第一个**：若首个是 `RFCOMM_GAIA` 就走 SPP，否则走 BLE；BLE 上找不到 GAIA 服务会自动回退 RFCOMM |
| `verified` | 是否真机验证 | 会写进 `PodSnapshot.modelVerified`，UI 用它提示「推断档案」 |
| `anc` | 降噪档案；`null` = 该机型不展示降噪 | 见第三节 |
| `dc` | 设备控制档案（增益/指示灯/空间音频） | 见第四节 |
| `features` | 新功能档案（提示音/LHDC/双设备/低延迟 + 命令号覆盖） | 见第五节 |
| `singleDeviceBattery` | 标注该机型以「单设备电量(type 0)」上报 | ⚠ 当前代码**只写不读**（EDGE 设为 `true` 但无消费方）；运行时判定用 `BatteryState.usesSingleDeviceBattery()`，见第七节 |
| `note` | 备注（证据 / 差异点 / 踩坑） | 建议写清「谁在什么条件下实测」 |

### `PodTransport`（传输层）

| 枚举 | 含义 | 实际链路 |
|---|---|---|
| `BLE_GAIA` | GAIA V3 over BLE GATT | 服务 `00001100-d102-11e1-9b23-00025b00a5a5` |
| `RFCOMM_GAIA` | GAIA V4 over Classic BT RFCOMM/SPP | UUID `00001101-0000-1000-8000-00805f9b34fb` |
| `BLE_SRC_9ECA` | 中科蓝讯私有 BleSourceSwitch（`9eca0000-…`），可与 GAIA 并存 | 同一 GATT 连接内的另一条服务 |

### `AncMode`（UI 档位，顺序即 UI 顺序）

`OFF` / `NOISE_CANCELLATION` / `TRANSPARENCY` / `ANTI_WIND` / `ADAPTIVE` / `LIVE`。

---

## 二·五、机型号档案速查表（ANC / 增益 / 指示灯 / 电量）

逐条对应 `MODELS`（17 条）+ `FALLBACK`；「电量」列写的是**已知的上报形态**，
未标注者表示**未实测**，只能等连接后的 `BATTERY cmd 0` 探测结果。

| id | ANC 路径 / 档位（SET / GET 映射） | 增益 `gainMap`（UI 低中高 → 设备码） | 指示灯 | 电量（证据） | verified |
|---|---|---|---|---|---|
| `edge` | AudioCuration / 3 档：SET `[1,2,4]`，GET `[0,1,2]` | `[0,1,2]` | — | **只回 type 0 单设备**（moondrop-link 真机 60%） | ✅ 实测 |
| `pudding` | ANC V2 / 5 档：SET `[0,4,2,3,1]`，GET 同 | `[0,1,2]` | ✅ | **三路** 1=左 2=右 3=盒（PuddingPods 文档） | ✅ 实测 |
| `golden_ages_2` | AudioCuration / 4 档：SET `[1,2,4,3]`，GET `[0,1,2,3]` | `[2,1,0]` | — | 仅左右耳，**无充电盒**（FxxkMoondrop 实测） | ✅ 实测 |
| `space_travel_2` | AudioCuration / 4 档：SET `[1,2,4,3]`，GET `[0,1,2,3]` | `[2,1,0]` | — | 未实测 | ✅ 实测 |
| `golden_ages` | AudioCuration / 4 档：同 GA2 | `[2,1,0]` | — | 未实测 | 推断 |
| `moca` | AudioCuration / 4 档恒等 `[1,2,3,4]` | `[0,1,2]` | ✅ | 未实测 | 推断 |
| `nekocake` | AudioCuration / 4 档恒等 | `[0,1,2]` | — | 未实测 | 推断 |
| `pill` | AudioCuration / 4 档恒等 | `[0,1,2]` | — | 未实测 | 推断 |
| `ultrasonic` | AudioCuration / 4 档恒等 | `[0,1,2]` | — | 未实测 | 推断 |
| `robin` | AudioCuration / 4 档恒等 | `[0,1,2]` | — | 未实测 | 推断 |
| `alice` | AudioCuration / 4 档恒等 | `[0,1,2]` | — | 未实测 | 推断 |
| `sparks` | AudioCuration / 4 档恒等 | `[0,1,2]` | — | 未实测 | 推断 |
| `voyager` | AudioCuration / 4 档恒等 | `[0,1,2]` | — | 未实测 | 推断 |
| `block` | AudioCuration / 4 档恒等 | 不展示（`DcProfile()`） | — | 未实测 | 推断 |
| `space_travel` | AudioCuration / 4 档恒等 | `[0,1,2]` | — | 未实测 | 推断 |
| `edge2` | AudioCuration / 3 档：SET `[1,2,4]`，GET `[0,1,2]` | `[0,1,2]` | — | 未实测 | 推断 |
| `space_travel_2_ultra` | AudioCuration / 4 档：同 GA2 | `[2,1,0]` | — | 未实测 | 推断 |
| `fallback` | AudioCuration / 4 档恒等 | 不展示（`DcProfile()`） | — | 完全由探测决定 | 兜底 |

> 说明：EDGE / EDGE2 现已显式给出 `getMap = [0,1,2]`（SET 是位掩码 `1/2/4`，GET 是 0-based 索引）。
> 其余档案中 `getMap = null` 者表示回包按 `setMap.indexOf(deviceCode)` **反查**；
> 若这些型号的固件读回也是 0-based 值域，反查会失败（得到 `-1`）—— 见第九节第 3 条。

---

## 三、`AncProfile`：降噪档案

```kotlin
data class AncProfile(
    val modes: List<AncMode>,
    val setMap: IntArray,      // UI 下标 → 设备码
    val getMap: IntArray?,     // 设备码 → UI 下标；null = 用 setMap 反查
    val path: AncPathKind? = null,  // 固定路径；null = 由能力位图自动探测
)
```

| 字段/方法 | 语义 |
|---|---|
| `modes` | UI 上展示哪些档位、以什么顺序（例如 PUDDING 是 `[关,降,透,抗,自适应]`） |
| `setMap` | `uiToDevice(uiIndex)`：把 UI 下标翻译成要写进 `SET` payload 的设备码；数组长度 = UI 档位数 |
| `getMap` | `deviceToUi(deviceCode)`：把设备回包字节翻译回 UI 下标。**为 `null` 时退回 `setMap.indexOf(deviceCode)`（要求设备回包值域与 SET 值域一致）** |
| `path` | 锁定走哪条 feature 路径（`ANC_V1(2)` / `AUDIO_CURATION(8)` / `ANC_V2(32)`）；`null` 表示等能力探测决定 |

### 档案库里实际使用的 5 种 ANC 组合

| 组合 | `modes` | `setMap` | `getMap` | `path` | 使用机型 |
|---|---|---|---|---|---|
| `anc4Ga2()` | 关/降/透/抗 | `[1,2,4,3]` | `[0,1,2,3]` | AudioCuration | 梦回2、太空漫游2、梦回、太空漫游2 ULTRA |
| `anc4Identity()` | 关/降/透/抗 | `[1,2,3,4]` | `null` | AudioCuration | 猫咖、猫饼、音乐胶囊、超声波、知更鸟、爱丽丝、火花、旅行者、方糖、太空漫游、fallback |
| `anc3Ac()` | 关/降/透 | `[1,2,4]` | `[0,1,2]` | AudioCuration | EDGE、EDGE2 |
| `ancPudding()` | 关/降/透/抗/自适应 | `[0,4,2,3,1]` | `[0,4,2,3,1]`（恒等读回） | ANC_V2 | PUDDING |
| `ancV2Identity()` | 关/降/透/抗/自适应/LIVE | `[0..5]` | `[0..5]` | ANC_V2 | ⚠ **定义了但没有任何机型使用（死代码）** |

> `anc4Identity` 的映射符合 AudioCuration 的**名义编码** `1=关 2=降噪 3=透传 4=抗风`，
> 这是「未实测型号的保守默认」（上游 FxxkMoondrop `AncProfileLib.DEFAULT_MAP` 同为 `[1,2,3,4]`）。

### 实测证据（务必区分「上游实测」与「本项目实测」）

| 机型 | 证据 | 内容 |
|---|---|---|
| 梦回2 / Golden Ages 2 | FxxkMoondrop 2026-08-24 官方 App 抓包 + 08-25 真机双向实测 | SET `[1,2,4,3]`；**GET 是 0-based `[0,1,2,3]`**，与 SET 枚举不同（`AncProfileLib.Profile("GOLDEN AGES 2", intArrayOf(1,2,4,3), intArrayOf(0,1,2,3))`） |
| 太空漫游2 / Space Travel 2 | FxxkMoondrop 2026-09-01 真机双向实测 | AudioCuration，映射同 GA2：SET `[1,2,4,3]` / GET `[0,1,2,3]` |
| PUDDING / MD-TWS-056 | PuddingPods 协议文档 + FxxkMoondrop 适配说明 | ANC V2 五档：TX `00 1D 40 04 <mode>`；`00`=关 `01`=自适应 `02`=通透 `03`=抗风 `04`=基础降噪；UI[关,降,透,抗,自适应] → `[0,4,2,3,1]` |
| EDGE | moondrop-link-desktop 真机验证（听感确认） | 走 AudioCuration（**无 ANC_V2**）；SET payload 是**位掩码** `1`=关闭降噪 / `2`=ANC / `4`=通透；读回是 0-based 索引 `0..2` |

### 读写与回包处理（`MoondropLink`）

* 读：按 `capabilities.ancPath` 选 `ancV2GetMode()` / `audioCurationGetMode()` / `ancV1GetState()`，超时 1500 ms。
* 回包：取 `payload[0]` → 按路径换算 UI 下标：
  * ANC_V2 → `prof.deviceToUi(dev)`
  * AudioCuration → `prof.deviceToUi(dev)`，档案缺省时退回 `dev - 1`
  * ANC_V1 → `dev == 0 ? 0 : 1`
* 写（乐观更新 + 300 ms 后读回确认）：
  * ANC_V2 → `ancV2SetMode(dev)`
  * AudioCuration → **特例**：写的是位掩码 `Gaia.AC_SET_PAYLOAD[uiIndex]`（`1/2/4`），不是 `setMap` 值
  * ANC_V1 → `ancV1SetState(dev == 0 ? 0 : 1)`

> ✅ **已修正（初稿之后）**：EDGE / EDGE2 的 `anc3Ac()` 现在带 `getMap = intArrayOf(0,1,2)`。
> 上游实测 EDGE 的 AudioCuration **SET 是位掩码 `1/2/4`、GET 是 0-based 索引 `0/1/2`**，
> 早先 `getMap=null` 时 `setMap.indexOf(0)` 会得到 `-1`（状态未知），现已与 GA2 一样双向分离。
> 该参数仍**未经本模块真机复核**（见第九节第 3 条）。

---

## 四、`DcProfile`：增益 / 指示灯 / 空间音频

```kotlin
data class DcProfile(
    val hasGain: Boolean = false,
    val gainMap: IntArray = intArrayOf(0, 1, 2),   // UI 下标（低,中,高）→ 设备码
    val gainLabels: List<String> = listOf("低", "中", "高"),
    val hasLed: Boolean = false,
    val hasSpatial: Boolean = false,
    val hasHeadTracking: Boolean = false,
)
```

| 字段 | 含义 |
|---|---|
| `hasGain` | 是否展示增益控制（feature `0x0F` DAC_GAIN，cmd 1 读 / cmd 2 写） |
| `gainMap` | `gainUiToDevice(ui)`：UI `[低,中,高]` → 设备码；`gainDeviceToUi(dev)` 为反查 |
| `gainLabels` | UI 标签（默认「低/中/高」） |
| `hasLed` | 是否展示指示灯（feature `0x13` LED，cmd 1 读 / cmd 2 写，`0/1`） |
| `hasSpatial` / `hasHeadTracking` | 空间音频 / 头动追踪（feature `18` SPATIAL_AUDIO，cmd 1/2 与 3/4） |

### 档案库里使用的 3 种 DC 组合

| 组合 | 内容 | 使用机型 |
|---|---|---|
| `GAIN_IDENTITY` | 增益 `[0,1,2]`（设备 0=低 1=中 2=高） | EDGE、猫饼、音乐胶囊、超声波、知更鸟、爱丽丝、火花、旅行者、太空漫游、EDGE2 |
| `GAIN_REVERSED` | 增益 `[2,1,0]`（设备 0=高 1=中 2=低） | 太空漫游2、太空漫游2 ULTRA |
| `NONE` | 全部不展示 | 方糖、fallback |
| （内联） | 增益 `[2,1,0]` + `hasSpatial` + `hasHeadTracking` | 梦回2（`hasHeadTracking = true`） |
| （内联） | 增益 `[2,1,0]` + `hasSpatial` | 梦回 |
| （内联） | 增益 `[0,1,2]` + `hasLed = true` | 布丁、猫咖 |

### 实测证据

| 机型 | 增益 | 指示灯 | 证据来源 |
|---|---|---|---|
| 梦回2 | 设备 0=高 / 1=中 / 2=低 → `[2,1,0]`；有空间音频 + 头动追踪；**无**指示灯 | — | FxxkMoondrop `AncProfileLib.DcProfile("GOLDEN AGES 2", …)` |
| 太空漫游2 | 设备 0=高 / 1=中 / 2=低 → `[2,1,0]`（2026-09-01 真机，alpha2.41.5 修正）；**无**空间音频、**无**指示灯 | — | FxxkMoondrop `AncProfileLib.DcProfile("SPACE TRAVEL 2", …)` |
| 布丁 PUDDING | 增益 `0x00/0x01/0x02` = 低/中/高（恒等） | `0x00`=关 / `0x01`=开 | PuddingPods 协议文档 |
| 猫咖 MOCA | 恒等 `[0,1,2]`（预置待实测） | 有指示灯 | FxxkMoondrop 真机日志 + 用户确认；增益映射**未实测** |
| EDGE | 档案为恒等 `[0,1,2]`（低/中/高） | — | ⚠ **无实测证据**：EDGE 的增益映射是推断值 |

> ⚠ 空间音频：`hasSpatial` / `hasHeadTracking` 目前只有档案标记与能力字段，
> `MoondropLink` **没有** `refreshSpatial()` / `setSpatial()` 调用路径（`Gaia.spatialGet()/spatialSet()/headTracking*` 已定义但未被调用）。
> 也就是说这三个开关当前不会真正读写设备。

---

## 五、`FeatureProfile`：新功能档案

```kotlin
data class FeatureProfile(
    val promptTone: Boolean = false,        // 展示「提示音开关」
    val promptVolume: Boolean = false,      // 展示「提示音音量」滑条
    val promptVolumeMax: Int = 15,          // 设备端音量最大值（滑条 0..max 换算到 0..255）
    val lhdc: Boolean = false,              // 展示「LHDC 开关」
    val dualConnection: Boolean = false,    // 展示「双设备连接」
    val lowLatency: Boolean = false,        // 展示「低延迟模式」
    // 可覆盖的命令号（默认 = Gaia 中已确认/约定的值）
    val cmdVoiceGetEnable: Int = Gaia.C_VOICE_GET_ENABLE,   // 1
    val cmdVoiceSetEnable: Int = Gaia.C_VOICE_SET_ENABLE,   // 2
    val cmdVoiceGetVolume: Int = Gaia.C_VOICE_GET_VOLUME,   // 3
    val cmdVoiceSetVolume: Int = Gaia.C_VOICE_SET_VOLUME,   // 4
)
```

| 字段 | 说明 | 是否已实测 |
|---|---|---|
| `promptTone` / `promptVolume` | 是否**默认**展示提示音开关与音量；实际展示条件是「档案为 true **或** 能力位图含 feature 14」 | ❌ 命令号未证实 |
| `promptVolumeMax` | 音量滑条量程；`MoondropLink` 只透出该值，不做换算（换算在 UI 层） | 默认 15，**无实测依据** |
| `cmdVoice*` | 逐设备覆盖提示音命令号（`HyperPodsPrefsKey.VOICE_CMD_*` 也是同一用途） | ❌ 默认 1/2/3/4 是**惯例推测** |
| `lhdc` | 是否默认展示 LHDC 开关（feature `0x10`，cmd 5 读 / cmd 6 写） | 帧已由单测锁定；行为未实测 |
| `dualConnection` | 是否默认展示双设备连接（feature `0x14`） | ✅ 命令号有真机证据（EDGE） |
| `lowLatency` | 是否默认展示低延迟；**这是 HyperOS 系统侧功能，不是 GAIA 命令** | ❌ 系统侧未验证 |

各机型默认值（源码事实）：

| 机型 | promptTone | promptVolume | lhdc | dualConnection | lowLatency |
|---|---|---|---|---|---|
| `edge` | ✅ | ✅ | ✅ | ✅ | ✅ 实测 |
| `edge2` | — | — | ✅ | ✅ | ✅ 实测 |
| `pudding` | — | — | ✅ | — | — |
| `golden_ages_2` / `golden_ages` | — | — | ✅ | — | — |
| `alice` / `sparks` / `voyager` | — | — | ✅ | — | — |
| `space_travel_2` / `space_travel_2_ultra` / `moca` / `nekocake` / `pill` / `ultrasonic` / `robin` / `block` / `space_travel` | — | — | — | — | — |

> 也就是说：提示音开关目前**只有 EDGE 档案默认展示**（其余机型要等能力位图出现 feature 14）。

---

## 六、能力探测流程（连接之后发生了什么）

`MoondropLink.connect()` → 建立链路（BLE GATT 或 SPP）→ `afterConnected()`：

1. **GAIA 版本探测**：先发 `00 0A 03 00`（`Gaia.getApiVersion()`，V1/V2 包、vendor `0x000A`），
   用 `runCatching` 包住，失败不阻塞后续（设备 GAIA 版本为 3 时功能命令才走 vendor `0x001D`）；
2. `emitState()` —— 先把「已连接」状态推给 UI；
3. `probeCapabilities()`：
   1. 循环最多 4 次请求 `BASIC(0) cmd 1 GET_SUPPORTED_FEATURES`（后续页用 `cmd 2 NEXT`）：
      * `payload[0] & 0x01` 表示「还有下一页」；
      * 其余字节交给 `Gaia.parseSupportedFeaturesSmart()`：先按 `[more][featureId][version]...` 字节对解析
        （条目合法，feature id 落在 0..63 就采用），否则回退 32-bit word 位图；
   2. 发 `BATTERY(0x0D) cmd 0`（`00 1D 1A 00`）问设备**支持哪些电池类型**，
      `BatteryCodec.parseSupported()` 只保留 `0..3` 的已知 type（这是电量修复的第一步）；
   3. 用 `Gaia.ancPathFrom(features)` 选 ANC 路径，**优先级 AudioCuration(8) > ANC_V2(32) > ANC_V1(2)**；
      位图给不出结论时退回档案的 `anc.path`；
   4. 汇总 `PodCapabilities`：
      * `hasGain = 档案.hasGain || 位图含 15`；`hasLed = 档案.hasLed || 位图含 19`；
      * `hasSpatial = 档案.hasSpatial || 位图含 18`；`hasHeadTracking = 档案.hasHeadTracking`（只看档案）；
      * `hasPromptTone / hasPromptVolume = 档案值 || 位图含 14`；
      * `hasLhdc = 档案.lhdc || 位图含 16`；`hasDualConnection = 档案.dualConnection || 位图含 20`；
      * `hasLowLatency = 档案.lowLatency`（**不依赖位图**，因为它是系统侧功能）；
      * `ancModes = 档案.anc.modes`；`probed = true`；
4. `refreshAll()` —— 读电量、ANC，再按能力开关读增益/指示灯/提示音/LHDC/双设备；
5. `startPolling()` —— 每 30 s 重新读一次电量；
6. 再 `emitState()` 一次。

请求/响应机制：GAIA 没有序列号，`MoondropLink` 按 **feature** 建 pending 表（`HashMap<Int, CompletableFuture>`），
每个 feature 只允许一个等待者，超时 1500/2000 ms；所有写操作由一个 `Mutex` 串行化。

> ⚠ **能力编码：两种读法都试（初稿之后修正）**。上游两派对 `GET_SUPPORTED_FEATURES` 响应体的解读不同且互斥：
> moondrop-link 当作「`[more][featureId][version]...` 字节对」序列，FxxkMoondrop 当作
> 「32-bit word 位图」（word i 覆盖 feature `32*i .. 32*i+31`，大端）。
> 现在 `Gaia.parseSupportedFeaturesSmart()` **先按字节对解析**（feature id 落在 0..63 才认为有效），
> 失败再回退位图；能力探测与通知型位图都走它。
> 仍**未真机抓包确认**具体固件用哪种编码、以及误判风险（若某固件的字节对恰好也像合法位图，可能选错 ANC 路径）。
> 建议：首次真机联调时抓一次 `GET_SUPPORTED_FEATURES` 的原始回包（形如 `00 1D 00 81 …`）并核对两种解析结果。
>
> 另外 `Gaia.isFeaturePayloadTruncated()` 已经写好（用于识别「payload 长度不是 4 的倍数」导致末位 word 丢失），
> 但**客户端流程没有调用它**。

---

## 七、「单设备电量」现象（type 0）

### 现象

有些水月雨固件（典型：水月雨 EDGE）在 `BATTERY(0x0D)` 回包里**只回 `type 0`**，
即「整机电量」这一个值，而不是 `type 1/2/3`（左耳/右耳/充电盒）。
上游 moondrop-link-desktop 在 EDGE 上实测到 60% 时只收到 type 0。

### 为什么会出现「左耳正常、右耳空白」

一个只把 `1 → 左耳`、`2 → 右耳` 写死的解析器，在这种机型上**两侧都拿不到值**。
如果代码又给左耳兜底了「系统广播的单值电量」却没给右耳兜底，就会精确表现为
**左耳有数字、右耳空白**。

### 本模块的处理（四件事，缺一不可）

1. **先问设备支持哪些 type**（`BATTERY cmd 0`），再按该集合去查 —— 不再写死 `[1]` 或 `[1,2]`；
   设备不回 cmd 0 时才退回 `FALLBACK_QUERY_IDS = [0,1,2,3]` 或老固件的 cmd 1 无 payload 查询。
2. **严格按 type 归位**：`BatteryCodec.parse()` 只认 `(type, level)` 对，未知 type 直接忽略、
   **绝不让后续字节位移**（有单测 `未知类型只忽略不位移`）。
3. **`type 0` 同时充填左右耳**：`BatteryState.currentLeft/currentRight` 优先返回显式分体值
   （type 1/2），没有分体值时回退 `single`（type 0）。因此只上报 type 0 的机型，
   **左右耳都会显示**（单测 `单设备电量型机型左右耳都要显示`）；一旦收到显式分体值，
   分体值优先（单测 `分体值优先于单设备值`）。
4. **每组件保留最近一次有效值**：单包缺项不清零（单测 `只收到左耳时右耳不会被清空`、
   `缺少右耳的单包不会让右耳变成未知`）。

另外：`BatteryState.fallbackFromSystem(level)` 的系统广播兜底**同时写左耳、右耳与整机**
（单测 `系统广播兜底必须左右都给`）——「只兜左耳」正是原 bug 的成因之一。

系统蓝牙栈只需要一个整机值：`systemLevel()` 取左右耳**较小者**，
与 AOSP `AdapterService.setBatteryLevel(device, level, false)` 的语义一致。

### 运行时判定

`BatteryState.usesSingleDeviceBattery()` =「有 type 0 值 **且** 没有任何显式 type 1/2 值」，
结果写进 `BatterySnapshot.singleDevice`，UI 可据此提示「该机型只提供整机电量」。
注意 `MoondropModel.singleDeviceBattery` 标记字段目前**没有消费方**，仅作文档用途。

其它实现细节：

* `parse()` 兼容三种 payload 形态：`[type,level,…]` 标准；`[count,type,level,…]` 带数量前缀变体
  （仅在 `(size-1)` 为偶数且 `first == 剩余对数` 且 `1..8` 时才当 count）；单值 `[level]`（需 `hintType`）。
* `level == 255`（无数据）与 `> 100` 的值会被丢弃；`0..100` 之外在直接喂 pair 时会被 `coerceIn(0,100)`。
* 充电位：`BatteryCodec.parseChargingFlag()` 与 `BatteryState.setCharging()` 已实现，
  但**客户端流程没有解析充电位**（不同固件位置不一致，宁可不显示也不显示错）。

---

## 八、新增一款机型：操作清单

1. 在 `MoondropModels.MODELS` 里加一条 `MoondropModel(...)`：
   * `id` 唯一、小写；`nameZh`/`nameEn` 写 UI 名；
   * `aliases` 写**设备名里真实出现的子串**（大写不敏感），越独特越好；避免与既有别名互为前缀；
   * `chipset` 与 `transports`：主控 / 协议栈 + 首选项传输层（首项决定 BLE 还是 SPP）；
   * `verified = false`，直到有人在真机上跑通；
   * `anc`：能确定就写实测映射（含 `getMap`！），不能确定用保守默认；
   * `dc`：按实测填增益/指示灯/空间音频；
   * `features`：只开有证据的开关；
   * `note` 写清证据来源与日期。
2. 若有**新的协议差异**（新命令号、新 payload 形状），先在 `core/Gaia.kt` 里加常量/构造器，
   再在 `MoondropLink` 里接线 —— 不要往档案里塞魔法数字。
3. 在 `app/src/test/java/moe/chenxy/hyperpods/core/` 加单测锁定新帧的逐字节结果
   （参照 `GaiaProtocolTest` 的 `assertEquals("00 1D …", Gaia.hex(...))` 风格）。
4. 真机联调：`adb logcat -s MoondropLink` 看能力探测日志（`capabilities: PodCapabilities(...)`）
   与收发帧日志；确认后把 `verified` 改成 `true`，并回填 `note`。
5. 更新 [README.md](README.md) 的机型表与 [CHANGELOG.md](CHANGELOG.md)。

---

## 九、已知未知（需要真机证据）

| # | 未知项 | 现状 / 建议验证方式 |
|---|---|---|
| 1 | 提示音（feature 0x0E）命令号 | 只保留 feature id；默认 GET=1/SET=2。用官方 App 抓 btsnoop 后回填 `cmdVoice*` |
| 2 | `GET_SUPPORTED_FEATURES` 响应体编码 | 代码两种都试（`parseSupportedFeaturesSmart`）；**具体固件用哪种、会不会误判**仍需抓原始回包裁决 |
| 3 | ANC 读回值域 | EDGE / EDGE2 已按上游实测补 `getMap=[0,1,2]`（参数待真机复核）；`anc4Identity` 系列仍是 `getMap=null` 反查，若这些机型也是 0-based 读回会得到 `-1` |
| 4 | GAIA 版本探测 | 已在连接流程中发送（`00 0A 03 00`）；**探测结果的解析与用途**（是否需要据此切换包格式）未真机确认 |
| 5 | EDGE 增益映射 | 档案为恒等 `[0,1,2]`，无实测证据 |
| 6 | `promptVolumeMax = 15` | 无实测依据；不同固件量程可能不同 |
| 7 | 9ECA 私有协议 | 已实现但未接线、未验证（上游亦标注未实机验证） |
| 8 | 空间音频 / 头动追踪 | 档案与能力字段存在，客户端无读写路径 |
| 9 | `AudioCuration cmd 41/42`（ANC 切换配置） | 上游 FxxkMoondrop 记录 GA2 对 cmd 41 回包不稳定，**不推荐**；本项目也未使用 |
| 10 | 双设备连接的写入与断开单台 | 读取已验证，写入/断开需双机实测（上游原文） |
| 11 | 13 款「推断」机型 | 需逐型核对 ANC/Gain/LED 映射 |
| 12 | 不同固件的充电位位置 | 已实现解析器但未接线，需抓包确认 |

---

## 十、代码级备注（阅读档案时容易踩的坑）

* `ancV2Identity()` 已定义但无任何机型引用（死代码）；`AncMode.LIVE` 亦只出现在该组合里。
* `Gaia` 中以下 API 已实现但客户端未调用：`registerNotification()`、
  `spatialGet/Set()`、`headTrackingGet/Set()`、`powerOff()`、`basicGetVariant/AppVersion/SerialNumber/TwsStatus/EarbudLang()`、
  `ldacGet/Set()`、`lc3Get/Set()`、`ancV2GetSwitchConf()/ancV2SwitchConf()`、`audioCurationSetStateIndex()`。
* `BatteryCodec.buildSupportedQuery()` / `buildLegacyQuery()` 与部分解析器同样是「备用实现」，
  真正在流程里用的是 `Gaia.batteryGetAllV4()` / `Gaia.batteryGetAll()` / `Gaia.batteryGet()`。
* `MoondropModel.singleDeviceBattery`、`Gaia.isFeaturePayloadTruncated()`、`BatteryCodec.parseChargingFlag()`、
  `BatteryState.setCharging()` 当前无调用方（部分有单测）。
* `connect()` 里 `useRfcomm` 只看 `transports` **首项**；双模机型（如猫咖）首项是 BLE，
  只有在 BLE 上找不到 GAIA 服务时才会回退到 SPP。

---

## 十一、系统集成层：适配新机型时会碰到的接线点

协议能读写耳机，不代表 HyperOS 界面上就能看到。系统集成层在 `hook/` 与 `ui/`：

| 组件 | 作用 | 与机型适配的关系 |
|---|---|---|
| `hook/HeadsetStateDispatcher.kt` | `com.android.bluetooth`：A2DP 连接感知 + 电量写回系统蓝牙栈 + MAC 应答 | 连接判定统一走 `MoondropModels.match()`；**新机型的蓝牙名必须能命中 `aliases`，否则整条链路不会启动** |
| `hook/SystemUIPluginHook.kt` + `hook/DeviceCardHook.kt` | 融合设备中心耳机卡点击接管 | 只认 `deviceType == "third_headset"` 的卡片；卡片 id 与耳机 MAC 比较，不改机型档案 |
| `hook/MiBluetoothToastHook.kt` | `com.xiaomi.bluetooth` 电量通知 | 三路电量由 `SystemApisUtils.readBatteryExtras()` 统一读；单设备机型走 `EXTRA_LEVEL` 兜底 |
| `hook/SettingsHeadsetHook.kt` | 设置页伪装原生耳机（`01010607`）+ 状态注入 | 伪装 ID 对应「小米四档 ANC 模板」，与真实机型 ANC 档位数不同，需要映射；ANC 改动经 `ANC_SELECT` 广播回传 |
| `pods/ControlBridge.kt` + manifest 的 `ControlReceiver` | **应用侧跨进程控制桥**：接收系统各进程的控制命令、把状态转发回去 | 是「新机型能力能否出现在系统界面」的关键一环；manifest 声明 + 显式广播，App 未运行也能被拉起 |
| `ui/PodDetailPage.kt` | 模块自己的详情页 | 开关行由 `PodCapabilities` **硬门控**（能力 false 即不进入组合树）；新增功能必须同时接能力字段与 UI 行 |

**接线现状（`pods/ControlBridge.kt`，初稿之后已实现）**：

| 方向 | 动作 | 说明 |
|---|---|---|
| `com.android.bluetooth` → 应用进程 | `PODS_CONNECTED` / `PODS_DISCONNECTED` | `getRemoteDevice(mac)` → `MoondropLink.connect()` / `disconnect()`；非水月雨设备忽略 |
| 设置页 → 应用进程 | `ANC_SELECT` / `GAIN_SELECT` / `LED_SELECT` / `PROMPT_TONE_SELECT` / `PROMPT_VOLUME_SELECT` / `LHDC_SELECT` / `DUAL_CONNECTION_SELECT` | 路由到 `MoondropLink.setXxx()`（`ANC_SELECT` 带的是本模块 UI 档位下标） |
| 系统侧 → 应用进程 | `UI_INIT` / `REQUEST_CAPABILITIES` / `REQUEST_BATTERY` | 状态重放 |
| 应用进程 → `com.android.bluetooth` | `UPDATE_SYSTEM_BATTERY` | 反射 `AdapterService.setBatteryLevel`，系统 UI 显示电量 |
| 应用进程 → `com.android.settings` | `ANC_CHANGED` / `BATTERY_CHANGED` | 喂被伪装的耳机页 |
| 应用进程 → `com.xiaomi.bluetooth` | `UPDATE_PODS_NOTIFICATION` / `SEND_STRONG_TOAST` / `CANCEL_PODS_NOTIFICATION` | 通知 / 电量展示 |
| 应用进程 → `com.android.bluetooth` → 应用进程 | `LOW_LATENCY_SELECT` → `LOW_LATENCY_CHANGED` | 低延迟（系统侧）：先反射厂商直通方法，否则 A2DP codec 兜底 |

电量以 `Bundle` 传递（`left`/`right`/`case` + `*_charging`；`255 = 未知`、`value or 128 = 充电中`）。

**仍未接线 / 未验证的部分**：

* 空间音频 / 头动追踪（`Gaia.spatialGet/Set`、`headTracking*`）**未接到客户端**；
* 低延迟的系统侧实现（反射桥 + A2DP codec 兜底）**从未真机验证**，隐藏 API 不可用时只回「保持原状态」；
* `SettingsHeadsetHook` 的 `updateAtUiInfo / updateAncUi / refreshStatus` 调用签名按 OppoPods 在 HyperOS
  上的用法书写，**需实机核对**；`MiuiHeadsetBattery` 电量控件注入未实现。

这些是**接线**而不是**协议**问题：新增机型时，只要档案能被匹配、能力位图/回包能被解析，
协议层就能工作；要让它在系统界面上出现，上面的桥已经就位（但需真机验证）。
