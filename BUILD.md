# 构建与安装（BUILD）

> **状态说明**：MiuixMoondrop 是一个**普通 Android 应用**（早期那套 LSPosed 模块形态已整体删除）。
> 构建产物就是普通 APK，安装后**不需要 root / Xposed / LSPosed**，只需授予蓝牙与通知权限；
> 连接弹窗还需要「显示在其他应用上层」/「后台弹出界面」权限（见应用内设置页入口）。
> 本文不含任何 `module.prop` / 作用域 / `scope.list` 相关内容。

> 本文说明如何编译、测试、打包与安装本项目。
>
> ⚠ **诚实声明**：编写本文档的环境**没有 JDK、没有 Android SDK、也没有网络**，因此本机的
> Gradle 命令**没有被本文档作者实际执行过**；但 CI（GitHub Actions）已通过单测与编译并产出 APK。
> 仓库本身不含 APK 产物。下面的内容全部来自对 `app/build.gradle.kts`、`gradle/libs.versions.toml`、
> `gradle/wrapper/gradle-wrapper.properties`、`settings.gradle.kts` 与 `.github/workflows/build.yml`
> 的逐行核对。

---

## 1. 前置条件

| 项 | 要求 | 依据 |
|---|---|---|
| JDK | 工具链目标 **17**；CI 用 **temurin 21** 运行 Gradle（AGP 9 要求 JDK 17+） | `app/build.gradle.kts` 的 `java { toolchain }` 与 `kotlin { jvmToolchain(17) }`、`.github/workflows/build.yml` |
| Android SDK | `compileSdk = 37` 的平台 + `build-tools;36.0.0`；`ANDROID_HOME` / `local.properties` 指向 SDK。**compileSdk 必须是 37**：Miuix 0.9.3 的 AAR metadata 声明 `minCompileSdk=37` | `app/build.gradle.kts`、`.github/workflows/build.yml` 的 `sdkmanager` 步骤 |
| Gradle | **9.4.1**（wrapper 会自动下载 `gradle-9.4.1-bin.zip`） | `gradle/wrapper/gradle-wrapper.properties` |
| Android Studio | 能打开 AGP 9.1 项目的版本即可（建议较新版本） | — |
| 网络 | 需要访问 `gradlePluginPortal()`、`google()`、`mavenCentral()`、`https://s01.oss.sonatype.org/content/repositories/releases/`、`https://jitpack.io`（以及 `org.gradle.toolchains.foojay-resolver-convention` 插件） | `settings.gradle.kts` |
| NDK | **不需要**。本项目**没有任何 native 代码** | 无 `externalNativeBuild`、无 `jniLibs` |

### 版本一览（`gradle/libs.versions.toml`）

| 组件 | 版本 |
|---|---|
| Android Gradle Plugin | 9.1.0（自带 Kotlin 编译支持，不再 apply `org.jetbrains.kotlin.android`） |
| Kotlin（KGP，由根 `build.gradle.kts` 的 buildscript classpath 提升） | 2.3.20 |
| Gradle | 9.4.1 |
| Compose | **androidx compose**：`androidx.compose:compose-bom` 2025.05.00 + `ui` / `foundation` / `ui-tooling(-preview)` |
| Miuix（**拆分产物**） | 0.9.3：`miuix-ui-android`、`miuix-preference-android`、`miuix-icons-android`、`miuix-blur-android`、`miuix-navigation3-ui-android` |
| Navigation 3 | `androidx.navigation3:navigation3-runtime` 1.1.0-rc01 |
| androidx core-ktx | 1.17.0 |
| androidx activity-compose | 1.13.0 |
| kotlinx-serialization-json | 1.9.0 |
| JUnit | 4.13.2 |
| minSdk / targetSdk / compileSdk | 35 / 36 / 37 |
| applicationId / namespace | `moe.huime.miuixmoondrop` / `moe.chenxy.hyperpods` |
| versionCode / versionName | 1 / `1.0.0` |

> `libs.versions.toml` 里还有 `agp-lib`（`com.android.library`）与 `kotlinSerialization` / `compose-compiler`
> 插件别名；`app/build.gradle.kts` 只 apply 了 `agp.app`、`kotlinSerialization`、`compose.compiler`。
>
> Miuix 0.9.3 只有**拆分产物**里才有 `preference.*` / `icon.*` / `overlay.OverlayDialog` 这些组件。
> 打包时按 `packaging.resources.excludes` 排除若干 `META-INF/*` 文件（依赖声明、LICENSE/NOTICE、
> `*.kotlin_module` 等）——这是普通应用的常规瘦身，与任何框架声明无关。

---

## 2. 本地构建

