# HyperPods for Moondrop

> 让 **Xiaomi HyperOS** 把 **MOONDROP（水月雨）** 蓝牙耳机当作系统原生耳机来管理：
> 融合设备中心设备卡、超级岛 / 通知电量、设置页耳机入口、系统级降噪与增益控制。
>
> 这是一个 **LSPosed / Xposed 模块**（libxposed API 102），Kotlin + Jetpack Compose + Miuix。
> 命名空间 `moe.chenxy.hyperpods`，applicationId `moe.chenxy.hyperpods.moondrop`，
> minSdk 35 / compileSdk 36 / targetSdk 36，许可 GPL-3.0。

---

## 构建状态（1.0.0）

| 项 | 状态 |
|---|---|
| 单元测试 | ✅ CI 通过，29 用例（协议帧构造 14 + 电量解析 15），含「单设备电量左右耳都显示」的回归用例与官方 App logcat 原样字节用例 |
| 编译 | ✅ CI `:app:assembleDebug` / `assembleRelease` 通过（AGP 8.13 / Kotlin 2.2.20 / JDK 17） |
| APK | ✅ GitHub Actions 产出 `app-debug.apk`（可直接安装）与 `app-release-unsigned.apk`（需自行签名） |
| 装机 | ✅ 已用 `pm install` 装到目标平板（Xiaomi Pad 8 Pro / Android 17 / HyperOS 4.0） |
| 真机功能验证 | ❌ **尚未开始**：模块需在 LSPosed 管理器中勾选启用并重启后才生效，本文档不声称任何功能已在真机上跑通 |

> ⚠️ 首次启用建议先确认可用于救砖（LSPosed 安全模式 / 音量键组合）。所有 hook 均已 `runCatching` 兜底，
> 单个系统类缺失只会跳过该 hook 并打日志，不会让进程崩溃。

## 一、这是什么

HyperOS 的「融合设备中心」「超级岛」「蓝牙设置页」原生只认识小米/部分合作品牌耳机。
本项目把一条 **水月雨（MOONDROP）GAIA 协议客户端**接进系统，跨 5 个进程工作
（进程间只靠广播通信，**所有跨进程广播都 `setPackage(...)`**，因为 Android 14+ 会丢弃未指定包名的隐式广播）：

| 进程 | 角色 | 关键动作 |
|---|---|---|
| 本模块应用进程 `moe.chenxy.hyperpods.moondrop` | **协议客户端 + UI** | 用 BLE GATT 或 RFCOMM/SPP 与耳机通信；Compose/Miuix 界面；能力探测与状态轮询 |
| `com.android.bluetooth` | 连接感知 + 电量写回 | hook `A2dpService#handleConnectionStateChanged` 感知 A2DP/HFP 连接；把电量写进系统蓝牙栈（`AdapterService.setBatteryLevel`）；应答 SystemUI 的 MAC 询问 |
| `com.android.systemui` | 融合设备中心 | 用 `miui.systemui.plugin` 插件 ClassLoader 挂 `DeviceInfoWrapper#performClicked`，接管 `deviceType == "third_headset"` 的耳机卡点击 |
| `com.xiaomi.bluetooth` | 通知 / 电量展示 | hook `MiuiBluetoothNotification` 构造函数拿到进程 Context，注册并展示耳机电量通知 |
| `com.android.settings` | 伪装原生耳机页 | 用兼容 Device ID `01010607` 伪装成小米原生耳机，拦截 `IMiuiHeadsetService$Stub$Proxy`，把系统耳机页的操作路由回本模块 |

> ⚠ 本模块**不写系统设置、不共享内存**，进程间只有广播；
> ⚠ 本模块**不含任何 `.mp4` / base64 强提示视频资产**：原 HyperPods 的 AirPods 强提示视频路线已丢弃，
> `SEND_STRONG_TOAST` 降级为同一套普通耳机电量通知。

---

## 二、本项目的由来

