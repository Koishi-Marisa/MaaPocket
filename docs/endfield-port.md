# MaaEnd → MaaPocket (Android) port note

This document describes the `app-endfield` resource pack produced by
`MaaPocket/scripts/migrate_maaend.py` from upstream **MaaEnd**, and — more
importantly — what that pack does **not** fix.

| | |
|---|---|
| Upstream | `https://github.com/MaaEnd/MaaEnd` (AGPL-3.0) |
| Upstream revision this pack was generated from | `ae43a184c02408410061c858a46205cf90606a26` |
| Generator | `MaaPocket/scripts/migrate_maaend.py` (Python 3.9+, standard library only) |
| Pack root | `app-endfield/src/main/assets/pi/` |
| Pack contents | 3104 files, 44 355 448 bytes, excluding `migration_report.json` |
| Machine-readable record | `migration_report.json`, alongside `interface.json` in the pack root |

Everything below was read out of the actual upstream files and the actual
generated pack. Where a number could not be established, it is marked as such
rather than estimated.

---

## 1. What MaaEnd is

MaaEnd is a MaaFramework-based automation resource project for **Arknights:
Endfield**. Like every MaaFramework project it is two things at once:

* a **resource pack** — `assets/interface.json` (Project Interface V2) plus
  `assets/resource/pipeline/**` (417 JSONC pipeline files, 2131 PNG templates at
  a 1280×720 baseline), `assets/tasks/**` (task definitions that
  `interface.json` pulls in via `import`), `assets/data/**`, and
  `assets/locales/**`;
* one or more **agent processes** — `agent/go-service/` (Go) and
  `agent/cpp-algo/` (C++), which register custom recognitions and custom actions
  that the pipeline calls by name.

MaaEnd ships desktop controllers (`Win32`, `PlayCover`, `Linux-*`, `MacOS-*`) and
one mobile one (`ADB`, plus the `CloudADB` and `PlayCover` variants). Its target
for phone play is *ADB over USB/Wi-Fi to a real device*, not an on-device
controller.

## 2. The Android gap

MaaPocket runs MaaFramework **on the phone itself**, using MaaFramework's
Android Native control unit. That controller is deliberately thin. From
`include/MaaControlUnit/ControlUnitAPI.h`, the optional capability mixins are:

```cpp
class ScrollableUnit      { virtual bool scroll(int dx, int dy) = 0; };
class RelativeMovableUnit { virtual bool relative_move(int dx, int dy) = 0; };
class ShellableUnit       { virtual bool shell(const std::string& cmd,
                                               std::string& output,
                                               std::chrono::milliseconds timeout) = 0; };
```

and the compositions are:

| control unit | scroll | relative_move | shell |
|---|---|---|---|
| `Win32ControlUnitAPI` / `LinuxControlUnitAPI` / `MacOSControlUnitAPI` | yes | yes | — |
| `AdbControlUnitAPI` | **no** | **no** | yes |
| **`AndroidNativeControlUnitAPI`** | **no** | **no** | **no** |
| `CustomControlUnitAPI` | yes | yes | yes |

This is not a soft degradation. `ControllerAgent::handle_scroll` in
`source/MaaFramework/Controller/ControllerAgent.cpp` ends with

```cpp
LogError << "Scroll is not supported for this controller type";
return false;
```

so a pipeline node whose action is `Scroll` **fails**, it does not silently skip.
The same is true for `handle_relative_move` and `handle_shell`.

Two further consequences that matter here:

* The gap is about the *action*, not the hardware. MaaPocket's own input bridge
  (`core/src/main/cpp/bridge_input.cpp`) turns `TOUCH_DOWN`/`TOUCH_MOVE`/
  `TOUCH_UP` into `MotionEvent` injection and `KEY_DOWN`/`KEY_UP` into
  `android.view.KeyEvent` injection via
  `core/src/main/java/com/maapocket/core/maa/InputControlUtils.java`. There is no
  scroll synthesised anywhere in that path.
* **Keycodes are not translated.** `AndroidNativeControlUnitMgr::key_down`
  forwards `param.args.key.key_code = key` verbatim, and
  `InputControlUtils.keyDown(keyCode, displayId)` constructs
  `new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)` with no
  lookup table. So a `key` value written for a Windows pipeline is interpreted on
  device as an **`android.view.KeyEvent` keycode** — and Windows VK codes and
  Android keycodes are different number spaces. `27` (VK_ESCAPE) is
  `KEYCODE_CAMERA` on Android; Android's Back is `4` and its Escape is `111`.

