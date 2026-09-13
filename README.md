# HyperPods for Moondrop

> 让 **Xiaomi HyperOS** 把 **MOONDROP（水月雨）** 蓝牙耳机当作系统原生耳机来管理：
> 融合设备中心设备卡、超级岛 / 通知电量、设置页耳机入口、系统级降噪与增益控制。
>
> 这是一个 **LSPosed / Xposed 模块**（libxposed API 102），Kotlin + Jetpack Compose + Miuix。
> 命名空间 `moe.chenxy.hyperpods`，applicationId `moe.chenxy.hyperpods.moondrop`，许可 GPL-3.0。

---

## 一、这是什么

HyperOS 的「融合设备中心」「超级岛」「蓝牙设置页」原生只认识小米/部分合作品牌耳机。
本项目把一条 **水月雨（MOONDROP）GAIA 协议客户端**接进系统：

* 模块自己的 App 进程跑协议客户端：GAIA V3 over BLE GATT，或 GAIA V4 over Classic Bluetooth RFCOMM/SPP；
* `com.android.bluetooth` 感知 A2DP 连接/断开，把电量写进系统蓝牙栈（`AdapterService.setBatteryLevel`）；
* `com.android.systemui` 接管融合设备中心耳机卡（`deviceType == "third_headset"`）的点击；
* `com.xiaomi.bluetooth` 负责通知 / 超级岛 / 强提示电量展示；
* `com.android.settings` 用兼容 Device ID `01010607` 伪装成小米原生耳机，并拦截 `IMiuiHeadsetService$Stub$Proxy`，把系统耳机页上的操作路由回本模块。

> 所有跨进程广播都必须 `setPackage(...)`：Android 14+ 会丢弃未指定包名的隐式广播。

---

## 二、本项目的由来

本项目**不是**从 PuddingPods fork 出来的 —— [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods)
的 GitHub 仓库里**只有文档，没有源码**（本仓库根的 `PuddingPods/` 目录就是它的克隆，
里面只有 `PUDDING_ADAPTATION.md`、README 和 gradle 空壳）。

因此本项目的做法是「拼装 + 重写协议层」：

| 来源 | 取用了什么 |
|---|---|
| [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) | HyperOS 系统集成骨架（四进程作用域、蓝牙状态分发、设备卡点击接管、通知/强提示、Miuix UI 结构） |
| [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) | 水月雨机型适配知识：GAIA V3/V4、9ECA 私有协议、三条 ANC 路径、GA2/太空漫游2 实测 ANC 与增益映射、电量与双地址连接经验 |
| [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) | EDGE 真机逆向的协议目录：GATT UUID、帧格式、BATTERY/ONEBRINGTWO/AUDIO_CURATION/ANC_V2 命令字节 |
| [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) | PUDDING（MD-TWS-056）协议文档：RFCOMM/SPP 传输、ANC V2 五档、三路电量、增益、指示灯、HyperOS Device ID `01010607` |
| [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) | HyperOS 设置页伪装成原生耳机的技术路线（`HeadsetIDConstants` / `IMiuiHeadsetService$Stub$Proxy`） |

协议字节与命令号均以本仓库源码为准，出处见 [PROTOCOL.md](PROTOCOL.md) 的「来源与推导」一节。

---

## 三、功能一览

| 功能 | 实现要点 | 状态 |
|---|---|---|
| **多机型适配** | `MoondropModels.MODELS` 17 条型号档案 + 兜底档案；「能力优先于型号」，型号档案只提供差异点 | 档案库已实现；4 款实测、13 款芯片级推断 |
| **三路电量（含「单设备电量」兼容）** | `BatteryCodec` / `BatteryState`：先问设备支持哪些电池类型 → 按 type 归位 → type 0 同时充填左右耳 → 每组件保留最近有效值 | 协议层已实现并有单测；EDGE 只回 type 0 已由上游真机证实 |
| **降噪（三条 ANC 路径）** | 自动从能力位图选路径：AudioCuration(8) > ANC V2(32) > ANC V1(2)；每型号独立映射表 | 协议层已实现；GA2 / 太空漫游2 / PUDDING / EDGE 映射有实测依据 |
| **增益（DAC_GAIN 0x0F）** | `DcProfile.gainMap`：低/中/高 → 设备码；GA2 与太空漫游2 为反向映射 | 已实现 |
| **指示灯（LED 0x13）** | `DcProfile.hasLed`；0/1 开关 | 已实现（PUDDING 有实测） |
| **提示音开关与音量（VOICE 0x0E）** | 默认按水月雨自家开关惯例 GET=1 / SET=2；可在设置页逐设备覆盖命令号 | ⚠ **命令号未真机证实**，见下文 |
| **LHDC 开关（CODEC_TYPE 0x10）** | cmd 5 读 / cmd 6 写（payload `01`/`00`）；同时保留 LC3、LDAC 的构造器 | 帧已由单测锁定；出厂默认 LHDC 关（实测当前编码为 AAC） |
| **双设备连接（ONEBRINGTWO 0x14）** | cmd 1/2 状态、3/4 超时、5/6 设备列表、7 断开单台 | 命令号已由 moondrop-link 在 EDGE 真机确认 |
| **低延迟模式** | **这是 HyperOS 系统侧功能，不是 GAIA 命令**；由系统 A2DP 会话的低延迟配置能力决定 | 不依赖能力位图；仅 EDGE / EDGE2 档案默认展示 |
| **超级岛与融合设备中心** | SystemUI 设备卡点击接管 + `com.xiaomi.bluetooth` 通知/岛 | 依赖 hook 层（见「实现进度」） |
| **通知与电量显示** | 电量推送到系统蓝牙栈 + 通知/强提示 | 依赖 hook 层（见「实现进度」） |

