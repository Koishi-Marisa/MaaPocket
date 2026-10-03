# `resource_android` — 已知限制

本文件由 `MaaPocket/scripts/migrate_maaend.py` 自动生成，**不要手改**（改了就等着下次迁移覆盖）。

`resource_android` 只补 `resource_adb` 没盖到的、Android Native 控制器的能力缺口。
覆盖不到的东西写在这里，而不是伪造一个看起来能跑的节点。

能力依据（都读过源码）：

- `MaaControlUnit/ControlUnitAPI.h`：`AndroidNativeControlUnitAPI : ControlUnitAPI`，
  **没有** `ScrollableUnit` / `RelativeMovableUnit` / `ShellableUnit`。
- `MaaFramework/Controller/ControllerAgent.cpp` `handle_scroll()`：拿不到
  `ScrollableUnit` 时 `LogError << "Scroll is not supported for this controller type"`
  并 `return false` —— 动作失败，不是静默跳过。
- `MaaPocket/core/src/main/java/com/maapocket/core/maa/InputControlUtils.java`：
  `key` 被当成 `android.view.KeyEvent` 的 keycode 直接注入，没有 VK→Android 的翻译表。

## 1. 汇总

| 类别 | 数量 |
| --- | --- |
| `Scroll` 总节点 | 14 |
| `Scroll` 已由 `resource_adb` 覆盖 | 3 |
| `Scroll` 本次改写成 `Swipe` | 10 |
| `Scroll` 未能改写 | 1 |
| 键类节点（ClickKey/KeyDown/KeyUp/LongPressKey）总节点 | 101 |
| 键类：已由 `resource_adb` 覆盖 | 20 |
| 键类：本次改写成 Android `KEYCODE_BACK`(4) | 10 |
| 键类：本次把 PC 修饰键改成 `DoNothing` | 24 |
| 键类：上游本身就是死代码 | 0 |
| 键类：**无法表示**（本文件下方逐条列出） | 47 |
| `StartApp` intent 被削成纯包名 | 5 处 |

## 2. 已改写：`Scroll` -> `Swipe`

换算公式：锚点 P = `target` 的中心（4 元矩形取 `x + w//2, y + h//2`；2 元点直接取），
手指位移 = `(dx, dy) * 0.675` 像素，幅度夹到 [40, 300] px，`begin = P - 位移/2`、`end = P + 位移/2`。

`0.675` 这个系数不是协议里的，是从上游自己的手工换算量出来的：`resource_adb` 把 `GrowthChamberSortBySwipe` 的 `Scroll dy:-120` 换成 `Swipe begin:[238,508] end:[239,427]`，位移 81 px，`81/120 = 0.675`；同一个 `dy:-120` 在 `ReceptionRoomSendCluesSwipe` 里上游写的是 341 px（约 2.84 px/单位），`GrowthChamberTargetNotFound` 的 `dx:-120` 是 80 px（约 0.67 px/单位）。三个样本、两个不同的量级，说明这个系数本来就跟具体列表的手感有关 —— 所以它是**有据可查的启发式**，不是定律；取 0.675 是因为它来自唯一一个能逐像素核对的样本。每个节点的换算值都写在 `migration_report.json` 的 `scroll_nodes[].swipe` 里，要调就按节点调。

| 文件 | 节点 | 原 `Scroll` | 新 `Swipe` |
| --- | --- | --- | --- |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferScrollDownwardBag` | dx=0 dy=-180 | begin=[837, 411] end=[837, 289] |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferScrollDownwardBagReturn` | dx=0 dy=-180 | begin=[837, 411] end=[837, 289] |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferScrollDownwardRepo` | dx=0 dy=-180 | begin=[640, 411] end=[640, 289] |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferScrollUpwardBag` | dx=0 dy=180 | begin=[837, 289] end=[837, 411] |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferScrollUpwardBagReturn` | dx=0 dy=180 | begin=[837, 289] end=[837, 411] |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferScrollUpwardRepo` | dx=0 dy=180 | begin=[640, 289] end=[640, 411] |
| `assets/resource/pipeline/PullCountCalculator.json` | `PullCountCalculatorWarehouseScrollDown` | dx=0 dy=-360 | begin=[640, 472] end=[640, 228] |
| `assets/resource/pipeline/IMS/SyncDepotItemData.json` | `SyncDepotItemDataScrollDown` | dx=0 dy=-180 | begin=[640, 411] end=[640, 289] |
| `assets/resource/pipeline/IMS/SyncDepotItemData.json` | `SyncDepotItemDataScrollUp` | dx=0 dy=180 | begin=[640, 289] end=[640, 411] |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmFindFarmland.json` | `_AutoEcoFarmScrollToIndustrialFacilities` | dx=0 dy=-480 | begin=[168, 504] end=[168, 204] |

节点级证据同时写在 `migration_report.json` 的 `scroll_nodes[]`（含原始 JSON）。

### 未改写：`_AutoEcoFarmScroll`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmFindFarmland.json:251`
- 原因：desc 写的是「将鼠标移到目标位置并滚动放大视角」，next 里有 _AutoEcoFarmScaleMax，这是 3D 视角的滚轮缩放，不是列表滚动。Android 上对应的手势是双指捏合（MultiSwipe + contact 0/1，协议支持），但本节点的 target 是锚点名「[Anchor]_AutoEcoFarmFindEcoFarmAnchor」而不是静态坐标，捏合的中心点无法静态求出；而且该节点是 DirectHit，Self 命中框为空，begin:true 也拿不到中心。因此不自动改写。

```json
{
    "_AutoEcoFarmScroll": {
        "desc": "将鼠标移到目标位置并滚动放大视角",
        "pre_delay": 0,
        "action": {
            "type": "Scroll",
            "param": {
                "target": "[Anchor]_AutoEcoFarmFindEcoFarmAnchor",
                "dy": 240
            }
        },
        "post_wait_freezes": {
            "time": 10,
            "target": [
                0,
                0,
                1280,
                720
            ]
        },
        "post_delay": 0,
        "next": [
            "_AutoEcoFarmFindFarmlandCustom",
            "_AutoEcoFarmScaleMax",
            "_AutoEcoFarmPullEcoFarmToCenter"
        ]
    }
}
```

## 3. 已改写：ESC -> `KEYCODE_BACK`