本项目**不是**从 PuddingPods fork 出来的 —— [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods)
的 GitHub 仓库里**只有文档，没有源码**（本仓库根的 `PuddingPods/` 目录就是它的克隆，
里面只有 `PUDDING_ADAPTATION.md`、README 和 gradle 空壳）。

因此本项目的做法是「拼装 + 重写协议层」：

| 来源 | 取用了什么 |
|---|---|
| [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) | HyperOS 系统集成骨架（四进程作用域、蓝牙状态分发、设备卡点击接管、通知、Miuix UI 结构） |
| [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) | 水月雨机型适配知识：GAIA V3/V4、9ECA 私有协议、三条 ANC 路径、GA2/太空漫游2 实测 ANC 与增益映射、电量与双地址连接经验 |
| [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) | EDGE 真机逆向的协议目录：GATT UUID、帧格式、BATTERY/ONEBRINGTWO/AUDIO_CURATION/ANC_V2 命令字节 |
| [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) | PUDDING（MD-TWS-056）协议文档：RFCOMM/SPP 传输、ANC V2 五档、三路电量、增益、指示灯、HyperOS Device ID `01010607` |
| [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) | HyperOS 设置页伪装成原生耳机的技术路线（`HeadsetIDConstants` / `IMiuiHeadsetService$Stub$Proxy`）与通用 hook 基类风格 |

协议字节与命令号均以本仓库源码为准，出处见 [PROTOCOL.md](PROTOCOL.md) 的「来源与推导」一节。

---

## 三、功能一览

| 功能 | 实现要点 | 状态 |
|---|---|---|
| **多机型适配** | `MoondropModels.MODELS` 17 条型号档案 + 兜底档案；「能力优先于型号」，档案只提供差异点 | 档案库已实现；4 款有上游实测依据、13 款芯片级推断 |
| **三路电量（含「单设备电量」兼容）** | `BatteryCodec` / `BatteryState`：先问设备支持哪些电池类型 → 按 type 归位 → type 0 同时充填左右耳 → 每组件保留最近有效值 | 协议层已实现并有单测；EDGE 只回 type 0 有上游真机证据 |
| **降噪（三条 ANC 路径）** | 自动从能力位图选路径：AudioCuration(8) > ANC V2(32) > ANC V1(2)；每型号独立映射表 | 协议层已实现；GA2 / 太空漫游2 / PUDDING / EDGE 映射有上游实测依据 |
| **增益（DAC_GAIN 0x0F）** | `DcProfile.gainMap`：低/中/高 → 设备码；GA2 与太空漫游2 为反向映射 | 已实现 |
| **指示灯（LED 0x13）** | `DcProfile.hasLed`；0/1 开关 | 已实现（PUDDING 有上游实测） |
| **提示音开关与音量（VOICE 0x0E）** | ✅ 命令号与 payload 已由**官方 App 自身 gaiaclient 的 logcat** 实机确认：GET=cmd 1 / SET=cmd 2，payload(V2)=`[enabled, volume, index]`，**音量是 0..100 百分比**（没有独立的音量命令）；写入必须一次给全三字节 | 命令与 payload **已确认**；`index` 语义未知，且本模块自身尚未真机跑通 |
| **LHDC 开关（CODEC_TYPE 0x10）** | cmd 5 读 / cmd 6 写（payload `01`/`00`）；同时保留 LC3、LDAC 构造器 | 帧已由单测锁定；出厂默认 LHDC 关闭（上游实测当前活动编码为 AAC） |
| **双设备连接（ONEBRINGTWO 0x14）** | cmd 1/2 状态、3/4 超时、5/6 设备列表、7 断开单台 | 命令号有 EDGE 真机证据；写入/断开需双机实测 |
| **低延迟模式** | **这是 HyperOS 系统侧功能，不是 GAIA 命令**；已接通 UI → `ControlBridge` → `com.android.bluetooth`（先反射厂商直通方法，否则走 A2DP codec `getCodecStatus`/`setCodecConfigPreference` 兜底）→ 回 `LOW_LATENCY_CHANGED` | 已实现，**未真机验证**（见第六节） |
| **超级岛与融合设备中心** | SystemUI 插件 ClassLoader 里接管耳机卡点击 → 打开模块 UI；MAC 握手在独立 HandlerThread 上完成（不阻塞 SystemUI 主线程） | 已实现，**未真机验证** |
| **通知与电量显示** | `ControlBridge` 把电量经 `UPDATE_PODS_NOTIFICATION` / `SEND_STRONG_TOAST` 发到 `com.xiaomi.bluetooth`，由该进程展示耳机电量通知（id 10003 / tag `BTHeadset<addr>` / `IMPORTANCE_MIN` 通道，复用小米蓝牙本地化字符串） | 已实现，**未真机验证** |
| **设置页耳机入口** | `com.android.settings` 用 `01010607` 伪装四档 ANC 模板，注入 `updateAtUiInfo/updateAncUi/refreshStatus`，3 秒实时同步 | 已实现，**未真机验证**；`MiuiHeadsetBattery` 注入未做 |

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