## 3. What this migration does

`MaaPocket/scripts/migrate_maaend.py --source <MaaEnd checkout> --out <pack dir>
[--schemas <dir>] [--force]` materialises the upstream `assets/` tree through
`git` (blob-by-blob, so it works on a partial/sparse clone and never expands the
sparse checkout), flattens it into the PI-pack root the app expects, rewrites
`interface.json` for on-device use, and generates an overlay directory
`resource_android/` that MaaFramework loads **after** `resource/` and
`resource_adb/`.

Because MaaFramework merges node overrides **field-wise, keyed by node name**
(`PipelineResMgr::parse_and_override_once` → `PipelineParser::parse_node`, where
every field falls back to the previously loaded node of the same name), each
generated override carries only the fields that change. Recognition, `next`,
`post_wait_freezes`, `anchor`, `rate_limit` and `timeout` are inherited from the
base node automatically, so a `{"action": …}` override is safe.

### 3.1 `interface.json`

* `controller` is reduced to exactly **one** entry: the upstream `ADB` entry,
  unchanged in `name` and `type` (`"Adb"`), with
  `attach_resource_path: ["./resource_adb", "./resource_android"]`. The desktop
  controllers (`Win32-Front`, `PlayCover`, `Linux-*`, `MacOS-*`, the commented-out
  `Win32-Window-Background`) are dropped, as are `CloudADB` and `PlayCover`.
  Keeping the `name` is load-bearing: MaaPocket does not filter tasks by
  controller, but the name is what the upstream task fragments' `controller`
  whitelists refer to.
* `resource`, `group`, `task`, `option`, `preset`, `import`, `languages`,
  `telemetry`, `version`, `license`, `github`, `mirrorchyan_*` are preserved.
* `agent[]` keeps `child_exec: "agent/go-service"` in the same shape. **The host
  app must substitute the real path at runtime** — see §5.
* Validation: the generated `interface.json` validates against
  `interface.schema.json` (all 27 allowed root keys, `additionalProperties:
  false`, `required: [interface_version, name, controller, resource]`).

### 3.2 `Scroll` → `Swipe`

`pipeline.schema.json` defines `Scroll` with `target`, `target_offset`, `dx`
(positive = scroll right) and `dy` (positive = scroll up). Because a `Swipe`
is available on Android Native, the migration converts genuine list scrolls by
anchoring the swipe on the `Scroll` node's own `target` rect and converting wheel
units to pixels.

Calibration is measured, not invented. Upstream's own `resource_adb` overlay
hand-converted three `Scroll` nodes to `Swipe`; two of them give a self-consistent
factor:

| node | base `Scroll` | upstream hand-written `Swipe` | implied px / wheel unit |
|---|---|---|---|
| `GrowthChamberTargetNotFound` | `dx -120` | `begin [280,380] end [[280,200],[200,200]]` | 0.6667 |
| `GrowthChamberSortBySwipe` | `dy -120` | `begin [238,508] end [239,427]` | 0.6750 |
| `ReceptionRoomSendCluesSwipe` | `dy -120` | `begin [225,572] end [236,231]` | 2.8417 |

The generator uses **0.675 px per wheel unit**, clamped to **40 – 300 px**, and
emits its own prediction for `GrowthChamberTargetNotFound` as
`begin [272,381] end [190,381]` — 82 px against upstream's 80 px, a 2 px (≈2 %)
disagreement between two independently derived numbers. The third sample is a
4.2× outlier, which is exactly why this is treated as a heuristic: the per-node
computed swipe is written into `migration_report.json` under
`scroll_nodes[].swipe`, and any node can be retuned individually. A swipe is
emitted without `duration`/`end_hold`, so the framework defaults (200 ms / 0)
apply.

**Of the five `Scroll` sites the port was scoped around, three were already fixed
upstream and two are fixed here:**

| site | status |
|---|---|
| `assets/resource/pipeline/DijiangRewards/GrowthChamber.json:711` `GrowthChamberTargetNotFound` | already covered by `resource_adb` |
| `assets/resource/pipeline/DijiangRewards/GrowthChamber.json:819` `GrowthChamberSortBySwipe` | already covered by `resource_adb` |
| `assets/resource/pipeline/DijiangRewards/ReceptionRoom.json:1332` `ReceptionRoomSendCluesSwipe` | already covered by `resource_adb` |
| `assets/resource/pipeline/IMS/SyncDepotItemData.json:390` `SyncDepotItemDataScrollUp` | **converted here** → `begin [640,289] end [640,411]` |
| `assets/resource/pipeline/IMS/SyncDepotItemData.json:502` `SyncDepotItemDataScrollDown` | **converted here** → `begin [640,411] end [640,289]` |

