# MiuixMoondrop

> **MiuixMoondrop** —— 水月雨（MOONDROP）蓝牙耳机控制应用：连接与三路电量、降噪（含子档位）、
> 手势操作、增益 / 指示灯 / 提示音、LHDC 与双设备连接；界面用 Miuix 按 HyperOS 风格实现。
>
> 这是一个**普通 Android 应用**：协议栈（GAIA over GATT / RFCOMM）跑在**应用进程内**，
> **不需要 root，也不需要 Xposed / LSPosed**。
> 应用名 `MiuixMoondrop`，`applicationId = moe.huime.miuixmoondrop`（namespace 与 Kotlin 包名
> 仍是 `moe.chenxy.hyperpods`，Gradle `rootProject.name = "MiuixMoondrop"`），许可 GPL-3.0。
>
> 仓库：<https://github.com/huime180/MiuixMoondrop>
> （旧地址 `huime180/HyperPods-for-Moondrop` 会 301 重定向到这里。）
>
> 早期那套 **LSPosed 模块形态已整体删除**（整个 `hook/` 目录、`META-INF/xposed/` 下的
> `module.prop` / `scope.list` / `java_init.list`、`arrays.xml` 的 `xposedscope` 等），
> 现在没有任何 hook、没有任何作用域概念；相关历史见文末「十一、历史」。

---

## 构建状态（1.0.0 / versionCode 1）

| 项 | 状态 |
|---|---|
| 单元测试 | ✅ CI 跑 `:app:testDebugUnitTest`，**47** 个用例：`GaiaProtocolTest` 14 + `BatteryCodecTest` 15 + `TouchV2Test` 18 |
| 编译 | ✅ CI `:app:assembleDebug` 与 `:app:assembleRelease`（release 步骤带 `continue-on-error: true`）；AGP 9.1.0 / Kotlin 2.3.20 / JDK 17 / Gradle 9.4.1 |
| APK | ✅ GitHub Actions 产出 `app-debug.apk`（可直接安装）与 `app-release-unsigned.apk`（需自行签名）；artifact 名 **`MiuixMoondrop-apk`**（另有 `test-results`） |
| 装机 | ✅ 已装到目标平板（Xiaomi Pad 8 Pro / HyperOS 4.0） |
| 真机功能验证 | 🟡 **布丁（PUDDING）** 已在真机上联调（2026-09-14 / 09-15），结论写在代码注释里：`core/MoondropModels.kt` 的 `note`、`pods/MoondropLink.kt`、`pods/ControlBridge.kt`、`ui/ConnectionPopupActivity.kt`。其余 16 款机型**没有**本应用自己的真机结论 |

> 本文档作者的本机环境没有 JDK / Android SDK，**CI 是唯一的编译验收手段**；
> 工作流见 [`.github/workflows/build.yml`](.github/workflows/build.yml)（job 名 `Build APK`）。

## 一、这是什么

把一副水月雨（MOONDROP）蓝牙耳机接进 Android：应用自己通过 BLE GATT 或 Classic BT
RFCOMM/SPP 与耳机通话（Qualcomm GAIA 协议），读写三路电量、降噪、手势、增益等状态，
并用 Miuix 画一套 HyperOS 风格的界面。**全部链路都在本应用进程内完成，不注入任何系统进程。**

| 关注点 | 实现在哪 |
|---|---|
| GAIA 帧 / feature / 命令 / 能力位图 / 9ECA | `core/Gaia.kt`、`core/SrcProtocol.kt` |
| RFCOMM/SPP 流式切帧 | `core/GaiaFramer.kt` |
| 三路电量解析 | `core/BatteryCodec.kt` |
| 机型档案（ANC 档位表、增益映射、能力位） | `core/MoondropModels.kt` |
| 连接与读写（自建 GATT / RFCOMM、能力探测、轮询、写队列） | `pods/MoondropLink.kt` |
| 耳机连上时唤醒应用进程 | `pods/BluetoothConnectReceiver.kt`（manifest 静态接收器，监听 A2DP `CONNECTION_STATE_CHANGED`） |
| 应用内状态分发（状态栏通知 / 连接弹窗） | `pods/ControlBridge.kt` |
| 状态栏通知 | `pods/PodNotification.kt` |
| 界面（Compose + Miuix） | `MainActivity.kt`、`ui/**` |

