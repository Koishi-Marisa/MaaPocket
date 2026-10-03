package com.maapocket.core.maafw

/**
 * Kotlin mirror of `MaaFramework/MaaDef.h` (and the `MaaMsg.h` message strings) from
 * **MaaFramework v5.14.2**.
 *
 * Only members that actually exist in the headers are declared here. Enumerators that are
 * commented out in the header (`MaaGlobalOption_Recording = 3`, `MaaGlobalOption_ShowHitDraw = 5`,
 * `MaaCtrlOption_Recording = 5`, `MaaStatus_Timeout = 5000`) are deliberately **not** mirrored,
 * so that a compilation failure is the signal if a future framework version re-enables them.
 *
 * Types are chosen to match the C typedefs exactly:
 * - C `int32_t` enums  -> Kotlin [Int]   (`MaaOption`, `MaaStatus`, `MaaCtrlOption`, ...)
 * - C `uint64_t` flags -> Kotlin [Long]  (`MaaAdbScreencapMethod`, `MaaGamepadButton`, ...)
 * - C `uint8_t`        -> Kotlin [Byte]  (`MaaBool`)
 * - C `int64_t` ids    -> Kotlin [Long]  (`MaaId`, `MaaSize` is `uint64_t` -> [Long])
 *
 * Bitmask flags are stored as **signed** [Long]s, exactly like `uint64_t` reinterpreted through
 * a two's-complement register; `MaaAdbScreencapMethod_All`, for instance, is `-1L` (= `~0ULL`).
 * Always combine them with `or` / `and`; never compare them numerically.
 */
object MaaDef {

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : scalar typedefs
    // ---------------------------------------------------------------------------------------

    /** `#define MaaNullSize UINT64_MAX` — the `MaaSize` equivalent of "not set". */
    const val MaaNullSize: Long = -1L

    /** `#define MaaInvalidId ((MaaId)0)` — returned by `MaaContextRun*` / `MaaTaskerPost*` on failure. */
    const val MaaInvalidId: Long = 0L

    /** `MaaBool` is `uint8_t`; use these two instead of raw `1`/`0` for readability. */
    const val MaaBool_True: Byte = 1

    /** `MaaBool` is `uint8_t`; use these two instead of raw `1`/`0` for readability. */
    const val MaaBool_False: Byte = 0

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : enum MaaStatusEnum  (MaaStatus = int32_t)
    // ---------------------------------------------------------------------------------------

    const val MaaStatus_Invalid: Int = 0
    const val MaaStatus_Pending: Int = 1000
    const val MaaStatus_Running: Int = 2000
    const val MaaStatus_Succeeded: Int = 3000
    const val MaaStatus_Failed: Int = 4000
    // MaaStatus_Timeout = 5000 is commented out in MaaDef.h; do not add it here.

    /** `status == MaaStatus_Succeeded || status == MaaStatus_Failed` — no further transition. */
    fun isTerminal(status: Int): Boolean =
        status == MaaStatus_Succeeded || status == MaaStatus_Failed

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : enum MaaLoggingLevelEnum  (MaaLoggingLevel = int32_t)
    // ---------------------------------------------------------------------------------------

    const val MaaLoggingLevel_Off: Int = 0
    const val MaaLoggingLevel_Fatal: Int = 1
    const val MaaLoggingLevel_Error: Int = 2
    const val MaaLoggingLevel_Warn: Int = 3
    const val MaaLoggingLevel_Info: Int = 4
    const val MaaLoggingLevel_Debug: Int = 5
    const val MaaLoggingLevel_Trace: Int = 6
    const val MaaLoggingLevel_All: Int = 7

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : enum MaaGlobalOptionEnum  (MaaGlobalOption = MaaOption = int32_t)
    // ---------------------------------------------------------------------------------------

    const val MaaGlobalOption_Invalid: Int = 0

    /** value: string, eg: `"/sdcard/log"`; val_size: string length (see [MaaFw.setLogDir]). */
    const val MaaGlobalOption_LogDir: Int = 1