Note that `resource_adb` does **not** contain a `pipeline/IMS/` directory at all;
`SyncDepotItemData` was genuinely uncovered on Android before this migration.

Ten `Scroll` nodes were converted in total (the two above plus six in
`ItemTransfer.json`, one in `PullCountCalculator.json`, one in
`AutoEcoFarm/CommonNodes/AutoEcoFarmFindFarmland.json`); see
`migration_report.json` → `scroll_nodes[]` for each node's base params and
generated swipe. One was deliberately left alone — see §6.

### 3.3 `ESC` → Android Back

Ten `ClickKey key: 27` (Windows VK_ESCAPE) nodes are rewritten to
`ClickKey` with an **Android** keycode of `4` (`KEYCODE_BACK`). This follows
upstream's own live precedent: `assets/resource/pipeline/SceneManager/SceneCommon.json:5`
`__ScenePrivateAnyExit` is overridden in `resource_adb` to `ClickKey key 4`, and
that node is referenced from `assets/resource/pipeline/Interface/Scene.json:22`.

| node | file:line |
|---|---|
| `ImportBluePrintsExists` | `assets/resource/pipeline/ImportBluePrints.json:183` |
| `ItemTransferClickEscOrigin` | `assets/resource/pipeline/ItemTransfer.json:312` |
| `ItemTransferClickEscDestination` | `assets/resource/pipeline/ItemTransfer.json:932` |
| `ItemTransferClickEscOriginReturn` | `assets/resource/pipeline/ItemTransfer.json:1525` |
| `SeizeDeliveryJobsPressEscAfterMatch` | `assets/resource/pipeline/SeizeDeliveryJobs/SeizeDeliveryJobsEndpointFilter.json:42` |
| `SeizeDeliveryJobsPressEscAfterNotMatch` | `assets/resource/pipeline/SeizeDeliveryJobs/SeizeDeliveryJobsEndpointFilter.json:84` |
| `SharedZiplineDeleteDismissNoMapMind` | `assets/resource/pipeline/SharedZiplineDelete/Delete.json:178` |
| `_AutoEcoFarmExitTargetManager` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmFindFarmland.json:239` |
| `_AutoEcoFarmIsMrarking` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmFindFarmland.json:415` |
| `__MapNavigatorObstacleDevice_CancelMove` | `assets/resource/pipeline/MapNavigator/ObstacleDevice.json:320` |

Caveat carried honestly: upstream's `key: 4` is only "Back" when the ADB input
method is `AdbShell` (`input keyevent 4`). Under MaaFramework's `maatouch` input
method the same number is forwarded to the minitouch protocol as a Linux **evdev**
code, where `4` is `KEY_3`. This port targets the on-device Android Native
controller, where the value really is an `android.view.KeyEvent` keycode, so
`4` = `KEYCODE_BACK` is correct here — but it is correct *for this controller*,
not universally.

### 3.4 PC modifier keys → `DoNothing`

24 nodes that press a **PC-only modifier** (`Shift` 16, `Ctrl` 17, `Alt` 18/164)
as a `KeyDown`/`KeyUp` pair are rewritten to `DoNothing`, keeping their
`KeyDown`/`KeyUp` siblings' `next` chain intact so the accompanying `Click` or
`Swipe` still runs. On Android the same keycodes would mean `KEYCODE_9`,
`KEYCODE_STAR` and `KEYCODE_POUND`/`KEYCODE_STAR`, i.e. they would inject
unrelated keys into the game.

