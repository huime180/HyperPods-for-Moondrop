## 1.0.0

首个版本。本版本包含**协议核心层**（纯 Kotlin，可单测）、**协议客户端**、
**libxposed API 102 系统集成层**（4 个作用域进程）与 **Compose / Miuix UI**。

> ⚠ **诚实声明**：本版本**没有构建出 APK，也没有在真机上测试过**
> （编写时的环境无 JDK / Android SDK / 网络）。下文所有「已由上游真机验证」均指
> 上游项目（FxxkMoondrop / moondrop-link-desktop / PuddingPods）的结论，**不是本模块的测试结论**；
> 凡是未验证的条目都在最后单独列出。

### 多机型适配（17 个型号档案 + 1 个兜底档案）

* 新增 `core/MoondropModels.kt`：以「能力优先于型号」为原则的档案库。
  * **有上游实测依据（`verified = true`，4 款）**：
    * 羽翼 EDGE —— GAIA v3 over BLE；电量只回 `type 0`（单设备）；ANC 走 AudioCuration 三态位掩码
      `1/2/4`；`ONEBRINGTWO(0x14)` 可用（moondrop-link-desktop 真机验证）；
    * 布丁 PUDDING（MD-TWS-056）—— GAIA v4 over RFCOMM/SPP；ANC V2 五档 `[0,4,2,3,1]`；
      三路电量（含充电盒）；增益恒等、指示灯（PuddingPods 协议文档 + FxxkMoondrop 实测）；
    * 梦回2 / Golden Ages 2 —— AudioCuration，SET `[1,2,4,3]` / GET `[0,1,2,3]`，增益 `[2,1,0]`，
      空间音频 + 头动追踪（FxxkMoondrop 2026-08-24/25 双向实测）；
    * 太空漫游2 / Space Travel 2 —— 中科蓝讯 BT8932F + 9ECA；ANC 同 GA2；增益反向 `[2,1,0]`
      （FxxkMoondrop 2026-09-01 真机实测）。
  * **芯片级推断（`verified = false`，13 款）**：梦回1979/梦回、猫咖、猫饼、音乐胶囊、超声波、
    知更鸟、爱丽丝、火花、旅行者、方糖、太空漫游（一代）、羽翼 EDGE2、太空漫游2 ULTRA。
  * 兜底档案 `FALLBACK`：设备名含 `MOONDROP` / `水月雨` 但未命中档案时使用，不假设任何能力，
    全部交给连接后的能力探测；不是水月雨设备则完全不接管。
  * 别名按长度降序 `contains` 匹配，避免 `EDGE` 抢在 `EDGE2` 前、`SPACE TRAVEL` 抢在 `SPACE TRAVEL 2` 前。

### 右耳电量不显示：根因与四段式修复（`core/BatteryCodec.kt`）

现象：左耳电量正常、**右耳空白**。根因是固件上报形态与解析器假设不一致
（实测水月雨 EDGE 只回 `type 0`「单设备」电量，而不是 `type 1/2` 分体值）。修复分四部分：

1. **先问设备支持哪些电池类型**（`BATTERY(0x0D) cmd 0`，`00 1D 1A 00`），再按该集合查询；
   设备不回 cmd 0 时退回 `[0,1,2,3]`，或老固件的 cmd 1 无 payload 查询 —— 不再写死「只查左耳」。
2. **严格按 `type` 归位、绝不按位置解析**：未知 type 直接忽略，不让后续字节错位
   （单测 `未知类型只忽略不位移`）。
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
| **提示音开关 + 音量滑条**（`VOICE 0x0E`） | `Gaia.promptToneGet/Set`、`promptVolumeGet/Set`；档案可逐设备覆盖命令号（`FeatureProfile.cmdVoice*` / `HyperPodsPrefsKey.VOICE_CMD_*`）；UI 行由能力位图（feature 14）或档案开关**硬门控**（无能力即隐藏）；滑条拖动只改本地显示，松手才下发 | ⚠ **命令号未证实**：官方 App 逆向只保留 feature id，默认按「GET=1 / SET=2」惯例，需真机验证 |
| **LHDC 开关**（`CODEC_TYPE 0x10`） | cmd 5 读 `00 1D 20 05` / cmd 6 写 `00 1D 20 06 01|00`；单测逐字节锁定；同时保留 LC3（1/3）与 LDAC（2/4）构造器 | 帧格式已锁定；**开关实际效果未真机验证**。上游实测耳机出厂默认 LHDC 关（当前活动编码 AAC，主机侧广告 LHDCv5/LHDC_V3/LHDC_V2/LDAC/aptX-adaptive） |
| **双设备连接**（`ONEBRINGTWO 0x14`） | cmd 1/2 状态（`00 1D 28 01` / `00 1D 28 02 01`）、3/4 超时、5/6 设备列表、7 断开单台（payload `[num:1][addr:6][name utf8]`）；`parseLinkedDevice()` 解析条目 | 命令号由 moondrop-link 在 EDGE **真机确认**；上游原文注明「读取与开关已验证，**写入/断开单台需双机场景实测**」 |
| **低延迟模式** | **明确：这是 HyperOS 系统侧功能，不是 GAIA 命令**，无对应 feature；`PodCapabilities.hasLowLatency` / `PodSnapshot.lowLatencyOn` 由系统侧状态驱动，按型号档案决定是否展示（EDGE、EDGE2） | ❌ **未验证**：详情页开关只发 `LOW_LATENCY_SELECT` 广播并乐观更新，**当前没有接收端** |