| 文件 | 节点 | 原动作 / key | 新 key |
| --- | --- | --- | --- |
| `assets/resource/pipeline/ImportBluePrints.json` | `ImportBluePrintsExists` | ClickKey 27 | 4 |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferClickEscDestination` | ClickKey 27 | 4 |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferClickEscOrigin` | ClickKey 27 | 4 |
| `assets/resource/pipeline/ItemTransfer.json` | `ItemTransferClickEscOriginReturn` | ClickKey 27 | 4 |
| `assets/resource/pipeline/SeizeDeliveryJobs/SeizeDeliveryJobsEndpointFilter.json` | `SeizeDeliveryJobsPressEscAfterMatch` | ClickKey 27 | 4 |
| `assets/resource/pipeline/SeizeDeliveryJobs/SeizeDeliveryJobsEndpointFilter.json` | `SeizeDeliveryJobsPressEscAfterNotMatch` | ClickKey 27 | 4 |
| `assets/resource/pipeline/SharedZiplineDelete/Delete.json` | `SharedZiplineDeleteDismissNoMapMind` | ClickKey 27 | 4 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmFindFarmland.json` | `_AutoEcoFarmExitTargetManager` | ClickKey 27 | 4 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmFindFarmland.json` | `_AutoEcoFarmIsMrarking` | ClickKey 27 | 4 |
| `assets/resource/pipeline/MapNavigator/ObstacleDevice.json` | `__MapNavigatorObstacleDevice_CancelMove` | ClickKey 27 | 4 |

## 4. 已改写：PC 修饰键 -> `DoNothing`

Shift / Ctrl / Alt 这类修饰键在 Android 上**没有语义**：宿主 App （`MaaPocket/core/src/main/java/com/maapocket/core/maa/InputControlUtils.java`）把 pipeline 里的 `key` 原样塞进 `new KeyEvent(..., keyCode, 0)` 再 `injectInputEvent`，没有任何映射表。同一个数字在 Android 上往往是完全无关的按键（16 -> KEYCODE_9、17 -> KEYCODE_STAR、18 -> KEYCODE_POUND、164 -> 未命名/保留）。

上游自己的 Android 覆盖就是这么处理的：`resource_adb` 把 `__AutoAltClickAltKeyDownAction` / `__AutoAltClickAltKeyUpAction` 改成 `DoNothing`，保留兄弟节点的那次点击。这两个节点是**活的**（`agent/go-service/common/autoalt/ctrl_click.go:9` 直接点名调用），所以这是可用的先例。
`DoNothing` 只替换动作 —— MaaFramework 的节点覆盖是**逐字段合并**（`PipelineParser::parse_node`，每个字段都回落到上一层同名节点），`recognition` / `next` / `post_delay` / `anchor` 全部原样继承，所以整条链的形状不变，只是不再注入那个无意义的 keycode。

| 文件 | 行 | 节点 | 原动作 | key |
| --- | --- | --- | --- | --- |
| `assets/resource/pipeline/Common/Button/ProtosyncMenuButton.json` | 20 | `AltClickProtosyncMenuButtonKeyDown` | KeyDown | 164 |
| `assets/resource/pipeline/Common/Button/ProtosyncMenuButton.json` | 42 | `AltClickProtosyncMenuButtonKeyUp` | KeyUp | 164 |
| `assets/resource/pipeline/Common/Button/RegionalDevelopmentButton.json` | 23 | `AltClickRegionalDevelopmentButtonKeyDown` | KeyDown | 164 |
| `assets/resource/pipeline/Common/Button/RegionalDevelopmentButton.json` | 45 | `AltClickRegionalDevelopmentButtonKeyUp` | KeyUp | 164 |
| `assets/resource/pipeline/ProdManual.json` | 249 | `ProdManualOpenInMain` | KeyDown | 18 |
| `assets/resource/pipeline/ProdManual.json` | 289 | `ProdManualOpenInMainDone` | KeyUp | 18 |
| `assets/resource/pipeline/StashBackpack.json` | 139 | `StashBackpackUsableItemsLimitedDrag` | KeyDown | 16 |
| `assets/resource/pipeline/StashBackpack.json` | 207 | `StashBackpackUsableItemsLimitedQuickMoveKeyUp` | KeyUp | 16 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmSwipeToTarget.json` | 373 | `_AutoEcoFarmAltDown` | KeyDown | 18 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmSwipeToTarget.json` | 404 | `_AutoEcoFarmAltUp` | KeyUp | 18 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmSwipeToTarget.json` | 192 | `_AutoEcoFarmChangeToExplore1` | KeyDown | 18 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmSwipeToTarget.json` | 218 | `_AutoEcoFarmChangeToExplore3` | KeyUp | 18 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmCommon.json` | 88 | `_AutoEcoFarmSwipeToGround2` | KeyDown | 18 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmCommon.json` | 119 | `_AutoEcoFarmSwipeToGround4` | KeyUp | 18 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json` | 237 | `_AutoEcoFarmWork1` | KeyDown | 18 |
| `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json` | 263 | `_AutoEcoFarmWork3` | KeyUp | 18 |
| `assets/resource/pipeline/Common/Private/AutoAltClick/Action.json` | 28 | `__AutoCtrlClickCtrlKeyDownAction` | KeyDown | 17 |
| `assets/resource/pipeline/Common/Private/AutoAltClick/Action.json` | 35 | `__AutoCtrlClickCtrlKeyUpAction` | KeyUp | 17 |
| `assets/resource/pipeline/AutoFight/Action.json` | 83 | `__AutoFightActionEndSkillAltKeyDown` | KeyDown | 18 |
| `assets/resource/pipeline/AutoFight/Action.json` | 90 | `__AutoFightActionEndSkillAltKeyUp` | KeyUp | 18 |
| `assets/resource/pipeline/Common/Private/CharacterController/Action.json` | 33 | `__CharacterControllerDeltaAltKeyDownAction` | KeyDown | 18 |
| `assets/resource/pipeline/Common/Private/CharacterController/Action.json` | 40 | `__CharacterControllerDeltaAltKeyUpAction` | KeyUp | 18 |
| `assets/resource/pipeline/MapNavigator/ObstacleDevice.json` | 54 | `__MapNavigatorObstacleDevice_InteractPost` | KeyUp | 18 |
| `assets/resource/pipeline/MapNavigator/ObstacleDevice.json` | 17 | `__MapNavigatorObstacleDevice_InteractPre` | KeyDown | 18 |

逐节点的理由与原始 JSON 见 `migration_report.json` 的 `key_nodes_neutralized[]`。

## 5. 无法表示 / 未改写：键类节点

全部 47 个节点。位置按 `git show HEAD:<file>` 的行号。

### 逐个分析过的节点（10）

#### `AutoPickFalls`

- 位置：`assets/resource/pipeline/RealTimeTask/AutoPick.json:2`
- 原动作：`ClickKey`，key = `70`
- 理由：采集交互，依赖键盘 F。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:45；assets/tasks/RealTimeTask.json:861；assets/tasks/RealTimeTask.json:872；assets/tasks/setting/Keymap.json:56。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "AutoPickFalls": {
        "desc": "拾取掉落物",
        "recognition": {
            "type": "And",
            "param": {
                "all_of": [
                    {
                        "sub_name": "number",
                        "recognition": "TemplateMatch",
                        "roi": [
                            755,
                            330,
                            297,
                            312
                        ],
                        "template": [
                            "RealTimeTask/AutoPickF1.png"
                        ],
                        "threshold": 0.95
                    },
                    {
                        "sub_name": "icon",
                        "recognition": "TemplateMatch",
                        "roi": [
                            755,
                            330,
                            297,
                            312
                        ],
                        "template": [
                            "RealTimeTask/AutoPickF.png"
                        ],
                        "threshold": 0.6
                    }
                ]
            }
        },
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 70,
        "post_delay": 50,
        "next": [
            "AutoPickFalls",
            "RealTimeFakeTrue"
        ],
        "focus": {
            "Node.Action.Succeeded": "拾取掉落物"
        }
    }
}
```