The 24 nodes: `AltClickProtosyncMenuButtonKeyDown`/`KeyUp`
(`Common/Button/ProtosyncMenuButton.json:20,42`),
`AltClickRegionalDevelopmentButtonKeyDown`/`KeyUp`
(`Common/Button/RegionalDevelopmentButton.json:23,45`),
`ProdManualOpenInMain`/`ProdManualOpenInMainDone` (`ProdManual.json:249,289`),
`StashBackpackUsableItemsLimitedDrag`/`…QuickMoveKeyUp`
(`StashBackpack.json:139,207`), `_AutoEcoFarmAltDown`/`AltUp`,
`_AutoEcoFarmChangeToExplore1`/`ChangeToExplore3`
(`AutoEcoFarm/CommonNodes/AutoEcoFarmSwipeToTarget.json:373,404,192,218`),
`_AutoEcoFarmSwipeToGround2`/`SwipeToGround4`
(`AutoEcoFarm/CommonNodes/AutoEcoFarmCommon.json:88,119`),
`_AutoEcoFarmWork1`/`Work3`
(`AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:237,263`),
`__AutoCtrlClickCtrlKeyDownAction`/`CtrlKeyUpAction`
(`Common/Private/AutoAltClick/Action.json:28,35`),
`__AutoFightActionEndSkillAltKeyDown`/`AltKeyUp` (`AutoFight/Action.json:83,90`),
`__CharacterControllerDeltaAltKeyDownAction`/`AltKeyUpAction`
(`Common/Private/CharacterController/Action.json:33,40`), and
`__MapNavigatorObstacleDevice_InteractPre`/`InteractPost`
(`MapNavigator/ObstacleDevice.json:17,54`).

Upstream precedent: `resource_adb/pipeline/Common/Private/AutoAltClick/Action.json`
does exactly this for the Alt node (`DoNothing`), and those nodes are live — they
are named from `agent/go-service/common/autoalt/ctrl_click.go`. A second
precedent, `resource_adb/pipeline/Common/Button/RegionalDevelopmentButton.json`,
goes further and replaces the chain's *entry* node with a plain `Click` and
empties its `next`, truncating the Alt chain entirely.

**This rewrite is restricted to `KeyDown`/`KeyUp` actions.** On `ClickKey` and
`LongPressKey` the same numeric key is a *keybinding*, not a modifier, and
neutralising it would delete a real action — `__AutoFightActionDodge` is
`ClickKey key 160` (VK_LSHIFT) and is deliberately left unresolved (§6) rather
than turned into a no-op.

Ideally the host app would resolve each of these into the correct on-device input
(`Swipe` for keyboard UI navigation, `TouchDown`+`TouchUp` for a key hold,
`LongPress` for a key-held-on-a-point, or a go-service `Custom` action for
anything needing real logic). That mapping cannot be derived mechanically from
the pipelines, so it is listed as a limitation instead of being guessed at.

## 4. What this migration does **not** fix

### 4.1 It is not a `relative_move` or `shell` fix

`AndroidNativeControlUnitAPI` also lacks `RelativeMovableUnit` and
`ShellableUnit`. MaaEnd's pipeline action histogram contains no `RelativeMove`
and no `Shell` node, so nothing was converted for them — but any future upstream
node using those actions will fail on Android in exactly the same way `Scroll`
did.

### 4.2 It does not make the agent-dependent tasks run

Measured from the generated pack by walking each task definition's entry point
through its node graph and collecting every `custom_action` / `custom_recognition`
name, then classifying against the 11 names registered by
`agent/cpp-algo/source/main.cpp:113-132`:

| | count |
|---|---|
| task definitions in the pack | 47 (spread over 68 task-fragment files) |
| …need **no** agent at all | 20 |
| …need **go-service only** | 21 |
| …need **go-service and cpp-algo** | 4 |
| …need **cpp-algo only** | 2 |

`MaaPocket/scripts/build_go_agent.py` builds `agent/go-service` for Android
(`GOOS=android`, cgo + NDK, producing `libMaaEnd_go_service.so`, with the agent's
cwd set to a bundle directory that must also contain a `maafw/` folder holding
the MaaFramework shared libraries). **There is no Android builder anywhere in
`MaaPocket/scripts/` for `agent/cpp-algo`.** Declaring it in `interface.json`
would give the host app an `agent[]` entry it cannot resolve. The 6 definitions
that depend on cpp-algo-provided names are therefore not runnable on device
regardless of the pipeline work in this migration:

| task | entry | cpp-algo names used |
|---|---|---|
| AutoEcoFarm | `AutoEcoFarmTask` | `MapLocateAssertLocation`, `MapNavigateAction` |
| GiftOperator | `GiftOperatorMain` | `MapLocateAssertLocation` |
| ItemTransfer | `ItemTransfer` | `IconRecognition` |
| RealTimeTask | `RealTimeTaskMain` | `RealTimeTaskAction` |
| SeizeDeliveryJobs | `SeizeDeliveryJobsCheckOngoingJob` | `MapFind` |
| ZiplineImport | `ZiplineImportMain` | `ZiplineImport` |