### HyperOS 系统集成层（libxposed API 102）

* `hook/XposedEntry.kt`：模块入口 `XposedModule`，`onPackageReady` 按包名分发 hook；
  实现 API 102 热重载（`onHotReloading` / `onHotReloaded`，稳定 hook id + 摘除失效 handle）；
  远程首选项组 `hyperpods_moondrop_settings`（取不到时用内存兜底）；**装载失败只记日志，不影响被注入进程**。
* `hook/HookContext.kt`：hook 基类（`hookBefore/After/ConstructorAfter`、`find*OrNull`、
  沿继承链的 `findAnyMethod`、反射助手），移植自 OppoPods 的通用封装风格。
* `hook/HeadsetStateDispatcher.kt`（`com.android.bluetooth`）：hook
  `A2dpService#handleConnectionStateChanged(int,int,int)` 感知 A2DP/HFP 连接；只处理
  `MoondropModels.match()` 命中的设备；维护状态栏 `wireless_headset` 图标；向应用进程广播
  `PODS_CONNECTED / PODS_DISCONNECTED`；应答 `GET_PODS_MAC`；接收 `UPDATE_SYSTEM_BATTERY` 并反射
  `mAdapterService.setBatteryLevel(device, level, false)` 写回系统蓝牙栈；
  **迟装兜底**：`A2dpService/ProfileService.onCreate` 后延迟 1.5 s 扫描已连接的 A2DP/HFP 设备补发一次连接事件。
* `hook/SystemUIPluginHook.kt` + `hook/DeviceCardHook.kt`（`com.android.systemui`）：
  hook `PluginInstance#loadPlugin`，在 `miui.systemui.plugin` 插件 ClassLoader 里挂
  `DeviceInfoWrapper#performClicked`，只接管 `deviceType == "third_headset"` 的卡片；
  MAC 握手在独立 `HandlerThread` 上完成，**点击路径不 sleep、不阻塞 SystemUI 主线程**
  （相比原 HyperPods 的 `Thread.sleep(50)` 忙等是明确的改进）；打开 UI 前用
  `MainPanelController#exitOrHide` 收起控制中心面板。
* `hook/MiBluetoothToastHook.kt`（`com.xiaomi.bluetooth`）：hook `MiuiBluetoothNotification`
  构造函数（两种签名 + 参数个数兜底）取进程 Context，展示耳机电量通知
  （id 10003 / tag `BTHeadset<addr>` / 通道 `hyperpods_moondrop_bt_headset`，`IMPORTANCE_MIN`），
  文案优先复用小米蓝牙本地化字符串；**不含任何 mp4 / base64 强提示视频资产**，
  `SEND_STRONG_TOAST` 降级为同一套普通通知。
* `hook/SettingsHeadsetHook.kt`（`com.android.settings`）：用 `FAKE_DEVICE_ID = "01010607"` /
  `FAKE_SUPPORT = "01010607,000000000000000010000000"` 伪装成小米原生四档 ANC 耳机；
  改写 `MiuiHeadsetActivity(Plugin)#onCreate` 的 intent extra；强制
  `HeadsetIDConstants` 的 `checkSupport` / `isTWS01Headset` / `isK77sHeadset` / `isBleMmaConnect`
  与 `getDeviceID/getSupport`；拦截 `IMiuiHeadsetService$Stub$Proxy`
  （`checkSupport / isMiTWS / checkIsMiTWS / getRingFindState / changeAncMode / connect /
  getDeviceConfig / getCommonConfig`）；用 MIUI payload 注入
  `MiuiHeadsetFragment#updateAtUiInfo / updateAncUi / refreshStatus`，页面存活时每 3 s 重新注入。
  所有分支都先确认是水月雨设备。