    /** value: bool; val_size: `sizeof(bool)` = 1. */
    const val MaaGlobalOption_SaveDraw: Int = 2

    /** value: `MaaLoggingLevel`; val_size: `sizeof(MaaLoggingLevel)` = 4. */
    const val MaaGlobalOption_StdoutLevel: Int = 4

    /** value: bool; val_size: 1. */
    const val MaaGlobalOption_DebugMode: Int = 6

    /** value: bool; val_size: 1. */
    const val MaaGlobalOption_SaveOnError: Int = 7

    /** value: int (0..100, default 85); val_size: 4. */
    const val MaaGlobalOption_DrawQuality: Int = 8

    /** value: `size_t` (default 4096); val_size: 8 on 64-bit ABI. */
    const val MaaGlobalOption_RecoImageCacheLimit: Int = 9

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaInferenceDevice / MaaInferenceExecutionProvider  (both int32_t)
    // ---------------------------------------------------------------------------------------

    const val MaaInferenceDevice_CPU: Int = -2
    const val MaaInferenceDevice_Auto: Int = -1
    const val MaaInferenceDevice_0: Int = 0
    const val MaaInferenceDevice_1: Int = 1

    const val MaaInferenceExecutionProvider_Auto: Int = 0
    const val MaaInferenceExecutionProvider_CPU: Int = 1
    const val MaaInferenceExecutionProvider_DirectML: Int = 2
    const val MaaInferenceExecutionProvider_CoreML: Int = 3
    const val MaaInferenceExecutionProvider_CUDA: Int = 4

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : enum MaaResOptionEnum  (MaaResOption = MaaOption = int32_t)
    // ---------------------------------------------------------------------------------------

    const val MaaResOption_Invalid: Int = 0

    /** value: `MaaInferenceDevice`; val_size: 4. Set **before** loading models. */
    const val MaaResOption_InferenceDevice: Int = 1

    /** value: `MaaInferenceExecutionProvider`; val_size: 4. Set **before** loading models. */
    const val MaaResOption_InferenceExecutionProvider: Int = 2

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : enum MaaCtrlOptionEnum  (MaaCtrlOption = MaaOption = int32_t)
    // ---------------------------------------------------------------------------------------

    const val MaaCtrlOption_Invalid: Int = 0

    /** value: int; val_size: 4. Mutually exclusive with short-side / expand. */
    const val MaaCtrlOption_ScreenshotTargetLongSide: Int = 1

    /** value: int; val_size: 4. Mutually exclusive with long-side / expand. */
    const val MaaCtrlOption_ScreenshotTargetShortSide: Int = 2

    /** value: bool; val_size: 1. */
    const val MaaCtrlOption_ScreenshotUseRawSize: Int = 3

    /** value: bool; val_size: 1. Win32-only. */
    const val MaaCtrlOption_MouseLockFollow: Int = 4

    /** value: int, `cv::InterpolationFlags` (0 NEAREST .. 4 LANCZOS4, default 3 AREA); val_size: 4. */
    const val MaaCtrlOption_ScreenshotResizeMethod: Int = 6

    /** value: `int32_t[]` of virtual-key codes; val_size: `4 * count`. Win32-only. */
    const val MaaCtrlOption_BackgroundManagedKeys: Int = 7

    /** value: `int32_t[2] = { width, height }`; val_size: 8. Unity Canvas "Expand" semantics. */
    const val MaaCtrlOption_ScreenshotTargetExpand: Int = 8

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : enum MaaTaskerOptionEnum  (MaaTaskerOption = MaaOption = int32_t)
    // ---------------------------------------------------------------------------------------

    /** The only member: `MaaTaskerSetOption` has no configurable option in v5.14.2. */
    const val MaaTaskerOption_Invalid: Int = 0

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaAdbScreencapMethod  (uint64_t bit flags)
    // ---------------------------------------------------------------------------------------

