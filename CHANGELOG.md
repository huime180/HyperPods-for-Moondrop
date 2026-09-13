## 1.0.0

初始版本。仓库当前 checkout（commit `43cc40e`）只包含**协议核心层**；
系统集成 hook 层与 UI 层不在本检出中 —— 因此 **1.0.0 尚未构建出 APK，也未经真机测试**
（详见 [README.md](README.md) 第六节）。下列「已由上游真机验证」一律指上游项目
（FxxkMoondrop / moondrop-link-desktop / PuddingPods）的结论，不是本模块的测试结论。

### 多机型适配（17 个型号档案 + 1 个兜底档案）

* 新增 `core/MoondropModels.kt`：以「能力优先于型号」为原则的档案库。
  * **实测依据（`verified = true`，4 款）**：
    * 羽翼 EDGE —— GAIA v3 over BLE；电量只回 `type 0`（单设备）；ANC 走 AudioCuration 三态位掩码；
      `ONEBRINGTWO(0x14)` 可用（moondrop-link-desktop 真机验证）；
    * 布丁 PUDDING（MD-TWS-056）—— GAIA v4 over RFCOMM/SPP；ANC V2 五档；三路电量（含盒）；
      增益、指示灯（PuddingPods 协议文档 + FxxkMoondrop 实测）；
    * 梦回2 / Golden Ages 2 —— AudioCuration，SET `[1,2,4,3]` / GET `[0,1,2,3]`，增益 `[2,1,0]`
      （FxxkMoondrop 2026-08-24/25 双向实测）；
    * 太空漫游2 / Space Travel 2 —— 中科蓝讯 BT8932F + 9ECA；ANC 同 GA2；增益反向 `[2,1,0]`
      （FxxkMoondrop 2026-09-01 真机实测）。
  * **芯片级推断（`verified = false`，13 款）**：梦回1979/梦回、猫咖、猫饼、音乐胶囊、超声波、
    知更鸟、爱丽丝、火花、旅行者、方糖、太空漫游（一代）、羽翼 EDGE2、太空漫游2 ULTRA
    —— 主控与协议可识别，但未逐型跑通。
  * 兜底档案 `FALLBACK`：设备名含 `MOONDROP` / `水月雨` 但未命中任何档案时使用，
    不假设任何能力，全部交给连接后的能力探测；不是水月雨设备则完全不接管。
  * 别名匹配按长度降序 `contains`，避免 `EDGE` 抢在 `EDGE2` 前、`SPACE TRAVEL` 抢在 `SPACE TRAVEL 2` 前。

### 右耳电量不显示：根因与四段式修复（`core/BatteryCodec.kt`）

现象：左耳电量正常、**右耳空白**。根因是固件上报形态与解析器假设不一致（实测水月雨 EDGE 只回
`type 0`「单设备」电量，而不是 `type 1/2` 分体值）。修复分四部分，缺一不可：

1. **先问设备支持哪些电池类型**（`BATTERY(0x0D) cmd 0`，`00 1D 1A 00`），再按该集合查询；
   设备不回 cmd 0 时退回 `[0,1,2,3]` 或老固件的 cmd 1 无 payload 查询 —— 不再写死「只查左耳」。
2. **严格按 `type` 归位、绝不按位置解析**：`parse()` 只读 `(type, level)` 对，
   未知 type 直接忽略，不会让后续字节错位（单测 `未知类型只忽略不位移`）。
3. **把 `type 0` 当作整机值同时充填左右耳**，显式分体值（type 1/2）优先于整机值
   —— 只上报 type 0 的机型左右耳都会显示（单测 `单设备电量型机型左右耳都要显示`）。
4. **每个组件保留最近一次有效值**，单包缺项不清零（单测 `只收到左耳时右耳不会被清空`、
   `缺少右耳的单包不会让右耳变成未知`）。

附带修复：系统广播的单值兜底 `fallbackFromSystem()` **同时写左右耳与整机**
（「只兜左耳」正是原 bug 的成因之一，单测 `系统广播兜底必须左右都给`）；
`systemLevel()` 取左右耳较小者，与 AOSP `AdapterService.setBatteryLevel(device, level, false)` 语义一致。

### 四个新功能