```bash
# 在仓库根目录
./gradlew :app:assembleDebug          # Debug APK
./gradlew :app:assembleRelease        # Release APK（未配置签名，产出未签名包）
./gradlew clean                       # 清理 build 目录
```

产物位置：

```
app/build/outputs/apk/debug/app-debug.apk                  # 未混淆，约 37 MB
app/build/outputs/apk/release/app-release-unsigned.apk     # R8 + 资源压缩，约 2.5 MB（未签名，见第 7 节）
```

`release` 构建设置：`isMinifyEnabled = true`、`isShrinkResources = true`（与 HyperPods 一致），
keep 规则见 `app/proguard-rules.pro`（协议层 `core.**` / `pods.**` 全保留）。

> **release 为什么能小这么多**：不开 R8 时 release 是 25 MB，其中 **23.5 MB 是未混淆的 dex** ——
> Compose / Miuix 的符号与没人调用的实现全留在包里；开启后同一个包 **2.83 MB**（签名后，
> 其中 `classes.dex` 2.40 MB）。本地 `assembleRelease` 产出的是**未签名**包，装之前要自己签
> （见第 7 节）；CI 会替你签（见第 4 节）。

Android Studio：直接 `Open` 仓库根目录 → 等待 Gradle Sync → 选择 `app` 运行配置 → Run。
（`local.properties` 不在版本库中，需要由 IDE 或手工创建以指向 Android SDK。）

---

## 3. 运行单元测试

```bash
./gradlew :app:testDebugUnitTest                       # 全部单元测试（CI 用的就是这一条）
./gradlew :app:testDebugUnitTest --tests "moe.chenxy.hyperpods.core.BatteryCodecTest"
./gradlew :app:test --stacktrace                       # 全部变体 + 堆栈
```

报告位置（HTML）：`app/build/reports/tests/testDebugUnitTest/index.html`。

测试内容（`app/src/test/java/moe/chenxy/hyperpods/core/`，共 **47** 个用例，纯 JVM、不依赖 Android）：

| 测试类 | 用例数 | 覆盖 |
|---|---:|---|
| `GaiaProtocolTest` | 14 | 电量/ANC V2/双设备连接/LHDC/增益/指示灯/提示音/版本探测/注册通知帧的**逐字节**期望值（提示音断言官方 App logcat 的原样字节）；提示音三字段回包解析；响应帧解析；能力位图解析与 ANC 路径选择；垃圾帧返回 null；位图截断检测 |
| `BatteryCodecTest` | 15 | 「右耳电量不显示」修复的完整回归：单设备 type 0 左右耳都显示、分体值优先、单包缺项不清零、系统广播兜底左右都给、未知 type 不位移、数量前缀变体、255 丢弃、非法值裁剪 |
| `TouchV2Test` | 18 | TOUCHV2（feature 22）手势协议：读写帧与实测明文一致、双耳半字节解码（高 4 位左 / 低 4 位右）、只改目标耳朵的半字节读改写、0..15 全掩码、5 字节长度校验、短包/多包处理、动作表覆盖与未知 id 显示 |

---

## 4. GitHub Actions 如何产出 APK

工作流：`.github/workflows/build.yml`（`name: Build Release APK`，job 名 `build`，`runs-on: ubuntu-latest`）。
做法与 HyperPods（dev）的工作流一致：**构建 release 包 → 签名 → 非压缩上传**。

| 项 | 值 |
|---|---|
| 触发 | `push` 到 `main`/`master`、`push` tag `v*`、`pull_request`、`workflow_dispatch`（手动） |
| Runner | `ubuntu-latest` |
| 产物 | `MiuixMoondrop-<versionName>-release.apk`（**已签名，可直接安装**）+ `test-results` |

步骤（逐条对应工作流文件）：

1. `actions/checkout@v4` 拉取代码；
2. `actions/setup-java@v4` 安装 **temurin JDK 21**（工程工具链目标仍是 17）；
3. `gradle/actions/setup-gradle@v4` 配好 Gradle 与依赖缓存；
4. `android-actions/setup-android@v3` + `sdkmanager "platforms;android-37" "build-tools;36.0.0"`
   （带 `continue-on-error: true`，失败也不阻塞 —— AGP 自身会在需要时下载缺失组件）；
5. `chmod +x ./gradlew`；
6. `./gradlew :app:testDebugUnitTest --stacktrace` —— **单测失败即整个 job 失败**；
7. 上传 `test-results`（`if: always()`）；
8. `./gradlew :app:assembleRelease --stacktrace` —— **不带 `continue-on-error`**：R8 裁错必须让 job
   变红，不能静默产出一个坏包；