    const val MaaAdbScreencapMethod_EncodeToFileAndPull: Long = 1L
    const val MaaAdbScreencapMethod_Encode: Long = 1L shl 1          // 2
    const val MaaAdbScreencapMethod_RawWithGzip: Long = 1L shl 2     // 4
    const val MaaAdbScreencapMethod_RawByNetcat: Long = 1L shl 3     // 8
    const val MaaAdbScreencapMethod_MinicapDirect: Long = 1L shl 4   // 16
    const val MaaAdbScreencapMethod_MinicapStream: Long = 1L shl 5   // 32
    const val MaaAdbScreencapMethod_EmulatorExtras: Long = 1L shl 6  // 64
    const val MaaAdbScreencapMethod_None: Long = 0L

    /** `#define MaaAdbScreencapMethod_All (~MaaAdbScreencapMethod_None)` == `~0ULL` == `-1L`. */
    const val MaaAdbScreencapMethod_All: Long = -1L

    /**
     * `All & ~RawByNetcat & ~MinicapDirect & ~MinicapStream`
     * == `~0b111000` == `~(8 | 16 | 32)` == `~(56)` == `-57L`.
     */
    const val MaaAdbScreencapMethod_Default: Long = -57L

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaAdbInputMethod  (uint64_t bit flags)
    // ---------------------------------------------------------------------------------------

    const val MaaAdbInputMethod_AdbShell: Long = 1L
    const val MaaAdbInputMethod_MinitouchAndAdbKey: Long = 1L shl 1  // 2
    const val MaaAdbInputMethod_Maatouch: Long = 1L shl 2            // 4
    const val MaaAdbInputMethod_EmulatorExtras: Long = 1L shl 3      // 8
    const val MaaAdbInputMethod_None: Long = 0L

    /** `#define MaaAdbInputMethod_All (~MaaAdbInputMethod_None)` == `-1L`. */
    const val MaaAdbInputMethod_All: Long = -1L

    /** `All & ~EmulatorExtras` == `~(8)` == `-9L`. */
    const val MaaAdbInputMethod_Default: Long = -9L

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaWin32ScreencapMethod  (uint64_t bit flags) — desktop only, mirrored for
    // completeness because MaaController.h exposes Win32 creators.
    // ---------------------------------------------------------------------------------------

    const val MaaWin32ScreencapMethod_None: Long = 0L
    const val MaaWin32ScreencapMethod_GDI: Long = 1L                 // 1
    const val MaaWin32ScreencapMethod_FramePool: Long = 1L shl 1     // 2
    const val MaaWin32ScreencapMethod_DXGI_DesktopDup: Long = 1L shl 2        // 4
    const val MaaWin32ScreencapMethod_DXGI_DesktopDup_Window: Long = 1L shl 3 // 8
    const val MaaWin32ScreencapMethod_PrintWindow: Long = 1L shl 4   // 16
    const val MaaWin32ScreencapMethod_ScreenDC: Long = 1L shl 5      // 32

    /** `~0ULL`. */
    const val MaaWin32ScreencapMethod_All: Long = -1L

    /** `DXGI_DesktopDup_Window | ScreenDC` == `8 | 32` == `40L`. */
    const val MaaWin32ScreencapMethod_Foreground: Long = 40L

    /** `FramePool | PrintWindow` == `2 | 16` == `18L`. */
    const val MaaWin32ScreencapMethod_Background: Long = 18L

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaWin32InputMethod  (uint64_t bit flags, select ONE)
    // ---------------------------------------------------------------------------------------

    const val MaaWin32InputMethod_None: Long = 0L
    const val MaaWin32InputMethod_Seize: Long = 1L                                       // 1
    const val MaaWin32InputMethod_SendMessage: Long = 1L shl 1                           // 2
    const val MaaWin32InputMethod_PostMessage: Long = 1L shl 2                           // 4
    const val MaaWin32InputMethod_LegacyEvent: Long = 1L shl 3                           // 8

    /** Deprecated in MaaDef.h. */
    const val MaaWin32InputMethod_PostThreadMessage: Long = 1L shl 4                     // 16