| 功能 | 实现 | 验证状态 |
|---|---|---|
| **提示音开关 + 音量滑条**（`VOICE 0x0E`） | `Gaia.promptToneGet/Set`、`promptVolumeGet/Set`；档案可逐设备覆盖命令号（`FeatureProfile.cmdVoice*` / `HyperPodsPrefsKey.VOICE_CMD_*`）；UI 由能力位图（feature 14）或档案开关决定是否展示 | ⚠ **命令号未证实**：官方 App 逆向只保留 feature id，默认按「GET=1 / SET=2」惯例，**需真机验证** |
| **LHDC 开关**（`CODEC_TYPE 0x10`） | cmd 5 读 `00 1D 20 05` / cmd 6 写 `00 1D 20 06 01|00`；单测逐字节锁定；同时保留 LC3（1/3）与 LDAC（2/4）构造器 | 帧格式已锁定；**开关实际效果未真机验证**。实测耳机出厂默认 LHDC 关，当前活动编码为 AAC（主机侧广告 LHDCv5/LHDC_V3/LHDC_V2/LDAC/aptX-adaptive） |
| **双设备连接**（`ONEBRINGTWO 0x14`） | cmd 1/2 状态（`00 1D 28 01` / `00 1D 28 02 01`）、3/4 超时、5/6 设备列表、7 断开单台（payload `[num:1][addr:6][name utf8]`）；设备条目解析 `parseLinkedDevice()` | 命令号由 moondrop-link 在 EDGE **真机确认**；上游原文注明「读取与开关已验证，**写入/断开单台需双机场景实测**」 |
| **低延迟模式** | **明确说明：这是 HyperOS 系统侧功能，不是 GAIA 命令**，无对应 feature；`PodCapabilities.hasLowLatency` / `PodSnapshot.lowLatencyOn` 由系统侧状态驱动，按型号档案决定是否展示（EDGE、EDGE2） | ❌ **未验证**（系统侧实现不在本检出中） |

### 其它协议层工作

* `core/Gaia.kt`：GAIA V3/V4 线格式、feature/命令常量、帧构造与解析、
  `GET_SUPPORTED_FEATURES` 位图解析、ANC 路径推导（AudioCuration > ANC V2 > ANC V1）、
  AC 位掩码 `1/2/4`、`OnBringTwo` 设备条目解析、版本探测帧 `00 0A 03 00`（已实现，连接流程未调用）。
* `core/GaiaFramer.kt`：RFCOMM/SPP 流式切帧状态机，支持官方传输帧（SOF `0xFF` + 可选校验和 /
  长度扩展）与裸 `00 1D` PDU 两种封装，半截帧保留不丢。
* `core/SrcProtocol.kt`：中科蓝讯 9ECA 私有协议（音源切换 / EQ / MIC 增益 / 固件信息）帧构造与解析。
  ⚠ **未接线、未真机验证**。
* `pods/MoondropLink.kt`：BLE GATT 与 SPP 两条链路、按 feature 的请求/响应关联（无序列号，带超时）、
  写操作单写者 `Mutex`、能力探测 → 全量刷新 → 30 s 电量轮询；
  9ECA 与 BLE 上的 GAIA 服务缺失时自动回退 SPP。
* `pods/PodSnapshot.kt`：`PodCapabilities` / `BatterySnapshot` / `PodSnapshot` / `PodEvent` 状态模型。
* `utils/data/HyperPodsAction.kt`：跨进程广播契约（4 个作用域进程 + 本应用进程），
  所有跨进程广播强制 `setPackage(...)`。
* 单元测试：`GaiaProtocolTest.kt`（13 个用例，逐字节锁定电量/ANC/LHDC/增益/指示灯/提示音/双设备/
  版本探测/注册通知帧与位图解析）、`BatteryCodecTest.kt`（14 个用例，覆盖右耳电量修复的全部回归点）。

### 未验证 / 待真机确认（1.0.0 遗留）

1. 提示音（0x0E）的命令号；
2. `GET_SUPPORTED_FEATURES` 响应体是 32-bit 位图还是 `(featureId, version)` 字节对（两派读法冲突）；
3. EDGE 与「恒等映射」档案的 ANC 读回值域（若为 0-based，需给档案补 `getMap`，否则状态显示为未知）；
4. GAIA 版本探测未被调用；
5. EDGE 增益映射无实测证据；`promptVolumeMax = 15` 无实测依据；
6. LHDC 打开后的稳定性、双设备连接写入/断开、9ECA 全部功能、空间音频/头动追踪、充电位解析；
7. 13 款「推断」机型未逐型验证；
8. 系统集成层（通知 / 超级岛 / 融合设备中心设备卡 / Settings 伪装）不在本检出中，无法验证。
