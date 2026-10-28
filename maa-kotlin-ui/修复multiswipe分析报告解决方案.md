# MultiSwipe（多指滑动）不适配问题：分析与解决方案报告

> 适用项目：`MAAFW-Android-火影忍者手游`
> 分析对象：`MaaFramework`（MAA-android-aarch64-v5.13.0 预编译库）的 AndroidNative 控制器
> 日期：2026-08-10

---

## 一、问题现象

- 周胜（决斗场连点器）进入战斗后，任务**稳定退出/中断**；
- 各战斗副本（秘境/忍法帖/天地/丰饶/忍刀/百忍/要塞/自动战斗）的「组合技」节点执行失败；
- 共同特征：这些节点都使用了 MaaFramework 的 **`MultiSwipe`（多指同时滑动）** 动作，例如：
  - `auto_battle.json / click_All_skill`：**8 指同时按**（替身+召唤+密卷+普攻+大招+一二技能+子技能）
  - `auto_battle.json / click_Ult_and_attack_3s`：2 指（大招+普攻）
  - `auto_battle.json / summon_and_roll`：2 指（通灵+密卷）
  - 各副本 `*_click_All_skill`：4~8 指组合技

---

## 二、根因（源码级证据）

### 2.1 MaaFramework AndroidNative 控制器**故意限制单指**

文件：`MaaFramework-main/source/MaaAndroidNativeControlUnit/Manager/AndroidNativeControlUnitMgr.cpp`（L322-330）：

```cpp
bool AndroidNativeControlUnitMgr::validate_contact(int contact)
{
    if (contact == 0) {
        return true;   // 只接受第 0 根手指
    }

    LogWarn << "native android controller only supports single touch" << VAR(contact);
    return false;      // contact>0（第二根及以后）直接拒绝
}
```

`touch_down / touch_move / touch_up` 在调用前都会执行 `validate_contact(contact)`。
因此 **MultiSwipe 的第二根手指（contact=1）在 MaaFramework 侧就被拒绝**，根本不会下发到 libbridge.so。

> 这就是 libMaaAndroidNativeControlUnit.so 内字符串 `"native android controller only supports single touch"` 的真实来源（非错误，是硬限制）。

### 2.2 bridge 协议**不携带 contact（指针序号）**

文件：`MaaFramework-main/source/MaaAndroidNativeControlUnit/General/AndroidExternalLib.h`：

```cpp
enum MethodType : int {
    START_GAME = 1, STOP_GAME = 2, INPUT = 4,
    TOUCH_DOWN = 6, TOUCH_MOVE = 7, TOUCH_UP = 8,
    KEY_DOWN = 9, KEY_UP = 10
};

struct TouchArgs {          // 单点触摸参数
    Position p { };         // 仅 x,y，无 contact / 无指针索引
};

struct MethodParam {
    int display_id = 0;
    MethodType method = START_GAME;
    ArgUnion args { };
};
```

即使放开 `validate_contact`，**协议里也没有字段能告诉 libbridge 当前事件属于第几根手指**，
libbridge 侧无法构造正确的多指针 `MotionEvent`（`ACTION_POINTER_DOWN / ACTION_POINTER_UP`）。

### 2.3 我们的 libbridge.so（自写）也只支持单点

`app/src/main/native/bridge.h`、`bridge_input.cpp` 与 MaaFramework 协议对齐，`TouchArgs` 同样是单 `Position`，
Java 侧 `NativeBridge.touchDown/touchMove/touchUp` 每次注入的都是单指针 `ACTION_DOWN/MOVE/UP`。

**结论：这是 MaaFramework AndroidNative 控制器的架构性限制（单指），不是偶然 bug。**

---

## 三、方案对比总览

