# Agent 集成设计说明

本文说明 MaaPocket 如何把 MaaFramework 资源包里声明的 **agent** 跑在 Android 上，以及
Go agent 当前可构建、C++ agent 尚未解决的状态。

本文只描述**已由源码/规范核实**的事实；凡属推断的地方都显式标注。

---

## 1. MaaFramework 里的 "agent" 是什么

MaaFramework 的自定义识别（Custom Recognition）与自定义动作（Custom Action）有两条实现路径：

1. **进程内**：宿主通过 C API 注册回调。
2. **进程外（AgentServer）**：另起一个子进程，由子进程调用
   `MaaAgentServerRegisterCustomRecognition` / `MaaAgentServerRegisterCustomAction`
   注册实现，再 `MaaAgentServerStartUp(identifier)` + `MaaAgentServerJoin()` 接入。

第二条路径里的子进程就是 **agent**。宿主侧的对应物是 **MaaAgentClient**：

- 宿主 `MaaAgentClientCreateV2` 建客户端，`MaaAgentClientIdentifier` 取回连接标识
  （`identifier == null` 时由框架自行生成，通常是一个 socket 路径）；
- 宿主把该标识作为**最后一个参数**启动 agent 子进程；
- 宿主 `MaaAgentClientConnect`，等 `MaaAgentClientAlive`；
- 此后 Pipeline 里所有 `custom_recognition` / `custom_action` 名字，只要 agent 注册过，
  就会通过这条连接被派发到 agent 进程执行。

关键含义：**agent 是可执行文件，不是动态库**。它需要 `execve`，需要自己的 `main`，
需要 `libMaaFramework.so` / `libMaaAgentServer.so` 在运行时可被加载。
它跟宿主的通信是进程间通信，不是函数调用。

MaaEnd 的两个 agent 都遵守同一套约定（已核实）：

- Go：`agent/go-service/main.go` → `runAgent(os.Args[1])` → `maa.AgentServerStartUp(identifier)` → `maa.AgentServerJoin()`
- C++：`agent/cpp-algo/source/main.cpp` → `const char* identifier = argv[argc - 1]; MaaAgentServerStartUp(identifier); MaaAgentServerJoin();`

两边都取**最后一个参数**当 identifier，与 `MaaAgentClient.kt` 里记录的握手顺序一致。

---

## 2. PI-V2 里 agent 的契约

MaaEnd 的 `assets/interface.json` 顶层 `interface_version` 为 `2`，其 agent 声明为：

```json
"agent": [
    {
        "child_exec": "agent/go-service"
    },
    {
        "child_exec": "agent/cpp-algo",
        "child_args": []
    }
]
```

按 Project Interface V2 规范（`MaaXYZ/MaaFramework` `docs/en_us/3.3-ProjectInterfaceV2.md`）：

| 字段 | 类型 | 规范原文要点 |
| --- | --- | --- |
| `agent` | `object \| object[]` | "Agent configuration; it can be a single object or an array of objects, containing information about the subprocess(es) (AgentServer)." 自 **v2.5.0** 起 Client 应在启动子进程时注入 `PI_` 前缀环境变量。 |
| `child_exec` | `string` | "The subprocess path, an executable file available in the system path." **"CWD is the directory containing interface.json."** |
| `child_args` | `string[]` | 可选，子进程参数数组。 |
| `identifier` | `string` | 可选。"A connection identifier used to create a communication socket. If provided, it will be used; otherwise, one is created automatically." |

要点：

- **`child_exec` 是"路径"，不是"必须是相对路径"**。规范允许写 `"python"` 这种 PATH 上的名字，
  也允许写 `"agent/go-service"` 这种相对（或绝对）路径。是否解析、怎么解析由 Client 决定。
- **agent 的 CWD 是 `interface.json` 所在目录**，即安装后的资源包根目录。
  这一点对 Go agent 尤其重要，见 §6。
- **MaaEnd 没有声明 `identifier`**，所以标识由框架生成后由 MaaPocket 交给子进程。
- 自 v2.5.0 起 Client 应注入 8 个 `PI_*` 变量：`PI_INTERFACE_VERSION`、`PI_CLIENT_NAME`、
  `PI_CLIENT_VERSION`、`PI_CLIENT_LANGUAGE`、`PI_CLIENT_MAAFW_VERSION`、`PI_VERSION`、
  `PI_CONTROLLER`、`PI_RESOURCE`（后两者是**单行压缩 JSON**，i18n 已解析、不含 `$` 前缀键）。
  规范明确："If a value is unavailable, the Client may omit the variable or set it empty;
  the child should tolerate missing variables."

  规范同时提醒：`PI_INTERFACE_VERSION` 是 **Client 实现的 PI 扩展面**的语义化版本，
  **不要**和 `interface.json` 里那个目前固定为 `2` 的数值型 `interface_version` 混淆。

  Go agent 确实会读这些变量：`agent/go-service/pkg/pienv/pienv.go` 逐个 `os.Getenv`，
  并把 `PI_CONTROLLER` / `PI_RESOURCE` 反序列化成结构体。`skill`/`processcheck` 之类的
  代码会通过 `pienv.ControllerType()` 判断是不是 `Win32`。若不注入，agent 仍能启动，
  但依赖控制器类型的逻辑会走"非 Win32"分支。

