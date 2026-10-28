# MaaKotlinUI

在 Android 上原生运行 [MaaFramework](https://github.com/MaaXYZ/MaaFramework) 的通用自动化客户端。

本项目把 PC 版 MAA 的「模拟器 + ADB 控制」移植到 Android 本机：用 JNA 直接调用 `libMaaFramework.so`，通过 `libbridge.so` 完成虚拟屏截屏与触摸注入，并提供一个基于 Jetpack Compose 的完整 UI，用于任务编排、运行控制、定时调度、日志与通知。

> 侧重：**Android 侧 UI 与 MaaFramework 引擎集成**。游戏脚本 / 资源（`assets/resource/base` 等）属于业务资源，后续会从本仓库解耦，不在此描述。

---

## 功能特性

### UI（Jetpack Compose）

- **Compose 界面**：Material 3 主题，支持明暗模式与动态取色。
- **首页 / 脚本 / 定时 / 设置**四个主页面，底部导航切换。
- **引导页（Onboarding）**：首次启动一键申请通知、精确闹钟、电池优化白名单等必要权限。
- **任务编排 UI**：脚本列表、任务勾选、任务参数配置、选项 override、配置（Profile）的导入导出与管理。
- **实时运行日志面板**：解析引擎回调日志，彩色渲染 focus / 当前操作，直观展示运行状态。
- **悬浮球控制**：运行中可显示悬浮窗，快速开始 / 停止任务。
- **定时任务管理**：定时任务的增删改查、导入导出、自动开机注册。

### Android 侧 Maa 引擎集成

- **原生运行 MaaFramework**：无需模拟器 / 无需 PC，Android 上直接加载 `libMaaFramework.so` 执行图像识别脚本。
- **Shizuku / Root 双通道**：优先使用 Shizuku（无 Root 也能运行），也支持 Root 模式（`app_process` 拉起 root 引擎进程），UI 无感切换。
- **独立引擎进程**：引擎运行在 Shizuku UserService 或 Root 进程中，App 进程只做编排与展示，避免 ANR 与内存压力。
- **虚拟屏截图与触摸注入**：通过 `VirtualDisplayManager` + `libbridge.so` 完成截屏与输入注入。
- **JNA 绑定**：`MaaFrameworkEngine` 封装 `MaaTasker / MaaController / MaaResource` 等核心 API。
- **引擎复用**：任务正常结束后复用 `resource/controller/tasker`、清识别缓存并重连控制器，单任务可省数秒。
- **守护自愈**：App 心跳看门狗、引擎 shutdown hook、显示漂移检测、异常断开后自动断点续跑。
- **定时任务**：闹钟 + 开机广播 + Root 守护进程（Root 模式下即使 App 未运行也能准时执行）。
- **任务通知**：任务开始 / 完成 / 出错通知，支持 Webhook、Server酱、钉钉、喵提醒、SMTP 等第三方推送。
- **日志诊断体系**：三层启动诊断日志（App 侧绑定 / 引擎侧启动 / Root 侧启动）+ 独立 logcat 进程。

---

## 工作原理

### 进程模型

```bash
┌──────────────────────────────────────────────┐
│ App 进程（com.maafw.naruto）                  │
│  Compose UI / 任务编排 / 资源管理 / 日志汇总    │
└──────────────────┬───────────────────────────┘
                   │ binder（AIDL：IRemoteEngineService）
                   ▼
┌──────────────────────────────────────────────┐
│ 引擎进程（Shizuku UserService 或 Root app_process）│
│  RemoteEngineServiceImpl                     │
│  ├─ MaaFrameworkEngine（JNA 封装 libMaaFramework.so）│
│  ├─ VirtualDisplayManager（虚拟屏截图）        │
│  ├─ ScreenWakeHelper / 保亮 / 电源控制        │
│  └─ ActivityUtils（启动应用 / 防漂移拉回）      │
└──────────────────┬───────────────────────────┘
                   │ 同进程直调（JNA upcall）
                   ▼
┌──────────────────────────────────────────────┐
│ 原生层：libMaaFramework.so + libbridge.so     │
│  MaaTasker / MaaController / 识别 / 触摸注入   │
└──────────────────────────────────────────────┘
```

核心设计：**所有重活都在引擎进程完成**（MaaCore 加载、识别、截图、触摸注入），App 进程只负责 UI 与编排。

### 启动时序

点击「开始任务」后：

1. 检查远程服务状态与资源是否就绪；
2. 启动前台服务，绑定远端引擎（Shizuku 或 Root）；
3. 远端创建 `MaaTasker`、设置 `TOUCH_MODE=ANDROID`；
4. 启动虚拟屏并 `AsyncConnect` 控制器；
5. `AppendTask → Start()`，进入 `RUNNING` 状态，回调通过 binder 回传给 UI 显示日志与进度。

---

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言 | Kotlin + Java |
| UI | Jetpack Compose（Material 3） |
| 引擎 | MaaFramework（`libMaaFramework.so`）+ libbridge.so |
| 原生调用 | JNA `5.14.0` |
| 权限通道 | rikka.shizuku `13.1.5`、rikka.sui、Root（app_process） |
| 构建 | Android Gradle Plugin `7.2.2`、Kotlin `1.8.22` |
| 其它 | Gson、kotlinx-coroutines、RecyclerView、Material Components |

---

## 目录结构

```bash
.
├── app/
│   └── src/main/
│       ├── aidl/                       # 远程引擎/状态监听/logcat AIDL 接口
│       ├── assets/
│       │   ├── interface.json          # MaaFW 界面/任务/选项配置（业务资源，待解耦）
│       │   └── resource/               # 游戏脚本资源（业务资源，待解耦）
│       ├── cpp/include/                # MaaFramework / MaaAgentClient 头文件
│       ├── java/com/maafw/naruto/
│       │   ├── ui/                     # Compose UI（首页/脚本/定时/设置/引导页）
│       │   │   ├── components/         # 通用组件（TopBar/BottomNav/日志面板等）
│       │   │   ├── home/               # 首页
│       │   │   ├── script/             # 脚本/任务配置/Profile 管理
│       │   │   ├── schedule/           # 定时任务 UI
│       │   │   ├── settings/           # 设置 UI
│       │   │   └── onboarding/         # 首次引导
│       │   ├── remote/                 # 远程引擎服务实现（Shizuku/Root 引擎进程）
│       │   ├── root/                   # Root 模式：app_process 启动、守护进程
│       │   ├── maa/                    # MaaFramework JNA 绑定、自定义识别/动作
│       │   ├── capture/                # 虚拟屏截图
│       │   ├── inject/                 # 触摸注入
│       │   ├── overlay/                # 悬浮球控制
│       │   ├── service/                # 前台服务、连接管理、看门狗、通知
│       │   ├── schedule/               # 定时任务调度
│       │   ├── data/                   # 设置/配置/日志/推送
│       │   └── third/                  # 系统服务反射包装（hidden API 兼容）
├── scripts/
│   ├── setup_maaframework.py           # 下载/部署 MaaFramework 预编译库
│   ├── update_narutomobile_assets.py   # 构建前同步业务资源到 Android assets
│   └── convert_multiswipe.py           # 多指滑动转换工具（开发辅助）
├── 编译说明.md                       # 编译指南
└── 性能优化方案.md / 滑动卡死问题修复报告.md  # 开发辅助文档
```

> `assets/` 下的 `interface.json` 与 `resource/` 为**游戏业务资源**，本仓库作为开发/示例保留，后续将解耦为外部资源包。

---

## 环境要求

| 项 | 要求 |
| --- | --- |
| Android | minSdk 24，targetSdk 33，compileSdk 34（arm64-v8a） |
| 权限 | 无需 Root（Shizuku 模式）；Root 模式需要 root 环境 |
| 前置条件 | 需要安装 [Shizuku](https://shizuku.rikka.app/) 并授权（非 Root 模式）；或使用 Root |

App 需要的权限见 `app/src/main/AndroidManifest.xml`，主要包括：通知、悬浮窗、精确闹钟、电池优化（后台保活）、外部存储（资源/日志）、Shizuku、Root 等。

---

## 编译构建

构建 APK 前，先同步 NarutoMobile 业务资源：

```bash
python scripts/update_narutomobile_assets.py
```

该脚本会下载最新稳定版业务资源，将 `interface.json` 和 `resource/` 写入
`app/src/main/assets/`，并默认完成 Android 所需的 MultiSwipe 转换。资源随后会随 APK
一起打包；App 不提供安装后导入业务资源包的功能。指定版本、本地压缩包等用法见
[`编译说明.md`](编译说明.md)。

### 方式一：PC / 终端（推荐）

```bash
# 1. 环境要求：JDK 17+（兼容 JDK 21）、Android SDK（build-tools 34.0.0）
export ANDROID_HOME=/path/to/android-sdk

# 2. 编译 debug APK
./gradlew :app:assembleDebug

# 3. 产物路径
# app/build/outputs/apk/debug/app-debug.apk
```

如果本机 aapt2 解析失败，可显式指定 SDK 自带 aapt2：

```bash
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2
```

### 方式二：AndroidIDE（设备上编译）

```bash
export ANDROID_HOME=/root/android-sdk
bash gradlew :app:assembleDebug --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/34.0.0/aapt2
```

更详细的构建步骤、常见报错与备用方案（ZeroTermux proot）见 [`编译说明.md`](编译说明.md)。

### 部署 MaaFramework 预编译库

本项目已内置 MaaFramework 的 `.so` 与头文件。如需重新下载 / 部署：

```bash
python scripts/setup_maaframework.py                 # 下载最新 release 并部署
python scripts/setup_maaframework.py --tag v5.12.3   # 指定版本
```

---

## 安装与使用

1. 编译出 `app-debug.apk`（或从发布渠道获取）。
2. 安装到设备：

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. 首次打开 App，按引导页完成授权：
   - 通知权限
   - 精确闹钟（定时任务需要）
   - 电池优化白名单（后台保活需要）
   - 悬浮窗权限（可选）
   - Shizuku 授权（或启用 Root 模式）
4. 在「脚本」页勾选要执行的任务并点击「启动任务」。业务资源已在构建 APK 前写入 `app/src/main/assets/` 并随 APK 打包，无需在安装后导入。
5. 可在「设置」页配置定时任务、第三方推送、引擎复用、Root 守护进程等。

> 资源首次启动时会由 `AssetResourceDeployer` 部署到 App 私有目录。后续会通过相对路径和文件大小对比自动发现大多数 `assets/resource` 变化；发布包含资源更新的版本时仍建议提升 `versionCode`，以确保同大小的内容变化也会触发重新部署。

---

## Android 侧 Maa 集成注意事项（红线）

项目在 Android 侧 Maa 引擎集成过程中沉淀了若干**绝对不能破**的规则，详见 [`滑动卡死问题修复报告.md`](滑动卡死问题修复报告.md)：

| # | 红线 | 原因 |
| --- | --- | --- |
| 1 | **不要把历史 `libbridge.so`、`liblauncher.so` 手动放进 `jniLibs`** | 标准 Gradle 构建会通过 CMake 从当前 native 源码生成 |
| 2 | **不要注册 `MaaTaskerAddContextSink`（focus 监听）** | 节点事件回调会导致引擎随机崩溃 / 卡死 |
| 3 | **不要用 MaaFramework Swipe 多途径点**（`end` 数组 + `duration` 数组） | 引擎卡死，已改为 `NonlinearSwipe` 引擎层直接注入 |
| 4 | **不要在 CustomRecognition 里嵌套 OCR** | 死锁 |
| 5 | **CustomAction 拿不到 image** | 无法在动作阶段做 OCR |

---

## 日志与诊断

- 日志导出目录：`/storage/emulated/0/MaaFw日志/`（设置页「导出日志」生成）
- 引擎实时日志：`/storage/emulated/0/Android/data/com.maafw.naruto/files/maa_logs/maafw.log`
- 三层启动诊断：
  - App 侧绑定：`service_bind_debug.log`
  - 引擎侧启动：`service_boot_debug.log`
  - Root 启动：`root_launch_debug.log`

排查套路：任务卡住 → 看 `maafw.log` 最后一条 Node / Action / Recognition 事件 → 定位卡死节点 → 对照红线表。

---

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`编译说明.md`](编译说明.md) | 设备端（AndroidIDE）编译步骤 |
| [`性能优化方案.md`](性能优化方案.md) | 已实施 / 待实施性能优化 |
| [`滑动卡死问题修复报告.md`](滑动卡死问题修复报告.md) | 滑动卡死根因与规避清单 |
| [`修复multiswipe分析报告解决方案.md`](修复multiswipe分析报告解决方案.md) | 多指滑动不适配分析与方案 |

---

## 许可证

项目根目录包含 [GPLv3 许可证](LICENSE)。项目仅用于学习与个人使用，请遵守软件及服务提供商的相关规定，自行承担使用风险。