| 方案 | 是否改 MaaFramework | 是否改 libbridge | 重编译 | 成本 | 推荐度 |
|---|---|---|---|---|---|
| **方案 A：改 MaaFramework 源码 + libbridge，重编译** | ✅ 是 | ✅ 是 | libMaaAndroidNativeControlUnit.so + libbridge.so | 高（完整构建链） | ⭐⭐⭐ 追求原生 |
| **方案 B：MultiSwipe → Custom action（引擎层多指注入）** | ❌ 否 | ❌ 否 | 仅 APK | 低 | ⭐⭐⭐⭐⭐ 已实施 |
| 方案 C：把 MultiSwipe 拆成单指顺序操作 | ❌ | ❌ | 无 | 低 | ❌ 游戏内不可用（组合技必须同时） |

> **方案 B 已经实施并编译进 APK**（见第五节）。以下重点说明**方案 A**（改 MAA-android-aarch64-v5.13.0 的完整做法）。

---

## 四、方案 A：修改 MaaFramework 源码适配 MultiSwipe（详细步骤）

### 4.1 需要修改的文件清单

| 序号 | 文件（MaaFramework-main 相对路径） | 作用 |
|---|---|---|
| 1 | `source/MaaAndroidNativeControlUnit/General/AndroidExternalLib.h` | bridge 协议：TouchArgs 加 contact、MethodType 加 EX 扩展 |
| 2 | `source/MaaAndroidNativeControlUnit/Manager/AndroidNativeControlUnitMgr.h` | validate_contact 声明（如需改签名） |
| 3 | `source/MaaAndroidNativeControlUnit/Manager/AndroidNativeControlUnitMgr.cpp` | 移除单指限制 + 填充 contact |
| 4 | 本项目的 `app/src/main/native/bridge.h` | libbridge 侧协议对齐（TouchArgs 加 contact） |
| 5 | 本项目的 `app/src/main/native/bridge_input.cpp` | libbridge 解析 contact → 调 Java 多指方法 |
| 6 | 本项目 `app/src/main/java/com/maafw/naruto/bridge/NativeBridge.kt` | 新增多指方法（带 contact） |
| 7 | 本项目 `app/src/main/java/com/maafw/naruto/shizuku/InputInjector.kt` | **多指状态机**：按 contact 生成 ACTION_DOWN / POINTER_DOWN / MOVE / POINTER_UP / UP |

### 4.2 修改内容

#### ① AndroidExternalLib.h —— 扩展协议（MaaFramework 侧）

```cpp
enum MethodType : int {
    START_GAME = 1, STOP_GAME = 2, INPUT = 4,
    TOUCH_DOWN = 6, TOUCH_MOVE = 7, TOUCH_UP = 8,
    KEY_DOWN = 9, KEY_UP = 10,
    // 新增：带 contact（指针序号）的多点触摸方法
    TOUCH_DOWN_EX = 11,
    TOUCH_MOVE_EX = 12,
    TOUCH_UP_EX = 13
};

struct TouchArgs {
    Position p { };
    int contact = 0;   // ★ 新增：指针序号（第几根手指）
};
```

> 保持原 TOUCH_DOWN/MOVE/UP 兼容旧 libbridge；新方法 EX 显式携带 contact，避免歧义。
> 旧 libbridge 收到不认识的 EX 方法默认返回 0 即可（不会崩溃，MethodParam 是值传递）。

#### ② AndroidNativeControlUnitMgr.cpp —— 放开单指限制 + 传 contact

```cpp
// L322 validate_contact：改为允许 0~9 指（Android 触摸最多 10 指针）
bool AndroidNativeControlUnitMgr::validate_contact(int contact)
{
    if (contact >= 0 && contact < 10) {
        return true;
    }
    LogWarn << "touch contact out of range" << VAR(contact);
    return false;
}
```

```cpp
// touch_down（L152）：改用 EX 方法并携带 contact
bool AndroidNativeControlUnitMgr::touch_down(int contact, int x, int y, int pressure)
{
    // ...（原有校验不变）...
    MethodParam param { };
    param.display_id = config_.display_id;
    param.method = TOUCH_DOWN_EX;                       // ★ EX
    param.args.touch.p = { mapped.x, mapped.y };
    param.args.touch.contact = contact;                 // ★ contact
    if (!dispatch_input_message(param)) return false;
    last_touch_points_[contact] = mapped;
    return true;
}
// touch_move / touch_up 同理：TOUCH_MOVE_EX / TOUCH_UP_EX + contact
```