---

## 3. MaaPocket 如何把 `child_exec` 映射到 APK 内的可执行文件

Android 不允许从 APK 的 assets 里直接 `execve`。可执行文件必须以**原生库**的身份
被安装到应用私有目录（`/data/app/.../lib/<abi>/`），才有可执行权限。

因此 MaaPocket 的映射规则是：

| 资源包声明 | 映射到的 APK 内容 |
| --- | --- |
| `child_exec: "agent/go-service"` | `lib/<abi>/libMaaEnd_go_service.so`（Go 交叉编译产物，即 ELF 可执行文件） |
| 该 agent 的运行时数据 | `lib/<abi>/` 同级或 app 私有目录下的 `bundle/go-service/…` |
| `child_exec: "agent/cpp-algo"` | 尚未解决，见 §7 |

解析 `child_exec` 的实际代码在 `core/src/main/java/com/maapocket/core/pi/PiSelection.kt`
（该类被注释为"从 interface 描述符的 `agent[].child_exec` / `child_args` 组装 agent 命令行"）。
`MaaAgentClient.kt` 的 `MaaAgentRuntime` 则承载"可执行文件 + 参数 + 原生库目录 + 工作目录"。

启动顺序必须是（来自 `MaaAgentClient.kt` 的文档契约）：

1. `create(...)`（`identifier` 传 `null`，让框架生成）
2. `identifier()` 取回标识
3. 启动子进程，把标识作为**最后一个参数**追加在 `child_args` 之后
4. `bindResource(resource)` —— **必须在 `connect()` 之前**
5. `connect()`，等待 `alive`

---

## 4. `lib*.so` 命名技巧与必需的 Gradle 开关

### 4.1 为什么产物必须叫 `lib*.so`

Android 的 APK 打包器只把 `lib/<abi>/` 下**匹配 `lib*.so`** 的文件当作原生库收录，
并按一定的压缩/页对齐规则处理。一个普通名字（例如 `go-service`）放进 `lib/<abi>/`
不会被当作原生库，若放进 `assets/` 又不能执行。

所以 MaaPocket 的约定是：**把一个单文件 ELF 可执行文件伪装成共享库**，
命名成 `libMaaEnd_go_service.so`，让它落进 `lib/<abi>/`，由系统安装到应用私有原生库目录，
之后就有 `x` 权限、可以 `execve`。

这就是 `build_go_agent.py` 把 `-o` 固定写成
`<out>/<abi>/libMaaEnd_go_service.so` 的原因；也是 `MaaAgentClient.kt` 里
`MaaAgentRuntime` 的注释所描述的行为——
"an agent is a single-file ELF whose file name must start with `lib` and end in `.so`
(so it can live in `jniLibs/`)"。

注意区分：**名字是库，内容是可执行文件**。所以校验时不能只看后缀，
`build_go_agent.py` 会解析 ELF 头，要求 `e_type == ET_DYN`（PIE），
因为 Android 5.0+ 不再执行非 PIE 的可执行文件。

### 4.2 为什么两个开关都必须开

