# 构建与安装（BUILD）

> 本文说明如何编译、测试、打包与安装本模块。
>
> ⚠ **诚实声明**：编写本文档的环境**没有 JDK、没有 Android SDK、也没有网络**，因此本文的命令**没有被本文档作者实际执行过**；
> 但 CI（GitHub Actions）已通过单测与编译并产出 APK，且 `app-debug.apk` 已装机（见 [README.md](README.md) 的「构建状态（1.0.0）」小节）。
> 仓库本身不含 APK 产物；本模块**没有任何真机功能验证结论**。
> 下面的内容全部来自对 `app/build.gradle.kts`、`gradle/libs.versions.toml`、
> `gradle/wrapper/gradle-wrapper.properties` 与 `.github/workflows/build.yml` 的逐行核对。

---

## 1. 前置条件

| 项 | 要求 | 依据 |
|---|---|---|
| JDK | **17**（GitHub Actions 用 temurin 17；AGP 9 要求 JDK 17+） | `java { toolchain }` 与 `kotlin { jvmToolchain(17) }` |
| Android SDK | `compileSdk = 37` 的平台 + build-tools 36.0.0；`ANDROID_HOME` / `local.properties` 指向 SDK。**compileSdk 必须是 37**：Miuix 0.9.3 的 AAR metadata 声明 `minCompileSdk=37` | `app/build.gradle.kts`、`miuix-*-android-0.9.3.aar` 内的 `aar-metadata.properties` |
| Gradle | **9.4.1**（wrapper 会自动下载 `gradle-9.4.1-bin.zip`；AGP 9.1 要求 Gradle >= 9.3.1） | `gradle/wrapper/gradle-wrapper.properties`、AGP `VersionCheckPlugin` 的 `GRADLE_MIN_VERSION` |
| Android Studio | 能打开 AGP 9.1 项目的版本即可（建议较新版本） | — |
| 网络 | 需要访问 `google()`、`mavenCentral()`、`https://api.xposed.info/`、`https://s01.oss.sonatype.org/content/repositories/releases/`、`https://jitpack.io` | `settings.gradle.kts` |
| NDK | **不需要**。本项目**没有任何 native 代码**：上游 HyperPods 的 native L2CAP patch 被有意移除（它只为放行 Apple 的 L2CAP 模式，对 GAIA over BLE/SPP 无用） | 无 `externalNativeBuild`、无 `jniLibs` |

### 版本一览（`gradle/libs.versions.toml`）

| 组件 | 版本 |
|---|---|
| Android Gradle Plugin | 9.1.0（自带 Kotlin 编译支持，不再 apply `org.jetbrains.kotlin.android`） |
| Kotlin（KGP，由根 `build.gradle.kts` 的 buildscript classpath 提升） | 2.3.20 |
| Gradle | 9.4.1 |
| Compose | **androidx compose**：`androidx.compose:compose-bom` 2025.05.00 + `ui` / `foundation` / `ui-tooling(-preview)`；不再使用 `org.jetbrains.compose` 插件与 `compose.*` 访问器 |
| **libxposed API** | **102.0.0（`compileOnly`，运行时由 LSPosed / Vector 注入）** |
| Miuix（**拆分产物**） | 0.9.3：`miuix-ui-android`、`miuix-preference-android`、`miuix-icons-android`、`miuix-navigation3-ui-android` |
| Navigation 3 | `androidx.navigation3:navigation3-runtime` 1.1.0-rc01 |
| androidx core-ktx | 1.17.0 |
| androidx activity-compose | 1.13.0 |
| kotlinx-serialization-json | 1.9.0 |
| JUnit | 4.13.2 |
| minSdk / targetSdk / compileSdk | 35 / 36 / 37 |
| applicationId / namespace | `moe.chenxy.hyperpods.moondrop` / `moe.chenxy.hyperpods` |