An earlier directory-level dependency scan of upstream (counting name usage
anywhere in each of the 71 tracked task JSONs, including inside option cases that
are not reachable from the task entry) reported *53 task directories needing
go-service and 15 also needing cpp-algo*. That figure and the table above measure
different things — directories-and-mentions versus definitions-and-reachability —
and both are true. The reachability figures above are the ones that describe what
a user pressing a task's start button will actually hit.

Note also that `ZiplineImport` is registered inside
`#ifdef MAAEND_HAVE_WEBVIEW2` upstream (it opens an embedded browser for login),
and that webview control only has a Windows implementation — so it is not merely
unbuilt for Android, it is Windows-only by construction.

The pack nonetheless ships `agent[]` with the go-service entry, because that is
the only agent Android can build; the host app is responsible for supplying it.

### 4.3 It does not change upstream's own "ADB unsupported" declarations

20 of the 47 task definitions carry a `controller` whitelist in their task
fragment that **omits `ADB`** — e.g. `assets/tasks/AutoEcoFarm.json` declares
`["Linux-Gamescope","Linux-ScreenCast","Linux-Wlroots","Win32-Front"]`. The full
list, recorded as `tasks.tasks_without_ADB_in_controller_whitelist` in
`migration_report.json`, is: `AccountSwitch`, `AeroSalvage`, `AutoEcoFarm`,
`BatchAddFriends`, `BatchDeleteFriends`, `BatchUseDetector`,
`ClaimSimulationRewards`, `CloseGamePC`, `ImportBluePrints`, `ItemTransfer`,
`ProtocolSpace`, `PuzzleSolver`, `RealTimeTask`, `ResourceRecycleStation`,
`SeizeDeliveryJobs`, `SharedZiplineDelete`, `StashBackpack`, `TrialOfSwordmancy`,
`WebEvent202605`, `ZiplineImport`.

In other words upstream itself considered these desktop-only. MaaPocket,
however, **does not enforce that whitelist**: `PiRepository.tasks()` and
`tasksByGroup()` filter by `group` only, and the parsed `PiTask.controller` field
is never consulted anywhere in `core/`. So these tasks *will* appear in the app's
UI. They were left unmodified here — the migration copies `assets/tasks/**`
verbatim — which means the app's real gate is the unresolved-node list in §6, not
the upstream whitelist. Treat upstream's exclusion as a strong hint that the
pipeline behind those tasks assumes a keyboard and mouse, not as a substitute for
the per-node analysis below.

## 5. What the host app must substitute

`interface.json` in this pack is schema-valid and internally consistent, but two
of its values are **placeholders that only the host app can resolve**:

1. **`agent[].child_exec` is `"agent/go-service"`, not a path.** MaaFramework
   spawns the agent as a child process and passes the identifier as the last
   argv. `scripts/build_go_agent.py` emits the Android artifact as
   `<dist>/agents/<abi>/libMaaEnd_go_service.so` with a bundle working directory
   of `<dist>/agents/<abi>/bundle/go-service`; the manifest it writes also uses
   `childExec: "agent/go-service"`. The host app must rewrite `child_exec` to the
   extracted native-library path (e.g.
   `File(applicationInfo.nativeLibraryDir, "libMaaEnd_go_service.so")`) and run
   the child with its cwd set to the bundle directory.
2. **The agent expects its resources beside it.** `agent/go-service/agent.go`
   hardcodes the MaaFramework library directory as `filepath.Join(getCwd(),
   "maafw")`, and `pkg/i18n/i18n.go` searches up to six levels up from both the
   cwd and the executable's directory for `locales/go-service` or
   `assets/locales/go-service`, requiring `zh_cn.json`. The cwd must also be
   **writable**: `logger.go` does `os.MkdirAll("debug", 0755)` and opens
   `debug/go-service.log`, and `log.Fatal()`s if that fails. The host must
   therefore place the MaaFramework shared libraries under `<workingDir>/maafw/`,
   make `locales/go-service/` and `locales/interface/` reachable, and point the
   cwd at a writable private directory. This script generates none of that.
   (`MaaPocket/docs/AGENTS.md` covers the agent-launch contract in full,
   including the `PI_*` environment variables that PI v2.5.0 clients should
   inject.)