#### `AutoPickInteractive`

- 位置：`assets/resource/pipeline/RealTimeTask/AutoPick.json:51`
- 原动作：`ClickKey`，key = `70`
- 理由：采集交互，依赖键盘 F。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:53；assets/tasks/RealTimeTask.json:864；assets/tasks/RealTimeTask.json:875；assets/tasks/setting/Keymap.json:59。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "AutoPickInteractive": {
        "desc": "拾取交互物,更新至1.2",
        "recognition": {
            "type": "And",
            "param": {
                "all_of": [
                    {
                        "sub_name": "icon",
                        "recognition": "TemplateMatch",
                        "roi": [
                            755,
                            330,
                            297,
                            312
                        ],
                        "template": "RealTimeTask/AutoPick.png",
                        "threshold": 0.9
                    },
                    {
                        "sub_name": "material",
                        "recognition": "OCR",
                        "roi": [
                            755,
                            330,
                            297,
                            312
                        ],
                        "expected": [
                            "萤壳虫",
                            "灼壳虫",
                            "柑实",
                            "灰芦麦",
                            "锦草",
                            "苦叶椒",
                            "映火荞花",
                            "砂叶",
                            "黯银柑实",
                            "芽针",
                            "荞花",
                            "蓬茸锦草",
                            "酮化灌木",
                            "荆刺芽针",
                            "原木",
                            "重红柱状菌",
                            "受蚀玉化叶",
                            "至晶多齿叶",
                            "岩天使叶",
                            "轻红柱状菌",
                            "晶化多齿叶",
                            "血菌",
                            "中红柱状菌",
                            "纯晶多齿叶",
                            "星门菌",
                            "琼叶参",
                            "金石稻",
                            "协议纹石",
                            "^采集$",
                            "^打开$",
                            "^收集$",
                            "^激活$",
                            "^触碰$"
                        ]
                    }
                ]
            }
        },
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 70,
        "post_delay": 50,
        "next": [
            "AutoPickInteractive",
            "RealTimeFakeTrue"
        ],
        "focus": {
            "Node.Action.Succeeded": "拾取交互物"
        }
    }
}
```

#### `ProtocolSpaceTouchExitTouch`

- 位置：`assets/resource/pipeline/ProtocolSpace/InSpace.json:607`
- 原动作：`ClickKey`，key = `70`
- 理由：F 键退出。Android 上等价物是返回手势/返回键，但这里 F 不是 ESC 语义，不能直接换成 KEYCODE_BACK。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:189；assets/tasks/setting/Keymap.json:62。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "ProtocolSpaceTouchExitTouch": {
        "desc": "判断出现触碰提示，开始触碰",
        "recognition": "And",
        "all_of": [
            "InProtocolSpace",
            {
                "recognition": "OCR",
                "roi": [
                    755,
                    330,
                    297,
                    312
                ],
                "expected": [
                    "领取",
                    "領取",
                    "[Cc]laim",
                    "受取",
                    "획득하기"
                ]
            }
        ],
        "box_index": 1,
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 70,
        "post_delay": 2000,
        "next": [
            "ProtocolSpaceReward",
            "ProtocolSpaceTouchExitRetry"
        ],
        "focus": {
            "Node.Action.Succeeded": "$task.ProtocolSpace.focus.open_reward"
        }
    }
}
```

#### `ProtocolSpaceTouchExitTouchRetry`