---

## 四、支持机型

下表**逐条对应** `app/src/main/java/moe/chenxy/hyperpods/core/MoondropModels.kt` 的 `MODELS`（共 17 条）
与兜底档案 `FALLBACK`。「实测」= 上游项目在该机型上真机验证过；「推断」= 主控确认、协议可自动识别，但未逐一实机跑通。

| id | 型号 | 主控 / 传输 | ANC 路径与档位 | 增益映射 | 指示灯 | 新功能开关 | 状态 |
|---|---|---|---|---|---|---|---|
| `edge` | 羽翼 EDGE / EDGE | Qualcomm QCC（GAIA v3 / vendor 0x001D）；BLE | AudioCuration，3 档（关/降/透），SET 位掩码 `1/2/4` | `[0,1,2]` 低中高 | — | 提示音 + 音量、LHDC、双设备连接、低延迟 | **实测**（电量只回 type 0） |
| `pudding` | 布丁 / PUDDING（MD-TWS-056） | 国产 SoC（GAIA v4）；RFCOMM/SPP | ANC V2，5 档（关/降/透/抗/自适应），`[0,4,2,3,1]` | `[0,1,2]` 低中高 | ✅ | LHDC | **实测** |
| `golden_ages_2` | 梦回2 / GOLDEN AGES 2 | TWS-01 定制 SoC（GAIA）；BLE | AudioCuration，4 档（关/降/透/抗），SET `[1,2,4,3]` / GET `[0,1,2,3]` | `[2,1,0]`（反向） | — | LHDC；空间音频 + 头动追踪 | **实测**（双向） |
| `space_travel_2` | 太空漫游2 / SPACE TRAVEL 2 | 中科蓝讯 BT8932F（9ECA + GAIA）；BLE / RFCOMM | AudioCuration，同 GA2 `[1,2,4,3]` / `[0,1,2,3]` | `[2,1,0]`（反向） | — | 无 | **实测**（双向） |
| `golden_ages` | 梦回1979 / 梦回 | 与梦回2同平台同款主控；BLE | AudioCuration，同 GA2 | `[2,1,0]` | — | LHDC；空间音频 | 推断 |
| `moca` | 猫咖 / MOCA | 中科蓝讯（dual-mode，BLE + RFCOMM 兜底） | AudioCuration，4 档恒等 `[1,2,3,4]` | `[0,1,2]` | ✅ | 无 | 推断（真机日志确认存在） |
| `nekocake` | 猫饼 / NEKOCAKE | 中科蓝讯 BT8922E（9ECA）；BLE | AudioCuration，4 档恒等 | `[0,1,2]` | — | 无 | 推断 |
| `pill` | 音乐胶囊 / PILL | 中科蓝讯 BT8932F（9ECA）；BLE | AudioCuration，4 档恒等 | `[0,1,2]` | — | 无 | 推断 |
| `ultrasonic` | 超声波 / ULTRASONIC | 中科蓝讯 BT8952F（9ECA）；BLE | AudioCuration，4 档恒等 | `[0,1,2]` | — | 无 | 推断 |
| `robin` | 知更鸟 / ROBIN | 中科蓝讯 BT8952F（9ECA）；BLE | AudioCuration，4 档恒等 | `[0,1,2]` | — | 无 | 推断 |
| `alice` | 爱丽丝 / ALICE | Qualcomm QCC5151（GAIA）；BLE | AudioCuration，4 档恒等 | `[0,1,2]` | — | LHDC | 推断 |
| `sparks` | 火花 / SPARKS | Qualcomm QCC3040（GAIA）；BLE | AudioCuration，4 档恒等 | `[0,1,2]` | — | LHDC | 推断 |
| `voyager` | 旅行者 / VOYAGER（颈挂） | Qualcomm QCC5144（GAIA）；BLE | AudioCuration，4 档恒等 | `[0,1,2]` | — | LHDC | 推断 |
| `block` | 方糖 / BLOCK | 疑似中科蓝讯 BT8922 系；BLE | AudioCuration，4 档恒等 | 不展示 | — | 无 | 推断 |
| `space_travel` | 太空漫游（一代）/ SPACE TRAVEL | 疑似中科蓝讯（型号未确认）；BLE | AudioCuration，4 档恒等 | `[0,1,2]` | — | 无 | 推断 |
| `edge2` | 羽翼 EDGE2 / EDGE2 | 国产 SoC（型号未公开）；BLE | AudioCuration，3 档，SET `1/2/4` | `[0,1,2]` | — | LHDC、双设备连接、低延迟 | 推断 |
| `space_travel_2_ultra` | 太空漫游2 ULTRA | 国产 SoC（型号未公开）；BLE / 9ECA | AudioCuration，同 GA2 | `[2,1,0]` | — | 无 | 推断 |
| `fallback` | 未知水月雨设备 | 未知；BLE / RFCOMM / 9ECA 全试 | AudioCuration，4 档恒等 | 不展示 | — | 无 | 兜底（能力全靠探测） |