匹配规则：按设备名**别名长度降序**做 `contains`（因此 `SPACE TRAVEL 2` 优先于 `SPACE TRAVEL`、
`EDGE2` 优先于 `EDGE`）；名字含 `MOONDROP` / `水月雨` 但未命中档案 → 用 `fallback`；
否则模块不接管（蓝牙进程/设置页/设备卡都先做这一步判定）。
新增机型只需在 `MODELS` 里加一条数据，**不需要改控制逻辑**（详见 [ADAPTATION.md](ADAPTATION.md)）。

---

## 五、安装与使用

### 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Xiaomi HyperOS（`minSdk 35`，`targetSdk`/`compileSdk` 36） |
| 框架 | LSPosed ≥ 2.1.1 或 Vector（libxposed API 102；模块 `module.prop` 声明 `minApiVersion=101` / `targetApiVersion=102` / `staticScope=true`） |
| 耳机 | 水月雨（MOONDROP）蓝牙耳机，需先与手机完成系统配对 |

### 作用域（4 个，必须全部勾选）

```
com.android.bluetooth
com.xiaomi.bluetooth
com.android.systemui
com.android.settings
```

### 权限

模块 App 声明并需要授予：`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`、`POST_NOTIFICATIONS`。

### 使用步骤

1. 安装模块 APK，在 LSPosed / Vector 中**启用模块**并勾选上面 4 个作用域；
2. **重启手机**（作用域进程需要重新加载模块代码）；
3. 完成耳机与系统的蓝牙配对；
4. 打开模块 App（或直接点融合设备中心里的耳机卡）查看电量 / 降噪 / 增益等状态。

### 使用上的注意事项

* 作用域变更、模块启用/停用后**必须重启**才生效；
* **模块迟装兜底**：模块可能在耳机已连接之后才被安装/启用。此时 `A2dpService.onCreate` 的 hook 会延迟
  1.5 秒扫描已连接的 A2DP/HFP 设备并补发一次连接事件（`HeadsetStateDispatcher` 的 bootstrap）；
* **融合设备中心卡片要点两次**：第一次点击只用来向蓝牙进程询问当前耳机 MAC（**不阻塞、不 sleep**，
  不卡 SystemUI 主线程），第二次点击（MAC 已缓存）才会打开模块 UI；
* 设备卡/设置页只在设备名能被 `MoondropModels.match()` 识别为水月雨设备时才处理；其它设备一律放行；
* 设置页伪装使用兼容 Device ID `01010607`（PuddingPods 记录的水月雨兼容 HyperOS 内部 ID，
  **不代表真实水月雨型号**）。

---

## 六、模块结构与实现状态

### 目录结构