- 位置：`assets/resource/pipeline/ProtocolSpace/InSpace.json:656`
- 原动作：`ClickKey`，key = `70`
- 理由：同 ProtocolSpaceTouchExitTouch。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:197；assets/tasks/setting/Keymap.json:65。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "ProtocolSpaceTouchExitTouchRetry": {
        "desc": "重试触碰退出点",
        "recognition": "And",
        "all_of": [
            "InProtocolSpace",
            {
                "recognition": "OCR",
                "roi": [
                    755,
                    330,
                    297,
                    312
                ],
                "expected": [
                    "领取",
                    "領取",
                    "[Cc]laim",
                    "受取",
                    "획득하기"
                ]
            }
        ],
        "box_index": 1,
        "action": "ClickKey",
        "key": 70,
        "next": [
            "ProtocolSpaceReward"
        ],
        "focus": {
            "Node.Action.Succeeded": "$task.ProtocolSpace.focus.open_reward"
        }
    }
}
```

#### `RealTimeAutoPat`

- 位置：`assets/resource/pipeline/RealTimeTask/AutoPat.json:2`
- 原动作：`ClickKey`，key = `70`
- 理由：'拍一拍' 是左键/按键交互，Android 上没有等价输入。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:205；assets/tasks/RealTimeTask.json:1019；assets/tasks/RealTimeTask.json:1027。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "RealTimeAutoPat": {
        "desc": "自动拍一拍",
        "recognition": {
            "type": "And",
            "param": {
                "all_of": [
                    {
                        "sub_name": "icon",
                        "recognition": "TemplateMatch",
                        "roi": [
                            755,
                            330,
                            297,
                            312
                        ],
                        "template": [
                            "RealTimeTask/AnimPlanInteract.png"
                        ],
                        "threshold": 0.9
                    },
                    {
                        "sub_name": "act",
                        "recognition": "OCR",
                        "roi": [
                            755,
                            330,
                            297,
                            312
                        ],
                        "expected": [
                            "拍一拍"
                        ],
                        "threshold": 0.95
                    }
                ]
            }
        },
        "pre_delay": 0,
        "action": {
            "type": "ClickKey",
            "param": {
                "key": 70
            }
        },
        "post_delay": 50,
        "next": [
            "RealTimeAutoPat",
            "RealTimeFakeTrue"
        ],
        "focus": {
            "Node.Action.Succeeded": "拍一拍"
        }
    }
}
```

#### `RealTimeAutoZiplineClick`

- 位置：`assets/resource/pipeline/RealTimeTask/AutoZipline.json:20`
- 原动作：`ClickKey`，key = `69`
- 理由：滑索交互，依赖键盘 E。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:213。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "RealTimeAutoZiplineClick": {
        "desc": "确认长距滑索",
        "recognition": "DirectHit",
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 69,
        "post_delay": 0,
        "next": [
            "RealTimeFakeTrue"
        ],
        "focus": {
            "Node.Action.Succeeded": "确认长距滑索"
        }
    }
}
```

#### `__AutoFightActionAttackKeyPress`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:53`
- 原动作：`ClickKey`，key = `48`
- 理由：同一文件里已经有触摸版攻击链（__AutoFightActionAttackClick / __AutoFightActionAttackTouchDown / __AutoFightActionAttackTouchUp / __AutoFightActionLockTarget），所以战斗不是完全没救；但本节点是键盘 F/空格 派生的，本身不可移植。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:505。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionAttackKeyPress": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 48,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionComboClick`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:29`
- 原动作：`ClickKey`，key = `69`
- 理由：连招宏，本质是键序；Android 没有等价的键盘面，需要重写意图。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:765；assets/resource_macos/pipeline/MacOSKeyMap.json:513；assets/tasks/setting/Keymap.json:121。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionComboClick": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 69,
        "post_delay": 0
    }
}
```

#### `__ScenePrivateWorldEnterMenuEmail`

- 位置：`assets/resource/pipeline/SceneManager/SceneMenu.json:1981`
- 原动作：`ClickKey`，key = `75`
- 理由：K 键直达邮件菜单；Android 需要走 UI 路径，属于上游界面知识，不在本次可自动推导的范围内。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:809。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__ScenePrivateWorldEnterMenuEmail": {
        "desc": "从大世界进入邮箱菜单",
        "recognition": {
            "type": "TemplateMatch",
            "param": {
                "roi": [
                    -200,
                    0,
                    200,
                    200
                ],
                "template": [
                    "SceneManager/WorldMenu.png"
                ],
                "green_mask": true
            }
        },
        "action": {
            "type": "ClickKey",
            "param": {
                "key": 75
            }
        },
        "next": [
            "__ScenePrivateAnyEnterMenuEmailSuccess"
        ]
    }
}
```

#### `__ScenePrivateWorldFactoryEnterMenuBluePrint`