匹配规则：按设备名**别名长度降序**做 `contains`（因此 `SPACE TRAVEL 2` 优先于 `SPACE TRAVEL`，
`EDGE2` 优先于 `EDGE`）；名字含 `MOONDROP` / `水月雨` 但未命中档案 → 用 `fallback`；
否则模块不接管。新增机型只需在 `MODELS` 里加一条数据，**不需要改控制逻辑**（详见 [ADAPTATION.md](ADAPTATION.md)）。

---

## 五、安装与使用

### 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Xiaomi HyperOS（`minSdk 35`，`targetSdk`/`compileSdk` 36） |
| 框架 | LSPosed ≥ 2.1.1 或 Vector（libxposed API 102，模块声明 `staticScope=true`） |
| 耳机 | 水月雨（MOONDROP）蓝牙耳机，需先与手机完成系统配对 |

### 作用域（4 个，必须全部勾选）

```
com.android.bluetooth
com.xiaomi.bluetooth
com.android.systemui
com.android.settings
```

### 权限

模块 App 自身声明并需要授予：`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`、`POST_NOTIFICATIONS`。

### 使用步骤

1. 安装模块 APK，在 LSPosed / Vector 中**启用模块**并勾选上面 4 个作用域；
2. **重启手机**（作用域进程需要重新加载模块代码）；
3. 完成耳机与系统的蓝牙配对；
4. 打开模块 App（或直接点融合设备中心里的耳机卡）查看电量 / 降噪 / 增益等状态。

### 注意

* 作用域变更、模块启用/停用后**必须重启**才生效；
* 本模块不写系统设置、不共享内存，进程间只靠广播通信；
* ⚠ 调试记录：**低延迟模式**是 HyperOS 系统侧开关，模块只负责把系统状态透出到 UI，不发送 GAIA 命令。

---

## 六、实现进度（重要：请先读这一节）

本仓库当前检出（commit `43cc40e`）**只包含协议核心层**；系统集成层（hook）与 UI 层**不在这个检出里**。
下面这张表是逐文件核对的结果，不是推测：

| 层 | 文件 | 状态 |
|---|---|---|
| GAIA 线格式 / feature / 命令构造 | `core/Gaia.kt` | ✅ 已实现 |
| 机型档案库 | `core/MoondropModels.kt` | ✅ 已实现 |
| 电量解析与「右耳不显示」修复 | `core/BatteryCodec.kt` | ✅ 已实现 |
| RFCOMM 流式切帧 | `core/GaiaFramer.kt` | ✅ 已实现 |
| 中科蓝讯 9ECA 私有协议 | `core/SrcProtocol.kt` | ✅ 已实现 |
| 协议客户端（BLE GATT / SPP、能力探测、轮询） | `pods/MoondropLink.kt`、`pods/PodSnapshot.kt` | ✅ 已实现 |
| 跨进程广播契约 / 配置键 | `utils/data/HyperPodsAction.kt`、`HyperPodsPrefsKey.kt` | ✅ 已实现 |
| 单元测试 | `app/src/test/.../GaiaProtocolTest.kt`、`BatteryCodecTest.kt` | ✅ 已实现 |
| **MainActivity / Compose UI** | — | ❌ 检出中无对应源文件（`AndroidManifest.xml` 引用了 `.MainActivity`） |
| **Xposed 入口与四进程 hook** | — | ❌ 检出中无对应源文件（`META-INF/xposed/java_init.list` 指向 `moe.chenxy.hyperpods.hook.XposedEntry`） |
| **字符串资源** | — | ❌ 无 `res/values/strings.xml`，而 manifest 使用 `@string/app_name` |