```kotlin
// app-*/build.gradle.kts 与 core/build.gradle.kts（由其他 agent 负责）
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

```xml
<!-- AndroidManifest.xml -->
<application android:extractNativeLibs="true" ... >
```

两者都必需，缺一不可：

- **`android:extractNativeLibs="true"`**：从 Android 6.0 / AGP 3.6 起，
  原生库默认**不**从 APK 解压到文件系统，而是直接从 APK 内 mmap 加载。
  未解压 = 文件系统上没有那个文件 = 没有路径可以 `execve`。
  必须显式 `extractNativeLibs="true"`，让安装器把 `lib/<abi>/*.so` 真正落盘。
- **`packaging.jniLibs.useLegacyPackaging = true`**：控制打包侧是否以"旧式"方式
  （不压缩、页对齐、`extractNativeLibs` 语义）写入 APK。AGP 默认 `false`，
  会按 `extractNativeLibs="false"` 的方式来打包；即使 manifest 写了 `true`，
  两处不一致也会导致解压行为不符预期。

两者本质上必须**取值一致**：manifest 的 `extractNativeLibs` 决定安装期行为，
Gradle 的 `useLegacyPackaging` 决定打包期布局。只改一个会得到"看起来配了但跑不起来"的状态。

> 该风险也适用于 MaaFramework 自身的 `.so`：不落盘的话，哪怕 agent 能起来，
> `maa.Init()` 也找不到 `libMaaFramework.so`。

---

## 5. 构建矩阵

`scripts/build_go_agent.py` 采用的、并经源码核实的组合：

| 变量 | 取值 | 理由 |
| --- | --- | --- |
| `GOOS` | `android` | `maa-framework-go` 的 `internal/native/framework.go` / `agent_server.go` 在 `switch runtime.GOOS` 里显式列出了 `case "linux", "android"`，返回 `libMaaFramework.so` / `libMaaAgentServer.so`。用 `android` 才能让二进制内的 `runtime.GOOS` 与真实运行环境一致。 |
| `GOARCH` | `arm64`（ABI `arm64-v8a`）/ `amd64`（ABI `x86_64`） | 对应 Android ABI。默认只出 `arm64-v8a`，见 `gradle.properties` 的 `maapocket.abis`。 |
| `CGO_ENABLED` | **`1`** | **不能是 `0`。** 虽然 `maa-framework-go` 自己确实不依赖 cgo（它用 `github.com/ebitengine/purego` 做 `Dlopen`/`Dlsym`，`internal/native/native_unix.go`，整仓没有任何 `.c`/`.h`，`import "C"` 出现 0 次），但那是**宿主程序**的事，和**目标平台**无关。Go 自己在 `src/cmd/go/internal/work/init.go` 的 `mustUseExternalLinker` 里对 android 返回 true，于是 CI 直接报 `android/amd64 requires external (cgo) linking, but cgo is not enabled`。原因是 Android 上所有可执行文件都必须走 Bionic 动态链接器（`/system/bin/linker64`），不能是静态链接的裸 ELF。 |
| `CC` / `CXX` / `AR` | NDK clang | 开了 cgo 之后必须给出交叉编译器，否则 cgo 会去用宿主 gcc（编出 x86-64 Linux 目标，`go build` 随后报 architecture mismatch）。取值 = `$NDK/toolchains/llvm/prebuilt/<host>/bin/{aarch64-linux-android28,x86_64-linux-android28}-clang` 与 `llvm-ar`。API level 取 28 与 gradle 的 `minSdk` 对齐。 |
| `NDK` | `29.0.13113456` | CI 里由 `android-actions/setup-android` 的 `packages: 'ndk;29.0.13113456'` 安装，与 `build.yml` 的 cmake NDK 同版本。脚本按 `--ndk` → `ANDROID_NDK_HOME` → `ANDROID_NDK_ROOT` → `NDK_HOME` → `$ANDROID_SDK_ROOT/ndk/<最大版本>` 顺序查找；**找不到就报错，不会静默退回 `CGO_ENABLED=0`**。 |
| `-tags` | 空 | 不需要额外 tag。Go 的构建约束规则里 `GOOS=android` 隐含满足 `linux` 约束（`src/go/build/build.go`：`if ctxt.GOOS == "android" && name == "linux" { return true }`），因此 `//go:build linux` 的 POSIX 实现（`ziplineimport/*`、`pkg/control/adaptor_linux.go`）和 `!windows` 的实现（`pkg/parentwatch/parentwatch_other.go`、`stderr_other.go`）都会被选中。**不要**手动加 `purego` tag：`pkg/minicv` 用它来二选一 SIMD 实现，加了会改变语义。 |

其他编译参数：`-trimpath`、`-buildvcs=false`（保证可复现）、`-ldflags="-s -w"`。

产物为 **PIE**：Go 对 `GOOS=android` 的默认 buildmode 就是 `pie`
（`src/internal/platform/supported.go` 的 `DefaultPIE` 中 `case "android", "ios": return true`；
`src/cmd/go/internal/work/init.go` 据此把 `ldBuildmode` 从 `exe` 改成 `pie`）。
脚本会额外校验 `e_type != ET_EXEC`。

`gopsutil/v4` 不构成障碍：它被 `common/closegame/action.go`、
`pretask/gamesetting/gamesetting.go`、`taskersink/processcheck/checker.go` 三个文件引用，
其 `process` 包的 Linux 实现文件头是 `//go:build linux`，在 `GOOS=android` 下同样满足；
cgo 只出现在 BSD/Darwin/AIX 文件里（`host/types_linux.go` 是 `//go:build ignore` 的
`cgo -godefs` 输入，从不编译）。

---

## 6. 运行时目录约定

Go agent 对 CWD 的要求来自源码，必须由宿主机满足：

| 需求 | 来源 | 宿主机怎么给 |
| --- | --- | --- |
| `<CWD>/maafw/` 下要有 `libMaaFramework.so`、`libMaaToolkit.so`、`libMaaAgentServer.so`、`libMaaAgentClient.so` | `agent/go-service/agent.go`：`libDir := filepath.Join(getCwd(), "maafw")`，随后 `maa.Init(maa.WithLibDir(libDir), …)` | 启动前把原生库目录（或它的软链）准备好。**注意**：go-service 内部**没有**读 `LD_LIBRARY_PATH` 或 `MAAFW_BINARY_PATH`；`MaaAgentRuntime.nativeLibDir` 的注释提到这两个变量是宿主侧约定，对这个二进制本身无效。 |
| CWD 必须**可写** | `logger.go`：`os.MkdirAll("debug", 0755)` 后打开 `debug/go-service.log`；失败即 `log.Fatal()...Msg("Failed to initialize logger")` | CWD 指向 app 私有可写目录 |
| `<CWD>/locales/go-service/zh_cn.json` 必须存在 | `pkg/i18n/i18n.go` 的 `resolveLocaleDir()` 以此文件判定候选目录是否合格 | 见下 |
| `<CWD>/locales/interface/` 可选但建议提供 | `i18n.Init()` 会额外合并同级 `interface` 目录 | 见下 |

**路径解析顺序**（`i18n.resolveLocaleDir()`）：以 `os.Getwd()` 及其最多 6 级祖先、
以及 `filepath.Dir(os.Executable())` 及其最多 6 级祖先为根，依次尝试
`locales/go-service` 和 `assets/locales/go-service`。

因此 `build_go_agent.py` 产出的数据目录布局是：

```
<out>/<abi>/
├── libMaaEnd_go_service.so          # 可执行文件，伪装成 .so
├── bundle/
│   └── go-service/
│       └── locales/
│           ├── go-service/          # 5 个 JSON + HTML/ 23 个模板
│           └── interface/           # 5 个 JSON
└── agents.json                      # 清单（不随 APK 分发）
```

宿主应把 `bundle/go-service` 作为 agent 的 **workingDir**，并在其中创建
`maafw/`（指向原生库）与 `debug/`（可写）。

> 说明：`go-service` 里只有两处 `go:embed`（`autostockpile/itemmap.go`、
> `creditshopping/itemmap.go` 的 `item_map.json`），物品表已编进二进制。
> 其余需要在运行时读取的数据文件（如 `data/EssenceFilter/*.json`）来自
> **游戏资源包**，由 `pkg/resource` 的 resource sink 解析，不由 agent bundle 提供。

---

## 7. 未解决：`agent/cpp-algo`

**状态：不能构建，没有可用的 Android 产物。** 以下障碍全部来自对
`agent/cpp-algo/CMakeLists.txt`、`agent/cpp-algo/source/CMakeLists.txt`、
`agent/cpp-algo/CMakePresets.json` 与源码树的实际阅读。

### 7.1 构建系统层面

1. **没有 Android 预设，也没有 NDK 工具链。**
   `CMakePresets.json` 的 configure preset 只有 `NinjaMulti`（Windows）、
   `NinjaMulti Win32`、`NinjaMulti Win32 ARM64`、`NinjaMulti Linux x64`、
   `NinjaMulti Linux arm64` 与 `MSVC 2022/2026`(ARM) 系列。
   Linux 预设用的工具链是 `MaaUtils/MaaDeps/cmake/maa-x64-linux-toolchain.cmake`
   和 `maa-arm64-linux-toolchain.cmake` —— **glibc Linux，不是 Bionic**。
   Android 需要新的 NDK toolchain file 与 preset。

2. **依赖包 `deps/` 是硬性前置条件。**
   `CMakeLists.txt` 在 `${CMAKE_CURRENT_SOURCE_DIR}/../../deps` 不存在时直接
   `message(FATAL_ERROR "Dependencies directory not found: ${DEPS_DIR}")`，
   并用它 `find_package(MaaAgentServer REQUIRED)`。
   这套 MaaDeps 预编译依赖（MaaAgentServer、OpenCV、ONNX Runtime、Boost、ZLIB）
   目前**没有 android/arm64-v8a 版本**，而 `MaaXYZ/MaaUtils` 仓库本身
   并不包含 `MaaDeps/` 目录（它只提供 `MaaUtils.cmake` 和 `cmake/`），
   MaaDeps 是另行分发的依赖包。这是第一大阻塞点：需要为 Android 烘出整套 ABI 兼容的
   OpenCV + ONNX Runtime + Boost + MaaAgentServer。

3. **`MaaUtils` 是 git submodule。**
   `.gitmodules` 声明 `[submodule "agent/cpp-algo/MaaUtils"] url = https://github.com/MaaXYZ/MaaUtils.git`，
   当前固定在 commit `6e9ba33f6ad835418097d9324c01c44a82825a2b`。
   CI checkout 必须带 `submodules: recursive`（或单独 checkout 该 submodule），
   否则 `include(MaaUtils/MaaUtils.cmake)` 直接失败。

4. **产物名不符合 `lib*.so` 约定。**
   `source/CMakeLists.txt` 是 `add_executable(cpp-algo …)` +
   `install(TARGETS cpp-algo RUNTIME DESTINATION agent LIBRARY DESTINATION agent)`，
   产物叫 `cpp-algo`。要放进 `lib/<abi>/` 必须改名/重新安装为
   `libMaaEnd_cpp_algo.so`。这一条相对最容易解决。

5. **RPATH 指向 `../maafw`。**
   `$ORIGIN/../maafw`（UNIX）/ `@executable_path/../maafw`（APPLE）。
   Android 上 `$ORIGIN` 的语义与可行目录需要重新确认。

### 7.2 源码层面：桌面专用文件被无条件编译

`source/CMakeLists.txt` 第 59 行是

```cmake
file(GLOB_RECURSE cpp_algo_src CONFIGURE_DEPENDS *.h *.hpp *.cpp)
```

只排除 `IconRecognition/test/`。**没有任何 `#ifdef`、也没有 CMake 门控**，
因此下面这些桌面专用实现会被无条件编进 Android 目标：

| 文件 | 依赖的桌面 API |
| --- | --- |
| `source/Common/CrashHandler.cpp` | `dbghelp`（Windows 崩溃转储） |
| `source/Common/FramelessWindow.cpp` | Win32 无边框窗口 |
| `source/Common/SystemMonitor.cpp` | `psapi`/`pdh` 的 GPU Engine 计数器 |
| `source/Common/WebView2.cpp` | WebView2 SDK |
| `source/Common/notice.cpp` | 桌面通知 |
| `source/MapNavigator/Backend/Desktop/desktop_input_backend.cpp` | 桌面输入注入 |
| `source/MapNavigator/Backend/Linux/linux_input_backend.cpp` | X11/Wayland 输入注入 |

这七个文件都需要 `#ifdef` 化（或改成 CMake 按平台过滤），Android 上要换成
基于 MaaFramework 控制器的输入后端。此外 Windows 分支还会链接
`psapi pdh bcrypt WebView2::WebView2 dbghelp` 并定义 `MAAEND_HAVE_WEBVIEW2`
（进而注册 `ZiplineImport` 自定义动作），Android 分支需要给出等价实现或明确禁用。

`cmake/WebView2.cmake` 本身在非 Windows 上会在开头 `return()`，不构成阻塞。

### 7.3 可行的推进方向（尚未验证）

- 为 MaaDeps 增加 `maa-arm64-android` / `maa-x86_64-android` triplet 并烘出依赖包；
- 新增 NDK toolchain file + `NinjaMulti Android arm64` preset；
- 给 `source/CMakeLists.txt` 换成显式源文件列表（或按平台过滤），把 7 个桌面文件排除；
- 输出重命名为 `libMaaEnd_cpp_algo.so` 并复用 Go agent 的 `lib/<abi>/` 机制。

在这些完成之前，**MaaPocket 只能启用 `agent/go-service`**；
`interface.json` 里声明了 `agent/cpp-algo`，但 MaaPocket 若照单全收会启动失败。
需要确定一个降级策略（例如：`child_exec` 在 APK 内找不到对应产物时跳过该 agent 并告警），
这一点也属于未决事项。

---

## 8. 相关文件

| 文件 | 作用 |
| --- | --- |
| `scripts/build_go_agent.py` | 交叉编译 Go agent、收集 locale bundle、校验 ELF、生成 `agents.json` |
| `.github/workflows/build-agents.yml` | CI：矩阵构建 `arm64-v8a` / `x86_64` 并上传产物 |
| `core/src/main/java/com/maapocket/core/maafw/MaaAgentClient.kt` | MaaAgentClient 封装与握手契约 |
| `core/src/main/java/com/maapocket/core/pi/PiSelection.kt` | 由 `agent[].child_exec` / `child_args` 组装命令行 |