- 位置：`assets/resource/pipeline/SceneManager/SceneMenu.json:2040`
- 原动作：`ClickKey`，key = `112`
- 理由：F1 键直达蓝图菜单；同上。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:817。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__ScenePrivateWorldFactoryEnterMenuBluePrint": {
        "desc": "从大世界工厂模式进入蓝图菜单",
        "recognition": "And",
        "all_of": [
            "InWorldFactory"
        ],
        "action": {
            "type": "ClickKey",
            "param": {
                "key": 112
            }
        },
        "next": [
            "__ScenePrivateAnyEnterMenuBluePrintSuccess"
        ]
    }
}
```

### 修饰键被当普通按键用（Shift / Ctrl / Alt 的按键绑定）（2）

#### `_AutoEcoFarmMoveToTargetErr2`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmCommon.json:49`
- 原动作：`ClickKey`，key = `16`
- 理由：这个 VK（VK_SHIFT）在 Android 上没有语义：宿主 App 把它原样当 `android.view.KeyEvent` 注入（KEYCODE_9），既不是 Shift 也不是 Alt。但这里的动作是 `ClickKey` —— 一次成对的按下+抬起（或长按），也就是把修饰键**当普通按键用**（按键绑定），删掉它就等于删掉一个功能，所以不能像 `KeyDown`/`KeyUp` 那样改成 `DoNothing`；必须在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:424。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmMoveToTargetErr2": {
        "desc": "恢复跑步模式，方式是按一下shift",
        "action": {
            "type": "ClickKey",
            "param": {
                "key": 16
            }
        },
        "next": [
            "AutoEcoFarmExit"
        ]
    }
}
```

#### `__AutoFightActionDodge`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:35`
- 原动作：`ClickKey`，key = `160`
- 理由：这个 VK（VK_LSHIFT）在 Android 上没有语义：宿主 App 把它原样当 `android.view.KeyEvent` 注入（KEYCODE_#160 (未命名/保留)），既不是 Shift 也不是 Alt。但这里的动作是 `ClickKey` —— 一次成对的按下+抬起（或长按），也就是把修饰键**当普通按键用**（按键绑定），删掉它就等于删掉一个功能，所以不能像 `KeyDown`/`KeyUp` 那样改成 `DoNothing`；必须在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:785；agent/go-service/autofight/autofight.go:802；agent/go-service/autofight/autofight.go:806；agent/go-service/autofight/autofight.go:810。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionDodge": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 160,
        "post_delay": 0
    }
}
```

### PC 角色移动（WASD / 空格）（20）

#### `_AutoEcoFarmCancelMove`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:309`
- 原动作：`KeyUp`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:277。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmCancelMove": {
        "desc": "退出任务前先松开w",
        "action": {
            "type": "KeyUp",
            "param": {
                "key": 87
            }
        },
        "next": [
            "_AutoEcoFarmMoveAndWorkExit"
        ]
    }
}
```

#### `_AutoEcoFarmJump`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:20`
- 原动作：`KeyDown`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:341。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmJump": {
        "desc": "往前跳，防止被卡住，先按下w",
        "pre_delay": 0,
        "action": {
            "type": "KeyDown",
            "param": {
                "key": 87
            }
        },
        "post_delay": 0,
        "next": [
            "_AutoEcoFarmJump2"
        ]
    }
}
```

#### `_AutoEcoFarmJump2`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:34`
- 原动作：`LongPressKey`，key = `32`
- 理由：空格跳跃（VK_SPACE=32）。同上，需要触摸侧的虚拟按键。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:349。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmJump2": {
        "desc": "按一下空格",
        "pre_delay": 0,
        "action": {
            "type": "LongPressKey",
            "param": {
                "duration": 500,
                "key": 32
            }
        },
        "post_delay": 0,
        "next": [
            "_AutoEcoFarmJump3"
        ]
    }
}
```

#### `_AutoEcoFarmJump3`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:49`
- 原动作：`KeyUp`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:358。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmJump3": {
        "desc": "松开w",
        "action": {
            "type": "KeyUp",
            "param": {
                "key": 87
            }
        },
        "post_wait_freezes": {
            "time": 200,
            "target": [
                806,
                340,
                266,
                266
            ]
        },
        "post_delay": 0,
        "next": [
            "_AutoEcoFarmFindMenuLoop",
            "_AutoEcoFarmMoveToTargetErr"
        ]
    }
}
```

#### `_AutoEcoFarmJumpNotWork`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:329`
- 原动作：`KeyDown`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:366。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmJumpNotWork": {
        "desc": "不工作版往前跳，防止被卡住，先按下w",
        "max_hit": 20,
        "pre_delay": 0,
        "action": {
            "type": "KeyDown",
            "param": {
                "key": 87
            }
        },
        "post_delay": 0,
        "next": [
            "_AutoEcoFarmJumpNotWork2"
        ]
    }
}
```

#### `_AutoEcoFarmJumpNotWork2`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:345`
- 原动作：`LongPressKey`，key = `32`
- 理由：空格跳跃（VK_SPACE=32）。同上，需要触摸侧的虚拟按键。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:374。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmJumpNotWork2": {
        "desc": "按一下空格",
        "pre_delay": 0,
        "action": {
            "type": "LongPressKey",
            "param": {
                "duration": 500,
                "key": 32
            }
        },
        "post_delay": 0,
        "next": [
            "_AutoEcoFarmJumpNotWork3"
        ]
    }
}
```

#### `_AutoEcoFarmJumpNotWork3`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:360`
- 原动作：`KeyUp`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:383。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmJumpNotWork3": {
        "desc": "松开w",
        "action": {
            "type": "KeyUp",
            "param": {
                "key": 87
            }
        },
        "post_delay": 200,
        "next": [
            "_AutoEcoFarmTargetNotExists",
            "_AutoEcoFarmJumpNotWork",
            "_AutoEcoFarmJump"
        ]
    }
}
```

#### `_AutoEcoFarmLastJump`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:73`
- 原动作：`KeyDown`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:391。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmLastJump": {
        "desc": "识别标记消失后最后往前跳一次",
        "pre_delay": 0,
        "action": {
            "type": "KeyDown",
            "param": {
                "key": 87
            }
        },
        "post_delay": 0,
        "next": [
            "_AutoEcoFarmLastJump2"
        ]
    }
}
```

#### `_AutoEcoFarmLastJump2`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:87`
- 原动作：`LongPressKey`，key = `32`
- 理由：空格跳跃（VK_SPACE=32）。同上，需要触摸侧的虚拟按键。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:399。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmLastJump2": {
        "desc": "按一下空格",
        "pre_delay": 0,
        "action": {
            "type": "LongPressKey",
            "param": {
                "duration": 500,
                "key": 32
            }
        },
        "post_delay": 0,
        "next": [
            "_AutoEcoFarmLastJump3"
        ]
    }
}
```

#### `_AutoEcoFarmLastJump3`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:102`
- 原动作：`KeyUp`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:408。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmLastJump3": {
        "desc": "松开w",
        "action": {
            "type": "KeyUp",
            "param": {
                "key": 87
            }
        },
        "post_wait_freezes": {
            "time": 200,
            "target": [
                0,
                0,
                1280,
                720
            ]
        },
        "next": [
            "_AutoEcoFarmFindMenuIcon",
            "_AutoEcoFarmCancelMove"
        ]
    }
}
```

#### `_AutoEcoFarmMoveToTargetErr1`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmCommon.json:37`
- 原动作：`KeyUp`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:416。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmMoveToTargetErr1": {
        "desc": "先解除w键占用",
        "action": {
            "type": "KeyUp",
            "param": {
                "key": 87
            }
        },
        "next": [
            "_AutoEcoFarmMoveToTargetErr2"
        ]
    }
}
```

#### `_AutoEcoFarmTurnAround1`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmSwipeToTarget.json:315`
- 原动作：`LongPressKey`，key = `83`
- 理由：PC 的 WASD 角色移动（VK_S ('S')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_linux/pipeline/AutoEcoFarm/Action.json:70；assets/resource_macos/pipeline/MacOSKeyMap.json:448。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmTurnAround1": {
        "desc": "长按 S 0.1 秒，转身",
        "action": {
            "type": "LongPressKey",
            "param": {
                "duration": 100,
                "key": 83
            }
        },
        "next": [
            "_AutoEcoFarmTurnAround2"
        ]
    }
}
```

#### `__AutoFightActionMoveBackKeyDown`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:97`
- 原动作：`KeyDown`，key = `83`
- 理由：PC 的 WASD 角色移动（VK_S ('S')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:801；assets/resource_macos/pipeline/MacOSKeyMap.json:581。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionMoveBackKeyDown": {
        "desc": "S键",
        "pre_delay": 0,
        "action": "KeyDown",
        "key": 83,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionMoveBackKeyUp`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:104`