9. `actions/cache@v4` 取回兜底签名用的 keystore；
10. 用 build-tools 的 `zipalign` + `apksigner` 签名，输出 `MiuixMoondrop-<versionName>-release.apk`；
11. `actions/upload-artifact@v7` 非压缩上传（`archive: false`，下载到的直接就是 APK）；
12. tag 构建且用的是**正式签名**时，`ncipollo/release-action@v1` 创建 GitHub Release。

**签名**：仓库目前没配签名 secrets（HyperPods 也没有），因此走兜底 —— 用
`~/.android/miuixmoondrop-ci.jks`（首次由 `keytool` 生成，之后由 `actions/cache` **跨 run 复用**；
必须复用：GitHub runner 每次都是新环境，若每次重新生成 keystore，签名就每次都不同，
Android 会以签名不一致拒绝覆盖安装）。配好 `SIGNING_KEY`（base64 的 keystore）、`ALIAS`、
`KEYSTORE_PASSWORD`、`KEY_PASSWORD` 四个 secrets 后自动切换为正式签名，
并且只有正式签名才创建 Release。

> 拿产物：Actions → 选择对应 run → 页面底部 Artifacts → 下载 `MiuixMoondrop-1.0.0-release.apk`。

---

## 5. 安装

```bash
# CI 产出的包已签名，可直接安装
adb install -r MiuixMoondrop-1.0.0-release.apk
```

也可以把 APK 拷到设备上直接点击安装。调试用途也可以装
`app/build/outputs/apk/debug/app-debug.apk`（未混淆，约 37 MB）。

> 从旧包换过来时**先卸载一次**：旧包的签名与现在这套 CI keystore 不同（包名可能也还是改名前的
> `moe.chenxy.hyperpods.moondrop`），Android 不允许签名不同的同包名覆盖安装。
> 之后同一套 CI 签名可以直接升级安装。

安装后：

1. 打开应用，按引导授予 `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` / `POST_NOTIFICATIONS`
   （应用首次打开时主动申请）；
2. 在系统蓝牙设置里配对并连接水月雨耳机 —— `pods/BluetoothConnectReceiver.kt` 是 manifest
   静态声明的接收器，监听 A2DP `CONNECTION_STATE_CHANGED`，耳机连上时会唤醒应用进程；
3. 需要「耳机连上时自动弹连接弹窗」时，在系统设置里授予 **「显示在其他应用上层」
   （`SYSTEM_ALERT_WINDOW`）**；**HyperOS 上还需要手动开启「后台弹出界面」**。
   应用设置页有这两个入口的跳转行（后台启动 Activity 的限制见 `pods/ControlBridge.kt`
   与 `ui/Permissions.kt` 的注释）；
4. 无需重启手机，无需任何框架管理器。

---

## 6. 日志与排查

```bash
# 协议客户端（GAIA 收发帧、能力探测、状态刷新）
adb logcat -s MoondropLink

# 应用内其它 TAG
adb logcat -s ControlBridge         # 状态桥：通知 / 连接弹窗的启动与重试
adb logcat -s HyperPods-BtConnect   # A2DP 广播唤醒进程、设备判定
adb logcat -s HyperPods-PodNotify   # 状态栏通知的创建 / 落地 / 撤销
adb logcat -s MoondropPodState      # UI 冷启动兜底发现已配对设备

# 一条命令看全部
adb logcat | grep -E "MoondropLink|ControlBridge|HyperPods-|MoondropPodState"
```

关键日志点：

* `MoondropLink`: `GAIA GATT ready: <name> model=<型号>` / `GAIA SPP ready: …`；
  `capabilities: PodCapabilities(features=[…], ancPath=…, batteryTypes=…)`（**能力探测是否成功，看这一行**）；
  每帧收发以 `TX`/`RX` + hex 打印；`setGesture:` 会打印同侧互斥自动置空的那一档；
* `ControlBridge`: `连接弹窗已在第 N 次尝试后显示` / `连接弹窗可能被 BAL 拦掉：缺「显示在其他应用上层」/「后台弹出界面」权限`
  （**排查「连上了但没弹窗」先看这里**）；
* `HyperPods-BtConnect`: `A2DP connected -> app-side connect` /
  `not a Moondrop device (name/address unavailable or unmatched); skipped`；
* `HyperPods-PodNotify`: `app notification posted: …` /
  `notifications disabled for this app (POST_NOTIFICATIONS not granted?); app notification dropped`。

系统侧交叉验证：

```bash
adb shell dumpsys notification --noredact | grep -i HyperPodsAppState   # 本应用那条状态通知
```

---

## 7. Release 签名