```
app/src/main/java/moe/chenxy/hyperpods/
├── MainActivity.kt                # Compose 入口（edge-to-edge）
├── core/                          # 纯 Kotlin 协议核心（不依赖 Android，可直接单测）
│   ├── Gaia.kt                    # GAIA 线格式 / feature / 命令构造与解析
│   ├── GaiaFramer.kt              # RFCOMM/SPP 流式切帧
│   ├── BatteryCodec.kt            # 电量解析 +「右耳不显示」修复
│   ├── MoondropModels.kt          # 机型档案库
│   └── SrcProtocol.kt             # 中科蓝讯 9ECA 私有协议
├── pods/
│   ├── MoondropLink.kt            # 协议客户端（BLE GATT / SPP、能力探测、轮询、读写）
│   ├── ControlBridge.kt           # 应用侧跨进程控制桥（ControlBridge + manifest 声明的 ControlReceiver）
│   └── PodSnapshot.kt             # 状态模型与事件
├── hook/                          # libxposed API 102 系统集成层
│   ├── XposedEntry.kt             # 模块入口（按进程分发 hook + 热重载）
│   ├── HookContext.kt             # hook 基类（*OrNull 查找、失败即降级）
│   ├── HeadsetStateDispatcher.kt  # com.android.bluetooth
│   ├── SystemUIPluginHook.kt      # com.android.systemui（插件 ClassLoader）
│   ├── DeviceCardHook.kt          # 融合设备中心设备卡
│   ├── MiBluetoothToastHook.kt    # com.xiaomi.bluetooth 通知
│   ├── SettingsHeadsetHook.kt     # com.android.settings 伪装原生耳机
│   └── SystemApisUtils.kt         # 反射系统 API + 三路电量 extra 约定
├── ui/                            # Compose + Miuix 界面（详情页 / 关于页 / 组件）
└── utils/data/                    # 跨进程广播契约 + 配置键
app/src/main/resources/META-INF/xposed/   # module.prop / scope.list / java_init.list
app/src/test/java/.../core/                # GaiaProtocolTest / BatteryCodecTest
```

### 各层状态

| 层 | 文件 | 状态 |
|---|---|---|
| 协议核心（线格式、机型档案、电量、切帧、9ECA） | `core/*` | ✅ 已实现 |
| 协议客户端 | `pods/MoondropLink.kt`、`PodSnapshot.kt` | ✅ 已实现 |
| **应用侧跨进程控制桥** | `pods/ControlBridge.kt` + manifest 里的 `ControlReceiver` | ✅ 已实现；**未真机验证**（低延迟路径见下） |
| 单元测试 | `app/src/test/.../core/GaiaProtocolTest.kt`、`BatteryCodecTest.kt` | ✅ 已实现（29 个用例：GaiaProtocolTest 14 + BatteryCodecTest 15，逐字节锁定帧与电量回归，含官方 App logcat 原样字节） |
| Xposed 入口 / 四进程 hook | `hook/*` | ✅ 已实现，**未真机验证** |
| Compose / Miuix UI | `ui/*`、`MainActivity.kt` | ✅ 已实现，**未真机验证** |
| 字符串资源 | `res/values/strings.xml`、`res/values-zh-rCN/strings.xml` | ✅ 已实现 |

### 应用侧跨进程控制桥（已接线）

协议客户端跑在应用进程，而控制入口分散在系统各进程。`pods/ControlBridge.kt` 用一个
**manifest 声明的广播接收器**（`pods.ControlReceiver`，见 `AndroidManifest.xml`）把这条链路接通：
因为是显式广播（`setPackage(...)`）+ manifest 声明，**即使本模块 App 没有在运行，系统也能用广播把
应用进程拉起来执行控制命令** —— 这正是「系统设置页点降噪」这种场景需要的（用户不必先打开本 App）。

