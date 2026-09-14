<div align="center">

# MiuixMoondrop

**水月雨 MOONDROP 蓝牙耳机控制应用 · HyperOS / Miuix 风格界面**

[![Platform](https://img.shields.io/badge/Platform-Android%2015%2B-green?style=flat-square&logo=android)](https://android.com)
![Form](https://img.shields.io/badge/Form-Plain%20app%20(no%20root%2FXposed)-blue?style=flat-square)
[![ROM](https://img.shields.io/badge/ROM-HyperOS-orange?style=flat-square)](https://hyperos.mi.com)
[![Build](https://github.com/huime180/MiuixMoondrop/actions/workflows/build.yml/badge.svg)](https://github.com/huime180/MiuixMoondrop/actions/workflows/build.yml)

</div>

连上水月雨耳机后自动接管：三路电量、降噪、增益、指示灯、提示音、LHDC 与双设备连接，
并在状态栏通知与连接弹窗里显示。协议栈（GAIA over GATT / RFCOMM）跑在**应用进程内**，
**不需要 root，也不需要 Xposed / LSPosed**。

> 应用名 `MiuixMoondrop`，`applicationId = moe.huime.miuixmoondrop`，Kotlin 包名 / namespace
> 仍是 `moe.chenxy.hyperpods`。仓库旧地址 `huime180/HyperPods-for-Moondrop` 会 301 重定向。

### 耳机功能

- **降噪控制** — 档位表由设备实际能力探测得出（三条 ANC 路径自动选择），不靠型号猜测；
  主档 **通透 / 降噪 / 关闭**，降噪子档 **自定义 / 抗风噪 / 基本**（三档各有图标）
- **电量显示** — 左耳 / 右耳 / 充电盒三路，含「单设备电量」机型的兼容处理
- **增益 / 指示灯 / 提示音** — 提示音是开关 + 音量滑条
- **LHDC 开关** — 与双设备连接互斥，开启时自动关掉另一个并给出确认弹窗
- **双设备连接（一拖二）**
- **手势控制（TOUCHV2）** — 5 个槽位 × 2 只耳，单独一页；**同侧**长按 1 秒与长按 3 秒互斥

### 应用界面

- **底栏两页** — 耳机 / 设置
- **快速弹窗** — 点状态栏通知打开（电量 / 降噪 / 增益 / 快捷控制）
- **连接弹窗** — 耳机连上时自动弹出三路电量，默认 8 秒自动关闭
- **设置页** — 主题、通知栏显示、连接时自动唤出连接弹窗、后台弹出权限入口、手势、关于

### 支持机型

`core/MoondropModels.kt` 的 `MODELS` 共 **17 条** + 1 条兜底档案；按设备名别名**长度降序**匹配
（`SPACE TRAVEL 2` 优先于 `SPACE TRAVEL`，`EDGE2` 优先于 `EDGE`）。新增机型只需加一条数据，
**不需要改控制逻辑**。

| 状态 | 机型 |
|---|---|
| 真机实测 | 羽翼 EDGE、布丁 PUDDING、梦回2、太空漫游2 |
| 档案推断（协议可自动识别，未逐型跑通） | 其余 13 款：梦回1979 / 猫咖 / 猫饼 / 音乐胶囊 / 超声波 / 知更鸟 / 爱丽丝 / 火花 / 旅行者 / 方糖 / 太空漫游 / 羽翼 EDGE2 / 太空漫游2 ULTRA |
| 兜底 | 名称含 `MOONDROP` / 水月雨但未命中档案 → `FALLBACK`（能力全靠探测） |

逐款的主控、传输、ANC 路径与档位、增益映射、能力位见 [ADAPTATION.md](ADAPTATION.md)。

### 系统要求与权限

- **平台**：Android 15+（`minSdk 35` / `targetSdk 36`），目标 ROM 是小米 HyperOS；
  **不需要 root，也不需要任何框架管理器**
- **运行时权限**：`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`、`POST_NOTIFICATIONS`（首次打开时申请）
- **后台弹出弹窗**：`SYSTEM_ALERT_WINDOW`（「显示在其他应用上层」，需在系统设置里手动开）
  加上 HyperOS 的「**后台弹出界面**」——两个都开，应用在后台时连接弹窗才不会被系统静默拦掉
  （Android 10+ 的后台启动 Activity 限制）。设置页有跳转入口

### 使用

1. 安装 APK —— 从 CI 产物 `MiuixMoondrop-apk` 取 `app-debug.apk`（debug 签名，可直接装），或自行构建
2. 打开应用，授予蓝牙 / 通知权限
3. 在**系统蓝牙设置**里配对并连接耳机 —— 应用监听系统 A2DP 广播，连上后自己接管并刷新状态
4. 想让连接弹窗在后台也出现，按上一节把两个权限开好

### 已知限制

- **低延迟模式本应用不实现** —— 它不是 GAIA 命令，要用就去系统蓝牙的「设备详情页」
- **空间音频 / 头动追踪未接线**（`Gaia.spatialGet/Set`、`headTracking*` 有实现，客户端没有读写路径）
- 13 款「推断」机型只有芯片级推断，没有逐型真机跑通

完整清单见 [ADAPTATION.md](ADAPTATION.md) 与 [CHANGELOG.md](CHANGELOG.md)。

### 文档

- [BUILD.md](BUILD.md) — 构建、测试、CI 产物与安装
- [ADAPTATION.md](ADAPTATION.md) — 机型档案、ANC 路径、能力位与逐款差异
- [PROTOCOL.md](PROTOCOL.md) — GAIA 帧格式、命令字节与实测依据
- [CHANGELOG.md](CHANGELOG.md) — 变更记录；顶部「当前状态」一节说明去模块化之后的样子

### 致谢

- [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) — 水月雨机型适配与真机实测数据
- [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) — EDGE 协议逆向
- [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) — PUDDING 协议文档
- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) — UI 版式与组件词汇
- [roxyyn0304/MOONDROP-Pods](https://github.com/roxyyn0304/MOONDROP-Pods) — UI 版式与交互
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件

早期还参考过 [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) 的 HyperOS 系统集成
**模块**骨架（作用域、hook、跨进程控制桥）—— 那部分代码与 manifest 声明已随去模块化整体删除。

### 许可证

GPL-3.0

---

> 早期那套 **LSPosed 模块形态已整体删除**（整个 `hook/`、`META-INF/xposed/` 下的
> `module.prop` / `scope.list`、`arrays.xml` 的 `xposedscope` 等），现在没有任何 hook，
> 也没有任何作用域概念。历史与逐项变更见 [CHANGELOG.md](CHANGELOG.md)。