- 原动作：`KeyUp`，key = `83`
- 理由：PC 的 WASD 角色移动（VK_S ('S')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:803；assets/resource_macos/pipeline/MacOSKeyMap.json:589。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionMoveBackKeyUp": {
        "desc": "S键",
        "pre_delay": 0,
        "action": "KeyUp",
        "key": 83,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionMoveForwardKeyDown`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:111`
- 原动作：`KeyDown`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:805；assets/resource_macos/pipeline/MacOSKeyMap.json:597。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionMoveForwardKeyDown": {
        "desc": "W键",
        "pre_delay": 0,
        "action": "KeyDown",
        "key": 87,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionMoveForwardKeyUp`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:118`
- 原动作：`KeyUp`，key = `87`
- 理由：PC 的 WASD 角色移动（VK_W ('W')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:807；assets/resource_macos/pipeline/MacOSKeyMap.json:605。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionMoveForwardKeyUp": {
        "desc": "W键",
        "pre_delay": 0,
        "action": "KeyUp",
        "key": 87,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionMoveLeftKeyDown`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:125`
- 原动作：`KeyDown`，key = `65`
- 理由：PC 的 WASD 角色移动（VK_A ('A')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:809；assets/resource_macos/pipeline/MacOSKeyMap.json:613。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionMoveLeftKeyDown": {
        "desc": "A键",
        "pre_delay": 0,
        "action": "KeyDown",
        "key": 65,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionMoveLeftKeyUp`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:132`
- 原动作：`KeyUp`，key = `65`
- 理由：PC 的 WASD 角色移动（VK_A ('A')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:811；assets/resource_macos/pipeline/MacOSKeyMap.json:621。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionMoveLeftKeyUp": {
        "desc": "A键",
        "pre_delay": 0,
        "action": "KeyUp",
        "key": 65,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionMoveRightKeyDown`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:139`
- 原动作：`KeyDown`，key = `68`
- 理由：PC 的 WASD 角色移动（VK_D ('D')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:813；assets/resource_macos/pipeline/MacOSKeyMap.json:629。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionMoveRightKeyDown": {
        "desc": "D键",
        "pre_delay": 0,
        "action": "KeyDown",
        "key": 68,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionMoveRightKeyUp`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:146`
- 原动作：`KeyUp`，key = `68`
- 理由：PC 的 WASD 角色移动（VK_D ('D')）。Android 端必须换成屏幕虚拟摇杆/方向区的触摸输入，这是重写而不是替换，本脚本不生成。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:815；assets/resource_macos/pipeline/MacOSKeyMap.json:637。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionMoveRightKeyUp": {
        "desc": "D键",
        "pre_delay": 0,
        "action": "KeyUp",
        "key": 68,
        "post_delay": 0
    }
}
```

### PC 快捷键（15）

#### `_AutoEcoFarmEnterCameraModeFallback`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmPhotoMode.json:44`
- 原动作：`KeyDown`，key = `82`
- 理由：PC 快捷键 VK_R ('R')。Android 上同一个数字是 KEYCODE_MENU，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:301；assets/tasks/setting/Keymap.json:41。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmEnterCameraModeFallback": {
        "desc": "点击拍照按钮失败时使用通用快捷键进入拍照模式",
        "recognition": "And",
        "all_of": [
            "_AutoEcoFarmInWorld"
        ],
        "pre_delay": 0,
        "action": "KeyDown",
        "key": 82,
        "post_delay": 0,
        "rate_limit": 0,
        "next": [
            "_AutoEcoFarmEnterCameraModeFallbackSelectTool"
        ],
        "on_error": [
            "_AutoEcoFarmEnterCameraModeFallbackReleaseOnError"
        ]
    }
}
```

#### `_AutoEcoFarmEnterCameraModeFallbackRelease`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmPhotoMode.json:108`
- 原动作：`KeyUp`，key = `82`
- 理由：PC 快捷键 VK_R ('R')。Android 上同一个数字是 KEYCODE_MENU，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:309；assets/tasks/setting/Keymap.json:44。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmEnterCameraModeFallbackRelease": {
        "desc": "释放通用快捷键并继续生态农场拍照流程",
        "pre_delay": 0,
        "action": "KeyUp",
        "key": 82,
        "post_delay": 0,
        "rate_limit": 0,
        "next": [
            "_AutoEcoFarmWaitCameraModeAfterFallback"
        ],
        "on_error": [
            "_AutoEcoFarmEnterCameraModeFallbackReleaseOnError"
        ]
    }
}
```

#### `_AutoEcoFarmEnterCameraModeFallbackReleaseOnError`

- 位置：`assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmPhotoMode.json:122`
- 原动作：`KeyUp`，key = `82`
- 理由：PC 快捷键 VK_R ('R')。Android 上同一个数字是 KEYCODE_MENU，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：assets/resource_macos/pipeline/MacOSKeyMap.json:317；assets/tasks/setting/Keymap.json:47。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "_AutoEcoFarmEnterCameraModeFallbackReleaseOnError": {
        "desc": "回退选择拍照工具失败时释放通用快捷键，本轮跳过隐藏工业设施并继续农场作业",
        "pre_delay": 0,
        "action": "KeyUp",
        "key": 82,
        "post_delay": 0,
        "rate_limit": 0,
        "next": [
            "_AutoEcoFarmWaitForTeammate"
        ],
        "focus": {
            "Node.Action.Succeeded": "$task.AutoEcoFarm.focus.camera_mode_failed"
        }
    }
}
```

#### `__AutoFightActionEndSkillOperators1`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:153`
- 原动作：`LongPressKey`，key = `49`
- 理由：PC 快捷键 VK_49 ('1')。Android 上同一个数字是 KEYCODE_U，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:775；assets/resource_macos/pipeline/MacOSKeyMap.json:545；assets/tasks/setting/Keymap.json:136。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionEndSkillOperators1": {
        "pre_delay": 0,
        "action": "LongPressKey",
        "duration": 1500,
        "key": 49,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionEndSkillOperators2`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:160`
- 原动作：`LongPressKey`，key = `50`
- 理由：PC 快捷键 VK_50 ('2')。Android 上同一个数字是 KEYCODE_V，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:777；assets/resource_macos/pipeline/MacOSKeyMap.json:554；assets/tasks/setting/Keymap.json:139。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionEndSkillOperators2": {
        "pre_delay": 0,
        "action": "LongPressKey",
        "duration": 1500,
        "key": 50,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionEndSkillOperators3`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:167`