* `hook/SystemApisUtils.kt`：反射小工具（`isHyperOS`、`SystemProperties`、`setIconVisibility`、
  `notifyAsUser/cancelAsUser`、`currentApplication`）与**三路电量 extra 约定**
  （`EXTRA_BATTERY` 放 Bundle：`left/right/case` + 可选 `*_charging`；或 Parcelable；
  或 `EXTRA_LEVEL` 单路兜底；255 = 未知，`value or 128` = 充电中）。
* `MainActivity` + `ui/*`（Compose + Miuix + haze）：两页界面（设备详情 / 关于）；
  详情页含机型卡（型号 / 实测徽标 / 传输 / 当前编码）、三路电量（单设备机型显示「整机」行）、
  降噪档位、增益、能力硬门控的开关区（指示灯 / 提示音 / LHDC / 双设备连接 / 低延迟）、
  提示音音量滑条、手动刷新；应用进程注册 `PODS_*` / `UI_INIT` / `REQUEST_*` 广播触发器，
  从已配对设备里按型号档案挑出水月雨耳机并建立 GAIA 通道。默认英文字符串 + `values-zh-rCN` 中文。

### 协议层与测试

* `core/Gaia.kt`：GAIA V3/V4 线格式、feature/命令常量、帧构造与解析、能力位图解析、
  ANC 路径推导（AudioCuration > ANC V2 > ANC V1）、AC 位掩码 `1/2/4`、`OnBringTwo` 设备条目解析、
  版本探测帧 `00 0A 03 00`（已实现，连接流程未调用）。
* `core/GaiaFramer.kt`：RFCOMM/SPP 流式切帧状态机，支持官方传输帧（SOF `0xFF` + 可选校验和 /
  长度扩展）与裸 `00 1D` PDU 两种封装，半截帧保留不丢。
* `core/SrcProtocol.kt`：中科蓝讯 9ECA 私有协议帧构造与解析（音源 / EQ / MIC 增益 / 固件信息）。
* `pods/MoondropLink.kt`：BLE GATT 与 SPP 两条链路；按 feature 的请求/响应关联（GAIA 无序列号，
  `CompletableFuture` + 超时）；写操作单写者 `Mutex`；能力探测 → 全量刷新 → 30 s 电量轮询；
  BLE 上找不到 GAIA 服务时自动回退 SPP。
* 单元测试 **28 例**：`GaiaProtocolTest`（13）逐字节锁定所有帧与位图解析；
  `BatteryCodecTest`（15）覆盖右耳电量修复的全部回归点。

### 未验证 / 遗留问题（1.0.0）

1. **构建与真机**：本版本**未构建 APK、未真机测试**。
2. 提示音（0x0E）命令号未证实。
3. `GET_SUPPORTED_FEATURES` 响应体是 32-bit 位图还是 `(featureId, version)` 字节对（两派读法冲突）。
4. EDGE 与「恒等映射」档案的 ANC 读回值域（若为 0-based，需给档案补 `getMap`，否则状态显示未知）。
5. GAIA 版本探测未被调用；EDGE 增益映射与 `promptVolumeMax = 15` 无实测依据。
6. **接线缺口（源码 grep 确认）**：
   * `UPDATE_SYSTEM_BATTERY`、`SEND_STRONG_TOAST`、`UPDATE_PODS_NOTIFICATION`、
     `CANCEL_PODS_NOTIFICATION` **只有接收端，应用进程没有发送端** → 电量不会写进系统蓝牙栈、通知不会主动发出；
   * `ANC_SELECT` 由设置页广播但应用进程**未注册该 action** → 设置页降噪切换不回传耳机；
   * `LOW_LATENCY_SELECT` **没有接收端** → 低延迟开关只有乐观 UI；
   * 空间音频 / 头动追踪（`Gaia.spatialGet/Set`、`headTracking*`）未接线。
7. 9ECA 全部功能、LHDC 打开后的稳定性、双设备连接写入/断开、充电位解析均未验证。
8. `SettingsHeadsetHook` 的注入签名（`updateAtUiInfo / updateAncUi / refreshStatus`）需实机核对；
   `MiuiHeadsetBattery` 电量控件注入未实现。
9. 13 款「推断」机型未逐型验证；系统集成层（通知 / 设备卡 / 设置页伪装）从未在真机上运行过。