> `libs.versions.toml` 里还声明了 `lsplugin-apksign` / `lsplugin-resopt` / `agp-lib` 等别名，
> 但 `app/build.gradle.kts` **没有引用**它们（未 apply、未进依赖）。
>
> Miuix 0.9.3 只有**拆分产物**里才有 `preference.*` / `icon.*` / `overlay.OverlayDialog` 这些组件；
> 早期 pin 的聚合产物 `top.yukonga.miuix.kmp:miuix:0.5.1` 解析不到它们（CI 实测），
> 因此 UI 一度退回普通 Compose。现在依赖与版式都与 `_refs/OppoPods` 对齐。
> 打包时保留 `META-INF/xposed/*`（`packaging.resources.merges`），因为 `module.prop` /
> `scope.list` / `java_init.list` 必须进 APK。

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
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk     # 未签名（见第 7 节）
```

`release` 构建设置：`isMinifyEnabled = false`、`isShrinkResources = false`
（模块靠字符串名反射 hook 目标，混淆模块自身不影响反射目标，但作者选择不混淆）。

Android Studio：直接 `Open` 仓库根目录 → 等待 Gradle Sync → 选择 `app` 运行配置 → Run。
（`local.properties` 不在版本库中，需要由 IDE 或手工创建以指向 Android SDK。）

---

## 3. 运行单元测试

```bash
./gradlew :app:testDebugUnitTest                       # 全部单元测试
./gradlew :app:testDebugUnitTest --tests "moe.chenxy.hyperpods.core.BatteryCodecTest"
./gradlew :app:test --stacktrace                       # 全部变体 + 堆栈
```

报告位置（HTML）：`app/build/reports/tests/testDebugUnitTest/index.html`。

测试内容（`app/src/test/java/moe/chenxy/hyperpods/core/`，共 **29** 个用例，纯 JVM、不依赖 Android）：

| 测试类 | 用例数 | 覆盖 |
|---|---:|---|
| `GaiaProtocolTest` | 14 | 电量/ANC V2/双设备连接/LHDC/增益/指示灯/提示音/版本探测/注册通知帧的**逐字节**期望值（提示音断言官方 App logcat 的原样字节 `data=[1,20,1]` / `data=[0,82,1]`）；提示音三字段回包解析；响应帧解析；能力位图解析与 ANC 路径选择；垃圾帧返回 null；位图截断检测 |
| `BatteryCodecTest` | 15 | 「右耳电量不显示」修复的完整回归：单设备 type 0 左右耳都显示、分体值优先、单包缺项不清零、系统广播兜底左右都给、未知 type 不位移、数量前缀变体、255 丢弃、非法值裁剪 |

---

## 4. GitHub Actions 如何产出 APK

工作流：`.github/workflows/build.yml`（`name: Build APK`）。

| 触发 | `push` 到 `main`/`master`、任意 `pull_request`、`workflow_dispatch`（手动） |
|---|---|
| Runner | `ubuntu-latest` |

步骤：

1. `actions/checkout@v4` 拉取代码；
2. `actions/setup-java@v4` 安装 **temurin JDK 17**；
3. `android-actions/setup-android@v3` 准备 Android SDK；
4. `chmod +x ./gradlew`；
5. `./gradlew :app:testDebugUnitTest --stacktrace` —— **单测失败即整个 job 失败**；
6. `./gradlew :app:assembleDebug --stacktrace`；
7. `./gradlew :app:assembleRelease --stacktrace`，带 `continue-on-error: true`（release 未签名，失败不阻塞）；
8. 收集 `app/build/outputs/apk/**/*.apk` 到 `out/`；
9. 上传两个 artifact：
   * **`HyperPods-for-Moondrop-apk`** → `out/*.apk`（`if-no-files-found: error`，没有 APK 就报错）；
   * **`test-results`** → `app/build/reports/tests/**`（`if: always()`）。

> 拿产物：Actions → 选择对应 run → 页面底部 Artifacts → 下载 `HyperPods-for-Moondrop-apk`。
> Debug APK 使用 Android 默认 debug 签名，可直接安装。

---

## 5. 安装与在 LSPosed 中启用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 打开 **LSPosed / Vector** → 模块列表里启用 **HyperPods for Moondrop**；
2. 勾选作用域（`module.prop` 声明 `staticScope=true`，作用域同时写在 `scope.list` 与
   `res/values/arrays.xml` 的 `xposedscope`，两处必须一致）：

```
com.android.bluetooth
com.xiaomi.bluetooth
com.android.systemui
com.android.settings
```

3. **重启手机**（或至少重启上述 4 个进程）—— 作用域进程需要重新加载模块代码；
4. 打开模块 App，或点融合设备中心里的耳机卡（第一次点击只询问 MAC，第二次才打开 UI）。

模块元数据（`app/src/main/resources/META-INF/xposed/`）：

```
module.prop      id=moe.chenxy.hyperpods.moondrop
                 name=HyperPods for Moondrop
                 version=1.0.0 / versionCode=1 / author=huime180
                 minApiVersion=101 / targetApiVersion=102 / staticScope=true
scope.list       上面 4 个包名
java_init.list   moe.chenxy.hyperpods.hook.XposedEntry
```

入口类 `XposedEntry : XposedModule` 在 `onPackageReady` 里按**包名**分发 hook：
`com.android.bluetooth → HeadsetStateDispatcher`、`com.android.systemui → SystemUIPluginHook`、
`com.xiaomi.bluetooth → MiBluetoothToastHook`、`com.android.settings → SettingsHeadsetHook`；
并实现 libxposed API 102 的**热重载**（`onHotReloading` / `onHotReloaded`，用稳定 hook id 摘掉旧 hook）。
任何 hook 装载失败都只写日志，绝不抛给被注入进程。

---

## 6. 日志与排查

```bash
# 协议客户端（GAIA 收发帧、能力探测、状态刷新）
adb logcat -s MoondropLink

# 系统集成层（各进程 TAG 独立）
adb logcat -s HyperPods-Moondrop      # 模块入口 / 装载
adb logcat -s HyperPods-Bluetooth     # A2DP 连接、MAC 应答、电量写回
adb logcat -s HyperPods-SystemUI      # 插件 ClassLoader 注入
adb logcat -s HyperPods-DeviceCard    # 设备卡点击、MAC 握手
adb logcat -s HyperPods-MiBtToast     # 通知
adb logcat -s HyperPods-Settings      # 设置页伪装与状态注入
adb logcat -s HyperPods-SysApi        # 反射系统 API 失败点

# 一条命令看全部
adb logcat | grep -E "MoondropLink|HyperPods-"
```

关键日志点：

* `MoondropLink`: `GAIA GATT ready: <name> model=<型号>` / `GAIA SPP ready: …`；
  `capabilities: PodCapabilities(features=[…], ancPath=…, batteryTypes=…)`（**能力探测是否成功，看这一行**）；
  每帧收发以 `TX`/`RX` + hex 打印（`PodEvent.Frame`）；
* `HyperPods-Bluetooth`: `A2DP state=… isMoondrop=…`、`bootstrap: found … -> dispatch connected`、
  `system battery level=… -> <MAC>`；
* `HyperPods-Settings`: 伪装入口改写与 `updateAtUiInfo` 注入结果；
* 模块内 `BuildConfig.DEBUG` 与 `HyperPodsPrefsKey.DEBUG_LOG` 用于调试开关。

系统侧交叉验证：

```bash
adb shell dumpsys bluetooth_manager | grep -i -A3 "Battery"     # 系统蓝牙栈里的电量
adb shell dumpsys notification --noredact | grep -i BTHeadset   # 耳机电量通知
```

---

## 7. Release 签名

`app/build.gradle.kts` 的 `release` **没有配置 `signingConfig`**，所以
`./gradlew :app:assembleRelease` 产出的是 `app-release-unsigned.apk`。

自签名步骤（示例，密钥请自行保管、不要提交到仓库）：

```bash
# 1) 生成密钥库（一次性）
keytool -genkeypair -v -keystore hyperpods-moondrop.jks \
  -alias hyperpods -keyalg RSA -keysize 2048 -validity 10000

# 2) 用 apksigner 签名（build-tools 里的工具）
$ANDROID_HOME/build-tools/36.0.0/apksigner sign \
  --ks hyperpods-moondrop.jks --ks-key-alias hyperpods \
  --out HyperPods-for-Moondrop-1.0.0.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

# 3) 校验
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs HyperPods-for-Moondrop-1.0.0.apk
```

若希望 Gradle 直接产出已签名包，在 `app/build.gradle.kts` 里增加
`signingConfigs { create("release") { … } }` 并在 `buildTypes.release` 中引用
（**本文档不改动任何 gradle 文件**，仅说明做法）。签名要注意：

* LSPosed/Vector **不要求**特定签名；但升级安装必须是**同一个签名**，否则会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
* `libs.versions.toml` 里预留了 `org.lsposed.lsplugin.apksign` / `resopt` 插件别名（LSPosed 官方签名/资源优化插件），
  当前**未启用**。

---

## 8. 已知构建/运行注意点

1. **必须 `staticScope=true` + 重启**：作用域是静态声明的，改作用域后不重启不会生效。
2. **没有 native 库**，所以不需要 NDK，也不会有 ABI 拆分问题。
3. **`packaging.resources` 会保留 `META-INF/xposed/*`**；如果你改打包规则，务必别把这些文件排除掉，
   否则 LSPosed 认不出模块。
4. 依赖里有 `compileOnly(libs.libxposedApi)`：**不要把 libxposed API 打进 APK**，
   它必须由框架在运行时提供（这正是 `compileOnly` 的含义）。
5. Debug 包可直接安装；Release 包未签名，需要自行签名（第 7 节）。
6. 跨进程链路已由 `pods/ControlBridge.kt` + manifest 声明的 `pods.ControlReceiver` 接通
   （连接/控制命令下发、电量写回系统蓝牙栈、通知、设置页回显、低延迟转发）；仍有若干
   「已实现但未接线」的能力（空间音频/头动追踪、9ECA、LC3/LDAC、充电位解析），构建不会报错，
   但功能不会生效；清单见 [README.md](README.md) 第六节。
7. 提示音（feature `0x0E`）的命令号与 payload **已由官方 App 自身 logcat 实机确认**
   （GET=cmd 1 / SET=cmd 2，payload(V2)=`[enabled, volume(0..100), index]`，写入必须一次给全三字节），
   单测断言的就是日志原样字节；但**本模块自身**尚未在真机上跑通提示音读写，`index` 语义也未确认
   （见 [PROTOCOL.md](PROTOCOL.md) 第 6 节）。