| 方向 | 动作 | 效果 |
|---|---|---|
| `com.android.bluetooth` → 应用进程 | `PODS_CONNECTED` / `PODS_DISCONNECTED` | 桥用 `getRemoteDevice(mac)` 取回 `BluetoothDevice` 并 `MoondropLink.connect()` / `disconnect()`；非水月雨设备直接忽略 |
| 系统设置页 → 应用进程 | `ANC_SELECT` / `GAIN_SELECT` / `LED_SELECT` / `PROMPT_TONE_SELECT` / `PROMPT_VOLUME_SELECT` / `LHDC_SELECT` / `DUAL_CONNECTION_SELECT` | 分别路由到 `MoondropLink.setAnc/setGain/setLed/setPromptTone/setPromptVolumeRaw/setLhdc/setDualConnection` |
| 系统侧 → 应用进程 | `UI_INIT` / `REQUEST_CAPABILITIES` / `REQUEST_BATTERY` | 触发 `refreshAll()` / `requestBatteryRefresh()` 做状态重放 |
| 应用进程 → `com.android.bluetooth` | `UPDATE_SYSTEM_BATTERY`（带 `EXTRA_LEVEL` + `EXTRA_DEVICE`） | 经 `HeadsetStateDispatcher` 反射调用 `AdapterService.setBatteryLevel`，系统蓝牙页 / 融合设备中心显示电量 |
| 应用进程 → `com.android.settings` | `ANC_CHANGED` / `BATTERY_CHANGED` | 喂给被伪装的系统耳机页做实时显示 |
| 应用进程 → `com.xiaomi.bluetooth` | `UPDATE_PODS_NOTIFICATION` / `SEND_STRONG_TOAST` / `CANCEL_PODS_NOTIFICATION` | 通知 / 电量展示链路 |
| 应用进程 → `com.android.bluetooth` → 应用进程 | `LOW_LATENCY_SELECT` → `LOW_LATENCY_CHANGED` | 见下 |

电量经 `Bundle` 传递：键 `left` / `right` / `case`（+ `left_charging` / `right_charging` / `case_charging`），
编码 `255 = 未知`、`value or 128 = 充电中`。系统蓝牙栈只需要一个单值，取左右耳较小者。
`MoondropLink` 现在维护**监听者列表**（`addListener` / `removeListener`），因此桥的转发器与 UI 监听者可以并存，
不会互相覆盖。

**低延迟（已实现完整路径，仍未真机验证）**：UI 广播 `LOW_LATENCY_SELECT` → `ControlReceiver`（做乐观状态更新）
→ 转发给 `com.android.bluetooth` 的 `HeadsetStateDispatcher`；那里先反射尝试厂商可能的直通方法
（`setLowLatencyMode` / `setLowLatencyAudioEnabled` / `setLatencyMode` / `enableLowLatency`），
都不存在时退化为 **A2DP codec 路径**（`getCodecStatus(device)` 读当前 codec，从可选能力里按
LHDC / LDAC / aptX-adaptive / LC3 / AAC 的顺序挑候选，用 `setCodecConfigPreference(device, config)` 下发；
关闭时恢复原 codec 配置），最后回一条 `LOW_LATENCY_CHANGED`。任何一步不可用都只回「保持原状态」并记日志。

### 仍未接线 / 未验证的部分

| # | 项目 | 影响 |
|---|---|---|
| 1 | 空间音频 / 头动追踪（`Gaia.spatialGet/Set`、`headTracking*`）**未接线到客户端** | 档案里的空间音频能力目前不会真正读写设备 |
| 2 | 低延迟的系统侧实现 | 代码路径完整（上述反射 + A2DP codec 兜底），但**从未在真机验证**；不同 ROM 的 A2DP 隐藏 API 可能不可用 |
| 3 | `SettingsHeadsetHook` 的状态注入 | `MiuiHeadsetFragment#updateAtUiInfo / updateAncUi / refreshStatus` 按 OppoPods 用法调用，**需实机核对**；`MiuiHeadsetBattery` 电量控件注入未实现 |