- 原动作：`LongPressKey`，key = `51`
- 理由：PC 快捷键 VK_51 ('3')。Android 上同一个数字是 KEYCODE_W，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:779；assets/resource_macos/pipeline/MacOSKeyMap.json:563；assets/tasks/setting/Keymap.json:142。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionEndSkillOperators3": {
        "pre_delay": 0,
        "action": "LongPressKey",
        "duration": 1500,
        "key": 51,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionEndSkillOperators4`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:174`
- 原动作：`LongPressKey`，key = `52`
- 理由：PC 快捷键 VK_52 ('4')。Android 上同一个数字是 KEYCODE_X，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:781；assets/resource_macos/pipeline/MacOSKeyMap.json:572；assets/tasks/setting/Keymap.json:145。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionEndSkillOperators4": {
        "pre_delay": 0,
        "action": "LongPressKey",
        "duration": 1500,
        "key": 52,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionSkillOperators1`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:59`
- 原动作：`ClickKey`，key = `49`
- 理由：PC 快捷键 VK_49 ('1')。Android 上同一个数字是 KEYCODE_U，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:767；assets/resource_macos/pipeline/MacOSKeyMap.json:645；assets/tasks/setting/Keymap.json:124。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionSkillOperators1": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 49,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionSkillOperators2`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:65`
- 原动作：`ClickKey`，key = `50`
- 理由：PC 快捷键 VK_50 ('2')。Android 上同一个数字是 KEYCODE_V，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:769；assets/resource_macos/pipeline/MacOSKeyMap.json:653；assets/tasks/setting/Keymap.json:127。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionSkillOperators2": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 50,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionSkillOperators3`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:71`
- 原动作：`ClickKey`，key = `51`
- 理由：PC 快捷键 VK_51 ('3')。Android 上同一个数字是 KEYCODE_W，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:771；assets/resource_macos/pipeline/MacOSKeyMap.json:661；assets/tasks/setting/Keymap.json:130。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionSkillOperators3": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 51,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionSkillOperators4`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:77`
- 原动作：`ClickKey`，key = `52`
- 理由：PC 快捷键 VK_52 ('4')。Android 上同一个数字是 KEYCODE_X，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:773；assets/resource_macos/pipeline/MacOSKeyMap.json:669；assets/tasks/setting/Keymap.json:133。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionSkillOperators4": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 52,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionSwitchCharacterOperators1`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:181`
- 原动作：`ClickKey`，key = `112`
- 理由：PC 快捷键 VK_F1。Android 上同一个数字是 KEYCODE_FORWARD_DEL，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:790；assets/resource_macos/pipeline/MacOSKeyMap.json:677；assets/tasks/setting/Keymap.json:148。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionSwitchCharacterOperators1": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 112,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionSwitchCharacterOperators2`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:187`
- 原动作：`ClickKey`，key = `113`
- 理由：PC 快捷键 VK_F2。Android 上同一个数字是 KEYCODE_CTRL_LEFT，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:793；assets/resource_macos/pipeline/MacOSKeyMap.json:685；assets/tasks/setting/Keymap.json:151。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionSwitchCharacterOperators2": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 113,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionSwitchCharacterOperators3`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:193`
- 原动作：`ClickKey`，key = `114`
- 理由：PC 快捷键 VK_F3。Android 上同一个数字是 KEYCODE_CTRL_RIGHT，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:796；assets/resource_macos/pipeline/MacOSKeyMap.json:693；assets/tasks/setting/Keymap.json:154。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionSwitchCharacterOperators3": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 114,
        "post_delay": 0
    }
}
```

#### `__AutoFightActionSwitchCharacterOperators4`

- 位置：`assets/resource/pipeline/AutoFight/Action.json:199`
- 原动作：`ClickKey`，key = `115`
- 理由：PC 快捷键 VK_F4。Android 上同一个数字是 KEYCODE_CAPS_LOCK，语义完全不同；需要在设备上找到等价的 UI 入口再重写。 【注意】本节点在 pipeline 图里没有引用（`next` 之类都指不到它），但它被 pipeline 之外的代码点名调用：agent/go-service/autofight/autofight.go:799；assets/resource_macos/pipeline/MacOSKeyMap.json:701；assets/tasks/setting/Keymap.json:157。所以它不是死代码，只是调用方不写在 pipeline 里 —— 这类节点同样必须在 Android 上给出等价实现。

```json
{
    "__AutoFightActionSwitchCharacterOperators4": {
        "pre_delay": 0,
        "action": "ClickKey",
        "key": 115,
        "post_delay": 0
    }
}
```

## 6. 上游就是死代码的键类节点（无需覆盖）

判定要求**同时**满足两条：（a）在 base + `resource_adb` 的全部 pipeline 里零引用（没有任何 `next`/`on_error`/`all_of` 指向它，也不是任务的 entry）；（b）在 pipeline 之外的源码里也搜不到它的名字 —— 搜索范围是 `agent`、`assets/tasks`、`assets/resource_cloud_adb`、`assets/resource_playcover`、`assets/resource_linux`、`assets/resource_macos`、`tools`。

第二条不能省：Go 代码可以直接点名执行节点（`agent/go-service/autofight/autofight.go:765` 的 `ctx.RunAction("__AutoFightActionComboClick", …)` 就是这样），任务片段也可以用 `pipeline_override` 覆盖节点，各平台覆盖（`assets/resource_macos/pipeline/MacOSKeyMap.json` 等）也会重新定义同名节点。只看 pipeline 的 `next` 会把 AutoFight 这一整类「由 agent 驱动」的节点误判成死代码 —— 本脚本第一版就犯过这个错，已按 (b) 修正。

修正后的结果是：**本次扫描判定为死代码的键类节点为 0 个**（MaaEnd 给每一个 PC 按键节点都在 `resource_macos` / `resource_linux` 里留了同名覆盖，再加上 go-service 点名调用，确实没有一个是真正到不了的）。

（无）

## 7. `agent/cpp-algo` 在 Android 上没有实现

`interface.json` 里的 `agent/cpp-algo` 已被本脚本删除：`MaaPocket/scripts/` 下只有
`build_go_agent.py`（`REQUIRED_SO` / `libMaaEnd_go_service.so`），没有 Android 侧的
cpp-algo 构建器。cpp-algo 注册的自定义识别/动作全集（`agent/cpp-algo/source/main.cpp:113-132`）：

- `MyReco1`
- `MapLocateRecognition`
- `MapLocateAssertLocation`
- `MapNavmeshQuery`
- `EssenceGridAdvanceRecognition`
- `EssenceGridPendingRecognition`
- `IconRecognition`
- `MapFind`
- `MapNavigateAction`
- `RealTimeTaskAction`
- `ZiplineImport`

受影响的任务定义文件（7 个），这些任务在设备上会因找不到自定义识别/动作而失败：

- `tasks/AutoEssence/AutoEssence.json` — `EssenceGridAdvanceRecognition`
- `tasks/EssenceFilter.json` — `EssenceGridAdvanceRecognition`
- `tasks/GiftOperator.json` — `MapLocateAssertLocation`, `MapNavigateAction`
- `tasks/RealTimeTask.json` — `RealTimeTaskAction`
- `tasks/SeizeDeliveryJobs.json` — `MapFind`
- `tasks/SharedZiplineDelete.json` — `MapFind`, `MapNavigateAction`
- `tasks/ZiplineImport.json` — `ZiplineImport`

## 8. 依赖上面任何一条的任务

按 pipeline 的 `next`/`on_error`/识别子名引用关系，从每个任务的 `entry` 做可达性遍历；
只要可达集合里碰到未改写节点，这个任务就没法完整跑完。

| 任务定义 | 任务名 | entry | 命中的未改写节点 |
| --- | --- | --- | --- |
| `tasks/AutoEcoFarm.json` | `AutoEcoFarm` | `AutoEcoFarmTask` | `_AutoEcoFarmCancelMove`, `_AutoEcoFarmEnterCameraModeFallback`, `_AutoEcoFarmEnterCameraModeFallbackRelease`, `_AutoEcoFarmEnterCameraModeFallbackReleaseOnError`, `_AutoEcoFarmJump` …(+11) |
| `tasks/ImportBluePrints.json` | `ImportBluePrints` | `ImportBluePrints` | `__ScenePrivateWorldFactoryEnterMenuBluePrint` |
| `tasks/ProtocolSpace.json` | `ProtocolSpace` | `ProtocolSpaceSchedule` | `ProtocolSpaceTouchExitTouch`, `ProtocolSpaceTouchExitTouchRetry` |

## 9. 启动 / 关闭游戏：`StartApp` 与 `StopApp`

上游用任务片段里的 `option`（`tasks/AndroidOpenGame.json` 的 `ClientVersion`）按渠道切换 `StartUpGame` / `CloseGame` 两个节点的 `action`。它在 PC 端能跑，是因为 ADB 控制器自己会拆 `"<package>/<activity>"`。MaaPocket 在 Android 上用的是 AndroidNative 控制器，这条链路是：

```
MaaFramework
  -> MaaAndroidNativeControlUnitMgr::start_app(intent)      # intent 原样存进 StartGameArgs.package_name
  -> MaaPocket/core/src/main/cpp/bridge_input.cpp
       DispatchInputMessage(START_GAME) -> UpcallStartApp(packageName, displayId, forceStop)
  -> MaaPocket/core/src/main/java/com/maapocket/core/remote/internal/ActivityUtils.kt:103-136
       packageManager.getLaunchIntentForPackage(packageName)
  -> PackageManager