    const val MaaWin32InputMethod_SendMessageWithCursorPos: Long = 1L shl 5              // 32
    const val MaaWin32InputMethod_PostMessageWithCursorPos: Long = 1L shl 6              // 64
    const val MaaWin32InputMethod_SendMessageWithWindowPos: Long = 1L shl 7              // 128
    const val MaaWin32InputMethod_PostMessageWithWindowPos: Long = 1L shl 8              // 256
    const val MaaWin32InputMethod_Interception: Long = 1L shl 9                          // 512
    const val MaaWin32InputMethod_AnchoredTouch: Long = 1L shl 10                        // 1024

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaMacOSScreencapMethod / MaaMacOSInputMethod  (uint64_t)
    // ---------------------------------------------------------------------------------------

    const val MaaMacOSScreencapMethod_None: Long = 0L
    const val MaaMacOSScreencapMethod_ScreenCaptureKit: Long = 1L

    const val MaaMacOSInputMethod_None: Long = 0L
    const val MaaMacOSInputMethod_GlobalEvent: Long = 1L
    const val MaaMacOSInputMethod_PostToPid: Long = 1L shl 1

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaLinuxScreencapMethod / MaaLinuxInputMethod  (uint64_t)
    // ---------------------------------------------------------------------------------------

    const val MaaLinuxScreencapMethod_None: Long = 0L
    const val MaaLinuxScreencapMethod_Wlr: Long = 1L
    const val MaaLinuxScreencapMethod_ExtImage: Long = 1L shl 1
    const val MaaLinuxScreencapMethod_PipeWire: Long = 1L shl 2

    const val MaaLinuxInputMethod_None: Long = 0L
    const val MaaLinuxInputMethod_Wlr: Long = 1L
    const val MaaLinuxInputMethod_UInput: Long = 1L shl 1
    const val MaaLinuxInputMethod_Libei: Long = 1L shl 2

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaGamepadType / MaaGamepadButton / MaaGamepadTouch  (uint64_t)
    // ---------------------------------------------------------------------------------------

    const val MaaGamepadType_Xbox360: Long = 0L
    const val MaaGamepadType_DualShock4: Long = 1L

    const val MaaGamepadButton_A: Long = 0x1000L
    const val MaaGamepadButton_B: Long = 0x2000L
    const val MaaGamepadButton_X: Long = 0x4000L
    const val MaaGamepadButton_Y: Long = 0x8000L
    const val MaaGamepadButton_LB: Long = 0x0100L
    const val MaaGamepadButton_RB: Long = 0x0200L
    const val MaaGamepadButton_LEFT_THUMB: Long = 0x0040L
    const val MaaGamepadButton_RIGHT_THUMB: Long = 0x0080L
    const val MaaGamepadButton_START: Long = 0x0010L
    const val MaaGamepadButton_BACK: Long = 0x0020L
    const val MaaGamepadButton_GUIDE: Long = 0x0400L
    const val MaaGamepadButton_DPAD_UP: Long = 0x0001L
    const val MaaGamepadButton_DPAD_DOWN: Long = 0x0002L
    const val MaaGamepadButton_DPAD_LEFT: Long = 0x0004L
    const val MaaGamepadButton_DPAD_RIGHT: Long = 0x0008L

    // DualShock 4 aliases to the Xbox face buttons (same numeric values).
    const val MaaGamepadButton_CROSS: Long = MaaGamepadButton_A
    const val MaaGamepadButton_CIRCLE: Long = MaaGamepadButton_B
    const val MaaGamepadButton_SQUARE: Long = MaaGamepadButton_X
    const val MaaGamepadButton_TRIANGLE: Long = MaaGamepadButton_Y
    const val MaaGamepadButton_L1: Long = MaaGamepadButton_LB
    const val MaaGamepadButton_R1: Long = MaaGamepadButton_RB
    const val MaaGamepadButton_L3: Long = MaaGamepadButton_LEFT_THUMB
    const val MaaGamepadButton_R3: Long = MaaGamepadButton_RIGHT_THUMB
    const val MaaGamepadButton_OPTIONS: Long = MaaGamepadButton_START
    const val MaaGamepadButton_SHARE: Long = MaaGamepadButton_BACK