> 接线桥依赖反射与系统隐藏 API，属「已实现、未真机验证」；核心协议与帧构造都有单测覆盖。

### 构建与验证状态（非常重要）

* 构建 / 装机（CI 通过、APK 产出、已装机到 Xiaomi Pad 8 Pro）见本文开头的
  **「构建状态（1.0.0）」**小节；本文档作者本机无 JDK / SDK / 网络，未在本机执行 Gradle。
* **本模块没有任何真机功能验证结论**（这是最重要的那句话）。
* 所有「实测」均指**上游项目**（FxxkMoondrop / moondrop-link-desktop / PuddingPods）或
  **官方 App 自身 logcat**（2026-09-14）取得的结论，不是本模块自身的真机运行结果。
* 构建与验证步骤见 [BUILD.md](BUILD.md)。

---

## 七、未实测 / 待真机验证清单

| # | 项目 | 现状 |
|---|---|---|
| 1 | 提示音 `index` 字段与「本模块自身」的实机表现 | ✅ 命令号（GET=cmd 1 / SET=cmd 2）与 payload `[enabled, volume(0..100), index]` 已由**官方 App logcat 实机确认**（写入必须一次给全三字节）。**仍未确认**：`index`（语言/主题）的取值语义；以及本模块自身尚未在真机上跑通提示音读写 |
| 2 | `GET_SUPPORTED_FEATURES` 响应体的编码 | 上游两派读法互斥（`(featureId, version)` 字节对 vs 32-bit word 位图）；代码现在**两种都试**（`Gaia.parseSupportedFeaturesSmart()` 先按字节对、失败回退位图）。**具体固件用哪种、是否会被误判，仍未真机抓包确认** |
| 3 | EDGE 的 ANC 读回值域 | 已按上游实测给 EDGE / EDGE2 补上 `getMap = [0,1,2]`（SET 仍是位掩码 `1/2/4`，GET 是 0-based `0..2`）；**该参数本身仍未真机复核** |
| 4 | GAIA 版本探测 | 连接流程现在会先发 `00 0A 03 00`（`Gaia.getApiVersion()`）再探测能力；**探测结果的解析与用途未真机确认** |
| 5 | 9ECA 私有协议 | `SrcProtocol.kt` 已实现帧构造与解析，但**未接线、未真机验证**（上游 FxxkMoondrop 亦标注未实机验证） |
| 6 | LHDC 开关的实际效果 | 帧格式已由单测锁定，且官方 App logcat 实机打印出「关闭 LHDC」= `00 1D 20 06 00`（feature `0x10`/cmd 6/payload `00`）作为旁证；出厂默认 LHDC 关闭（上游实测当前活动编码为 AAC），开启后是否稳定协商**未实测** |
| 7 | 低延迟模式 | 已实现完整链路（`ControlBridge` → `com.android.bluetooth` 的反射桥 + A2DP codec 兜底 + `LOW_LATENCY_CHANGED` 回包），但**从未在真机验证**，隐藏 API 可能不可用 |
| 8 | 13 款「推断」机型 | 芯片级推断，协议可自动识别但未逐型跑通 |
| 9 | 双设备连接的写入与断开单台 | moondrop-link 已读取验证；**写入/断开需双机场景实测**（上游原文） |
| 10 | 系统集成层（通知 / 超级岛 / 设备卡 / Settings 伪装 / 跨进程控制桥） | 源码已就位并已接线，但**从未在真机上运行过** |
| 11 | `SettingsHeadsetHook` 的状态注入签名 | `MiuiHeadsetFragment#updateAtUiInfo / updateAncUi / refreshStatus` 的真实签名与字段含义按 OppoPods 在 HyperOS 上的用法调用，需实机核对；`MiuiHeadsetBattery` 电量控件注入未实现 |
| 12 | EDGE 增益映射 | 档案为恒等 `[0,1,2]`，**无实测依据**（推断值）。提示音音量量程已确认为 100（官方 App logcat） |

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