The `controller` entry does **not** need substitution: it keeps
`type: "Adb"`, and MaaPocket substitutes the Android Native controller for
`Adb` at runtime.

## 6. Known limitations

Nothing below is converted. Each entry names the file and line of the upstream
node it comes from.

### 6.1 A 3D camera zoom that looks like a list scroll

| node | file:line | action | why it is not converted |
|---|---|---|---|
| `_AutoEcoFarmScroll` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmFindFarmland.json:251` | `Scroll {target:"[Anchor]_AutoEcoFarmFindEcoFarmAnchor", dy:240}` | Its own `desc` says the mouse is moved to the target and the wheel zooms the camera, and its `next` leads to `_AutoEcoFarmScaleMax`. A swipe would **rotate** the camera, not zoom. Android's equivalent is a two-finger pinch (`MultiSwipe` with `contact` 0 and 1 diverging), which the protocol does support — but the pinch centre is the node's `target`, an anchor *name* rather than a static coordinate, and the node is `DirectHit` so the hit box is empty and its centre cannot be resolved either. Generating a pinch here would mean inventing coordinates, so it is left for a human. |

### 6.2 Keyboard-driven nodes with no derivable Android equivalent (47 nodes)

All 47 are `ClickKey` / `KeyDown` / `KeyUp` / `LongPressKey` nodes whose Windows
VK code is either a movement key, a hotkey or a camera/interaction key. On
Android **every one of these numbers means something unrelated**
(`87`=W→`KEYCODE_MEDIA_NEXT`, `65`=A→`KEYCODE_ENVELOPE`, `69`=E→`KEYCODE_MINUS`,
`70`=F→`KEYCODE_EQUALS`, `75`=K→`KEYCODE_APOSTROPHE`, `112`–`115`=F1–F4→
`KEYCODE_FORWARD_DEL`/`CTRL_LEFT`/`CTRL_RIGHT`/`CAPS_LOCK`, `82`=R→`KEYCODE_MENU`,
`83`=S→`KEYCODE_NOTIFICATION`, `32`=Space→`KEYCODE_D`, `16`=Shift→`KEYCODE_9`,
`48`='0'→`KEYCODE_T`, `49`–`52`='1'–'4'→U/V/W/X, `160`=VK_LSHIFT→an Android media
key). Nothing was emitted, because a wrong substitution here is worse than a
missing one: the node would inject a random key into the game every time it ran.

**Movement (synthetic WASD — 21 nodes).** These assume a keyboard-driven
character controller. Android has no keyboard, so the honest fix is a rewrite to
touch-drag movement, which requires real UI knowledge of the in-game joystick and
cannot be produced from the pipeline alone.

| node | file:line | action, key |
|---|---|---|
| `_AutoEcoFarmJump` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:20` | `KeyDown` 87 (W) |
| `_AutoEcoFarmJump2` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:34` | `LongPressKey` 32 (Space) |
| `_AutoEcoFarmJump3` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:49` | `KeyUp` 87 (W) |
| `_AutoEcoFarmLastJump` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:73` | `KeyDown` 87 (W) |
| `_AutoEcoFarmLastJump2` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:87` | `LongPressKey` 32 (Space) |
| `_AutoEcoFarmLastJump3` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:102` | `KeyUp` 87 (W) |
| `_AutoEcoFarmCancelMove` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:309` | `KeyUp` 87 (W) |
| `_AutoEcoFarmJumpNotWork` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:329` | `KeyDown` 87 (W) |
| `_AutoEcoFarmJumpNotWork2` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:345` | `LongPressKey` 32 (Space) |
| `_AutoEcoFarmJumpNotWork3` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmMoveAndWork.json:360` | `KeyUp` 87 (W) |
| `_AutoEcoFarmMoveToTargetErr1` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmCommon.json:37` | `KeyUp` 87 (W) |
| `_AutoEcoFarmTurnAround1` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmSwipeToTarget.json:315` | `LongPressKey` 83 (S) |
| `__AutoFightActionMoveForwardKeyDown` | `assets/resource/pipeline/AutoFight/Action.json:111` | `KeyDown` 87 (W) |
| `__AutoFightActionMoveForwardKeyUp` | `assets/resource/pipeline/AutoFight/Action.json:118` | `KeyUp` 87 (W) |
| `__AutoFightActionMoveBackKeyDown` | `assets/resource/pipeline/AutoFight/Action.json:97` | `KeyDown` 83 (S) |
| `__AutoFightActionMoveBackKeyUp` | `assets/resource/pipeline/AutoFight/Action.json:104` | `KeyUp` 83 (S) |
| `__AutoFightActionMoveLeftKeyDown` | `assets/resource/pipeline/AutoFight/Action.json:125` | `KeyDown` 65 (A) |
| `__AutoFightActionMoveLeftKeyUp` | `assets/resource/pipeline/AutoFight/Action.json:132` | `KeyUp` 65 (A) |
| `__AutoFightActionMoveRightKeyDown` | `assets/resource/pipeline/AutoFight/Action.json:139` | `KeyDown` 68 (D) |
| `__AutoFightActionMoveRightKeyUp` | `assets/resource/pipeline/AutoFight/Action.json:146` | `KeyUp` 68 (D) |
| `_AutoEcoFarmEnterCameraModeFallback` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmPhotoMode.json:44` | `KeyDown` 82 (R, PC photo-mode hotkey) |
| `_AutoEcoFarmEnterCameraModeFallbackRelease` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmPhotoMode.json:108` | `KeyUp` 82 |
| `_AutoEcoFarmEnterCameraModeFallbackReleaseOnError` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmPhotoMode.json:122` | `KeyUp` 82 |

