# MaaPocket

**在 Android 手机本机上跑 MaaFramework 自动化** —— 崩坏：星穹铁道 / 绝区零 / 明日方舟：终末地的迁移版。

不需要电脑，不需要 ADB，不需要数据线。装 APK + 授权 Shizuku（或 root），手机上直接跑。

---

## 这是什么

三个目标游戏（星铁、绝区零、终末地）此前**都没有可用的 on-device Android 自动化方案**：

| 游戏 | PC 侧领先方案 | 已有的 MaaFW 包 |
|---|---|---|
| 崩坏：星穹铁道 | [March7thAssistant](https://github.com/moesnow/March7thAssistant)（11.6k★） | `VincenttHo/MaaStarRail` — 2★，2024-09 后停更 |
| 绝区零 | [ZenlessZoneZero-OneDragon](https://github.com/OneDragon-Anything/ZenlessZoneZero-OneDragon)（7.2k★） | `z-w-h-m-x/MaaZZZ` — 7★，2025-07 后停更 |
| 明日方舟：终末地 | [MaaEnd](https://github.com/MaaEnd/MaaEnd)（4.0k★） | 无 on-device 方案（MaaEnd 自身是「PC 通过 ADB 驱动手机」） |

MaaPocket 把这三个方案的任务定义**迁移**成 MaaFramework 资源包（PI 格式），配上一个把 MaaFramework 直接嵌进 Android App 的运行时，做出手机端原生版。

**技术范式来自 [Aliothmoon/MAA-Meow](https://github.com/Aliothmoon/MAA-Meow)**（「在 Android 设备上原生运行 MAA」）。MaaPocket 复刻了它的 native bridge 层、hidden-API 包装层与特权进程引导架构，见下方「与 MAA-Meow 的关系」。

---

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│ app-hsr / app-zzz / app-endfield   ← 三个游戏各一个 APK      │
│   各自 assets/pi/ 里带自己的 PI 资源包                        │
├──────────────────────────────────────────────────────────────┤
│ :core                                                        │
│  ┌──────────────────┐  ┌─────────────────────────────────┐   │
│  │ pi/  PI 资源包    │  │ run/  MaaRunController          │   │
│  │  解析 + 选解      │──▶│  抽包 → load → controller → run │   │
│  └──────────────────┘  └────────────┬────────────────────┘   │
│  ┌──────────────────┐               │                        │
│  │ maafw/ JNA 绑定   │◀──────────────┘                       │
│  │ MaaFrameworkApi   │                                       │
│  └────────┬─────────┘                                       │
│           │ jniLibs/libMaaFramework.so（CI 下载并打入 APK）   │
│  ┌────────▼──────────┐  ┌────────────────────────────────┐  │
│  │ cpp/  原生 bridge  │  │ privilege/  特权 helper 进程    │  │
│  │ 截图 + 输入注入     │  │  socket 协议 + RemoteEngine     │  │
│  └───────────────────┘  └────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 截图：不用 MediaProjection

`core/src/main/cpp/bridge_capture.cpp` 用 `AImageReader` 建一个 `Surface`，交给反射调用的隐藏 6 参
`DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, flags)` 创建虚拟显示器；
每帧 `AHardwareBuffer` 从 RGBA8888 逐像素转成 **packed BGR**（NEON 加速），写进三缓冲之一。
主屏镜像模式失败时回退到 `SurfaceControl.createDisplay` + `setDisplaySurface`。

两种模式：

- **后台**：新建虚拟显示器，手机屏幕照常用（`VirtualDisplayManager`）
- **前台镜像**：抓主屏，适合需要真实 GPU 渲染的游戏（`PrimaryDisplayManager`）

### 输入：不用无障碍、不用 uinput、不用 `input` 命令

`core/src/main/cpp/bridge_input.cpp` 把 MaaFramework 下发的 `DispatchInputMessage` 上抛到 Java，
由 `third/wrappers/InputManager.java` 反射调用 `injectInputEvent`。多指靠 `MotionEvent` 的
`PointerProperties`/`PointerCoords` 组装。**每次 `ACTION_DOWN` 用 `WAIT_FOR_FINISH` 模式**，
其余用 `ASYNC`，避免手势被截断。

> Android 14+ 上注入事件需要 **root uid**，Shizuku 的 adb 身份不够 —— 见 `PrivilegedSession`。
> 手机需要在开发者选项里打开「USB 调试（安全设置）」，否则 `INJECT_EVENTS permission` 会一直失败。

### 特权进程

`core/src/main/cpp/launcher.c` 编成一个**伪装成 `.so` 的可执行文件**（`liblauncher.so`）。
它 fork 后 `setresuid` 到 shell uid，再 `execv("/system/bin/app_process", ...)` 带着 APK 的
`CLASSPATH` 起一个裸 App 运行时，在里面跑 `RemoteMain` → `RemoteServer`（Unix domain socket）
→ `RemoteEngine`。App 进程通过 socket 下发 `maa.controller.start` / `resource.load` /
`task.run` 等命令，**MaaFramework 与 bridge 都在 helper 进程里加载**，因此 `dlopen`、
`injectInputEvent`、`createVirtualDisplay` 全部跑在特权身份下。

helper 每 5s 做一轮看门狗：App 进程消失 / 心跳超 20s / 无客户端超 60s → 自行退出。

---

## 构建

### 用 GitHub Actions（推荐）

推送即触发 `.github/workflows/build.yml`：

1. 从 MaaFramework release 下载 Android native 库（`v5.14.2`）
2. 用 NDK clang 交叉编译 MaaEnd 的 Go agent（`agent/go-service`）成 `libMaaEnd_go_service.so`
3. `./gradlew :app-hsr:assembleRelease :app-zzz:assembleRelease :app-endfield:assembleRelease`
4. 上传 `MaaPocket-apks-<abi>.zip`

矩阵：`arm64-v8a`（真机）与 `x86_64`（模拟器）。产物用 debug key 签名，可直接 sideload。

`.github/workflows/prepare-packs.yml` 是**手动触发**的资源包重生成流程：

```bash
# 在 CI 上：checkout 三个上游 → 跑迁移脚本 → 生成 PI 包
python scripts/migrate_march7th.py     --source <March7thAssistant> --out <app-hsr>/src/main/assets/pi
python scripts/migrate_onedragon_zzz.py --source <ZZZ-OneDragon>    --out <app-zzz>/src/main/assets/pi
python scripts/migrate_maaend.py        --source <MaaEnd>           --out <app-endfield>/src/main/assets/pi
```

三个脚本都是**纯 Python stdlib**，不需要 pip 安装任何东西，跑完自带 JSON-Schema 校验。

### 本地

需要 Android SDK（compileSdk 37）、NDK `29.0.13113456`、CMake `3.22.1`、JDK 17+。

```bash
python scripts/setup_maa_framework.py --abi arm64-v8a     # 下载并解出 native 库
./gradlew :app-hsr:assembleRelease
```

---

## 三个资源包的迁移状态

⚠️ **三个包都是「结构完整、转换可验证，但尚未在真机上跑过」。** 关键限制如下。

### 星铁（`app-hsr`）— 来自 March7thAssistant

- 56 屏 / 110 边 / 30 个任务，427 个 pipeline 节点，0 重复 0 悬空引用
- 屏幕状态图（`assets/config/screens.json`）→ 每个屏幕一个 `resource/pipeline/screen/<id>.json`，
  导航由 `NavTo_<id>` 路由器 + `Nav_<B>_from_<X>` 守卫驱动
- **设计分辨率 1920×1080**（与上游按 `n/1920`、`n/1080` 的 crop 自洽）
- **20 条动作无法映射**：全是 `find_type='text'` 的文本点击，没有 OCR 模型可对应
- **51 条边首跳是键盘按键**（`esc`）—— 上游的状态机假设有键盘
- 详见 [`app-hsr/RECAPTURE.md`](app-hsr/RECAPTURE.md)

### 绝区零（`app-zzz`）— 来自 ZenlessZoneZero-OneDragon

- 79 屏 / 700 个 area 全部转换 / 57 个模板 / 30 个任务
- `pc_rect` 绝对像素 → `roi`（基准 1920×1080）
- 上游的多边形掩码（`point_list`）**无法表达** —— MaaFramework 的 `TemplateMatch` 只有
  `green_mask`，没有 polygon 字段
- 详见 [`app-zzz/RECAPTURE.md`](app-zzz/RECAPTURE.md)

### 终末地（`app-endfield`）— 来自 MaaEnd

- 47 个任务定义 / 68 个 fragment / 3000+ 文件，三棵树分层（`resource` / `resource_adb` /
  `resource_android`）
- **`Scroll` → `Swipe`**：14 处，3 处上游已修，10 处转换（0.675 px/轮位），1 处拒绝
  （`_AutoEcoFarmScroll` 其实是 3D 相机变焦，Swipe 会变成转镜头）
- **47 个按键节点未解析**：MaaPocket 把 `key` 原样当 `android.view.KeyEvent` 码注入，
  于是 Windows 虚拟键码在 Android 上语义完全不同（`W`→`KEYCODE_MEDIA_NEXT`，
  `A`→`ENVELOPE`，`F1`→`FORWARD_DEL`…）。其中 10 个 `Esc`→`KEYCODE_BACK(4)`、
  24 个 PC 修饰键→`DoNothing` 已按上游活的先例解决，其余逐条记在
  [`resource_android/KNOWN_LIMITATIONS.md`](app-endfield/src/main/assets/pi/resource_android/KNOWN_LIMITATIONS.md)
- `agent/cpp-algo` 被丢弃（Android 无构建路径），`interface.json` 只保留 `agent/go-service`
- 详见 [`docs/endfield-port.md`](docs/endfield-port.md)

### 三个包共同的硬限制

**模板和 ROI 都来自 PC 截图。** 手机上的渲染分辨率、UI 缩放、安全区裁切都不同，
所以坐标系转换是对的、**图像本身对不上**。要让包真正跑起来，必须在目标手机上
按 1:1 重新截取模板图与 ROI —— 这就是每个包旁边那份 `RECAPTURE.md` 的用途。

---

## 与 MAA-Meow 的关系

MaaPocket 的特权进程、native bridge、hidden-API 包装层是从
[Aliothmoon/MAA-Meow](https://github.com/Aliothmoon/MAA-Meow)（AGPL-3.0）移植的：

| 本仓库 | 来源 |
|---|---|
| `core/src/main/cpp/launcher.c` | MAA-Meow `app/src/main/native/launcher.c` |
| `core/src/main/cpp/bridge_*.cpp`、`bridge.h` | MAA-Meow `app/src/main/native/` |
| `core/src/main/java/com/maapocket/core/third/**` | MAA-Meow `third/` |
| `hidden-api/src/main/java/**` | MAA-Meow `hidden-api/src/main/java/` |
| `scripts/setup_maa_framework.py` 的思路 | MAA-Meow `scripts/setup_maa_core.py` |

因此 MaaPocket 以 **AGPL-3.0** 发布，见 [`LICENSE`](LICENSE)。

三个迁移脚本读取的上游项目许可以及包内保留的许可证：

- March7thAssistant — GPL-3.0
- ZenlessZoneZero-OneDragon — GPL-3.0
- MaaEnd — AGPL-3.0

MaaFramework 本体来自 [MaaXYZ/MaaFramework](https://github.com/MaaXYZ/MaaFramework)（MIT），
CI 构建时下载，**不提交进仓库**。

---

## 已知问题

- **未 root 的手机必须开「USB 调试（安全设置）」**，否则 Shizuku 拿不到 `INJECT_EVENTS`，
  表现是截屏正常但所有点击静默失败。开发者选项 → 「USB 调试（安全设置）」→ 重启。
  实测设备 HONOR AGI-AN00 / Android 15 的 Shizuku 以 `shell`(uid 2000) 运行，
  而 Android 14+ 起注入事件在部分机型上要求 root uid。
- **APK 体积**（实测）：hsr 76.2 MB、zzz 72.7 MB、endfield 102.8 MB。`useLegacyPackaging = true`
  是承重的（`dlopen` 与 `liblauncher.so` 需要真实文件而非 APK 内压缩项）。侧载没问题，
  上架 Play 会撞 200 MB 上限。
- **模板需实机重拍**（见上）。
- **`agent/cpp-algo` 未移植**：终末地的地图导航、AutoEcoFarm 等 6 个任务依赖它。
- **多跳导航未实现**：星铁包只支持屏幕状态图的单跳跳转，全覆盖需要 2313 条路径。
- **`Scroll` 动作在 Android Native controller 上不存在**：MaaFramework 的
  `AndroidNativeControlUnitAPI` 不带 `ScrollableUnit`，只能用 `Swipe` 近似。
- **APK 用仓库内固定 keystore 签名**（`keystore/maapocket.jks`，口令 `maapocket`）。早期版本用
  CI 每次重新生成的 debug keystore，后果是**新版装不上旧版**：`adb install -r` 报
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE: ... signatures do not match`，用户必须先卸载。
  这个 keystore 只用于侧载分发，**不是上架密钥**。
- **排查特权进程**：启动器的日志按后端分两处 —— root 走
  `/data/user/0/<pkg>/debug/maapocket-root-launcher.log`，Shizuku 走
  `/data/local/tmp/maapocket/maapocket-shizuku-launcher.log`（`/data/data/<pkg>/` 对
  `adb shell` 不可读，所以才挪到 `/data/local/tmp`）。同一条日志还会以
  `RootLauncher` 这个 tag 打到 logcat。

---

## 许可

AGPL-3.0。见 [`LICENSE`](LICENSE)。