```

`getLaunchIntentForPackage()` 只吃**纯包名**；传 `"<pkg>/<activity>"` 会返回 `null`，`ActivityUtils.startApp` 打一行 `Cannot create launch intent for app ...` 然后返回 `false`，游戏根本不会启动。所以本脚本在迁移时把所有 `StartApp` / `StopApp` 的 `package` 削成纯包名：

| 文件 | 动作 | 原值 | 新值 |
| --- | --- | --- | --- |
| `tasks/AndroidOpenGame.json` | `StartApp` | `com.hypergryph.endfield/com.u8.sdk.U8UnityContext` | `com.hypergryph.endfield` |
| `tasks/AndroidOpenGame.json` | `StartApp` | `com.hypergryph.endfield.bilibili/com.u8.sdk.U8UnityContext` | `com.hypergryph.endfield.bilibili` |
| `tasks/AndroidOpenGame.json` | `StartApp` | `com.gryphline.endfield.gp/com.u8.sdk.U8UnityContext` | `com.gryphline.endfield.gp` |
| `tasks/AndroidOpenGame.json` | `StartApp` | `com.hypergryph.endfield.vn/com.u8.sdk.U8UnityContext` | `com.hypergryph.endfield.vn` |
| `tasks/AndroidOpenGame.json` | `StartApp` | `com.hypergryph.cloud.endfield/com.hypergryph.cloud.endfield.splash.SplashActivity` | `com.hypergryph.cloud.endfield` |

`option` 的 `default_case` 保持上游的 `CN`（不替用户选渠道）：真机是 B 服时，在任务详情里把「渠道」切到 `Bilibili` 即可，该 case 的包名已是 `com.hypergryph.endfield.bilibili`。

### `StopApp` 在 Android 上是静默 no-op

`MaaPocket/core/src/main/cpp/bridge_input.cpp` 的 `DispatchInputMessage()` 只处理
`TOUCH_DOWN` / `TOUCH_MOVE` / `TOUCH_UP` / `KEY_DOWN` / `KEY_UP` / `START_GAME`，
`STOP_GAME` 落进 `default: return 0;` —— 返回值 0 表示成功，但什么也没做：
**不会关闭游戏，也不会报错**。所以 `CloseGame` 节点（`resource/pipeline/OpenGame.json`）在 Android 上只能算是走到就过。上游 `StuckRepairAction` 靠 `CloseGame` 重启游戏的自愈路径因此在设备上不会生效，`ResetStartUpGame` 也只能清命中计数、不能真的重开。

修这个要动 `core/src/main/cpp/bridge_input.cpp` 和 `ActivityUtils.kt`（补一个 `stopApp` upcall 并加 `case STOP_GAME`），属于仓库代码而不是迁移产物，本次不碰。