**Hotkeys (15 nodes).** Skill/character/dodge bindings. `AutoFight/Action.json`
already contains a working touch path in the same file
(`__AutoFightActionAttackClick` → `Click [600,320,80,80]`,
`__AutoFightActionAttackTouchDown` → `TouchDown`, `…AttackTouchUp` → `TouchUp`,
`__AutoFightActionLockTarget` → `Click` with `contact:2`), so a touch rebinding of
these is plausible — but the correct on-screen positions must be read off real
game footage, which is exactly the "do not author pipeline JSON without real
screenshots" rule this project inherits from upstream's `AGENTS.md`.

| node | file:line | action, key |
|---|---|---|
| `__AutoFightActionComboClick` | `assets/resource/pipeline/AutoFight/Action.json:29` | `ClickKey` 69 (E) |
| `__AutoFightActionAttackKeyPress` | `assets/resource/pipeline/AutoFight/Action.json:53` | `ClickKey` 48 ('0') |
| `__AutoFightActionDodge` | `assets/resource/pipeline/AutoFight/Action.json:35` | `ClickKey` 160 (VK_LSHIFT used as a binding) |
| `__AutoFightActionSkillOperators1` | `assets/resource/pipeline/AutoFight/Action.json:59` | `ClickKey` 49 |
| `__AutoFightActionSkillOperators2` | `assets/resource/pipeline/AutoFight/Action.json:65` | `ClickKey` 50 |
| `__AutoFightActionSkillOperators3` | `assets/resource/pipeline/AutoFight/Action.json:71` | `ClickKey` 51 |
| `__AutoFightActionSkillOperators4` | `assets/resource/pipeline/AutoFight/Action.json:77` | `ClickKey` 52 |
| `__AutoFightActionEndSkillOperators1` | `assets/resource/pipeline/AutoFight/Action.json:153` | `LongPressKey` 49, 1500 ms |
| `__AutoFightActionEndSkillOperators2` | `assets/resource/pipeline/AutoFight/Action.json:160` | `LongPressKey` 50, 1500 ms |
| `__AutoFightActionEndSkillOperators3` | `assets/resource/pipeline/AutoFight/Action.json:167` | `LongPressKey` 51, 1500 ms |
| `__AutoFightActionEndSkillOperators4` | `assets/resource/pipeline/AutoFight/Action.json:174` | `LongPressKey` 52, 1500 ms |
| `__AutoFightActionSwitchCharacterOperators1` | `assets/resource/pipeline/AutoFight/Action.json:181` | `ClickKey` 112 (F1) |
| `__AutoFightActionSwitchCharacterOperators2` | `assets/resource/pipeline/AutoFight/Action.json:187` | `ClickKey` 113 (F2) |
| `__AutoFightActionSwitchCharacterOperators3` | `assets/resource/pipeline/AutoFight/Action.json:193` | `ClickKey` 114 (F3) |
| `__AutoFightActionSwitchCharacterOperators4` | `assets/resource/pipeline/AutoFight/Action.json:199` | `ClickKey` 115 (F4) |

