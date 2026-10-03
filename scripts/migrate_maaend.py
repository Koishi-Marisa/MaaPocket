#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把上游 MaaEnd 工程迁成一份 Android 可用的 Project Interface V2 资源包。

上游：https://github.com/MaaEnd/MaaEnd （AGPL-3.0）
产出：`--out` 指向 **PI 包根目录**，也就是放着 `interface.json` 的那一层：

    <out>/interface.json            改写过：只留一个"设备内"控制器
    <out>/tasks/**                  原样
    <out>/resource/**               原样（基线 720p 图 + pipeline + default_pipeline.json）
    <out>/resource_adb/**           原样（上游自己的 ADB overlay）
    <out>/resource_android/**       **本脚本新生成**，补 resource_adb 没盖到的 Android 缺口
    <out>/data/**                   原样
    <out>/locales/**                原样
    <out>/LICENSE                   原样（AGPL-3.0 要求随包分发）
    <out>/migration_report.json     机器可读的迁移报告

`MaaPocket/scripts/build_pi_pack.py` 再把这份包按白名单组装进
`app-endfield/src/main/assets/pi/`（它的 DEFAULT_INCLUDE 正好是
`interface.json` / `tasks/**` / `resource/**` / `resource_*/**` / `data/**` / `locales/**`
/ `CONTACT` / `LICENSE`，所以上面的布局就是它认的那一层）。


为什么需要这一层迁移
--------------------

MaaEnd 的 Windows 版把 `Scroll`（滚轮）和一堆键盘动作当作一等公民。MaaPocket 在
Android 上用的是 MaaFramework 的 Android Native 控制器
（`libMaaAndroidNativeControlUnit.so` → 宿主 App 的 `DispatchInputMessage`），它的能力边界是：

* `MaaControlUnit/ControlUnitAPI.h`：`AndroidNativeControlUnitAPI : ControlUnitAPI`，
  **不继承 `ScrollableUnit` / `RelativeMovableUnit` / `ShellableUnit`**；
  而 `AdbControlUnitAPI : ControlUnitAPI, ShellableUnit`，`CustomControlUnitAPI` 三者全有。
* `MaaFramework/Controller/ControllerAgent.cpp` 的 `handle_scroll()`：
  拿不到 `ScrollableUnit` 就 `LogError << "Scroll is not supported for this controller type"`
  并 **`return false`** —— 动作判定为失败，不是静默跳过。所以带 `Scroll` 的节点在
  Android Native 上一定会失败，必须换掉。
* 键盘反过来是 **通的**：`AndroidNativeControlUnitMgr::key_down/key_up` 原样把 keycode
  塞进 `param.args.key.key_code`，`ControllerAgent::handle_click_key` 因为
  `MaaControllerFeature_UseKeyboardDownAndUpInsteadOfClick` 而走 key_down/key_up。
  但 **keycode 的含义完全不同**：MaaEnd 写的是 Windows VK 码，而宿主 App 侧
  `MaaPocket/core/src/main/java/com/maapocket/core/maa/InputControlUtils.java` 是

      KeyEvent keyEvent = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0);
      getManager().injectInputEvent(keyEvent, ...);

  —— 没有任何翻译表，`key` 被直接当成 `android.view.KeyEvent` 的 keycode。
  于是 VK 27（ESC）在 Android 上是 `KEYCODE_CAMERA`，VK 69（'E'）是 `KEYCODE_MINUS`，
  VK 18（Alt）是 `KEYCODE_POUND`，VK 65（'A'）是 `KEYCODE_ENVELOPE` …
  除了"改成 Android 上确实代表同一意图的码"（ESC → `KEYCODE_BACK` = 4）
  或者"改成触摸动作"，没有别的正确解法。本脚本只做前两者，其余一律进
  `resource_android/KNOWN_LIMITATIONS.md`，不伪造。


====== 宿主 App 必须替换的东西（comment block） ======

脚本产出的是**纯资源包**，只声明"要什么"，不提供"实体在哪"。以下三件事由宿主 App 负责，
本脚本刻意不改（改了就与 `interface.json` 的语义不符）：

1. `agent[].child_exec`
   `interface.json` 里保持上游的字面量 `"agent/go-service"`（= MaaPocket 的
   `scripts/build_go_agent.py` 里的常量 `GO_SERVICE_IDENTIFIER = "agent/go-service"`）。
   那不是一个能直接 spawn 的路径，而是**标识符**。宿主 App 必须：
     * 把 `libMaaEnd_go_service.so` 按 ABI 放到 `lib/<abi>/`（配合
       `android:extractNativeLibs="true"` + `packaging.jniLibs.useLegacyPackaging = true`）；
     * 运行期把 `child_exec` 换成真路径，例如
       `new File(context.getApplicationInfo().nativeLibraryDir, "libMaaEnd_go_service.so")`；
     * 把子进程工作目录设成 `bundle/go-service`（build_go_agent.py 的
       `BUNDLE_AGENT_DIRNAME`），因为 `agent/go-service/pkg/i18n/i18n.go` 的
       `resolveLocaleDir()` 是从 cwd 向上找 `locales/go-service` / `assets/locales/go-service`；
     * 在 `<workingDir>/maafw/` 放 `libMaaFramework.so` 等，因为
       `agent/go-service/agent.go` 写死了 `filepath.Join(getCwd(), "maafw")`。
2. `agent/cpp-algo`
   上游 `interface.json` 声明了两个 agent。MaaPocket **没有** Android 侧的 cpp-algo 构建器
   （`scripts/` 下只有 `build_go_agent.py`），所以本脚本**删掉了 `agent/cpp-algo` 这一项**，
   并把受影响的任务列进报告和文档。宿主 App 不要把它加回来。
3. `controller`
   上游 8 个控制器只剩 1 个，名字仍叫 `ADB`、`type` 仍是 `Adb` —— 这是刻意的：
   `tasks/**` 里每个 task 都带 `"controller": ["ADB", ...]` 白名单，改名会让任务在 UI 上消失。
   设备上由 MaaFramework 的 Android Native 控制器实现这个 `type`，与上游
   `Aliothmoon/MaaFwApp` 的 `INTEGRATION.md` 一致（"只取 `type: Adb` 的一项"）。


关于注释丢失
------------
`interface.json` 会被解析后重新序列化（`json.dumps`），因此**上游的行内注释与尾随逗号不会保留**。
`resource/**`、`resource_adb/**` 等是按 blob 逐字节复制的，注释原样保留。
这是刻意的取舍：产物必须能过 `interface.schema.json`，而标准库没有 JSONC 的保注释读写。


纯标准库，Python 3.9+。不用 jsonschema（脚本自带一个够用的子集校验器），不依赖 PowerShell。
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

# Windows 控制台是 ANSI 代码页，路径里的中文 print 会崩；显式换成 UTF-8。
if sys.platform == "win32":
    for _stream_name in ("stdout", "stderr"):
        _stream = getattr(sys, _stream_name, None)
        if _stream is None or not hasattr(_stream, "buffer"):
            continue
        try:
            import io

            setattr(sys, _stream_name, io.TextIOWrapper(_stream.buffer, encoding="utf-8", errors="replace"))
        except (AttributeError, ValueError):  # pragma: no cover
            pass


# --------------------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------------------

GENERATOR = "MaaPocket/scripts/migrate_maaend.py"
UPSTREAM = "https://github.com/MaaEnd/MaaEnd"
UPSTREAM_LICENSE = "AGPL-3.0"

REPORT_NAME = "migration_report.json"
OVERLAY_DIR = "resource_android"
LIMITATIONS_NAME = "KNOWN_LIMITATIONS.md"

# (源前缀, 目标前缀)。源来自 `git ls-tree -r HEAD <prefix>`，逐个 blob 读出来写盘。
COPY_TREES: Tuple[Tuple[str, str], ...] = (
    ("assets/tasks", "tasks"),
    ("assets/resource", "resource"),
    ("assets/resource_adb", "resource_adb"),
    ("assets/data", "data"),
    ("assets/locales", "locales"),
)
# 单文件： (源路径, 目标相对路径)
COPY_FILES: Tuple[Tuple[str, str], ...] = (("LICENSE", "LICENSE"),)
# interface.json 单独处理（要改写）
INTERFACE_SRC = "assets/interface.json"
INTERFACE_DST = "interface.json"

# 唯一一个 submodule gitlink（assets/resource/model -> MaaEnd/MaaEnd-AI），没有 blob 内容。
SUBMODULE_PATHS = frozenset({"assets/resource/model"})

KEY_FAMILY = ("ClickKey", "KeyDown", "KeyUp", "LongPressKey")
SCROLL = "Scroll"

# V1 把动作参数写在节点顶层（`"action": "ClickKey", "key": 27`）。当覆盖写成 V2
# （`"action": {"type": ..., "param": {...}}`）时，MaaFramework 只读 `action.param`，
# 顶层这些同名字段会被忽略。合并校验前把它们摘掉，产出的合并视图才干净。
V1_ACTION_PARAM_KEYS = frozenset({
    "target", "target_offset", "begin", "begin_offset", "end", "end_offset",
    "duration", "end_hold", "only_hover", "starting", "contact", "pressure",
    "key", "key_code", "auto_up", "input_text", "package", "exec", "args",
    "detach", "custom_action", "custom_action_param", "cmd", "swipes", "dx", "dy",
    "type", "param",
})

# Android 侧没有 ESC，返回键是 KEYCODE_BACK = 4。
ANDROID_KEYCODE_BACK = 4
WINDOWS_VK_ESCAPE = 27

# 只起「PC 修饰键」作用的 key。「修饰键的语义」在 Android 上不存在：宿主 App 会把
# 同一个数字原样当 `android.view.KeyEvent` 注入（16 -> KEYCODE_9、18 -> KEYCODE_POUND…），
# 既不是 Shift 也不是 Alt，纯粹是一次无意义的按键。上游自己的 Android 覆盖就是这样处理的：
# 只丢掉这个 KeyDown/KeyUp，保留兄弟节点的点击 —— 见
# `assets/resource_adb/pipeline/Common/Private/AutoAltClick/Action.json`
# （`__AutoAltClickAltKeyDownAction` / `__AutoAltClickAltKeyUpAction` -> `DoNothing`），
# 而这两个节点是**活的**：`agent/go-service/common/autoalt/ctrl_click.go:9` 直接点名调用。
# 同样的手法上游还用在 `assets/resource_adb/pipeline/Common/Button/RegionalDevelopmentButton.json`：
# 把入口节点改成 `"action": "Click"` 且 `"next": []`，整条 Alt 链直接被截断。
MODIFIER_VK_KEYS = frozenset({16, 17, 18, 160, 161, 162, 163, 164, 165})

# 滚轮 1 单位 ≈ 多少像素的手指位移。
# 这不是协议规定的，是**从上游自己的手工换算里量出来的**：resource_adb 把
# `Scroll dy:-120` 换成 `Swipe begin:[238,508] end:[239,427]`，位移 81 px，
# 81 / 120 = 0.675。同一个 dy 在 ReceptionRoomSendCluesSwipe 里上游却写了 341 px，
# 说明这个系数本来就跟具体列表的手感有关。取 0.675 作为有据可查的默认值，
# 并把每个节点的换算结果写进报告，方便按需覆盖。
PX_PER_WHEEL_UNIT = 0.675
SWIPE_PX_MIN = 40
SWIPE_PX_MAX = 300

# 明确**不是**列表滚动、因而不能用 Swipe 替代的 Scroll 节点。
SCROLL_NOT_A_LIST: Dict[str, str] = {
    "_AutoEcoFarmScroll": (
        "desc 写的是「将鼠标移到目标位置并滚动放大视角」，next 里有 _AutoEcoFarmScaleMax，"
        "这是 3D 视角的滚轮缩放，不是列表滚动。Android 上对应的手势是双指捏合"
        "（MultiSwipe + contact 0/1，协议支持），但本节点的 target 是锚点名"
        "「[Anchor]_AutoEcoFarmFindEcoFarmAnchor」而不是静态坐标，捏合的中心点无法静态求出；"
        "而且该节点是 DirectHit，Self 命中框为空，begin:true 也拿不到中心。因此不自动改写。"
    ),
}

# agent/cpp-algo 注册的自定义识别/动作全集。
# 来源：agent/cpp-algo/source/main.cpp:113-132（逐行核对过）。
CPP_ALGO_CUSTOM_NAMES: Tuple[str, ...] = (
    "MyReco1",                          # 113  示例
    "MapLocateRecognition",             # 114
    "MapLocateAssertLocation",          # 115
    "MapNavmeshQuery",                  # 116
    "EssenceGridAdvanceRecognition",    # 117-120
    "EssenceGridPendingRecognition",    # 121-124
    "IconRecognition",                  # 125
    "MapFind",                          # 126
    "MapNavigateAction",                # 127  自定义动作
    "RealTimeTaskAction",               # 128  自定义动作
    "ZiplineImport",                    # 132  自定义动作，且被 #ifdef MAAEND_HAVE_WEBVIEW2 包住（仅 Windows）
)

# android.view.KeyEvent 的常量名。只为"上游用到的那些码"服务，够用即止。
_VK_LETTER = {48 + i: "VK_%d ('%s')" % (48 + i, chr(48 + i)) for i in range(10)}
_VK_LETTER.update({65 + i: "VK_%s ('%s')" % (chr(65 + i), chr(65 + i)) for i in range(26)})
_VK_LETTER.update({112 + i: "VK_F%d" % (i + 1) for i in range(12)})
_VK_LETTER.update({96 + i: "VK_NUMPAD%d" % i for i in range(10)})
_VK_SPECIAL = {
    8: "VK_BACK", 9: "VK_TAB", 13: "VK_RETURN", 16: "VK_SHIFT", 17: "VK_CONTROL",
    18: "VK_MENU (Alt)", 19: "VK_PAUSE", 20: "VK_CAPITAL", 27: "VK_ESCAPE", 32: "VK_SPACE",
    33: "VK_PRIOR", 34: "VK_NEXT", 35: "VK_END", 36: "VK_HOME", 37: "VK_LEFT", 38: "VK_UP",
    39: "VK_RIGHT", 40: "VK_DOWN", 45: "VK_INSERT", 46: "VK_DELETE", 91: "VK_LWIN",
    92: "VK_RWIN", 93: "VK_APPS", 160: "VK_LSHIFT", 161: "VK_RSHIFT", 162: "VK_LCONTROL",
    163: "VK_RCONTROL", 164: "VK_LMENU (Left Alt)", 165: "VK_RMENU (Right Alt)",
    186: "VK_OEM_1", 187: "VK_OEM_PLUS", 188: "VK_OEM_COMMA", 189: "VK_OEM_MINUS",
    190: "VK_OEM_PERIOD", 191: "VK_OEM_2", 192: "VK_OEM_3", 219: "VK_OEM_4",
    220: "VK_OEM_5", 221: "VK_OEM_6", 222: "VK_OEM_7",
}

ANDROID_KEYCODES: Dict[int, str] = {
    1: "KEYCODE_SOFT_LEFT", 2: "KEYCODE_SOFT_RIGHT", 3: "KEYCODE_HOME", 4: "KEYCODE_BACK",
    5: "KEYCODE_CALL", 6: "KEYCODE_ENDCALL",
    17: "KEYCODE_STAR", 18: "KEYCODE_POUND",
    19: "KEYCODE_DPAD_UP", 20: "KEYCODE_DPAD_DOWN", 21: "KEYCODE_DPAD_LEFT",
    22: "KEYCODE_DPAD_RIGHT", 23: "KEYCODE_DPAD_CENTER", 24: "KEYCODE_VOLUME_UP",
    25: "KEYCODE_VOLUME_DOWN", 26: "KEYCODE_POWER", 27: "KEYCODE_CAMERA", 28: "KEYCODE_CLEAR",
    55: "KEYCODE_COMMA", 56: "KEYCODE_PERIOD", 57: "KEYCODE_ALT_LEFT", 58: "KEYCODE_ALT_RIGHT",
    59: "KEYCODE_SHIFT_LEFT", 60: "KEYCODE_SHIFT_RIGHT", 61: "KEYCODE_TAB", 62: "KEYCODE_SPACE",
    63: "KEYCODE_SYM", 64: "KEYCODE_EXPLORER", 65: "KEYCODE_ENVELOPE", 66: "KEYCODE_ENTER",
    67: "KEYCODE_DEL", 68: "KEYCODE_GRAVE", 69: "KEYCODE_MINUS", 70: "KEYCODE_EQUALS",
    71: "KEYCODE_LEFT_BRACKET", 72: "KEYCODE_RIGHT_BRACKET", 73: "KEYCODE_BACKSLASH",
    74: "KEYCODE_SEMICOLON", 75: "KEYCODE_APOSTROPHE", 76: "KEYCODE_SLASH", 77: "KEYCODE_AT",
    78: "KEYCODE_NUM", 79: "KEYCODE_HEADSETHOOK", 80: "KEYCODE_FOCUS", 81: "KEYCODE_PLUS",
    82: "KEYCODE_MENU", 83: "KEYCODE_NOTIFICATION", 84: "KEYCODE_SEARCH",
    85: "KEYCODE_MEDIA_PLAY_PAUSE", 86: "KEYCODE_MEDIA_STOP", 87: "KEYCODE_MEDIA_NEXT",
    88: "KEYCODE_MEDIA_PREVIOUS", 89: "KEYCODE_MEDIA_REWIND", 90: "KEYCODE_MEDIA_FAST_FORWARD",
    91: "KEYCODE_MUTE", 92: "KEYCODE_PAGE_UP", 93: "KEYCODE_PAGE_DOWN",
    94: "KEYCODE_PICTSYMBOLS", 95: "KEYCODE_SWITCH_CHARSET",
    111: "KEYCODE_ESCAPE", 112: "KEYCODE_FORWARD_DEL", 113: "KEYCODE_CTRL_LEFT",
    114: "KEYCODE_CTRL_RIGHT", 115: "KEYCODE_CAPS_LOCK", 116: "KEYCODE_SCROLL_LOCK",
    117: "KEYCODE_META_LEFT", 118: "KEYCODE_META_RIGHT", 119: "KEYCODE_FUNCTION",
    120: "KEYCODE_SYSRQ", 121: "KEYCODE_BREAK", 122: "KEYCODE_MOVE_HOME",
    123: "KEYCODE_MOVE_END", 124: "KEYCODE_INSERT", 125: "KEYCODE_FORWARD",
    126: "KEYCODE_MEDIA_PLAY", 127: "KEYCODE_MEDIA_PAUSE", 128: "KEYCODE_MEDIA_CLOSE",
    129: "KEYCODE_MEDIA_EJECT", 130: "KEYCODE_MEDIA_RECORD",
}
for _i in range(10):
    ANDROID_KEYCODES[7 + _i] = "KEYCODE_%d" % _i
for _i in range(26):
    ANDROID_KEYCODES[29 + _i] = "KEYCODE_%s" % chr(65 + _i)
for _i in range(12):
    ANDROID_KEYCODES[131 + _i] = "KEYCODE_F%d" % (_i + 1)

# 逐节点的补充说明（只写"读了源码/看了兄弟节点才知道"的那类事实）。
NODE_NOTES: Dict[str, str] = {
    "__AutoFightActionAttackKeyPress": (
        "同一文件里已经有触摸版攻击链（__AutoFightActionAttackClick / "
        "__AutoFightActionAttackTouchDown / __AutoFightActionAttackTouchUp / "
        "__AutoFightActionLockTarget），所以战斗不是完全没救；但本节点是键盘 F/空格 派生的，"
        "本身不可移植。"
    ),
    "__AutoFightActionComboClick": "连招宏，本质是键序；Android 没有等价的键盘面，需要重写意图。",
    "ProdManualOpenInMain": (
        "按住 Alt 会在背包界面上叠加出现入口。**同一文件里有不依赖键盘的等价路径**："
        "ProdManualOpenInMenu（OCR roi [195,390,400,203]，expected 探索等级 / 探索等級 / "
        "(?i)Exploration\\s*Level / 探索レベル / Explore）+ ProdManualOpenInMenuSwipe"
        "（Swipe begin [1099,525,0,0] end [[1112,244,0,0]]）。也就是说是任务入口选错了，"
        "不是功能缺失 —— 但本脚本不会替上游改任务的 entry。"
    ),
    "ProdManualOpenInMainDone": "见 ProdManualOpenInMain：同一文件的 ProdManualOpenInMenu 路径不需要键盘。",
    "__MapNavigatorObstacleDevice_InteractPre": (
        "按住 Alt 让交互按钮显形，识别的是 TemplateMatch roi [760,330,170,310] → "
        "MapNavigator/ObstacleDevice/InteractButton.png。Android 上'让按钮显形'的等价手段不存在；"
        "把它改成 TouchDown 会改变语义（按住 Alt ↔ 按住屏幕），而兄弟节点 "
        "__MapNavigatorObstacleDevice_Interact 是 Click(target=Pre)，一旦 Pre 变成 DoNothing，"
        "Click 就会打到空识别框上。没有设备实测不能断言哪一种成立，因此不自动改写。"
    ),
    "__MapNavigatorObstacleDevice_InteractPost": (
        "与 _InteractPre 成对（松开 Alt）。同文件 61-62 行注释说明设备弹窗淡入很慢"
        "（0.79 s 未识别 / 1.28 s 才 0.97），post_delay/post_wait_freezes 的时序在 Android 上"
        "还要重测，一起留作已知限制。"
    ),
    "StashBackpackUsableItemsLimitedDrag": (
        "Shift + 拖拽 = 只搬运指定数量。Android 没有修饰键，也没有'第二只手'，"
        "要移植必须改成走数量输入框的整套流程。"
    ),
    "StashBackpackUsableItemsLimitedQuickMoveKeyUp": "见 StashBackpackUsableItemsLimitedDrag。",
    "ProtocolSpaceTouchExitTouch": "F 键退出。Android 上等价物是返回手势/返回键，但这里 F 不是 ESC 语义，不能直接换成 KEYCODE_BACK。",
    "ProtocolSpaceTouchExitTouchRetry": "同 ProtocolSpaceTouchExitTouch。",
    "__ScenePrivateWorldEnterMenuEmail": "K 键直达邮件菜单；Android 需要走 UI 路径，属于上游界面知识，不在本次可自动推导的范围内。",
    "__ScenePrivateWorldFactoryEnterMenuBluePrint": "F1 键直达蓝图菜单；同上。",
    "RealTimeAutoPat": "'拍一拍' 是左键/按键交互，Android 上没有等价输入。",
    "AutoPickFalls": "采集交互，依赖键盘 F。",
    "AutoPickInteractive": "采集交互，依赖键盘 F。",
    "RealTimeAutoZiplineClick": "滑索交互，依赖键盘 E。",
}

_ANCHOR_PREFIX_RE = re.compile(r"^(?:\[[^\]]*\])+")
_JS_COMMENT_RE = re.compile(r"^/")

# --------------------------------------------------------------------------------------
# JSONC -> JSON
# --------------------------------------------------------------------------------------


def strip_jsonc(text: str) -> str:
    """去掉 `//`、`/* */` 注释与尾随逗号（字符串里不动）。

    比"正则删逗号"稳：逗号是在扫描器内部记住位置再删的，不会误伤字符串里的 `,}`。
    """
    out: List[str] = []
    i = 0
    n = len(text)
    in_str = False
    escaped = False
    pending_comma: Optional[int] = None
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            # A string is a value: any comma we were holding back was a real separator.
            pending_comma = None
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] not in "\r\n":
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if ch == ",":
            pending_comma = len(out)
            out.append(ch)
            i += 1
            continue
        if ch in " \t\r\n":
            out.append(ch)
            i += 1
            continue
        if ch in "}]" and pending_comma is not None:
            del out[pending_comma]
        pending_comma = None
        out.append(ch)
        i += 1
    return "".join(out)


def loads_jsonc(text: str) -> Any:
    return json.loads(strip_jsonc(text))


# --------------------------------------------------------------------------------------
# 小工具
# --------------------------------------------------------------------------------------


def as_int(value: Any, default: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        return default
    return value


def now_iso() -> str:
    return (
        datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def rel_under(prefix: str, path: str) -> str:
    prefix = prefix.rstrip("/")
    if path == prefix:
        return ""
    if not path.startswith(prefix + "/"):
        raise ValueError("%r is not under %r" % (path, prefix))
    return path[len(prefix) + 1:]


def has_underscore_dir(rel_path: str) -> bool:
    """任何一**层目录**以 `_` 开头就 True（文件名不算）。

    MaaFramework 的 Android 打包逻辑处理不了下划线开头的目录名（上游 AGENTS.md
    明确写了这条），所以产物里绝不能出现。
    """
    parts = rel_path.replace("\\", "/").split("/")[:-1]
    return any(p.startswith("_") for p in parts)


def strip_node_ref(name: str) -> str:
    """`[JumpBack][Anchor]Foo` -> `Foo`。"""
    if not isinstance(name, str):
        return ""
    return _ANCHOR_PREFIX_RE.sub("", name).strip()


def vk_name(code: int) -> str:
    if code in _VK_LETTER:
        return _VK_LETTER[code]
    return _VK_SPECIAL.get(code, "VK_0x%02X" % code if code > 0 else "0")


def android_keycode_name(code: int) -> str:
    return ANDROID_KEYCODES.get(code, "KEYCODE_#%d (未命名/保留)" % code)


# --------------------------------------------------------------------------------------
# JSON Schema 子集校验器（标准库实现，够用即可）
# --------------------------------------------------------------------------------------


class SchemaValidator:
    """一个够用的 JSON Schema 子集校验器。

    支持：`$ref`（本文档内的 JSON Pointer，`definitions` 与 `$defs` 都能用）、
    `type`/`const`/`enum`/`required`/`properties`/`patternProperties`/
    `additionalProperties`/`unevaluatedProperties`/`items`/`minItems`/`maxItems`/
    `minimum`/`maximum`/`minLength`/`maxLength`/`anyOf`/`oneOf`/`allOf`/`not`/
    `if`/`then`/`else`/`dependentSchemas`。

    **跨文件 `$ref`（例如 `./custom.action.schema.json`）不解析**：解析不了就记进
    `unresolved_refs` 并放行（当作"无法判定"），绝不假装校验过。
    实测 `maafw_dev/tools/` 只有 pipeline/interface 两个 schema，缺的两个是上游
    插件示例的 schema，拿它去校验 MaaEnd 的 ~89 个自定义动作名只会产生假错误。
    """

    MAX_ERRORS = 40

    def __init__(self, schema: Dict[str, Any]):
        self.root = schema
        self.errors: List[str] = []
        self.unresolved_refs: Set[str] = set()
        self._prop_cache: Dict[int, Tuple[frozenset, Tuple[str, ...]]] = {}
        self._pattern_cache: Dict[str, Any] = {}

    # -- 工具 -------------------------------------------------------------------
    @staticmethod
    def _type_ok(value: Any, name: str) -> bool:
        if name == "object":
            return isinstance(value, dict)
        if name == "array":
            return isinstance(value, list)
        if name == "string":
            return isinstance(value, str)
        if name == "boolean":
            return isinstance(value, bool)
        if name == "null":
            return value is None
        if name == "integer":
            return isinstance(value, int) and not isinstance(value, bool)
        if name == "number":
            return isinstance(value, (int, float)) and not isinstance(value, bool)
        return True

    def _lookup_pointer(self, ref: str) -> Optional[Any]:
        if not ref.startswith("#"):
            return None
        ptr = ref[1:]
        if ptr in ("", "/"):
            return self.root
        if not ptr.startswith("/"):
            return None
        node: Any = self.root
        for token in ptr[1:].split("/"):
            token = token.replace("~1", "/").replace("~0", "~")
            if isinstance(node, dict) and token in node:
                node = node[token]
            elif isinstance(node, list) and token.isdigit() and int(token) < len(node):
                node = node[int(token)]
            else:
                return None
        return node

    def _resolve(self, schema: Any) -> Any:
        hops = 0
        while isinstance(schema, dict) and isinstance(schema.get("$ref"), str):
            ref = schema["$ref"]
            target = self._lookup_pointer(ref)
            if target is None:
                self.unresolved_refs.add(ref)
                return {k: v for k, v in schema.items() if k != "$ref"}
            schema = target
            hops += 1
            if hops > 32:
                break
        return schema

    def _declared_index(self, schema: Any) -> Tuple[frozenset, Tuple[str, ...]]:
        """`(所有被声明过的属性名, 所有 patternProperties 正则)`，从 `schema` 出发全图可达。

        这里刻意取**超集**：`unevaluatedProperties: false` / `additionalProperties: false`
        是"闭集"检查，而迁移工具绝不能凭空造出违规 —— 宁可漏掉一个真的多余字段，
        也不要否掉一个合法的 pipeline 节点。走的是 schema 里一切可能藏 `properties`
        的键（`allOf`/`anyOf`/`oneOf`/`if`/`then`/`else`/`dependentSchemas`/`items`/
        `not`/`$ref` …），按 `id()` 缓存，每个 schema 对象只算一次。
        """
        cached = self._prop_cache.get(id(schema))
        if cached is not None:
            return cached
        names: Set[str] = set()
        patterns: List[str] = []
        stack: List[Any] = [schema]
        visited: Set[int] = set()
        opaque = {
            "default", "markdownDescription", "description", "examples", "title",
            "$comment", "enum", "const", "$ref", "$schema", "$id",
        }
        while stack:
            cur = stack.pop()
            if isinstance(cur, list):
                stack.extend(cur)
                continue
            if not isinstance(cur, dict):
                continue
            if id(cur) in visited:
                continue
            visited.add(id(cur))
            ref = cur.get("$ref")
            if isinstance(ref, str):
                target = self._lookup_pointer(ref)
                if target is not None:
                    stack.append(target)
            props = cur.get("properties")
            if isinstance(props, dict):
                names.update(props)
                stack.extend(props.values())
            pats = cur.get("patternProperties")
            if isinstance(pats, dict):
                patterns.extend(pats.keys())
                stack.extend(pats.values())
            for key, value in cur.items():
                if key in ("properties", "patternProperties", "patternRequired"):
                    continue
                if key in opaque:
                    continue
                stack.append(value)
        result = (frozenset(names), tuple(patterns))
        self._prop_cache[id(schema)] = result
        return result

    def _declared_props(self, schema: Any, instance: Any) -> Set[str]:
        names, patterns = self._declared_index(schema)
        out = set(names)
        if patterns and isinstance(instance, dict):
            for pat in patterns:
                rx = self._pattern_cache.get(pat)
                if rx is None:
                    try:
                        rx = re.compile(pat)
                    except re.error:
                        rx = False
                    self._pattern_cache[pat] = rx
                if rx is False:
                    continue
                out.update(k for k in instance if rx.search(k))
        return out

    def _err(self, path: str, message: str) -> None:
        if len(self.errors) < self.MAX_ERRORS:
            self.errors.append("%s: %s" % (path, message))

    # -- 主流程 -----------------------------------------------------------------
    def validate(self, instance: Any, schema: Optional[Any] = None) -> List[str]:
        self.errors = []
        self._check(instance, self.root if schema is None else schema, "$")
        return list(self.errors)

    def _quiet(self, instance: Any, schema: Any, path: str) -> Optional[List[str]]:
        saved = self.errors
        self.errors = []
        try:
            self._check(instance, schema, path)
            return list(self.errors)
        finally:
            self.errors = saved

    def _check(self, instance: Any, schema: Any, path: str) -> None:
        if len(self.errors) >= self.MAX_ERRORS:
            return
        schema = self._resolve(schema)
        if not isinstance(schema, dict) or not schema:
            return

        t = schema.get("type")
        if isinstance(t, str) and not self._type_ok(instance, t):
            self._err(path, "expected %s, got %s" % (t, type(instance).__name__))
            return
        if isinstance(t, list) and not any(self._type_ok(instance, x) for x in t):
            self._err(path, "expected one of %s, got %s" % (t, type(instance).__name__))
            return

        if "const" in schema and instance != schema["const"]:
            self._err(path, "expected const %r" % (schema["const"],))
        enum = schema.get("enum")
        if isinstance(enum, list) and instance not in enum:
            self._err(path, "value %r not in enum %r" % (instance, enum[:8]))

        if isinstance(instance, str):
            if isinstance(schema.get("minLength"), int) and len(instance) < schema["minLength"]:
                self._err(path, "shorter than minLength %d" % schema["minLength"])
            if isinstance(schema.get("maxLength"), int) and len(instance) > schema["maxLength"]:
                self._err(path, "longer than maxLength %d" % schema["maxLength"])

        if isinstance(instance, (int, float)) and not isinstance(instance, bool):
            if isinstance(schema.get("minimum"), (int, float)) and instance < schema["minimum"]:
                self._err(path, "less than minimum %r" % (schema["minimum"],))
            if isinstance(schema.get("maximum"), (int, float)) and instance > schema["maximum"]:
                self._err(path, "greater than maximum %r" % (schema["maximum"],))

        for branch in ("allOf",):
            subs = schema.get(branch)
            if isinstance(subs, list):
                for idx, sub in enumerate(subs):
                    self._check(instance, sub, path)

        if isinstance(schema.get("not"), dict):
            if not self._quiet(instance, schema["not"], path):
                self._err(path, "matched a `not` schema")

        if isinstance(schema.get("if"), dict):
            taken = not self._quiet(instance, schema["if"], path)
            branch = "then" if taken else "else"
            if isinstance(schema.get(branch), dict):
                self._check(instance, schema[branch], path)

        for branch in ("anyOf", "oneOf"):
            subs = schema.get(branch)
            if isinstance(subs, list):
                matched = [i for i, sub in enumerate(subs) if not self._quiet(instance, sub, path)]
                if not matched:
                    self._err(path, "no %s branch matched (%d tried)" % (branch, len(subs)))
                elif branch == "oneOf" and len(matched) > 1:
                    self._err(path, "matched %d `oneOf` branches" % len(matched))

        if isinstance(instance, dict):
            self._check_object(instance, schema, path)
        elif isinstance(instance, list):
            self._check_array(instance, schema, path)

    def _check_object(self, instance: Dict[str, Any], schema: Dict[str, Any], path: str) -> None:
        required = schema.get("required")
        if isinstance(required, list):
            for key in required:
                if key not in instance:
                    self._err(path, "missing required property %r" % key)
        props = schema.get("properties")
        if isinstance(props, dict):
            for key, sub in props.items():
                if key in instance:
                    self._check(instance[key], sub, "%s.%s" % (path, key))
        pats = schema.get("patternProperties")
        if isinstance(pats, dict):
            for pat, sub in pats.items():
                try:
                    rx = re.compile(pat)
                except re.error:
                    continue
                for key in instance:
                    if rx.search(key):
                        self._check(instance[key], sub, "%s.%s" % (path, key))
        for keyword in ("additionalProperties", "unevaluatedProperties"):
            spec = schema.get(keyword)
            if spec is False:
                allowed = self._declared_props(schema, instance)
                bad = sorted(k for k in instance if k not in allowed)
                for key in bad[:5]:
                    self._err(path, "unexpected property %r (not allowed by %s)" % (key, keyword))
        dep = schema.get("dependentSchemas")
        if isinstance(dep, dict):
            for key, sub in dep.items():
                if key in instance:
                    self._check(instance, sub, path)

    def _check_array(self, instance: List[Any], schema: Dict[str, Any], path: str) -> None:
        if isinstance(schema.get("minItems"), int) and len(instance) < schema["minItems"]:
            self._err(path, "fewer than minItems %d" % schema["minItems"])
        if isinstance(schema.get("maxItems"), int) and len(instance) > schema["maxItems"]:
            self._err(path, "more than maxItems %d" % schema["maxItems"])
        items = schema.get("items")
        if isinstance(items, dict):
            for idx, item in enumerate(instance):
                self._check(item, items, "%s[%d]" % (path, idx))
        elif isinstance(items, list):
            for idx, sub in enumerate(items):
                if idx < len(instance):
                    self._check(instance[idx], sub, "%s[%d]" % (path, idx))


# --------------------------------------------------------------------------------------
# git 访问
# --------------------------------------------------------------------------------------


class GitSource:
    """只读访问一个 git checkout。partial clone 的懒加载也能用。

    `read_blobs()` 走单条 `git cat-file --batch` 长连接，逐 blob 请求/读取
    （不是每个 blob 起一次 `git show`）。在 `blob:none` 的 partial clone 上这很重要：
    懒加载是实现层的事，走长连接既不会把 3000 个进程开出来，也不会把管道写爆
    —— 每个 sha 写进去之后立刻读回它那一个 blob。
    """

    def __init__(self, path: str):
        self.path = os.path.abspath(path)
        if not os.path.isdir(os.path.join(self.path, ".git")):
            raise SystemExit("--source is not a git checkout: %s" % self.path)

    def _git(self, args: Sequence[str], binary: bool = False) -> Any:
        proc = subprocess.Popen(
            ["git", "-C", self.path] + list(args),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        out, err = proc.communicate()
        if proc.returncode != 0:
            raise RuntimeError(
                "git %s failed (%d): %s"
                % (" ".join(args), proc.returncode, err.decode("utf-8", "replace").strip())
            )
        return out if binary else out.decode("utf-8", "replace")

    def head(self) -> str:
        return self._git(["rev-parse", "HEAD"]).strip()

    def grep_many(self, needles: Sequence[str], pathspecs: Sequence[str]) -> List[Tuple[str, int, str]]:
        """一次 `git grep` 里查多个**字面量**（`-F`），返回 `[(path, lineno, line)]`。

        在 pipeline 图之外找节点引用时必须查全仓库（`agent/**` 的 Go 代码用
        `ctx.RunAction("节点名", …)` 点名调用节点，`assets/tasks/**` 用
        `pipeline_override` 覆盖节点），只看 pipeline 的 `next` 会把它们误判成死代码。
        一次进程查完所有名字，避免几十次 fork。没有命中时 git grep 退出码是 1。
        """
        if not needles:
            return []
        args: List[str] = ["grep", "-n", "-I", "-F"]
        for needle in needles:
            args += ["-e", needle]
        args += ["HEAD", "--"]
        args += list(pathspecs)
        proc = subprocess.Popen(
            ["git", "-C", self.path] + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        out, _err = proc.communicate()
        if proc.returncode not in (0, 1):
            return []
        hits: List[Tuple[str, int, str]] = []
        for line in out.decode("utf-8", "replace").splitlines():
            # 注意 `git grep -n`（不带 `--column`）的输出是 `path:lineno:text` —— 三段，
            # 没有列号。这里曾经按四段解析（多切了一个 `_col`），结果把每一条命中都丢掉了。
            if line.startswith("HEAD:"):
                line = line[5:]
            parts = line.split(":", 2)
            if len(parts) < 3:
                continue
            path, lineno, text = parts
            try:
                hits.append((path, int(lineno), text))
            except ValueError:
                continue
        return hits

    def head_subject(self) -> str:
        return self._git(["log", "-1", "--format=%s"]).strip()

    def ls_tree(self, prefix: str) -> List[Tuple[str, str, str, str]]:
        """`[(mode, type, sha, path)]`，递归。type 为 `blob` 或 `commit`（submodule gitlink）。"""
        raw = self._git(["ls-tree", "-r", "-z", "HEAD", prefix], binary=True)
        entries: List[Tuple[str, str, str, str]] = []
        for chunk in raw.split(b"\0"):
            if not chunk:
                continue
            meta, _, path = chunk.partition(b"\t")
            mode, typ, sha = meta.decode("ascii").split()
            entries.append((mode, typ, sha, path.decode("utf-8")))
        return entries

    @staticmethod
    def _read_exact(stream, size: int) -> bytes:
        chunks: List[bytes] = []
        remaining = size
        while remaining > 0:
            buf = stream.read(remaining)
            if not buf:
                raise RuntimeError("git cat-file closed mid-blob (%d bytes missing)" % remaining)
            chunks.append(buf)
            remaining -= len(buf)
        return b"".join(chunks)

    def read_blobs(self, entries: Sequence[Tuple[str, str, str, str]]):
        """yield `(path, data|None)`；None 表示该对象在远端已不存在。

        逐个 sha 写 + flush，再读回那一个 blob —— 既不会把子进程 stdin 管道写满，
        也能在 partial clone 上正常工作（git 自己决定要不要懒加载）。
        """
        proc = subprocess.Popen(
            ["git", "-C", self.path, "cat-file", "--batch"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
        )
        assert proc.stdin is not None and proc.stdout is not None
        try:
            for mode, typ, sha, path in entries:
                if typ != "blob":
                    yield path, None
                    continue
                proc.stdin.write(sha.encode("ascii") + b"\n")
                proc.stdin.flush()
                header = proc.stdout.readline()
                if not header:
                    raise RuntimeError("git cat-file closed while reading %s" % path)
                parts = header.split()
                if len(parts) == 2 and parts[1] == b"missing":
                    yield path, None
                    continue
                if len(parts) != 3 or parts[1] != b"blob":
                    raise RuntimeError("unexpected cat-file header for %s: %r" % (path, header))
                size = int(parts[2])
                data = self._read_exact(proc.stdout, size)
                if proc.stdout.read(1) != b"\n":
                    raise RuntimeError("missing LF after blob %s" % path)
                yield path, data
        finally:
            try:
                proc.stdin.close()
            except Exception:  # pragma: no cover
                pass
            proc.wait()


# --------------------------------------------------------------------------------------
# pipeline 节点解析
# --------------------------------------------------------------------------------------


def action_type(node: Dict[str, Any]) -> str:
    act = node.get("action")
    if isinstance(act, dict):
        return act.get("type", "Default")
    if isinstance(act, str):
        return act
    return "Default"


def action_param(node: Dict[str, Any]) -> Dict[str, Any]:
    """V2 -> `action.param`（没有 param 就是 action 对象本身）；V1 -> 节点顶层。"""
    act = node.get("action")
    if isinstance(act, dict):
        param = act.get("param")
        if isinstance(param, dict):
            return param
        return {k: v for k, v in act.items() if k != "type"}
    return node


def merge_node(base: Dict[str, Any], override: Dict[str, Any]) -> Dict[str, Any]:
    """按 MaaFramework 的字段级合并语义把覆盖叠加到基准节点上。

    `PipelineResMgr::load_all_json` 对同名节点调用 `insert_or_assign`，把先前那个节点
    当作本次解析的 `default_value`；`PipelineParser::parse_node` 对**每一个字段**都回退到
    `default_value`，所以覆盖只需要写"改动的字段"，其余（识别、next、freeze、anchor、
    rate_limit、timeout、focus…）自动继承。

    动作类型改变时（本例是 Scroll -> Swipe）参数不会跨类型泄漏：`parse_action` 里
    `same_type = parent_type == out_type`，不同型就用 `default_mgr` 的默认值。
    """
    merged = dict(base)
    act = override.get("action")
    for key, value in override.items():
        merged[key] = value
    if isinstance(act, dict):
        # V2 覆盖：节点顶层的 V1 动作参数不再被读取，摘掉以免混淆读者与校验器。
        for key in V1_ACTION_PARAM_KEYS:
            if key != "action":
                merged.pop(key, None)
        merged["action"] = act
    return merged


def node_keys(param: Dict[str, Any]) -> List[int]:
    raw = param.get("key", param.get("key_code"))
    if isinstance(raw, bool):
        return []
    if isinstance(raw, int):
        return [raw]
    if isinstance(raw, list):
        return [v for v in raw if isinstance(v, int) and not isinstance(v, bool)]
    return []


def collect_refs(obj: Any, out: Set[str]) -> None:
    """把节点里能被"指向别的节点"的字符串都收进来（next/on_error/anchor 目标/识别子名）。"""
    if isinstance(obj, dict):
        for key, value in obj.items():
            if key in ("next", "on_error", "interrupt"):
                if isinstance(value, str):
                    out.add(strip_node_ref(value))
                elif isinstance(value, list):
                    for item in value:
                        if isinstance(item, str):
                            out.add(strip_node_ref(item))
                collect_refs(value, out)
            elif key in ("all_of", "any_of"):
                if isinstance(value, str):
                    out.add(strip_node_ref(value))
                elif isinstance(value, list):
                    for item in value:
                        if isinstance(item, str):
                            out.add(strip_node_ref(item))
                        else:
                            collect_refs(item, out)
                collect_refs(value, out)
            elif key in ("target", "begin", "end"):
                if isinstance(value, str):
                    out.add(strip_node_ref(value))
                collect_refs(value, out)
            else:
                collect_refs(value, out)
    elif isinstance(obj, list):
        for item in obj:
            collect_refs(item, out)


def collect_strings(obj: Any, out: Set[str]) -> None:
    if isinstance(obj, str):
        out.add(obj)
        out.add(strip_node_ref(obj))
    elif isinstance(obj, dict):
        for value in obj.values():
            collect_strings(value, out)
    elif isinstance(obj, list):
        for item in obj:
            collect_strings(item, out)


# --------------------------------------------------------------------------------------
# 迁移主体
# --------------------------------------------------------------------------------------


class Migration:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.source = GitSource(args.source)
        self.out = os.path.abspath(args.out)
        self.schemas_dir = os.path.abspath(args.schemas) if args.schemas else None
        self.force = bool(args.force)

        self.files_written: List[str] = []
        self.bytes_written = 0
        self.content_hashes: Dict[str, str] = {}
        self.parse_errors: List[Dict[str, str]] = []
        self.warnings: List[str] = []

        # 分析用的内存数据
        self.base_pipeline: Dict[str, str] = {}   # resource 相对路径 -> 文本
        self.adb_pipeline: Dict[str, str] = {}    # resource_adb 相对路径 -> 文本
        self.base_nodes: Dict[str, Dict[str, Any]] = {}
        self.node_file: Dict[str, str] = {}
        self.node_scope: Dict[str, str] = {}
        self.adb_node_names: Set[str] = set()
        self.adb_nodes: Dict[str, Dict[str, Any]] = {}
        self.duplicate_nodes: List[Dict[str, str]] = []
        self.unknown_top_level_keys: Dict[str, List[str]] = {}
        self.external_refs: Dict[str, List[str]] = {}

        self.scroll_entries: List[Dict[str, Any]] = []
        self.key_converted: List[Dict[str, Any]] = []
        self.key_neutralized: List[Dict[str, Any]] = []
        self.key_unresolved: List[Dict[str, Any]] = []
        self.key_dead: List[Dict[str, Any]] = []
        self.key_already_adb: List[Dict[str, Any]] = []
        self.scroll_already_adb: List[Dict[str, Any]] = []
        self.cross_check: List[Dict[str, Any]] = []

        self.stats: Dict[str, Any] = {}

    # -- 输出 -------------------------------------------------------------------
    def prepare_out(self) -> None:
        out = self.out
        real_out = os.path.realpath(out)
        real_src = os.path.realpath(self.source.path)
        if os.path.exists(out):
            if not self.force:
                raise SystemExit("--out already exists (pass --force to wipe it): %s" % out)
            if os.path.basename(real_out) in ("", os.path.sep):
                raise SystemExit("refusing to wipe filesystem root: %s" % out)
            if len(os.path.basename(real_out)) < 2:
                raise SystemExit("refusing to wipe suspiciously short path: %s" % out)
            if os.path.isdir(os.path.join(real_out, ".git")):
                raise SystemExit("refusing to wipe a git repository root: %s" % out)
            if real_out == real_src or real_src.startswith(real_out + os.sep):
                raise SystemExit("refusing to wipe --out because --source lives inside it: %s" % out)
            shutil.rmtree(out)
        os.makedirs(out, exist_ok=True)

    def _abs(self, rel_path: str) -> str:
        return os.path.join(self.out, rel_path.replace("/", os.sep))

    def emit_bytes(self, rel_path: str, data: bytes) -> None:
        rel_path = rel_path.replace("\\", "/")
        if has_underscore_dir(rel_path):
            raise SystemExit("refusing to write a path with an underscore-prefixed dir: %s" % rel_path)
        dest = self._abs(rel_path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as fh:
            fh.write(data)
        self.files_written.append(rel_path)
        self.bytes_written += len(data)
        self.content_hashes[rel_path] = hashlib.sha256(data).hexdigest()

    def emit_json(self, rel_path: str, obj: Any) -> None:
        text = json.dumps(obj, ensure_ascii=False, indent=4) + "\n"
        self.emit_bytes(rel_path, text.encode("utf-8"))

    def emit_markdown(self, rel_path: str, text: str) -> None:
        if not text.endswith("\n"):
            text += "\n"
        self.emit_bytes(rel_path, text.encode("utf-8"))

    # -- 复制 -------------------------------------------------------------------
    def copy_trees(self) -> Dict[str, Any]:
        per_tree: Dict[str, Any] = {}
        for src_prefix, dst_prefix in COPY_TREES:
            entries = self.source.ls_tree(src_prefix)
            blobs = [e for e in entries if e[1] == "blob"]
            skipped: List[str] = []
            kept: List[Tuple[str, str, str, str]] = []
            for entry in blobs:
                if entry[3] in SUBMODULE_PATHS:
                    skipped.append(entry[3])
                    continue
                kept.append(entry)
            count = 0
            total = 0
            for path, data in self.source.read_blobs(kept):
                if data is None:
                    raise SystemExit("git object missing for %s" % path)
                rel = rel_under(src_prefix, path)
                dst = "%s/%s" % (dst_prefix, rel) if rel else dst_prefix
                if dst_prefix == "resource" and rel.startswith("pipeline/"):
                    self.base_pipeline[rel[len("pipeline/"):]] = data.decode("utf-8", "replace")
                if dst_prefix == "resource_adb" and rel.startswith("pipeline/"):
                    self.adb_pipeline[rel[len("pipeline/"):]] = data.decode("utf-8", "replace")
                self.emit_bytes(dst, data)
                count += 1
                total += len(data)
            per_tree[src_prefix] = {
                "dest": dst_prefix,
                "entries": len(entries),
                "files_copied": count,
                "bytes_copied": total,
                "skipped_non_blob": [e[3] for e in entries if e[1] != "blob"],
            }
        for src_path, dst_path in COPY_FILES:
            entries = self.source.ls_tree(src_path)
            blob = [e for e in entries if e[1] == "blob"]
            if len(blob) != 1:
                self.warnings.append("expected exactly one blob for %s, found %d" % (src_path, len(blob)))
                continue
            for path, data in self.source.read_blobs(blob):
                if data is None:
                    raise SystemExit("git object missing for %s" % path)
                self.emit_bytes(dst_path, data)
                per_tree[src_path] = {"dest": dst_path, "files_copied": 1, "bytes_copied": len(data)}
        return per_tree

    # -- 节点索引 ---------------------------------------------------------------
    def index_nodes(self) -> None:
        for rel, text in sorted(self.base_pipeline.items()):
            try:
                data = loads_jsonc(text)
            except ValueError as exc:
                self.parse_errors.append({"file": "resource/pipeline/" + rel, "error": str(exc)})
                continue
            if not isinstance(data, dict):
                self.parse_errors.append({"file": "resource/pipeline/" + rel, "error": "root is not an object"})
                continue
            for name, node in data.items():
                if name.startswith("$"):
                    continue
                if not isinstance(node, dict):
                    self.unknown_top_level_keys.setdefault(rel, []).append(name)
                    continue
                if name in self.node_file:
                    self.duplicate_nodes.append(
                        {"node": name, "first": self.node_file[name], "second": rel}
                    )
                    continue
                self.node_file[name] = rel
                self.node_scope[name] = "base"
                self.base_nodes[name] = node

        for rel, text in sorted(self.adb_pipeline.items()):
            try:
                data = loads_jsonc(text)
            except ValueError as exc:
                self.parse_errors.append({"file": "resource_adb/pipeline/" + rel, "error": str(exc)})
                continue
            if not isinstance(data, dict):
                continue
            for name, node in data.items():
                if name.startswith("$") or not isinstance(node, dict):
                    continue
                self.adb_node_names.add(name)
                self.adb_nodes[name] = node

    def reference_graph(self) -> Tuple[Set[str], Dict[str, Set[str]]]:
        refs_by_node: Dict[str, Set[str]] = {}
        all_refs: Set[str] = set()

        def add_from(rel_text_map: Dict[str, str], scope: str) -> None:
            for rel, text in rel_text_map.items():
                try:
                    data = loads_jsonc(text)
                except ValueError:
                    continue
                if not isinstance(data, dict):
                    continue
                for name, node in data.items():
                    if name.startswith("$") or not isinstance(node, dict):
                        continue
                    bag: Set[str] = set()
                    collect_refs(node, bag)
                    refs_by_node[name] = bag
                    all_refs.update(bag)

        add_from(self.base_pipeline, "base")
        add_from(self.adb_pipeline, "adb")
        return all_refs, refs_by_node

    # pipeline 之外的引用来源。Go 代码用 `ctx.RunAction("节点名", …)` 直接点名执行节点
    # （`agent/go-service/autofight/autofight.go:765` 就是这么调 `__AutoFightActionComboClick`
    # 的），任务片段用 `pipeline_override` 覆盖节点 —— 只看 pipeline 的 next/on_error
    # 会把 AutoFight 这类"由 agent 驱动"的节点整批误判成死代码。
    EXTERNAL_REF_PATHSPECS = (
        "agent",
        "assets/tasks",
        "assets/resource_cloud_adb",
        "assets/resource_playcover",
        "assets/resource_linux",
        "assets/resource_macos",
        "tools",
    )

    def collect_external_refs(self) -> None:
        """`节点名 -> ["path:line", …]`，只在 pipeline 图之外搜。"""
        self.external_refs = {}
        names = sorted(
            n
            for n, node in self.base_nodes.items()
            if action_type(node) in KEY_FAMILY or action_type(node) == SCROLL
        )
        if not names:
            return
        name_set = set(names)
        needles = ['"%s"' % n for n in names]
        hits: List[Tuple[str, int, str]] = []
        chunk = 200  # 一次 200 个字面量，避免超出命令行长度上限
        for i in range(0, len(needles), chunk):
            hits.extend(
                self.source.grep_many(needles[i : i + chunk], self.EXTERNAL_REF_PATHSPECS)
            )
        quoted = re.compile(r'"([A-Za-z_][A-Za-z0-9_]*)"')
        found: Dict[str, Set[str]] = {}
        for path, lineno, text in hits:
            where = "%s:%d" % (path, lineno)
            for token in set(quoted.findall(text)):
                if token in name_set:
                    found.setdefault(token, set()).add(where)
        self.external_refs = {n: sorted(v) for n, v in found.items()}

    # -- 位置 -------------------------------------------------------------------
    @staticmethod
    def _locate(text: str, node_name: str) -> int:
        """找出节点**定义**所在的行号（1-based），找不到返回 0。

        注意：节点名在文件里会出现在两个地方 —— `next` / `custom_action_param.nodes`
        之类的**引用**，以及真正的**定义**。引用行也可能整行就是一个带引号的字符串
        （`    "ItemTransferScrollDownwardBag"`），所以"以 `"名字"` 开头"是不够的：
        必须要求它后面紧跟冒号。早期版本只判断前缀，于是把引用行当成了定义行
        （例：`ItemTransferScrollDownwardBag` 报 1062，实际定义在 1129）。
        """
        escaped = re.escape(node_name)
        exact = re.compile(r'^\s*"%s"\s*:' % escaped)
        loose = re.compile(r'^\s*"%s"\s*[,}\]]' % escaped)
        lines = text.splitlines()
        for pattern in (exact, loose):
            for idx, line in enumerate(lines, 1):
                if pattern.match(line):
                    return idx
        needle = '"%s"' % node_name
        for idx, line in enumerate(lines, 1):
            if needle in line:
                return idx
        return 0

    # -- Scroll -> Swipe ---------------------------------------------------------
    @staticmethod
    def scroll_to_swipe(param: Dict[str, Any]) -> Tuple[Optional[Tuple[int, int, int, int]], Optional[str]]:
        target = param.get("target", True)
        dx = as_int(param.get("dx"), 0)
        dy = as_int(param.get("dy"), 0)
        if not isinstance(target, list) or not target:
            return None, (
                "`target` is %r (default true = Target::Type::Self, i.e. whatever the recognition "
                "hit box is). No static screen coordinate is available at migration time." % (target,)
            )
        if not all(isinstance(v, int) and not isinstance(v, bool) for v in target):
            return None, "`target` contains non-integer elements: %r" % (target,)
        if len(target) == 2:
            px, py = target[0], target[1]
        elif len(target) == 4:
            px, py = target[0] + target[2] // 2, target[1] + target[3] // 2
        else:
            return None, "`target` has %d elements (expected 2 or 4)" % len(target)
        if dx == 0 and dy == 0:
            return None, "both `dx` and `dy` are 0, nothing to emulate"

        vx = dx * PX_PER_WHEEL_UNIT
        vy = dy * PX_PER_WHEEL_UNIT
        mag = max(abs(vx), abs(vy))
        if mag > SWIPE_PX_MAX:
            scale = SWIPE_PX_MAX / mag
            vx *= scale
            vy *= scale
        mag = max(abs(vx), abs(vy))
        if mag < SWIPE_PX_MIN:
            scale = SWIPE_PX_MIN / mag
            vx *= scale
            vy *= scale

        def rnd(value: float) -> int:
            return int(round(value))

        begin = (rnd(px - vx / 2.0), rnd(py - vy / 2.0))
        end = (rnd(px + vx / 2.0), rnd(py + vy / 2.0))
        return (begin[0], begin[1], end[0], end[1]), None

    def analyse_scroll(self) -> None:
        for name in sorted(self.base_nodes):
            node = self.base_nodes[name]
            if action_type(node) != SCROLL:
                continue
            rel = self.node_file[name]
            src_text = self.base_pipeline.get(rel, "")
            param = action_param(node)
            record: Dict[str, Any] = {
                "node": name,
                "file": "assets/resource/pipeline/" + rel,
                "line": self._locate(src_text, name),
                "action": "Scroll",
                "param": param,
                "node_json": node,
            }
            if name in self.adb_node_names:
                record["status"] = "already_covered_by_resource_adb"
                self.scroll_already_adb.append(record)
                continue
            if name in SCROLL_NOT_A_LIST:
                record["status"] = "not_converted"
                record["reason"] = SCROLL_NOT_A_LIST[name]
                self.scroll_entries.append(record)
                continue
            swipe, why = self.scroll_to_swipe(param)
            if swipe is None:
                record["status"] = "not_converted"
                record["reason"] = why
                self.scroll_entries.append(record)
                continue
            record["status"] = "converted"
            record["new_action"] = "Swipe"
            record["swipe"] = {"begin": [swipe[0], swipe[1]], "end": [swipe[2], swipe[3]]}
            record["dx"] = as_int(param.get("dx"), 0)
            record["dy"] = as_int(param.get("dy"), 0)
            record["px_per_wheel_unit"] = PX_PER_WHEEL_UNIT
            self.scroll_entries.append(record)

    def cross_check_hand_conversions(self) -> None:
        """拿上游 resource_adb 里那三个手工 Scroll->Swipe 换算来核对本脚本的公式。"""
        for record in self.scroll_already_adb:
            name = record["node"]
            pair: Dict[str, Any] = {
                "node": name,
                "file": record["file"],
                "base_scroll_param": record["param"],
            }
            adb_rel = None
            adb_node: Dict[str, Any] = {}
            for rel, text in sorted(self.adb_pipeline.items()):
                try:
                    adb_data = loads_jsonc(text)
                except ValueError:
                    continue
                if isinstance(adb_data, dict) and isinstance(adb_data.get(name), dict):
                    adb_rel = rel
                    adb_node = adb_data[name]
                    break
            up: Dict[str, Any] = {}
            if adb_rel is not None:
                adb_param = action_param(adb_node)
                up = {k: adb_param.get(k) for k in ("begin", "end", "duration", "end_hold") if k in adb_param}
                pair["upstream_adb_file"] = "assets/resource_adb/pipeline/" + adb_rel
                pair["upstream_swipe"] = up
            # 上游的 end 可能是一串途径点；净位移取 begin -> 最后一个途径点。
            begin = up.get("begin")
            end = up.get("end")
            if isinstance(end, list) and end and isinstance(end[0], list):
                end = end[-1]
            base_param = record["param"]
            # 只比基准 `Scroll` 真正要求的那条轴：GrowthChamberTargetNotFound 只写 dx，
            # 上游的 swipe 顺带还有个纵向分量（那是手调加的路径），拿它比会得出假结论。
            if int(base_param.get("dx") or 0):
                axis = 0
            elif int(base_param.get("dy") or 0):
                axis = 1
            else:
                axis = None
            if (
                axis is not None
                and isinstance(begin, list) and len(begin) == 2
                and isinstance(end, list) and len(end) == 2
            ):
                up_delta = [end[0] - begin[0], end[1] - begin[1]]
                unit = abs(int(base_param.get("dx") or base_param.get("dy") or 1))
                pair["upstream_net_delta"] = up_delta
                pair["comparison_axis"] = "x" if axis == 0 else "y"
                pair["upstream_px_per_wheel_unit"] = round(abs(up_delta[axis]) / unit, 4)
            predicted, why = self.scroll_to_swipe(record["param"])
            pair["script_prediction"] = (
                {"begin": [predicted[0], predicted[1]], "end": [predicted[2], predicted[3]]}
                if predicted
                else None
            )
            pair["script_prediction_note"] = why
            if predicted and "upstream_px_per_wheel_unit" in pair:
                delta = [predicted[2] - predicted[0], predicted[3] - predicted[1]]
                pair["delta_predicted"] = delta
                diff = abs(abs(delta[axis]) - abs(pair["upstream_net_delta"][axis]))
                pair["axis_agreement_px"] = diff
                pair["verdict"] = (
                    "同一方向；沿 %s 轴脚本预测 %d px、上游手调 %d px，差 %d px（≈%.0f%%）—— "
                    "公式是从上游自己的换算量出来的，两端独立吻合。上游仍然逐节点按列表手感"
                    "微调过，所以每个节点的换算值都写进 migration_report.json 的 "
                    "scroll_nodes[].swipe，可以逐节点改。"
                    % (
                        pair["comparison_axis"],
                        abs(delta[axis]),
                        abs(pair["upstream_net_delta"][axis]),
                        diff,
                        100.0 * diff / max(1, abs(pair["upstream_net_delta"][axis])),
                    )
                    if diff <= 15
                    else
                    "同一方向，但幅度有差异：沿 %s 轴脚本预测 %d px、上游手调 %d px，差 %d px。"
                    "上游这个节点的手感系数（%.3f px/滚轮单位）明显偏离 0.675 的默认值 —— 这正是"
                    "本脚本把它当启发式、并把逐节点换算值写进报告的原因。"
                    % (
                        pair["comparison_axis"],
                        abs(delta[axis]),
                        abs(pair["upstream_net_delta"][axis]),
                        diff,
                        pair["upstream_px_per_wheel_unit"],
                    )
                )
            elif predicted:
                pair["verdict"] = (
                    "脚本给了静态预测；上游那一份是手调的，基准 `Scroll` 没有可比的轴，"
                    "两者无法逐像素对比。"
                )
            else:
                pair["verdict"] = (
                    "上游手调了这个节点；基准 `Scroll.target` 是 `true`（默认 = `Target::Type::Self`，"
                    "即识别命中框），迁移时拿不到静态屏幕坐标，所以脚本**拒绝猜**。"
                )
            self.cross_check.append(pair)

    # -- 键 -> Android -----------------------------------------------------------
    @staticmethod
    def key_reason(name: str, action: str, keys: List[int]) -> Tuple[str, str]:
        if name in NODE_NOTES:
            return "specific", NODE_NOTES[name]
        joined = keys[0] if len(keys) == 1 else keys
        if any(k in (16, 17, 18, 160, 161, 162, 163, 164, 165) for k in keys):
            return (
                "pc_modifier",
                "这个 VK（%s）在 Android 上没有语义：宿主 App 把它原样当 "
                "`android.view.KeyEvent` 注入（%s），既不是 Shift 也不是 Alt。"
                "但这里的动作是 `%s` —— 一次成对的按下+抬起（或长按），也就是把修饰键"
                "**当普通按键用**（按键绑定），删掉它就等于删掉一个功能，"
                "所以不能像 `KeyDown`/`KeyUp` 那样改成 `DoNothing`；"
                "必须在设备上找到等价的 UI 入口再重写。"
                % (
                    ", ".join(vk_name(k) for k in keys),
                    ", ".join(android_keycode_name(k) for k in keys),
                    action,
                ),
            )
        if any(k in (65, 68, 83, 87) for k in keys):
            return (
                "pc_movement",
                "PC 的 WASD 角色移动（%s）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，"
                "这是重写而不是替换，本脚本不生成。" % ", ".join(vk_name(k) for k in keys),
            )
        if 32 in keys:
            return "pc_movement", "空格跳跃（VK_SPACE=32）。同上，需要触摸侧的虚拟按键。"
        return (
            "pc_hotkey",
            "PC 快捷键 %s。Android 上同一个数字是 %s，语义完全不同；需要在设备上找到等价的 UI 入口再重写。"
            % (
                ", ".join(vk_name(k) for k in keys),
                ", ".join(android_keycode_name(k) for k in keys),
            ),
        )

    def analyse_keys(self, all_refs: Set[str]) -> None:
        for name in sorted(self.base_nodes):
            node = self.base_nodes[name]
            act = action_type(node)
            if act not in KEY_FAMILY:
                continue
            param = action_param(node)
            keys = node_keys(param)
            rel = self.node_file[name]
            src_text = self.base_pipeline.get(rel, "")
            record: Dict[str, Any] = {
                "node": name,
                "file": "assets/resource/pipeline/" + rel,
                "line": self._locate(src_text, name),
                "action": act,
                "key": param.get("key", param.get("key_code")),
                "node_json": node,
            }
            if name in self.adb_node_names:
                record["status"] = "already_covered_by_resource_adb"
                self.key_already_adb.append(record)
                continue
            if keys and all(k == WINDOWS_VK_ESCAPE for k in keys) and not isinstance(param.get("key"), list):
                record["status"] = "converted"
                record["new_action"] = act
                record["new_key"] = ANDROID_KEYCODE_BACK
                record["reason"] = (
                    "VK_ESCAPE(27) -> Android KEYCODE_BACK(4). 上游 resource_adb 已经对同名的 "
                    "__ScenePrivateAnyExit / __ScenePrivateLoginCloseDialog 做过同样的替换，"
                    "在本仓库里是**活的**先例（Interface/Scene.json:22 会引用它）。"
                    "Android Native 侧 ClickKey 会因为 UseKeyboardDownAndUpInsteadOfClick "
                    "特征位退化成 key_down -> 50ms -> key_up，注入的就是 KEYCODE_BACK。"
                )
                self.key_converted.append(record)
                continue
            if act in ("KeyDown", "KeyUp") and keys and all(k in MODIFIER_VK_KEYS for k in keys):
                record["status"] = "neutralized"
                record["new_action"] = "DoNothing"
                record["new_key"] = None
                record["reason"] = (
                    "PC 修饰键（%s）在 Android 上没有语义：宿主 App 会把这个数字原样当 "
                    "`android.view.KeyEvent` 注入（%s），既不是 Shift 也不是 Alt。上游自己的 "
                    "Android 覆盖就是这么处理的 —— 把这个 KeyDown/KeyUp 改成 `DoNothing`、"
                    "兄弟节点的点击保持不变，见 `resource_adb` 的 "
                    "`__AutoAltClickAltKeyDownAction` / `__AutoAltClickAltKeyUpAction`"
                    "（这两个节点由 `agent/go-service/common/autoalt/ctrl_click.go:9` 点名调用，是活代码，"
                    "所以这是**活的先例**）。`DoNothing` 只换动作、不动识别与 next，"
                    "所以整条链的形状不变，只是不再注入这个无意义的 keycode。"
                    "（若设备实测发现这个修饰键改变了交互语义、而不是仅仅被忽略，"
                    "就必须改成触摸侧的重写：键盘导航用 `Swipe`、按住用 "
                    "`TouchDown`/`TouchUp`、需要真实逻辑的用 go-service 的 `Custom`。）"
                    % (
                        ", ".join(vk_name(k) for k in keys),
                        ", ".join(android_keycode_name(k) for k in keys),
                    )
                )
                record["modifier_scope"] = (
                    "只对 `KeyDown` / `KeyUp` 做这一步。同一个 VK 出现在 `ClickKey` / "
                    "`LongPressKey` 上时是**按键绑定**而不是修饰键 —— 丢掉它就等于删掉一个功能"
                    "（例如 `__AutoFightActionDodge` 的 VK_LSHIFT(160)），所以那类节点留在"
                    "「无法表示」清单里。"
                )
                self.key_neutralized.append(record)
                continue
            if name not in all_refs and name not in self.external_refs:
                record["status"] = "dead_upstream"
                record["reason"] = (
                    "本节点在 base 与 resource_adb 的全部 pipeline 里都没有任何引用"
                    "（没有 next/on_error/all_of 指向它，也不是任务的 entry），在 pipeline 之外的"
                    "源码（agent/**、assets/tasks/**、assets/resource_* 的平台覆盖）里也搜不到它的名字，"
                    "上游本身就到不了。因此不需要覆盖。"
                )
                self.key_dead.append(record)
                continue
            category, reason = self.key_reason(name, act, keys)
            record["status"] = "unresolved"
            record["category"] = category
            record["reason"] = reason
            if name in self.external_refs:
                record["referenced_outside_pipeline"] = self.external_refs[name]
                reason = (
                    reason + " 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），"
                    "但它被 pipeline 之外的代码点名调用：%s。所以它不是死代码，"
                    "只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。"
                    % ("；".join(self.external_refs[name][:4]))
                )
                record["reason"] = reason
            self.key_unresolved.append(record)

    # -- 生成 resource_android ---------------------------------------------------
    @staticmethod
    def _generated_desc(entry: Dict[str, Any]) -> str:
        if entry["status"] == "converted" and entry.get("new_action") == "Swipe":
            return (
                "[resource_android] Scroll(dx=%d, dy=%d) emulated as Swipe "
                "(Android Native has no ScrollableUnit); auto-generated by migrate_maaend.py."
                % (entry.get("dx", 0), entry.get("dy", 0))
            )
        if entry["status"] == "neutralized":
            return (
                "[resource_android] PC modifier VK %s dropped (DoNothing); "
                "auto-generated by migrate_maaend.py." % entry.get("key")
            )
        return (
            "[resource_android] %s VK %s -> Android %s; auto-generated by migrate_maaend.py."
            % (entry["action"], entry.get("key"), entry.get("new_key"))
        )

    def build_overlay(self) -> Dict[str, Any]:
        by_file: Dict[str, Dict[str, Any]] = {}
        for entry in self.scroll_entries:
            if entry["status"] != "converted":
                continue
            rel = rel_under("assets/resource/pipeline/", entry["file"])
            node_override = {
                "desc": self._generated_desc(entry),
                "action": {"type": "Swipe", "param": {"begin": entry["swipe"]["begin"], "end": entry["swipe"]["end"]}},
            }
            by_file.setdefault(rel, {})[entry["node"]] = node_override
        for entry in self.key_converted:
            rel = rel_under("assets/resource/pipeline/", entry["file"])
            node_override = {
                "desc": self._generated_desc(entry),
                "action": {"type": entry["action"], "param": {"key": entry["new_key"]}},
            }
            by_file.setdefault(rel, {})[entry["node"]] = node_override
        for entry in self.key_neutralized:
            rel = rel_under("assets/resource/pipeline/", entry["file"])
            node_override = {
                "desc": self._generated_desc(entry),
                "action": {"type": "DoNothing"},
            }
            by_file.setdefault(rel, {})[entry["node"]] = node_override

        for rel in sorted(by_file):
            self.emit_json("%s/pipeline/%s" % (OVERLAY_DIR, rel), by_file[rel])
        return {
            "files": len(by_file),
            "nodes": sum(len(v) for v in by_file.values()),
            "paths": sorted("%s/pipeline/%s" % (OVERLAY_DIR, r) for r in by_file),
        }

    # -- 已知限制文档 -------------------------------------------------------------
    def build_limitations(self, task_analysis: Dict[str, Any]) -> str:
        lines: List[str] = []
        add = lines.append
        add("# `resource_android` — 已知限制")
        add("")
        add("本文件由 `%s` 自动生成，**不要手改**（改了就等着下次迁移覆盖）。" % GENERATOR)
        add("")
        add("`resource_android` 只补 `resource_adb` 没盖到的、Android Native 控制器的能力缺口。")
        add("覆盖不到的东西写在这里，而不是伪造一个看起来能跑的节点。")
        add("")
        add("能力依据（都读过源码）：")
        add("")
        add("- `MaaControlUnit/ControlUnitAPI.h`：`AndroidNativeControlUnitAPI : ControlUnitAPI`，")
        add("  **没有** `ScrollableUnit` / `RelativeMovableUnit` / `ShellableUnit`。")
        add("- `MaaFramework/Controller/ControllerAgent.cpp` `handle_scroll()`：拿不到")
        add("  `ScrollableUnit` 时 `LogError << \"Scroll is not supported for this controller type\"`")
        add("  并 `return false` —— 动作失败，不是静默跳过。")
        add("- `MaaPocket/core/src/main/java/com/maapocket/core/maa/InputControlUtils.java`：")
        add("  `key` 被当成 `android.view.KeyEvent` 的 keycode 直接注入，没有 VK→Android 的翻译表。")
        add("")
        add("## 1. 汇总")
        add("")
        add("| 类别 | 数量 |")
        add("| --- | --- |")
        add("| `Scroll` 总节点 | %d |" % (len(self.scroll_entries) + len(self.scroll_already_adb)))
        add("| `Scroll` 已由 `resource_adb` 覆盖 | %d |" % len(self.scroll_already_adb))
        add("| `Scroll` 本次改写成 `Swipe` | %d |" % sum(1 for e in self.scroll_entries if e["status"] == "converted"))
        add("| `Scroll` 未能改写 | %d |" % sum(1 for e in self.scroll_entries if e["status"] != "converted"))
        add("| 键类节点（ClickKey/KeyDown/KeyUp/LongPressKey）总节点 | %d |"
            % (len(self.key_converted) + len(self.key_neutralized) + len(self.key_unresolved)
               + len(self.key_dead) + len(self.key_already_adb)))
        add("| 键类：已由 `resource_adb` 覆盖 | %d |" % len(self.key_already_adb))
        add("| 键类：本次改写成 Android `KEYCODE_BACK`(4) | %d |" % len(self.key_converted))
        add("| 键类：本次把 PC 修饰键改成 `DoNothing` | %d |" % len(self.key_neutralized))
        add("| 键类：上游本身就是死代码 | %d |" % len(self.key_dead))
        add("| 键类：**无法表示**（本文件下方逐条列出） | %d |" % len(self.key_unresolved))
        add("")

        add("## 2. 已改写：`Scroll` -> `Swipe`")
        add("")
        add("换算公式：锚点 P = `target` 的中心（4 元矩形取 `x + w//2, y + h//2`；2 元点直接取），")
        add("手指位移 = `(dx, dy) * %.3f` 像素，幅度夹到 [%d, %d] px，`begin = P - 位移/2`、"
            "`end = P + 位移/2`。" % (PX_PER_WHEEL_UNIT, SWIPE_PX_MIN, SWIPE_PX_MAX))
        add("")
        add("`%.3f` 这个系数不是协议里的，是从上游自己的手工换算量出来的：`resource_adb` 把 "
            "`GrowthChamberSortBySwipe` 的 `Scroll dy:-120` 换成 `Swipe begin:[238,508] "
            "end:[239,427]`，位移 81 px，`81/120 = 0.675`；同一个 `dy:-120` 在 "
            "`ReceptionRoomSendCluesSwipe` 里上游写的是 341 px（约 2.84 px/单位），"
            "`GrowthChamberTargetNotFound` 的 `dx:-120` 是 80 px（约 0.67 px/单位）。"
            "三个样本、两个不同的量级，说明这个系数本来就跟具体列表的手感有关 —— 所以它是"
            "**有据可查的启发式**，不是定律；取 0.675 是因为它来自唯一一个能逐像素核对的样本。"
            "每个节点的换算值都写在 `migration_report.json` 的 `scroll_nodes[].swipe` 里，"
            "要调就按节点调。" % PX_PER_WHEEL_UNIT)
        add("")
        add("| 文件 | 节点 | 原 `Scroll` | 新 `Swipe` |")
        add("| --- | --- | --- | --- |")
        for entry in self.scroll_entries:
            if entry["status"] != "converted":
                continue
            add(
                "| `%s` | `%s` | dx=%d dy=%d | begin=%s end=%s |"
                % (
                    entry["file"],
                    entry["node"],
                    entry.get("dx", 0),
                    entry.get("dy", 0),
                    entry["swipe"]["begin"],
                    entry["swipe"]["end"],
                )
            )
        add("")
        add("节点级证据同时写在 `migration_report.json` 的 `scroll_nodes[]`（含原始 JSON）。")
        add("")
        for entry in self.scroll_entries:
            if entry["status"] == "converted":
                continue
            add("### 未改写：`%s`" % entry["node"])
            add("")
            add("- 位置：`%s:%d`" % (entry["file"], entry["line"]))
            add("- 原因：%s" % entry["reason"])
            add("")
            add("```json")
            add(json.dumps({entry["node"]: entry["node_json"]}, ensure_ascii=False, indent=4))
            add("```")
            add("")

        add("## 3. 已改写：ESC -> `KEYCODE_BACK`")
        add("")
        add("| 文件 | 节点 | 原动作 / key | 新 key |")
        add("| --- | --- | --- | --- |")
        for entry in self.key_converted:
            add(
                "| `%s` | `%s` | %s %s | %d |"
                % (entry["file"], entry["node"], entry["action"], entry.get("key"), entry["new_key"])
            )
        add("")

        add("## 4. 已改写：PC 修饰键 -> `DoNothing`")
        add("")
        add("Shift / Ctrl / Alt 这类修饰键在 Android 上**没有语义**：宿主 App "
            "（`MaaPocket/core/src/main/java/com/maapocket/core/maa/InputControlUtils.java`）"
            "把 pipeline 里的 `key` 原样塞进 `new KeyEvent(..., keyCode, 0)` 再 "
            "`injectInputEvent`，没有任何映射表。同一个数字在 Android 上往往是完全无关的按键"
            "（16 -> KEYCODE_9、17 -> KEYCODE_STAR、18 -> KEYCODE_POUND、164 -> 未命名/保留）。")
        add("")
        add("上游自己的 Android 覆盖就是这么处理的：`resource_adb` 把 "
            "`__AutoAltClickAltKeyDownAction` / `__AutoAltClickAltKeyUpAction` 改成 `DoNothing`，"
            "保留兄弟节点的那次点击。这两个节点是**活的**（`agent/go-service/common/autoalt/ctrl_click.go:9` "
            "直接点名调用），所以这是可用的先例。")
        add("`DoNothing` 只替换动作 —— MaaFramework 的节点覆盖是**逐字段合并**"
            "（`PipelineParser::parse_node`，每个字段都回落到上一层同名节点），"
            "`recognition` / `next` / `post_delay` / `anchor` 全部原样继承，"
            "所以整条链的形状不变，只是不再注入那个无意义的 keycode。")
        add("")
        add("| 文件 | 行 | 节点 | 原动作 | key |")
        add("| --- | --- | --- | --- | --- |")
        for entry in self.key_neutralized:
            add(
                "| `%s` | %d | `%s` | %s | %s |"
                % (entry["file"], entry["line"], entry["node"], entry["action"], entry.get("key"))
            )
        add("")
        add("逐节点的理由与原始 JSON 见 `migration_report.json` 的 `key_nodes_neutralized[]`。")
        add("")

        add("## 5. 无法表示 / 未改写：键类节点")
        add("")
        add("全部 %d 个节点。位置按 `git show HEAD:<file>` 的行号。" % len(self.key_unresolved))
        add("")
        for category, title in (
            ("specific", "逐个分析过的节点"),
            ("pc_modifier", "修饰键被当普通按键用（Shift / Ctrl / Alt 的按键绑定）"),
            ("pc_movement", "PC 角色移动（WASD / 空格）"),
            ("pc_hotkey", "PC 快捷键"),
        ):
            bucket = [e for e in self.key_unresolved if e.get("category") == category]
            if not bucket:
                continue
            add("### %s（%d）" % (title, len(bucket)))
            add("")
            for entry in bucket:
                add("#### `%s`" % entry["node"])
                add("")
                add("- 位置：`%s:%d`" % (entry["file"], entry["line"]))
                add("- 原动作：`%s`，key = `%s`" % (entry["action"], entry.get("key")))
                add("- 理由：%s" % entry["reason"])
                add("")
                add("```json")
                add(json.dumps({entry["node"]: entry["node_json"]}, ensure_ascii=False, indent=4))
                add("```")
                add("")

        add("## 6. 上游就是死代码的键类节点（无需覆盖）")
        add("")
        add("判定要求**同时**满足两条：（a）在 base + `resource_adb` 的全部 pipeline 里零引用"
            "（没有任何 `next`/`on_error`/`all_of` 指向它，也不是任务的 entry）；（b）在 pipeline "
            "之外的源码里也搜不到它的名字 —— 搜索范围是 `%s`。"
            % "`、`".join(self.EXTERNAL_REF_PATHSPECS))
        add("")
        add("第二条不能省：Go 代码可以直接点名执行节点（`agent/go-service/autofight/autofight.go:765` "
            "的 `ctx.RunAction(\"__AutoFightActionComboClick\", …)` 就是这样），任务片段也可以用 "
            "`pipeline_override` 覆盖节点，各平台覆盖（`assets/resource_macos/pipeline/MacOSKeyMap.json` 等）"
            "也会重新定义同名节点。只看 pipeline 的 `next` 会把 AutoFight 这一整类"
            "「由 agent 驱动」的节点误判成死代码 —— 本脚本第一版就犯过这个错，已按 (b) 修正。")
        add("")
        add("修正后的结果是：**本次扫描判定为死代码的键类节点为 %d 个**"
            "（MaaEnd 给每一个 PC 按键节点都在 `resource_macos` / `resource_linux` 里留了同名覆盖，"
            "再加上 go-service 点名调用，确实没有一个是真正到不了的）。" % len(self.key_dead))
        add("")
        if self.key_dead:
            add("| 文件 | 行 | 节点 | 动作 | key |")
            add("| --- | --- | --- | --- | --- |")
            for entry in self.key_dead:
                add(
                    "| `%s` | %d | `%s` | %s | %s |"
                    % (entry["file"], entry["line"], entry["node"], entry["action"], entry.get("key"))
                )
        else:
            add("（无）")
        add("")

        add("## 7. `agent/cpp-algo` 在 Android 上没有实现")
        add("")
        add("`interface.json` 里的 `agent/cpp-algo` 已被本脚本删除：`MaaPocket/scripts/` 下只有")
        add("`build_go_agent.py`（`REQUIRED_SO` / `libMaaEnd_go_service.so`），没有 Android 侧的")
        add("cpp-algo 构建器。cpp-algo 注册的自定义识别/动作全集（`agent/cpp-algo/source/main.cpp:113-132`）：")
        add("")
        for name in CPP_ALGO_CUSTOM_NAMES:
            add("- `%s`" % name)
        add("")
        affected = task_analysis.get("tasks_requiring_cpp_algo", [])
        add("受影响的任务定义文件（%d 个），这些任务在设备上会因找不到自定义识别/动作而失败：" % len(affected))
        add("")
        for item in affected:
            names = ", ".join("`%s`" % n for n in item["names"][:6])
            more = "" if len(item["names"]) <= 6 else " …(+%d)" % (len(item["names"]) - 6)
            add("- `%s` — %s%s" % (item["file"], names, more))
        add("")

        add("## 8. 依赖上面任何一条的任务")
        add("")
        add("按 pipeline 的 `next`/`on_error`/识别子名引用关系，从每个任务的 `entry` 做可达性遍历；")
        add("只要可达集合里碰到未改写节点，这个任务就没法完整跑完。")
        add("")
        broken = task_analysis.get("tasks_broken_by_unresolved_nodes", [])
        add("| 任务定义 | 任务名 | entry | 命中的未改写节点 |")
        add("| --- | --- | --- | --- |")
        for item in broken:
            hits = ", ".join("`%s`" % h for h in item["hits"][:5])
            more = "" if len(item["hits"]) <= 5 else " …(+%d)" % (len(item["hits"]) - 5)
            add("| `%s` | `%s` | `%s` | %s%s |" % (item["file"], item["name"], item["entry"], hits, more))
        add("")
        return "\n".join(lines) + "\n"

    # -- 任务分析 ---------------------------------------------------------------
    def analyse_tasks(self, all_refs: Set[str], refs_by_node: Dict[str, Set[str]]) -> Dict[str, Any]:
        unresolved_names = {e["node"] for e in self.key_unresolved}
        unresolved_names |= {e["node"] for e in self.scroll_entries if e["status"] != "converted"}
        cpp_names = set(CPP_ALGO_CUSTOM_NAMES)

        files: List[Dict[str, Any]] = []
        tasks_total = 0
        tasks_without_adb: List[Dict[str, Any]] = []
        tasks_requiring_cpp: List[Dict[str, Any]] = []
        tasks_broken: List[Dict[str, Any]] = []

        entries = self.source.ls_tree("assets/tasks")
        for path, data in self.source.read_blobs(entries):
            if data is None:
                continue
            rel = rel_under("assets/tasks", path)
            try:
                frag = loads_jsonc(data.decode("utf-8", "replace"))
            except ValueError as exc:
                self.parse_errors.append({"file": "tasks/" + rel, "error": str(exc)})
                continue
            if not isinstance(frag, dict):
                continue
            file_rec: Dict[str, Any] = {"file": "tasks/" + rel, "top_level_keys": sorted(frag)}
            task_items = frag.get("task")
            names_here: List[Dict[str, Any]] = []
            if isinstance(task_items, list):
                for item in task_items:
                    if not isinstance(item, dict):
                        continue
                    tasks_total += 1
                    entry = item.get("entry")
                    entry_name = entry if isinstance(entry, str) else (entry[0] if isinstance(entry, list) and entry else "")
                    ctrls = item.get("controller")
                    info = {
                        "file": "tasks/" + rel,
                        "name": item.get("name", ""),
                        "entry": entry_name,
                        "controller": ctrls if isinstance(ctrls, list) else None,
                    }
                    names_here.append(info)
                    if isinstance(ctrls, list) and "ADB" not in ctrls:
                        tasks_without_adb.append(info)
            file_rec["tasks"] = names_here

            # 可达性：从本文件里出现的所有节点名出发
            strings: Set[str] = set()
            collect_strings(frag, strings)
            seeds = {s for s in strings if s in self.node_file and s in refs_by_node}
            reachable: Set[str] = set()
            stack = list(seeds)
            while stack:
                cur = stack.pop()
                if cur in reachable:
                    continue
                reachable.add(cur)
                for nxt in refs_by_node.get(cur, ()):  # base + adb 节点的出边
                    if nxt in refs_by_node and nxt not in reachable:
                        stack.append(nxt)
            # cpp-algo 命中
            cpp_hits: Set[str] = set()
            for name in reachable:
                node = self.base_nodes.get(name)
                if not isinstance(node, dict):
                    continue
                blob = json.dumps(node, ensure_ascii=False)
                for cname in cpp_names:
                    if ('"%s"' % cname) in blob:
                        cpp_hits.add(cname)
            if cpp_hits:
                tasks_requiring_cpp.append({"file": "tasks/" + rel, "names": sorted(cpp_hits)})
            hits = sorted(reachable & unresolved_names)
            if hits:
                for info in names_here or [{"file": "tasks/" + rel, "name": "", "entry": ""}]:
                    tasks_broken.append(
                        {
                            "file": "tasks/" + rel,
                            "name": info.get("name", ""),
                            "entry": info.get("entry", ""),
                            "hits": hits,
                        }
                    )
            files.append(file_rec)

        return {
            "task_definitions": tasks_total,
            "task_files": len(files),
            "tasks_without_ADB_in_controller_whitelist": tasks_without_adb,
            "tasks_requiring_cpp_algo": tasks_requiring_cpp,
            "tasks_broken_by_unresolved_nodes": tasks_broken,
            "files": files,
        }

    # -- interface.json ----------------------------------------------------------
    def build_interface(self) -> Dict[str, Any]:
        raw = self.source._git(["show", "HEAD:" + INTERFACE_SRC], binary=True)
        upstream = loads_jsonc(raw.decode("utf-8", "replace"))
        if not isinstance(upstream, dict):
            raise SystemExit("upstream %s did not parse to an object" % INTERFACE_SRC)

        controllers = upstream.get("controller")
        if not isinstance(controllers, list):
            raise SystemExit("upstream interface.json has no controller list")
        adb_entry = None
        for item in controllers:
            if isinstance(item, dict) and item.get("name") == "ADB":
                adb_entry = item
                break
        if adb_entry is None:
            raise SystemExit("upstream interface.json has no controller named 'ADB'")

        on_device = {
            "name": adb_entry["name"],
            "label": adb_entry.get("label", "$controller.ADB.label"),
            "description": adb_entry.get("description", "$controller.ADB.description"),
            "type": adb_entry.get("type", "Adb"),
            "attach_resource_path": ["./resource_adb", "./%s" % OVERLAY_DIR],
        }
        if isinstance(adb_entry.get("option"), list):
            on_device["option"] = adb_entry["option"]

        agents_in = upstream.get("agent") or []
        agent_out = [a for a in agents_in if isinstance(a, dict) and a.get("child_exec") == "agent/go-service"]
        dropped_agents = [a for a in agents_in if a not in agent_out]
        if not agent_out:
            agent_out = [{"child_exec": "agent/go-service"}]

        order = [
            "interface_version", "name", "label", "title", "icon", "description", "version",
            "contact", "license", "welcome", "github", "mirrorchyan_rid",
            "mirrorchyan_multiplatform", "languages", "telemetry",
        ]
        result: Dict[str, Any] = {}
        for key in order:
            if key in upstream:
                result[key] = upstream[key]
        result["controller"] = [on_device]
        result["resource"] = upstream.get("resource", [])
        result["agent"] = agent_out
        for key in ("group", "pretask", "task", "option", "global_option", "setting", "import", "preset"):
            if key in upstream:
                result[key] = upstream[key]

        self.emit_json(INTERFACE_DST, result)
        return {
            "controller_in": len(controllers),
            "controller_out": [on_device["name"]],
            "controller_type_out": on_device["type"],
            "attach_resource_path": on_device["attach_resource_path"],
            "dropped_controllers": [c.get("name") for c in controllers if isinstance(c, dict) and c is not adb_entry],
            "agent_in": agents_in,
            "agent_out": agent_out,
            "dropped_agent_entries": dropped_agents,
            "comments_dropped": True,
            "preserved_root_keys": sorted(k for k in result if k not in ("controller", "agent")),
            "upstream_interface_version": upstream.get("interface_version"),
            "upstream_version": upstream.get("version"),
        }

    # -- 校验 -------------------------------------------------------------------
    def load_schema(self, name: str) -> Optional[Dict[str, Any]]:
        if not self.schemas_dir:
            return None
        path = os.path.join(self.schemas_dir, name)
        if not os.path.isfile(path):
            return None
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)

    def build_validation(self) -> Dict[str, Any]:
        if not self.schemas_dir:
            return {"skipped": "no --schemas given"}
        result: Dict[str, Any] = {
            "schemas_dir": self.schemas_dir,
            "pipeline_schema": None,
            "interface_schema": None,
            "gating_errors": [],
            "informational_errors": [],
            "files_checked": {"interface": 0, "pipeline": 0, "task_fragments": 0},
        }

        interface_schema = self.load_schema("interface.schema.json")
        pipeline_schema = self.load_schema("pipeline.schema.json")
        if interface_schema is None:
            self.warnings.append("interface.schema.json not found under --schemas")
        if pipeline_schema is None:
            self.warnings.append("pipeline.schema.json not found under --schemas")

        if interface_schema is not None:
            validator = SchemaValidator(interface_schema)
            with open(self._abs(INTERFACE_DST), "r", encoding="utf-8") as fh:
                instance = json.load(fh)
            errors = validator.validate(instance)
            result["files_checked"]["interface"] = 1
            result["interface_schema"] = {
                "$schema": interface_schema.get("$schema"),
                "defs_key": "definitions" if "definitions" in interface_schema else "$defs",
                "unresolved_refs": sorted(validator.unresolved_refs),
            }
            for err in errors:
                result["gating_errors"].append({"file": INTERFACE_DST, "error": err})

            # 任务片段是"根对象的子集"，把 required 放宽后按同一套 schema 校验。
            relaxed = dict(interface_schema)
            relaxed["required"] = []
            frag_validator = SchemaValidator(relaxed)
            entries = self.source.ls_tree("assets/tasks")
            for path, data in self.source.read_blobs(entries):
                if data is None:
                    continue
                rel = rel_under("assets/tasks", path)
                try:
                    frag = loads_jsonc(data.decode("utf-8", "replace"))
                except ValueError:
                    continue
                if not isinstance(frag, dict):
                    continue
                errors = frag_validator.validate(frag)
                result["files_checked"]["task_fragments"] += 1
                for err in errors:
                    result["informational_errors"].append({"file": "tasks/" + rel, "error": err})
            result["task_fragment_note"] = (
                "tasks/** 在上游是被 interface.json 的 import 合并的**片段**，没有独立的 schema。"
                "这里把根 schema 的 required 清空后按同一套约束校验，属于近似；结果只作参考，"
                "不参与退出码。"
            )

        if pipeline_schema is not None:
            validator = SchemaValidator(pipeline_schema)
            result["pipeline_schema"] = {
                "$schema": pipeline_schema.get("$schema"),
                "defs_key": "$defs" if "$defs" in pipeline_schema else "definitions",
                "unresolved_refs": [],
            }
            targets: List[str] = []
            for rel in sorted(self.base_pipeline):
                targets.append("resource/pipeline/" + rel)
            for rel in sorted(self.adb_pipeline):
                targets.append("resource_adb/pipeline/" + rel)
            for rel in sorted(self.base_pipeline):
                path = self._abs("%s/pipeline/%s" % (OVERLAY_DIR, rel))
                if os.path.isfile(path):
                    targets.append("%s/pipeline/%s" % (OVERLAY_DIR, rel))
            for rel in targets:
                try:
                    with open(self._abs(rel), "r", encoding="utf-8") as fh:
                        instance = loads_jsonc(fh.read())
                except (OSError, ValueError) as exc:
                    result["gating_errors"].append({"file": rel, "error": "read/parse: %s" % exc})
                    continue
                errors = validator.validate(instance)
                result["files_checked"]["pipeline"] += 1
                for err in errors:
                    result["gating_errors"].append({"file": rel, "error": err})
            result["pipeline_schema"]["unresolved_refs"] = sorted(validator.unresolved_refs)

            # 覆盖文件本身是"部分节点"，合法但不能证明合并后的节点合法。这里按
            # MaaFramework 的字段级合并语义重建每个被覆盖的节点，再整体校验一遍。
            # 基准取 resource_adb 的同名节点（如果它也有），否则取 resource 的 —— 因为
            # 框架的加载顺序是 resource -> resource_adb -> resource_android。
            merged_files: Dict[str, Dict[str, Any]] = {}
            overlay_root = os.path.join(self.out, OVERLAY_DIR, "pipeline")
            for root, _dirs, names in os.walk(overlay_root):
                for fname in sorted(names):
                    if not fname.endswith(".json"):
                        continue
                    rel = os.path.relpath(os.path.join(root, fname), overlay_root).replace("\\", "/")
                    try:
                        with open(os.path.join(root, fname), "r", encoding="utf-8") as fh:
                            overlay = json.load(fh)
                    except (OSError, ValueError) as exc:
                        result["gating_errors"].append(
                            {"file": "%s/pipeline/%s" % (OVERLAY_DIR, rel), "error": "read/parse: %s" % exc}
                        )
                        continue
                    if not isinstance(overlay, dict):
                        continue
                    for name, ov in overlay.items():
                        if not isinstance(ov, dict):
                            continue
                        base = self.adb_nodes.get(name) or self.base_nodes.get(name)
                        if not isinstance(base, dict):
                            result["gating_errors"].append(
                                {"file": "%s/pipeline/%s" % (OVERLAY_DIR, rel),
                                 "error": "merged node %r has no base node" % name}
                            )
                            continue
                        merged_files.setdefault(rel, {})[name] = merge_node(base, ov)
            for rel, nodes in sorted(merged_files.items()):
                errors = validator.validate(nodes)
                result["files_checked"]["pipeline"] += 1
                for err in errors:
                    result["gating_errors"].append(
                        {"file": "%s/pipeline/%s (合并后/merged)" % (OVERLAY_DIR, rel), "error": err}
                    )
            result["overlay_merged_nodes"] = {
                "nodes": sum(len(v) for v in merged_files.values()),
                "files": len(merged_files),
                "note": (
                    "把 resource_android 的每个覆盖节点按 MaaFramework 的字段级合并语义叠加到基准节点上，"
                    "再对合并结果做 schema 校验 —— 这才是框架实际读到的形状。基准取 resource_adb 的同名"
                    "节点（若存在），否则取 resource 的，与框架 resource -> resource_adb -> "
                    "resource_android 的加载顺序一致。"
                ),
            }

        return result

    # -- 主流程 -----------------------------------------------------------------
    def run(self) -> int:
        self.prepare_out()
        git_head = self.source.head()

        per_tree = self.copy_trees()
        self.index_nodes()
        all_refs, refs_by_node = self.reference_graph()

        interface_info = self.build_interface()
        self.analyse_scroll()
        self.cross_check_hand_conversions()
        self.collect_external_refs()
        self.analyse_keys(all_refs)
        overlay_info = self.build_overlay()
        # analyse_tasks 依赖 unresolved_names，必须在 scroll/key 分析**之后**跑。
        task_analysis = self.analyse_tasks(all_refs, refs_by_node)
        limitations = self.build_limitations(task_analysis)
        self.emit_markdown("%s/%s" % (OVERLAY_DIR, LIMITATIONS_NAME), limitations)

        key_collisions = sorted(
            {
                as_int(e.get("key"))
                for e in (self.key_unresolved + self.key_converted + self.key_neutralized + self.key_dead)
                if isinstance(e.get("key"), int)
            }
        )
        collision_table = [
            {
                "key": k,
                "windows_vk": vk_name(k),
                "android_key_event": android_keycode_name(k),
            }
            for k in key_collisions
        ]

        pack_digest = hashlib.sha256()
        for rel in sorted(self.content_hashes):
            if rel == REPORT_NAME:
                continue
            pack_digest.update(rel.encode("utf-8"))
            pack_digest.update(b"\0")
            pack_digest.update(self.content_hashes[rel].encode("ascii"))
            pack_digest.update(b"\n")

        # 摘要与计数都以"不含 migration_report.json"的口径统计 —— 报告没法描述自己的大小。
        pack_file_count = len(self.files_written)
        pack_bytes = self.bytes_written

        validation = self.build_validation()

        report: Dict[str, Any] = {
            "generator": GENERATOR,
            "generated_at": now_iso(),
            "source": {
                "path": self.source.path,
                "git_head": git_head,
                "git_head_subject": self.source.head_subject(),
                "upstream": UPSTREAM,
                "license": UPSTREAM_LICENSE,
            },
            "arguments": {
                "source": self.args.source,
                "out": self.args.out,
                "schemas": self.args.schemas,
                "force": self.force,
            },
            "design": {
                "pack_layout": [
                    INTERFACE_DST, "tasks/**", "resource/**", "resource_adb/**",
                    "%s/**" % OVERLAY_DIR, "data/**", "locales/**", "LICENSE", REPORT_NAME,
                ],
                "overlay_precedence": (
                    "MaaFramework loads `resource` first, then each `attach_resource_path` in order; "
                    "`PipelineResMgr::parse_and_override_once` merges a same-named node field-by-field "
                    "onto the previously loaded one (unset fields inherit). resource_android therefore "
                    "only lists the fields it changes, and skips any node name resource_adb already "
                    "defines. `PipelineParser::parse_action` resets action params to framework defaults "
                    "when the action TYPE changes, which is exactly what Scroll->Swipe needs."
                ),
                "underscore_dir_rule": (
                    "MaaFramework's Android packaging cannot handle directory names starting with '_'; "
                    "every emitted path is asserted, and the whole upstream tree was checked."
                ),
                "swipe_emulation": {
                    "px_per_wheel_unit": PX_PER_WHEEL_UNIT,
                    "clamp_px": [SWIPE_PX_MIN, SWIPE_PX_MAX],
                    "origin": (
                        "measured from MaaEnd's own hand conversion in resource_adb "
                        "(Scroll dy:-120 -> Swipe delta-y -81 => 0.675 px/unit)"
                    ),
                },
                "keycode_rule": (
                    "Android Native forwards `key` verbatim as an android.view.KeyEvent keycode, and "
                    "MaaEnd's values are Windows VK codes. Only VK_ESCAPE(27) has an unambiguous "
                    "Android counterpart (KEYCODE_BACK=4), which upstream itself already uses in "
                    "resource_adb. Everything else is left alone and documented."
                ),
            },
            "copy": {
                "trees": per_tree,
                "submodules_not_materialised": sorted(SUBMODULE_PATHS),
                "submodule_note": (
                    "assets/resource/model is a gitlink to MaaEnd/MaaEnd-AI; a blob-less partial clone "
                    "cannot materialise it and `git ls-tree` reports type=commit. It is only consumed by "
                    "agent/cpp-algo (ONNX models + navmesh: MapLocateAction.cpp:327, navi_config.h:385), "
                    "which has no Android implementation, so nothing in the copied pack references it."
                ),
                "underscore_dirs_emitted": [
                    rel for rel in self.files_written if has_underscore_dir(rel)
                ],
            },
            "interface_rewrite": interface_info,
            "scroll_nodes": self.scroll_entries,
            "scroll_already_covered_by_resource_adb": self.scroll_already_adb,
            "hand_conversion_cross_check": self.cross_check,
            "key_nodes_converted": self.key_converted,
            "key_nodes_neutralized": self.key_neutralized,
            "key_nodes_unresolved": self.key_unresolved,
            "key_nodes_dead_upstream": self.key_dead,
            "key_nodes_already_covered_by_resource_adb": self.key_already_adb,
            "android_keycode_collisions": collision_table,
            "cpp_algo": {
                "custom_names": list(CPP_ALGO_CUSTOM_NAMES),
                "source": "agent/cpp-algo/source/main.cpp:113-132",
                "note": (
                    "agent/cpp-algo has no Android build path in MaaPocket (scripts/ only ships "
                    "build_go_agent.py). zipline/ImportBluePrints WebView2 path is Windows-only even upstream."
                ),
            },
            "tasks": task_analysis,
            "overlay": overlay_info,
            "parse_errors": self.parse_errors,
            "duplicate_nodes": self.duplicate_nodes,
            "unknown_top_level_keys": self.unknown_top_level_keys,
            "warnings": self.warnings,
            "validation": validation,
            "stats": {
                "files_written": len(self.files_written),
                "bytes_written": self.bytes_written,
                "pack_digest_sha256": pack_digest.hexdigest(),
                "pack_digest_note": (
                    "sha256 over sorted '<relpath>\\0<sha256(file)>\\n' for every pack file except "
                    "migration_report.json. `files_written`/`bytes_written` likewise EXCLUDE "
                    "migration_report.json (it cannot describe its own size); add it separately when "
                    "measuring the pack on disk. generated_at is the only non-deterministic field, so two "
                    "runs with the same source HEAD must produce the same pack_digest_sha256."
                ),
            },
            "files": sorted(self.files_written + [REPORT_NAME]),
        }

        self.emit_json(REPORT_NAME, report)

        converted_scroll = sum(1 for e in self.scroll_entries if e["status"] == "converted")
        print("MaaEnd -> Android PI pack")
        print("  source        : %s @ %s" % (self.source.path, git_head[:12]))
        print("  out           : %s" % self.out)
        for src_prefix, _dst in COPY_TREES:
            info = per_tree.get(src_prefix, {})
            print("  copy %-24s -> %-16s %5d files %10d bytes"
                  % (src_prefix, info.get("dest", "?"), info.get("files_copied", 0), info.get("bytes_copied", 0)))
        print("  scroll nodes  : %d total, %d already in resource_adb, %d converted to Swipe, %d left"
              % (len(self.scroll_entries) + len(self.scroll_already_adb), len(self.scroll_already_adb),
                 converted_scroll, len(self.scroll_entries) - converted_scroll))
        print("  key nodes     : %d converted (ESC->BACK), %d neutralized (PC modifier->DoNothing), "
              "%d unresolved, %d dead upstream, %d already in resource_adb"
              % (len(self.key_converted), len(self.key_neutralized), len(self.key_unresolved),
                 len(self.key_dead), len(self.key_already_adb)))
        print("  overlay       : %d files, %d nodes" % (overlay_info["files"], overlay_info["nodes"]))
        print("  tasks         : %d definitions, %d needing cpp-algo, %d hit an unresolved node"
              % (task_analysis["task_definitions"], len(task_analysis["tasks_requiring_cpp_algo"]),
                 len(task_analysis["tasks_broken_by_unresolved_nodes"])))
        if validation.get("skipped"):
            print("  validation    : skipped (%s)" % validation["skipped"])
        else:
            print("  validation    : interface=%d pipeline=%d task-fragments=%d, gating errors=%d, informational=%d"
                  % (validation["files_checked"]["interface"], validation["files_checked"]["pipeline"],
                     validation["files_checked"]["task_fragments"], len(validation["gating_errors"]),
                     len(validation["informational_errors"])))
        report_bytes = os.path.getsize(self._abs(REPORT_NAME))
        report_files = len(self.files_written) - pack_file_count
        print("  pack files    : %d (%d bytes), excluding %s"
              % (pack_file_count, pack_bytes, REPORT_NAME))
        print("  %s   : %d file (%d bytes)" % (REPORT_NAME, report_files, report_bytes))
        print("  pack digest   : %s" % pack_digest.hexdigest())

        if self.parse_errors:
            print("", file=sys.stderr)
            print("PARSE ERRORS (%d):" % len(self.parse_errors), file=sys.stderr)
            for item in self.parse_errors[:20]:
                print("  %s: %s" % (item["file"], item["error"]), file=sys.stderr)
            return 1
        if validation.get("gating_errors"):
            print("", file=sys.stderr)
            print("SCHEMA ERRORS (%d):" % len(validation["gating_errors"]), file=sys.stderr)
            for item in validation["gating_errors"][:20]:
                print("  %s: %s" % (item["file"], item["error"]), file=sys.stderr)
            return 1
        return 0


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="migrate_maaend.py",
        description=(
            "Migrate an upstream MaaEnd checkout into an Android-ready Project Interface V2 "
            "resource pack (plus the resource_android overlay that patches the Android Native "
            "controller gaps). Standard library only."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--source", required=True, metavar="DIR",
                        help="MaaEnd git checkout (partial/sparse clones are fine)")
    parser.add_argument("--out", required=True, metavar="DIR",
                        help="PI pack root to (re)generate, i.e. the folder that will hold interface.json")
    parser.add_argument("--schemas", default=None, metavar="DIR",
                        help="directory holding pipeline.schema.json and interface.schema.json")
    parser.add_argument("--force", action="store_true",
                        help="wipe --out first (refuses if it looks like a repo root or contains --source)")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    return Migration(args).run()


if __name__ == "__main__":
    sys.exit(main())