正式签名的密钥库与口令**不在仓库里**：`.gitignore` 已挡掉 `*.jks` / `*.keystore` / `*.p12` /
`keystore.properties` / `signing.properties`。两条链路：

### 7.1 CI（推荐）：GitHub Secrets

`.github/workflows/build.yml` 的 `Sign release APK` 步骤读四个 **repository secrets**：

| secret | 内容 |
| --- | --- |
| `SIGNING_KEY` | 密钥库文件的 **base64**（`base64 -w0 xxx.jks` 的结果） |
| `ALIAS` | 密钥别名 |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_PASSWORD` | 私钥口令（JKS 里通常与库口令相同） |

配好后产物 `MiuixMoondrop-<versionName>-release.apk` 就是正式签名包（步骤输出
`signed=secret`）；**没配**时退回一个由 `actions/cache` 跨 run 复用的 CI 兜底密钥库
（签名稳定、可覆盖安装，但不是正式签名，输出 `signed=fallback`）。轮换密钥＝重新上传这四个
secret，**必须重跑一次构建才会生效**。

secret 的写入只有两条路：GitHub 网页，或用仓库 public key 做封箱加密的 API。在 PC 上最省事：

```bash
gh secret set SIGNING_KEY -R huime180/MiuixMoondrop < <(base64 -w0 xxx.jks)
gh secret set ALIAS             -R huime180/MiuixMoondrop --body '<别名>'
gh secret set KEYSTORE_PASSWORD -R huime180/MiuixMoondrop --body '<库口令>'
gh secret set KEY_PASSWORD      -R huime180/MiuixMoondrop --body '<私钥口令>'
```

### 7.2 本地构建：Gradle 属性（可选）

`app/build.gradle.kts` 在**四个值都齐**时才创建 `signingConfigs.release`，于是
`./gradlew :app:assembleRelease` 直接产出已签名包；没配则与以前一致
（`app-release-unsigned.apk`）。值只从**仓库外**读：Gradle 属性
（`~/.gradle/gradle.properties` 或命令行 `-P`）优先，其次同名环境变量：

```properties
# ~/.gradle/gradle.properties —— 不要放进仓库
KEYSTORE_FILE=/绝对路径/xxx.jks
KEYSTORE_PASSWORD=…
KEY_ALIAS=…
KEY_PASSWORD=…
```

不想动 gradle 时也可以手工签：

```bash
BT="$ANDROID_HOME/build-tools/<版本>"
"$BT/zipalign" -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
"$BT/apksigner" sign --ks xxx.jks --ks-key-alias <别名> \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --out MiuixMoondrop-1.0.0-release.apk aligned.apk
"$BT/apksigner" verify --print-certs MiuixMoondrop-1.0.0-release.apk
```

签名要注意：升级安装必须是**同一个签名**，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
换签名等于换应用身份，老用户要卸载重装。

---

## 8. 已知构建/运行注意点

1. **没有 native 库**，所以不需要 NDK，也不会有 ABI 拆分问题。
2. Debug 包可直接安装；Release 包**要么配好签名（第 7 节），要么自己签**，未签名的包装不上。
3. 蓝牙权限是运行时权限：全新安装后需要打开过一次应用（或手动在系统设置里授予）
   `POST_NOTIFICATIONS`，否则状态栏通知会被系统静默丢弃（`PodNotification` 只打日志）。
   这是平台规则，任何应用都绕不过。
4. 「连上了但不弹连接弹窗」九成是缺「显示在其他应用上层」/「后台弹出界面」——
   Android 10+ 的 BAL 会**静默**拦掉后台启动 Activity（不报错、无回调）。
   `ControlBridge` 会重试 3 次并把判定结果写进日志。
5. 若干「已实现但未接线」的能力（9ECA、LC3 / LDAC、声道反转 / 关机 / 动态低音）构建不会报错，
   但功能不会生效；清单见 [ADAPTATION.md](ADAPTATION.md) 第九 / 十一节与
   [PROTOCOL.md](PROTOCOL.md) 第 12 节。空间音频 / 头动追踪**已接线**（详情页两个开关 + 读写 +
   回读），构建同样不会报错，但**未真机验证**，命令号与 payload 只来自
   [PROTOCOL.md](PROTOCOL.md) 第 8 节。
6. 提示音（feature `0x0E`）的命令号与 payload **已由官方 App 自身 logcat 实机确认**
   （GET=cmd 1 / SET=cmd 2，payload(V2)=`[enabled, volume(0..100), index]`，写入必须一次给全三字节），
   单测断言的就是日志原样字节；`index` 语义未确认（见 [PROTOCOL.md](PROTOCOL.md) 第 6 节）。