**Camera / interaction / menu keys (11 nodes).** PC-only entry points into
camera, real-time-task and menu UI. Each has a different reason:

| node | file:line | action, key | note |
|---|---|---|---|
| `AutoPickFalls` | `assets/resource/pipeline/RealTimeTask/AutoPick.json:2` | `ClickKey` 70 (F) | interactive-pick prompt key |
| `AutoPickInteractive` | `assets/resource/pipeline/RealTimeTask/AutoPick.json:51` | `ClickKey` 70 (F) | same |
| `RealTimeAutoPat` | `assets/resource/pipeline/RealTimeTask/AutoPat.json:2` | `ClickKey` 70 (F) | same |
| `RealTimeAutoZiplineClick` | `assets/resource/pipeline/RealTimeTask/AutoZipline.json:20` | `ClickKey` 69 (E) | same family |
| `ProtocolSpaceTouchExitTouch` | `assets/resource/pipeline/ProtocolSpace/InSpace.json:607` | `ClickKey` 70 (F) | same family |
| `ProtocolSpaceTouchExitTouchRetry` | `assets/resource/pipeline/ProtocolSpace/InSpace.json:656` | `ClickKey` 70 (F) | same |
| `__ScenePrivateWorldEnterMenuEmail` | `assets/resource/pipeline/SceneManager/SceneMenu.json:1981` | `ClickKey` 75 (K) | an on-screen menu button probably exists; not located |
| `__ScenePrivateWorldFactoryEnterMenuBlueprint` | `assets/resource/pipeline/SceneManager/SceneMenu.json:2040` | `ClickKey` 112 (F1) | same |
| `_AutoEcoFarmMoveToTargetErr2` | `assets/resource/pipeline/AutoEcoFarm/CommonNodes/AutoEcoFarmCommon.json:49` | `ClickKey` 16 (Shift used as a binding) | a real action, not a modifier |

Three tasks are therefore **known-broken**: their entry point reaches at least one
node from the list above. They are recorded as
`tasks.tasks_broken_by_unresolved_nodes` in `migration_report.json`:

| task file | entry | unresolved nodes reached |
|---|---|---|
| `tasks/AutoEcoFarm.json` | `AutoEcoFarmTask` | 15 |
| `tasks/ProtocolSpace.json` | `ProtocolSpaceSchedule` | 2 |
| `tasks/ImportBluePrints.json` | `ImportBluePrints` | 1 |

(A fourth task, `tasks/RealTimeTask.json`, reaches the `RealTimeTask` cpp-algo
action and is unbuildable for the reason in §4.2 rather than for a keycode.)

### 6.3 A note on what is *not* a limitation

The `resource_android` overlay deliberately contains no override for
`assets/resource/model/` — the `MaaEnd-AI` submodule that a partial clone does not
check out. That submodule is consumed only by cpp-algo (ONNX map-locator models
and navmesh data); no pipeline JSON references it, and `interface.json` never
mentions it. Its absence has no effect on the resource pack itself.

## 7. Verification

The pack validates against MaaFramework's own schemas — with a stdlib validator
built into the migration script (`--schemas`), and independently with
`jsonschema` 4.26.0:

```
interface.json  : 0 error(s)
note: 2 external $ref(s) stubbed as {} (files absent from the release copy)
pipeline files  : 519 checked, 0 failed
merged input    : 44 node(s) discovered under resource_android/pipeline
merged nodes    : 44 checked, 0 failed
VERDICT: ALL STRICT CHECKS PASS
```

The one caveat is in that note: `pipeline.schema.json` `$ref`s
`./custom.action.schema.json` and `./custom.recognition.schema.json`, and
MaaFramework's release copy of `tools/` ships only `interface.schema.json` and
`pipeline.schema.json`. Those two schemas are **not** available, so custom action
and custom recognition names could not be checked against their enums. (The
release copy's own versions only enumerate the demo plugin's names anyway, so
validating MaaEnd's ~89 custom names against them would produce mass false
errors.) Treat custom-name validity as **unverified**.

Also worth stating plainly: no part of this port was executed on a device. There
is no Android SDK in this environment and the game cannot be started here. The
`Scroll`→`Swipe` geometry is calibrated against upstream's own hand conversions
(and reproduces two of them to within 2 px), the keycode semantics are read out
of MaaPocket's own input bridge, and the merge semantics are read out of
MaaFramework's own parser — but the runtime behaviour of any individual converted
node is unverified.