    // DS4-only buttons.
    const val MaaGamepadButton_PS: Long = 0x10000L
    const val MaaGamepadButton_TOUCHPAD: Long = 0x20000L

    const val MaaGamepadTouch_LeftStick: Long = 0L
    const val MaaGamepadTouch_RightStick: Long = 1L
    const val MaaGamepadTouch_LeftTrigger: Long = 2L
    const val MaaGamepadTouch_RightTrigger: Long = 3L

    // ---------------------------------------------------------------------------------------
    // MaaDef.h : MaaControllerFeature  (uint64_t bit flags, returned by custom controllers)
    //
    // NOTE: the typedef in the header is spelled `MaaControllerFeature`, not `MaaCtrlFeature`.
    // ---------------------------------------------------------------------------------------

    const val MaaControllerFeature_None: Long = 0L
    const val MaaControllerFeature_UseMouseDownAndUpInsteadOfClick: Long = 1L
    const val MaaControllerFeature_UseKeyboardDownAndUpInsteadOfClick: Long = 1L shl 1
    const val MaaControllerFeature_NoScalingTouchPoints: Long = 1L shl 2

    // ---------------------------------------------------------------------------------------
    // MaaMsg.h : callback message strings
    // ---------------------------------------------------------------------------------------

    /** Message string constants from `MaaFramework/MaaMsg.h`. */
    object Msg {
        const val Resource_Loading_Starting = "Resource.Loading.Starting"
        const val Resource_Loading_Succeeded = "Resource.Loading.Succeeded"
        const val Resource_Loading_Failed = "Resource.Loading.Failed"

        const val Controller_Action_Starting = "Controller.Action.Starting"
        const val Controller_Action_Succeeded = "Controller.Action.Succeeded"
        const val Controller_Action_Failed = "Controller.Action.Failed"

        const val Tasker_Task_Starting = "Tasker.Task.Starting"
        const val Tasker_Task_Succeeded = "Tasker.Task.Succeeded"
        const val Tasker_Task_Failed = "Tasker.Task.Failed"

        const val Node_PipelineNode_Starting = "Node.PipelineNode.Starting"
        const val Node_PipelineNode_Succeeded = "Node.PipelineNode.Succeeded"
        const val Node_PipelineNode_Failed = "Node.PipelineNode.Failed"

        const val Node_RecognitionNode_Starting = "Node.RecognitionNode.Starting"
        const val Node_RecognitionNode_Succeeded = "Node.RecognitionNode.Succeeded"
        const val Node_RecognitionNode_Failed = "Node.RecognitionNode.Failed"

        const val Node_ActionNode_Starting = "Node.ActionNode.Starting"
        const val Node_ActionNode_Succeeded = "Node.ActionNode.Succeeded"
        const val Node_ActionNode_Failed = "Node.ActionNode.Failed"

        const val Node_NextList_Starting = "Node.NextList.Starting"
        const val Node_NextList_Succeeded = "Node.NextList.Succeeded"
        const val Node_NextList_Failed = "Node.NextList.Failed"

        const val Node_Recognition_Starting = "Node.Recognition.Starting"
        const val Node_Recognition_Succeeded = "Node.Recognition.Succeeded"
        const val Node_Recognition_Failed = "Node.Recognition.Failed"

        const val Node_Action_Starting = "Node.Action.Starting"
        const val Node_Action_Succeeded = "Node.Action.Succeeded"
        const val Node_Action_Failed = "Node.Action.Failed"

        const val Node_WaitFreezes_Starting = "Node.WaitFreezes.Starting"
        const val Node_WaitFreezes_Succeeded = "Node.WaitFreezes.Succeeded"
        const val Node_WaitFreezes_Failed = "Node.WaitFreezes.Failed"
    }
}