> 本应用**不写系统设置、不共享内存、不注入系统进程**。除了接收系统广播（A2DP 连接状态）以外，
> 它没有任何跨进程通信：`pods/ControlBridge.kt` 只把进程内的协议状态转发给本应用自己的
> 通知与连接弹窗，**不做任何跨进程转发**。

---

## 二、本项目的由来

本项目**不是**从 PuddingPods fork 出来的 —— [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods)
的 GitHub 仓库里**只有文档，没有源码**（本仓库根的 `PuddingPods/` 目录就是它的克隆，
里面只有 `PUDDING_ADAPTATION.md`、README 和 gradle 空壳）。

因此本项目的做法是「拼装 + 重写协议层」：

| 来源 | 取用了什么 |
|---|---|
| [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) | **协议/实测数据**：水月雨机型适配知识：GAIA V3/V4、9ECA 私有协议、三条 ANC 路径、GA2/太空漫游2 实测 ANC 与增益映射、电量与双地址连接经验 |
| [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) | **协议/实测数据**：EDGE 真机逆向的协议目录：GATT UUID、帧格式、BATTERY/ONEBRINGTWO/AUDIO_CURATION/ANC_V2 命令字节 |
| [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) | **协议文档**：PUDDING（MD-TWS-056）的 RFCOMM/SPP 传输、ANC V2 五档、三路电量、增益、指示灯 |
| [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) | **UI 版式与组件词汇**：设备详情页、设置页、主题、互斥确认弹窗等（见 `ui/**` 里对参考实现的逐行注释） |
| [roxyyn0304/MOONDROP-Pods](https://github.com/roxyyn0304/MOONDROP-Pods) | **UI 版式与交互**：底部导航 / 页签分页、快速弹窗、连接弹窗、关于页、设备选择页等 |

> 早期还参考过 [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) 的 HyperOS
> 系统集成**模块**骨架（作用域、hook、跨进程控制桥）——那部分代码与 manifest 声明已随去模块化
> **整体删除**，因此不再算作当前项目的依赖，只在文末「历史」里提一句。

协议字节与命令号均以本仓库源码为准，出处见 [PROTOCOL.md](PROTOCOL.md) 的「来源与推导」一节。

---

## 三、功能一览

| 功能 | 实现要点 | 状态 |
|---|---|---|
| **多机型适配** | `MoondropModels.MODELS` 17 条型号档案 + 兜底档案 `FALLBACK`；「能力优先于型号」，档案只提供差异点 | 档案库已实现；4 款有上游实测依据、13 款芯片级推断 |
| **三路电量（含「单设备电量」兼容）** | `core/BatteryCodec.kt` / `BatteryState`：先问设备支持哪些电池类型 → 按 type 归位 → type 0 同时充填左右耳 → 每组件保留最近有效值 | 协议层已实现并有单测；布丁真机读取通过 |
| **降噪（三条 ANC 路径）** | `ui/components/AncSwitch.kt`。主排「通透 / 降噪 / 关闭」（**图标在上、文案在下**）；降噪生效时展开子排「自定义 / 抗风噪 / 基本」，每档带 on/off 两态图标（自定义 `ic_adaptive_*`、抗风噪 `ic_anti_wind_*` 为本项目自绘、基本复用 `ic_openanc_*`）；idle 图标有 `drawable-night` 变体。档位表来自型号档案，**表里没有的档位不渲染** | 已实现 |
| **ANC 路径自动选择** | `core/Gaia.kt`：AudioCuration(8) > ANC V2(32) > ANC V1(2)，每型号独立映射表 | 协议层已实现 |
| **手势（TOUCHV2，feature 22）** | `ui/GesturePage.kt` + `MoondropLink.setGesture`：配置是 **5 个字节**，每字节打包双耳（高 4 位左、低 4 位右），因此是 **5 × 2 = 10 行**；**同侧**长按 1 秒与长按 3 秒互斥（把一侧设成非「无」时只清同一只耳的另一档），单击/双击/三击之间没有互斥 | 已实现，`TouchV2Test` 18 用例覆盖 |
| **增益（DAC_GAIN 0x0F）** | `DcProfile.gainMap`：低/中/高 → 设备码；PUDDING 与太空漫游2 为反向映射 `[2,1,0]` | 已实现 |
| **指示灯（LED 0x13）** | `DcProfile.hasLed`；0/1 开关 | 已实现 |
| **提示音开关与音量（VOICE 0x0E）** | `PromptTone.kt`：GET=cmd 1 / SET=cmd 2，payload(V2)=`[enabled, volume, index]`，**音量是 0..100 百分比**（没有独立的音量命令）；写入必须一次给全三字节 | 命令与 payload 由官方 App 自身 logcat 实机确认；`index` 语义未知 |
| **LHDC 开关（CODEC_TYPE 0x10）** | cmd 5 读 / cmd 6 写（payload `01`/`00`）；同时保留 LC3、LDAC 构造器 | 帧已由单测锁定；出厂默认 LHDC 关闭（上游实测当前活动编码为 AAC） |
| **双设备连接（ONEBRINGTWO 0x14）** | cmd 1/2 状态、3/4 超时、5/6 设备列表、7 断开单台 | 命令号有 EDGE 真机证据；写入/断开需双机实测 |
| **LHDC / 双设备连接互斥** | `ui/components/MutualExclusion.kt`：两者不能同时开启，切换前弹确认框（`conflict_*` 文案） | 用户实测 PUDDING 固件互斥 |
| **主题** | 跟随系统 / 浅色 / 深色（设置页下拉，`MainActivity` 持久化） | 已实现 |
| **状态栏通知** | `pods/PodNotification.kt`：channel id `hyperpods_moondrop_app_status`（IMPORTANCE_LOW，不响铃）、tag `HyperPodsAppState`、id `10004`；点通知落点是 `ui/PopupActivity.kt`（快速弹窗：电量/降噪/增益/快捷控制） | 已实现；由设置页「通知栏显示」开关控制 |
| **连接弹窗** | `ui/ConnectionPopupActivity.kt`：设备名 + 三路电量，默认 **8s** 自动关闭（可选 3/5/8/10/15/30） | 已实现 |
| **系统蓝牙设置入口** | 详情页跳转系统设备详情页（系统级 LHDC、低延迟、音量同步在**系统页面**设置） | 已实现 |
| **低延迟模式** | **不是 GAIA 命令**，本应用也**没有**自己的低延迟开关（UI 已移除）；系统蓝牙设备详情页里仍有该功能 | 不在本应用实现 |

> 「档案能力位」里的 `lowLatency` 字段仍保留在 `FeatureProfile` 中，但**已不再门控任何 UI**。

---

## 四、支持机型

下表**逐条对应** `app/src/main/java/moe/chenxy/hyperpods/core/MoondropModels.kt` 的 `MODELS`（共 17 条）
与兜底档案 `FALLBACK`。「实测」= 上游项目在该机型上真机验证过；「推断」= 主控确认、协议可自动识别，
但未逐一实机跑通。

| id | 型号 | 主控 / 传输 | ANC 路径与档位 | 增益映射 | 指示灯 | 档案能力位 | 状态 |
|---|---|---|---|---|---|---|---|
| `edge` | 羽翼 EDGE / EDGE | Qualcomm QCC（GAIA v3 / vendor 0x001D）；BLE | AudioCuration，3 档（关/降/透），SET 位掩码 `1/2/4`，GET 0-based `[0,1,2]` | `[0,1,2]` 低中高 | — | 提示音+音量、LHDC、双设备连接 | **实测**（电量只回 type 0） |
| `pudding` | 布丁 / PUDDING（MD-TWS-056） | 国产 SoC（GAIA v4）；RFCOMM/SPP | ANC V2，5 档（关/降/透/抗/自定义），`[0,4,2,3,1]` | `[2,1,0]`（设备码 0=高，反向） | ✅ | 提示音+音量、LHDC、双设备连接 | **实测** |
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
| `edge2` | 羽翼 EDGE2 / EDGE2 | 国产 SoC（型号未公开）；BLE | AudioCuration，3 档，SET 位掩码 `1/2/4`，GET 0-based `[0,1,2]` | `[0,1,2]` | — | LHDC、双设备连接 | 推断 |
| `space_travel_2_ultra` | 太空漫游2 ULTRA | 国产 SoC（型号未公开）；BLE / 9ECA | AudioCuration，同 GA2 | `[2,1,0]` | — | 无 | 推断 |
| `fallback` | 未知水月雨设备 | 未知；BLE / RFCOMM / 9ECA 全试 | AudioCuration，4 档恒等 | 不展示 | — | 无 | 兜底（能力全靠探测） |

匹配规则：按设备名**别名长度降序**做 `contains`（因此 `SPACE TRAVEL 2` 优先于 `SPACE TRAVEL`、
`EDGE2` 优先于 `EDGE`）；名字含 `MOONDROP` / `水月雨` 但未命中档案 → 用 `fallback`；
否则应用不处理该设备（`BluetoothConnectReceiver` 收到其它设备的 A2DP 广播时直接静默返回）。
新增机型只需在 `MODELS` 里加一条数据，**不需要改控制逻辑**（详见 [ADAPTATION.md](ADAPTATION.md)）。

---

## 五、安装与使用

### 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Android（`minSdk 35`，`targetSdk 36`，`compileSdk 37`） |
| 框架 | **无**。普通应用，不需要 root，也不需要 Xposed / LSPosed / Vector |
| 耳机 | 水月雨（MOONDROP）蓝牙耳机，需先与手机完成系统配对 |

### 安装

从 CI 产物（或自行构建）拿到 APK 后：

```bash
# debug 包用 Android 默认 debug 签名，可直接安装
adb install -r app-debug.apk
```

也可以把 APK 拷到设备上直接点击安装。

### 权限

应用声明并在首次打开时申请这几个权限：

| 权限 | 用途 |
|---|---|
| `BLUETOOTH_CONNECT` | 连接耳机、读设备名与连接状态 |
| `BLUETOOTH_SCAN` | 扫描 / 发现耳机 |
| `POST_NOTIFICATIONS` | 发那条耳机状态通知（Android 13+ 运行时权限） |
| `SYSTEM_ALERT_WINDOW` | **特殊权限，需在系统设置里手动开**。为的是让应用在后台也能弹出连接弹窗（Android 10+ 的后台启动 Activity 限制）；**HyperOS 上还需要手动开启「后台弹出界面」**，两个都开才稳。设置页提供了跳转入口 |

### 使用步骤

1. 安装 APK，打开应用并授予蓝牙 / 通知权限；
2. 在**系统蓝牙设置**里把水月雨耳机配对并连接（应用监听系统 A2DP 广播，连上就会自己连接耳机并刷新状态）；
3. 需要后台弹连接弹窗时，打开「显示在其他应用上层」/「后台弹出界面」；
4. 回到应用查看电量 / 降噪 / 手势 / 增益等状态，或用通知里的快速弹窗操作。

### 使用上的注意事项

* 耳机连上后：**先刷新状态栏通知，再延后 600 ms 弹连接弹窗**。后台启动 Activity 可能被
  系统 BAL 静默拦掉，所以最多**重试 3 次（间隔 800 ms）**，用
  `ConnectionPopupActivity.lastShownAt / visible` 确认是否真的显示了；
* 提示音 / LHDC / 双设备连接等开关都受**能力位**与**型号档案**双重门控：耳机没上报对应
  feature 就不展示该行；

---

## 六、目录结构与各层状态

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
│   ├── MoondropLink.kt            # 协议客户端（自建 BLE GATT / RFCOMM、能力探测、轮询、读写）
│   ├── BluetoothConnectReceiver.kt # manifest 静态接收器：A2DP 连上时唤醒应用进程
│   ├── ControlBridge.kt           # 应用内状态桥（通知 + 连接弹窗），不做跨进程转发
│   ├── PodNotification.kt         # 应用进程自己发的那条耳机状态通知
│   └── PodSnapshot.kt             # 状态模型与事件
├── ui/                            # Compose + Miuix 界面
│   ├── PodDetailPage.kt           # 设备页：机型卡 / 电量 / 降噪 / 增益 / 指示灯 / 提示音 / LHDC / 双设备 / 手势入口
│   ├── GesturePage.kt             # 手势页（10 行）
│   ├── PopupActivity.kt           # 快速弹窗（通知点击落点）
│   ├── ConnectionPopupActivity.kt # 连接弹窗（连上耳机时弹一次）
│   ├── pages/                     # EarphonesTabPage / SettingsPage / AboutPage / DevicePickerPage
│   ├── components/                # AncSwitch / PodStatus / PromptTone / MutualExclusion / AppIcons
│   └── effect/                    # 背景动效（RuntimeShader）
└── utils/data/                    # Intent action / 偏好键
app/src/test/java/.../core/        # GaiaProtocolTest / BatteryCodecTest / TouchV2Test
```

### 各层状态

| 层 | 文件 | 状态 |
|---|---|---|
| 协议核心（线格式、机型档案、电量、切帧、9ECA） | `core/*` | ✅ 已实现 |
| 协议客户端 | `pods/MoondropLink.kt`、`pods/PodSnapshot.kt` | ✅ 已实现 |
| 进程唤醒 | `pods/BluetoothConnectReceiver.kt` | ✅ 已实现 |
| 应用内状态桥（通知 / 连接弹窗） | `pods/ControlBridge.kt`、`pods/PodNotification.kt` | ✅ 已实现 |
| 单元测试 | `app/src/test/.../core/*.kt` | ✅ 已实现（47 个用例：GaiaProtocolTest 14 + BatteryCodecTest 15 + TouchV2Test 18） |
| Compose / Miuix UI | `ui/*`、`MainActivity.kt` | ✅ 已实现 |
| 字符串资源 | `res/values/strings.xml`（英文）、`res/values-zh-rCN/strings.xml`（中文） | ✅ 已实现，两份键集一致 |

---

## 七、未接线 / 未验证清单

| # | 项目 | 现状 |
|---|---|---|
| 1 | 提示音 `index` 字段 | 命令号（GET=cmd 1 / SET=cmd 2）与 payload `[enabled, volume(0..100), index]` 已由**官方 App logcat 实机确认**；**仍未确认** `index`（语言/主题）的取值语义 |
| 2 | `GET_SUPPORTED_FEATURES` 响应体的编码 | 上游两派读法互斥（`(featureId, version)` 字节对 vs 32-bit word 位图）；代码现在**两种都试**（`Gaia.parseSupportedFeaturesSmart()` 先按字节对、失败回退位图）。**具体固件用哪种、是否会被误判，仍未真机抓包确认** |
| 3 | EDGE 的 ANC 读回值域 | 已按上游实测给 EDGE / EDGE2 补上 `getMap = [0,1,2]`（SET 仍是位掩码 `1/2/4`，GET 是 0-based `0..2`）；**该参数本身仍未真机复核** |
| 4 | GAIA 版本探测 | 连接流程会先发 `00 0A 03 00`（`Gaia.getApiVersion()`）再探测能力；**探测结果的解析与用途未真机确认** |
| 5 | 9ECA 私有协议 | `core/SrcProtocol.kt` 已实现帧构造与解析，但**未接线、未真机验证**（上游 FxxkMoondrop 亦标注未实机验证） |
| 6 | LHDC 开关的实际效果 | 帧格式已由单测锁定，且官方 App logcat 实机打印出「关闭 LHDC」= `00 1D 20 06 00`；出厂默认 LHDC 关闭，开启后是否稳定协商**未实测** |
| 7 | 空间音频 / 头动追踪 | `Gaia.spatialGet/Set`、`headTracking*` 与档案字段存在，但**客户端没有读写路径** |
| 8 | 13 款「推断」机型 | 芯片级推断，协议可自动识别但未逐型跑通 |
| 9 | 双设备连接的写入与断开单台 | moondrop-link 已读取验证；**写入/断开需双机场景实测**（上游原文） |
| 10 | EDGE 增益映射 | 档案为恒等 `[0,1,2]`，**无实测依据**（推断值）。提示音音量量程已确认为 100（官方 App logcat） |
| 11 | 低延迟模式 | 本应用不实现（无 GAIA 命令、无自己的开关）；系统设备页的功能不归本应用控制 |

> 所有「实测」均指**上游项目**（FxxkMoondrop / moondrop-link-desktop / PuddingPods）或
> **官方 App 自身 logcat / 布丁真机联调**取得的结论，不是全部机型在本应用里的运行结果。

---

## 八、文档

| 文档 | 内容 |
|---|---|
| [ADAPTATION.md](ADAPTATION.md) | 机型适配指南：档案字段逐项说明、能力探测流程、每型号的 ANC/增益/电量证据、已知未知 |
| [PROTOCOL.md](PROTOCOL.md) | 协议参考：GAIA 帧与逐字节示例、feature 表、电量协议、RFCOMM 切帧、9ECA、ANC 路径选择、来源与推导 |
| [CHANGELOG.md](CHANGELOG.md) | 变更记录（顶部有「当前状态」说明，其下为历史条目） |
| [BUILD.md](BUILD.md) | 构建、单测、GitHub Actions 产物、安装、日志、签名 |

---

## 九、致谢

* [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) —— 水月雨机型适配与实测数据（协议来源）
* [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) —— EDGE 协议逆向（GAIA V3 命令目录）
* [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) —— PUDDING（MD-TWS-056）协议文档
* [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) —— UI 版式与组件词汇参考
* [roxyyn0304/MOONDROP-Pods](https://github.com/roxyyn0304/MOONDROP-Pods) —— UI 版式与交互参考

---

## 十、许可

本项目以 **GPL-3.0** 许可发布，见 [LICENSE](LICENSE)。

水月雨（MOONDROP）、Qualcomm、Xiaomi、HyperOS 等名称与商标归各自权利人所有；
本项目为第三方非官方适配，与水月雨公司及小米公司无任何关联。

---

## 十一、历史：LSPosed 模块形态（**代码已删除**）

> ⚠️ 本节只是历史记录。下面提到的文件与 manifest 声明**在本仓库里已经不存在**，
> 引用它们只为解释文档与提交历史的来龙去脉；当前实现一律**以代码为准**。

这个项目最初是一个 **LSPosed / Xposed 模块**，试图把水月雨耳机「接进」HyperOS 的系统界面。
它当时跨 5 个进程工作（进程间靠广播通信），并需要 root + LSPosed/Vector：

| 当时的进程 | 当时做了什么 |
|---|---|
| 应用进程（协议客户端 + UI） | BLE GATT / RFCOMM 与耳机通信 |
| `com.android.bluetooth` | hook `A2dpService#handleConnectionStateChanged` 感知连接、把电量写回系统蓝牙栈 |
| `com.android.systemui` | 用 `miui.systemui.plugin` 插件 ClassLoader 接管融合设备中心的耳机卡点击 |
| `com.xiaomi.bluetooth` | hook `MiuiBluetoothNotification` 展示耳机电量通知 |
| `com.android.settings` | 用 Device ID `01010607` 伪装成小米原生耳机，路由系统耳机页的操作 |

已删除的内容（现在的仓库里找不到任何一行）：

* 整个 `hook/` 目录（14 个文件，含 `XposedEntry.kt`、`HookContext.kt`、`HeadsetStateDispatcher.kt`、
  `SystemUIPluginHook.kt`、`DeviceCardHook.kt`、`MiBluetoothToastHook.kt`、`SettingsHeadsetHook.kt` 等）；
* `app/src/main/resources/META-INF/xposed/`（`module.prop` / `scope.list` / `java_init.list`）；
* `app/src/main/res/values/arrays.xml`（`xposedscope`）；
* `ui/XposedServiceState.kt`、`ui/BluetoothStatus.kt`、`ui/pages/HomePage.kt`、`ui/components/RestartScope.kt`；
* `pods/ControlBridge.kt` 里的跨进程转发与 manifest 里的跨进程广播接收器
  （原先把 ANC / 电量 / 手势 / 通知状态发往 `com.android.bluetooth`、`com.android.settings`、
  `com.xiaomi.bluetooth`、`com.milink.service`，那些接收方全是 hook）。

**随之一起失效的功能**（不是 bug，是没有数据源了）：超级岛 / 焦点通知的 HyperOS 专属显示、
融合设备中心设备卡接管、系统设置页的原生耳机伪装、跨进程控制桥、详情页的编码显示
（数据源消失，该行随后也已删除）。
其中 `miui.focus.*`（焦点通知 / 超级岛）的字段知识仍保留在 [PROTOCOL.md](PROTOCOL.md)
第 10.5 节，作为**参考资料**（当前应用只发一条普通的 `IMPORTANCE_LOW` 状态通知）。