由此可以确定的两件事（请据此调整预期）：

1. **本仓库当前状态下 `./gradlew :app:assembleDebug` 会在资源链接阶段失败**（`@string/app_name` 缺失），
   单元测试任务同样无法完成（它依赖资源处理）。修复方式是把缺失的 UI / hook / 资源补齐，或临时补一个
   `res/values/strings.xml` —— 但这超出本文档范围，本文档不改任何代码。
2. **截至本文档编写时，没有任何 APK 被构建过，也没有在真机上跑过。** 文中一切「实测」均指**上游项目**
   （FxxkMoondrop / moondrop-link-desktop / PuddingPods）在真机上取得的结论，不是本模块的测试结论。

---

## 七、未实测 / 待真机验证清单

下列项目**没有**真机证据，使用前请自行验证（细节见 [ADAPTATION.md](ADAPTATION.md) 与 [PROTOCOL.md](PROTOCOL.md)）：

| # | 项目 | 现状 |
|---|---|---|
| 1 | **提示音开关 / 音量（feature 0x0E）的命令号** | 官方 App 反编译数据只保留了 feature id，命令号未保留。默认按水月雨自家开关惯例 GET=1 / SET=2，**未实测**；已留逐设备命令号覆盖入口 |
| 2 | `GET_SUPPORTED_FEATURES` 响应体的编码 | 本项目按「32-bit word 位图」解析，上游 moondrop-link 按「(featureId, version) 字节对」解析。两种读法互斥，**未真机抓包裁决**（见 PROTOCOL.md） |
| 3 | EDGE 的 ANC 读回值域 | moondrop-link 实测为 0-based `0..2`；而 EDGE 档案用 `setMap=[1,2,4]` + `getMap=null`（反查），设备回 `0` 会得到 `-1`。**需要真机确认后用 `getMap` 修正** |
| 4 | GAIA 版本探测 | `Gaia.getApiVersion()`（`00 0A 03 00`）已实现但**连接流程没有调用**；当前直接按 vendor `0x001D` 发功能命令，未确认设备 GAIA 版本 |
| 5 | 9ECA 私有协议 | `SrcProtocol.kt` 已实现命令构造与解析，但**未接线到 UI，也无真机验证**（上游 FxxkMoondrop 亦标注未实机验证） |
| 6 | LHDC 开关的实际效果 | 帧格式已由单测锁定；出厂默认 LHDC 关闭（实测当前活动编码为 AAC），开启后是否稳定协商 **未实测** |
| 7 | 低延迟模式 | 判定为 HyperOS 系统侧功能；系统侧实现未在检出中，**未验证** |
| 8 | 13 款「推断」机型 | 芯片级推断，协议可自动识别但未逐型跑通 |
| 9 | 双设备连接的写入与断开单台 | moondrop-link 已读取验证；**写入/断开需双机场景实测**（上游原文） |
| 10 | 系统集成层（通知、超级岛、设备卡、Settings 伪装） | 依赖的 hook 层不在检出中，**无法在本文档范围内验证** |

---

## 八、文档

| 文档 | 内容 |
|---|---|
| [ADAPTATION.md](ADAPTATION.md) | 机型适配指南：档案字段逐项说明、能力探测流程、每型号的 ANC/增益/电量证据、已知未知 |
| [PROTOCOL.md](PROTOCOL.md) | 协议参考：GAIA 帧与逐字节示例、feature 表、电量协议、RFCOMM 切帧、9ECA、ANC 路径选择、来源与推导 |
| [CHANGELOG.md](CHANGELOG.md) | 1.0.0 变更记录 |
| [BUILD.md](BUILD.md) | 构建、单测、GitHub Actions 产物、LSPosed 安装、日志、签名 |

---

## 九、致谢

* [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) —— HyperOS 系统集成基础架构
* [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) —— 水月雨机型适配与实测数据
* [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) —— EDGE 协议逆向（GAIA V3 命令目录）
* [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) —— PUDDING（MD-TWS-056）协议文档
* [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) —— HyperOS 设置页原生耳机伪装技术

---

## 十、许可

本项目以 **GPL-3.0** 许可发布，见 [LICENSE](LICENSE)。

水月雨（MOONDROP）、Qualcomm、Xiaomi、HyperOS 等名称与商标归各自权利人所有；
本项目为第三方非官方适配，与水月雨公司及小米公司无任何关联。