> `get_features()` 已返回 `UseMouseDownAndUpInsteadOfClick`，MaaFramework 动作层会用
> `touch_down(contact,...) → touch_move(...) → touch_up(contact)` 序列驱动 MultiSwipe，
> **改完这两处即可让 MultiSwipe 的多指事件流完整下发**。

#### ③ bridge.h / bridge_input.cpp（本项目 libbridge，对齐协议）

`bridge.h`：
```cpp
enum MethodType { START_GAME=1, STOP_GAME=2, INPUT=4, TOUCH_DOWN=6, TOUCH_MOVE=7, TOUCH_UP=8,
                  KEY_DOWN=9, KEY_UP=10, TOUCH_DOWN_EX=11, TOUCH_MOVE_EX=12, TOUCH_UP_EX=13 };
struct TouchArgs { Position p; int contact = 0; };
```

`bridge_input.cpp`（DispatchInputMessage 增加 EX 分支，把 contact 传给 Java）：
```cpp
case TOUCH_DOWN_EX:
    return UpcallInputControlEx(env, TOUCH_DOWN_EX, param.args.touch.p.x, param.args.touch.p.y,
                                param.args.touch.contact, param.display_id);
// TOUCH_MOVE_EX / TOUCH_UP_EX 同理
```
（JNI 侧新增 `touchDownEx(x, y, contact, displayId)` 等方法绑定）

#### ④ NativeBridge.kt + InputInjector.kt —— 多指状态机（Java 层核心）

```kotlin
// InputInjector：维护当前按下的手指（contact → 坐标）
private val activePointers = sortedMapOf<Int, Pair<Int,Int>>()  // contact -> (x,y)

fun touchDown(contact: Int, x: Int, y: Int, displayId: Int): Boolean {
    val wasEmpty = activePointers.isEmpty()
    activePointers[contact] = x to y
    // 第一根手指 → ACTION_DOWN；后续手指 → ACTION_POINTER_DOWN(actionIndex=当前索引)
    val action = if (wasEmpty) MotionEvent.ACTION_DOWN
                 else MotionEvent.ACTION_POINTER_DOWN or (index shl ACTION_POINTER_INDEX_SHIFT)
    return injectMultiPointer(action, displayId)
}

fun touchMove(contact: Int, x: Int, y: Int, displayId: Int): Boolean {
    activePointers[contact] = x to y
    return injectMultiPointer(MotionEvent.ACTION_MOVE, displayId)  // 所有手指一起 MOVE
}

fun touchUp(contact: Int, displayId: Int): Boolean {
    activePointers.remove(contact)
    val action = if (activePointers.isEmpty()) MotionEvent.ACTION_UP
                 else MotionEvent.ACTION_POINTER_UP or (index shl ACTION_POINTER_INDEX_SHIFT)
    return injectMultiPointer(action, displayId)
}
```

`injectMultiPointer`：按 `activePointers` 全部坐标构造多指针 `MotionEvent`（PointerProperties + PointerCoords），
`InputManager.setDisplayId(ev, displayId)` + `injectInputEvent` 注入到虚拟屏。

### 4.3 重编译方法

#### libMaaAndroidNativeControlUnit.so（MaaFramework 侧）

MaaFramework 5.x 构建依赖较多（3rdparty：opencv / onnxruntime / boost / json 等，通过 vcpkg 或预构建管理）：

```bash
# 在 MaaFramework-main 目录（需先准备 Android NDK）
export ANDROID_NDK_HOME=/data/ndksupport-1710240003/android-ndk-aide
# 用 MaaFramework 的 CMake 预设（android-arm64）：
cmake --preset android-arm64
cmake --build build/android-arm64 --target MaaAndroidNativeControlUnit
```

产物：`libMaaAndroidNativeControlUnit.so` → 替换
`app/src/main/jniLibs/arm64-v8a/libMaaAndroidNativeControlUnit.so`

#### libbridge.so（本项目）

```bash
cd app/src/main/native
/data/ndksupport-1710240003/android-ndk-aide/ndk-build clean
/data/ndksupport-1710240003/android-ndk-aide/ndk-build -j14
```
（或使用 CMake + NDK toolchain，见 `CMakeLists.txt`）

产物：`libs/arm64-v8a/libbridge.so` → 替换
`app/src/main/jniLibs/arm64-v8a/libbridge.so`

### 4.4 风险与注意事项

1. **协议必须两端同步改**：libMaaAndroidNativeControlUnit 与 libbridge 的 `MethodParam` 内存布局必须一致，
   否则 `DispatchInputMessage` 读到错位数据会**崩溃（SIGSEGV）**。
2. **重编译成本高**：MaaFramework 5.x 依赖链长（vcpkg 拉取 opencv/onnxruntime 等），
   Android 交叉编译需完整 NDK + 依赖，设备上构建耗时**数小时级**，且需保证 ABI（arm64-v8a）与版本一致。
3. **向后兼容**：若旧 libbridge 遇到 EX 方法，`DispatchInputMessage` 的 switch 需有 default 返回 0（成功），
   避免 MaaFramework 判定失败。
4. **多指上限**：Android 单次触摸最多 10 指针，`validate_contact` 上限建议设 10。

---

## 五、方案 B（已实施）：MultiSwipe → Custom action 引擎层多指注入

**不改 MaaFramework、不改 libbridge，零重编译**，直接绕过 AndroidNative 单指限制：

### 5.1 已完成的改动

| 文件 | 内容 |
|---|---|
| `app/src/main/assets/resource/base/pipeline/*.json` | 11 个 `MultiSwipe` 节点 → `action: Custom` + `custom_action: MultiSwipeCustom`（转换脚本 `scripts/convert_multiswipe.py`，原始 pipeline 已备份 /tmp） |
| `app/src/main/java/com/maafw/naruto/maa/CustomActions.kt` | 注册 `MultiSwipeCustom`，解析 `swipes[]`，用 `injectInputEvent` 注入**多指针 MotionEvent**（手指逐指 DOWN → 插值 MOVE → 逆序 UP）到虚拟屏 displayId |
| `app/src/main/java/com/maafw/naruto/maa/AssetResourceDeployer.kt` | pipeline 文件数变化 → 强制重新部署资源（否则旧资源不生效） |

### 5.2 原理

- MaaFramework 的 Custom action 由引擎进程（shell/root）回调执行；
- 回调里直接构造**多指针 MotionEvent**（PointerProperties/PointerCoords，ACTION_POINTER_DOWN/UP），
  经 `InputManager.injectInputEvent` 注入到虚拟屏——**绕开 AndroidNative 的单指限制，且协议无需改动**。

### 5.3 验证方法

1. 安装新 APK（首次启动会重新部署资源）；
2. 跑周胜/秘境等战斗副本；
3. 引擎日志出现 `multiSwipeInject: 8指 时长=...ms displayId=...` 即表示多指注入生效；
4. 战斗组合技正常、任务不再退出。

---

## 六、结论与建议

1. **根因**：MaaFramework AndroidNative 控制器 `validate_contact` 硬限制单指 + bridge 协议不传 contact，
   导致 `MultiSwipe` 多指在 AndroidNative 上被拒绝（任务退出）。
2. **方案 A（改 MaaFramework 重编译）**：完整可行但成本高（需构建 MaaFramework 依赖链），
   适合**长期维护、追求原生 MultiSwipe** 的场景；改动文件见第四节清单。
3. **方案 B（Custom action 多指注入）**：**已实施、零重编译、立即生效**，推荐先用此方案验证；
   若后续确需原生 MultiSwipe，可再按方案 A 重编译（两方案可共存，Custom 优先级更高）。