package com.maapocket.core.maafw

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference

/**
 * JNA binding for **MaaFramework v5.14.2** (`libMaaFramework.so`).
 *
 * Every declaration here is transcribed from the headers shipped inside
 * `MAA-android-aarch64-v5.14.2.zip` (`include/MaaFramework/**`). Names, parameter order and
 * types are kept byte-identical to C so that a future framework release breaking the ABI
 * shows up as a link failure rather than silent corruption.
 *
 * Type mapping (see [MaaDef] for the constant mirrors):
 *
 * | C                        | Kotlin            | JNA                        |
 * |--------------------------|-------------------|----------------------------|
 * | `MaaBool` (`uint8_t`)    | [Byte]            | `byte`                     |
 * | `MaaSize` (`uint64_t`)   | [Long]            | `long`                     |
 * | `MaaId` and friends      | [Long]            | `long`                     |
 * | opaque `Maa*` handle     | [Pointer]         | `void*`                    |
 * | `MaaOptionValue`         | [Pointer]         | `void*`                    |
 * | `MaaOptionValueSize`     | [Long]            | `uint64_t`                 |
 *
 * **`MaaBool` is deliberately [Byte], not [Boolean].** JNA maps a Java `boolean` onto a 4-byte
 * C `int`; reading a 1-byte `uint8_t` return through a 4-byte load picks up whatever the upper
 * bits of the return register happen to hold. Use `value.toInt() != 0` to test.
 *
 * The library is loaded by [MaaFw.ensureLoaded], never by touching this interface directly.
 */
interface MaaFrameworkApi : Library {

    // ==========================================================================
    // include/MaaFramework/Utility/MaaUtility.h
    // ==========================================================================

    /** `const char* MaaVersion()` — the framework version string, owned by the framework. */
    fun MaaVersion(): String?

    // ==========================================================================
    // include/MaaFramework/Global/MaaGlobal.h
    // ==========================================================================

    /**
     * `MaaBool MaaGlobalSetOption(MaaGlobalOption key, MaaOptionValue value, MaaOptionValueSize val_size)`
     *
     * Process-global, so it must be set before any resource/controller is created. Use the
     * typed helpers on [MaaFw] rather than calling this directly.
     */
    fun MaaGlobalSetOption(key: Int, value: Pointer?, valSize: Long): Byte

    /** `MaaBool MaaGlobalLoadPlugin(const char* library_path)` — full path, bare name, or a directory. */
    fun MaaGlobalLoadPlugin(libraryPath: String?): Byte

    // ==========================================================================
    // include/MaaFramework/Utility/MaaBuffer.h — string
    // ==========================================================================

    fun MaaStringBufferCreate(): Pointer?
    fun MaaStringBufferDestroy(handle: Pointer?)
    fun MaaStringBufferIsEmpty(handle: Pointer?): Byte
    fun MaaStringBufferClear(handle: Pointer?): Byte

    /** `const char* MaaStringBufferGet(...)` — **borrowed** pointer into the buffer's own storage. */
    fun MaaStringBufferGet(handle: Pointer?): String?
    fun MaaStringBufferSize(handle: Pointer?): Long
    fun MaaStringBufferSet(handle: Pointer?, str: String?): Byte
    fun MaaStringBufferSetEx(handle: Pointer?, str: String?, size: Long): Byte

    // ==========================================================================
    // include/MaaFramework/Utility/MaaBuffer.h — string list
    // ==========================================================================

    fun MaaStringListBufferCreate(): Pointer?
    fun MaaStringListBufferDestroy(handle: Pointer?)
    fun MaaStringListBufferIsEmpty(handle: Pointer?): Byte
    fun MaaStringListBufferSize(handle: Pointer?): Long

    /** Returns a **borrowed** `MaaStringBuffer*` owned by the list; do not destroy it. */
    fun MaaStringListBufferAt(handle: Pointer?, index: Long): Pointer?
    fun MaaStringListBufferAppend(handle: Pointer?, value: Pointer?): Byte
    fun MaaStringListBufferRemove(handle: Pointer?, index: Long): Byte
    fun MaaStringListBufferClear(handle: Pointer?): Byte

    // ==========================================================================
    // include/MaaFramework/Utility/MaaBuffer.h — image
    // ==========================================================================

    fun MaaImageBufferCreate(): Pointer?
    fun MaaImageBufferDestroy(handle: Pointer?)
    fun MaaImageBufferIsEmpty(handle: Pointer?): Byte
    fun MaaImageBufferClear(handle: Pointer?): Byte

    /** `void* MaaImageBufferGetRawData(...)` — a `cv::Mat`'s pixel storage, **borrowed**. */
    fun MaaImageBufferGetRawData(handle: Pointer?): Pointer?
    fun MaaImageBufferWidth(handle: Pointer?): Int
    fun MaaImageBufferHeight(handle: Pointer?): Int
    fun MaaImageBufferChannels(handle: Pointer?): Int

    /** OpenCV `Mat::type()`, e.g. `CV_8UC3` == 16. */
    fun MaaImageBufferType(handle: Pointer?): Int

    /**
     * `MaaBool MaaImageBufferSetRawData(MaaImageBuffer*, void* data, int32_t width, int32_t height, int32_t type)`
     *
     * Copies `width * height * channels(type)` bytes out of [data]. Returns the width actually used.
     */
    fun MaaImageBufferSetRawData(handle: Pointer?, data: Pointer?, width: Int, height: Int, type: Int): Byte
    fun MaaImageBufferResize(handle: Pointer?, width: Int, height: Int): Byte

    /** PNG/JPEG bytes, **borrowed**. Pair with [MaaImageBufferGetEncodedSize]. */
    fun MaaImageBufferGetEncoded(handle: Pointer?): Pointer?
    fun MaaImageBufferGetEncodedSize(handle: Pointer?): Long
    fun MaaImageBufferSetEncoded(handle: Pointer?, data: Pointer?, size: Long): Byte

    // ==========================================================================
    // include/MaaFramework/Utility/MaaBuffer.h — rect
    // ==========================================================================

    fun MaaRectCreate(): Pointer?
    fun MaaRectDestroy(handle: Pointer?)
    fun MaaRectGetX(handle: Pointer?): Int
    fun MaaRectGetY(handle: Pointer?): Int
    fun MaaRectGetW(handle: Pointer?): Int
    fun MaaRectGetH(handle: Pointer?): Int
    fun MaaRectSet(handle: Pointer?, x: Int, y: Int, w: Int, h: Int): Byte

    // ==========================================================================
    // include/MaaFramework/Instance/MaaController.h
    //
    // Only the creators MaaPocket can actually use on-device are declared. The framework still
    // exports MaaAdbControllerCreate / MaaWin32ControllerCreate / ... but those control units are
    // not shipped in the Android release zip, so calling them would fail at dlopen time anyway.
    // ==========================================================================

    /**
     * `MaaController* MaaAndroidNativeControllerCreate(const char* config_json)`
     *
     * `config_json` keys: `library_path` (our own external lib — `libbridge.so`),
     * `screen_resolution.{width,height}`, `display_id` (default 0), `force_stop` (default false).
     *
     * The framework requires the control unit's reported raw frame resolution to equal
     * `screen_resolution` exactly; it does **not** remap touch coordinates on this path.
     */
    fun MaaAndroidNativeControllerCreate(configJson: String?): Pointer?

    fun MaaControllerDestroy(ctrl: Pointer?)

    fun MaaControllerAddSink(ctrl: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long
    fun MaaControllerRemoveSink(ctrl: Pointer?, sinkId: Long)
    fun MaaControllerClearSinks(ctrl: Pointer?)

    /** `value` is a raw byte array: `int*`, `char*` or `bool*` depending on [key]. */
    fun MaaControllerSetOption(ctrl: Pointer?, key: Int, value: Pointer?, valSize: Long): Byte

    fun MaaControllerPostConnection(ctrl: Pointer?): Long
    fun MaaControllerPostClick(ctrl: Pointer?, x: Int, y: Int): Long

    /** `contact` = finger id (>= 0) / mouse button (0 left, 1 right, 2 middle). */
    fun MaaControllerPostClickV2(ctrl: Pointer?, x: Int, y: Int, contact: Int, pressure: Int): Long

    fun MaaControllerPostSwipe(
        ctrl: Pointer?, x1: Int, y1: Int, x2: Int, y2: Int, duration: Int,
    ): Long

    fun MaaControllerPostSwipeV2(
        ctrl: Pointer?, x1: Int, y1: Int, x2: Int, y2: Int, duration: Int,
        contact: Int, pressure: Int,
    ): Long

    fun MaaControllerPostClickKey(ctrl: Pointer?, keycode: Int): Long
    fun MaaControllerPostInputText(ctrl: Pointer?, text: String?): Long
    fun MaaControllerPostStartApp(ctrl: Pointer?, intent: String?): Long
    fun MaaControllerPostStopApp(ctrl: Pointer?, intent: String?): Long

    fun MaaControllerPostTouchDown(ctrl: Pointer?, contact: Int, x: Int, y: Int, pressure: Int): Long
    fun MaaControllerPostTouchMove(ctrl: Pointer?, contact: Int, x: Int, y: Int, pressure: Int): Long
    fun MaaControllerPostTouchUp(ctrl: Pointer?, contact: Int): Long

    fun MaaControllerPostKeyDown(ctrl: Pointer?, keycode: Int): Long
    fun MaaControllerPostKeyUp(ctrl: Pointer?, keycode: Int): Long
    fun MaaControllerPostScreencap(ctrl: Pointer?): Long
    fun MaaControllerPostScroll(ctrl: Pointer?, dx: Int, dy: Int): Long
    fun MaaControllerPostInactive(ctrl: Pointer?): Long

    fun MaaControllerStatus(ctrl: Pointer?, id: Long): Int

    /** Blocks until the action settles. Returns a `MaaStatus`. */
    fun MaaControllerWait(ctrl: Pointer?, id: Long): Int

    fun MaaControllerConnected(ctrl: Pointer?): Byte
    fun MaaControllerCachedImage(ctrl: Pointer?, buffer: Pointer?): Byte
    fun MaaControllerGetUuid(ctrl: Pointer?, buffer: Pointer?): Byte
    fun MaaControllerGetResolution(ctrl: Pointer?, width: IntByReference?, height: IntByReference?): Byte
    fun MaaControllerGetInfo(ctrl: Pointer?, buffer: Pointer?): Byte

    // ==========================================================================
    // include/MaaFramework/Instance/MaaResource.h
    // ==========================================================================

    fun MaaResourceCreate(): Pointer?
    fun MaaResourceDestroy(res: Pointer?)
    fun MaaResourceAddSink(res: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long
    fun MaaResourceRemoveSink(res: Pointer?, sinkId: Long)
    fun MaaResourceClearSinks(res: Pointer?)

    /**
     * `MaaResId MaaResourcePostBundle(MaaResource* res, const char* path)`
     *
     * `path` is the **root** of a bundle: the framework looks for `pipeline/`, `image/`,
     * `model/` and `default_pipeline.json` beneath it. Multiple bundles form a layering chain;
     * later ones win.
     */
    fun MaaResourcePostBundle(res: Pointer?, path: String?): Long

    fun MaaResourcePostOcrModel(res: Pointer?, path: String?): Long
    fun MaaResourcePostPipeline(res: Pointer?, path: String?): Long
    fun MaaResourcePostImage(res: Pointer?, path: String?): Long

    fun MaaResourceOverridePipeline(res: Pointer?, pipelineOverride: String?): Byte
    fun MaaResourceOverrideNext(res: Pointer?, nodeName: String?, nextList: Pointer?): Byte
    fun MaaResourceOverrideImage(res: Pointer?, imageName: String?, image: Pointer?): Byte
    fun MaaResourceGetNodeData(res: Pointer?, nodeName: String?, buffer: Pointer?): Byte
    fun MaaResourceClear(res: Pointer?): Byte
    fun MaaResourceStatus(res: Pointer?, id: Long): Int
    fun MaaResourceWait(res: Pointer?, id: Long): Int
    fun MaaResourceLoaded(res: Pointer?): Byte
    fun MaaResourceSetOption(res: Pointer?, key: Int, value: Pointer?, valSize: Long): Byte
    fun MaaResourceGetHash(res: Pointer?, buffer: Pointer?): Byte
    fun MaaResourceGetNodeList(res: Pointer?, buffer: Pointer?): Byte

    // ==========================================================================
    // include/MaaFramework/Instance/MaaTasker.h
    // ==========================================================================

    fun MaaTaskerCreate(): Pointer?
    fun MaaTaskerDestroy(tasker: Pointer?)
    fun MaaTaskerAddSink(tasker: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long
    fun MaaTaskerRemoveSink(tasker: Pointer?, sinkId: Long)
    fun MaaTaskerClearSinks(tasker: Pointer?)
    fun MaaTaskerAddContextSink(tasker: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long
    fun MaaTaskerRemoveContextSink(tasker: Pointer?, sinkId: Long)
    fun MaaTaskerClearContextSinks(tasker: Pointer?)

    fun MaaTaskerSetOption(tasker: Pointer?, key: Int, value: Pointer?, valSize: Long): Byte
    fun MaaTaskerBindResource(tasker: Pointer?, res: Pointer?): Byte
    fun MaaTaskerBindController(tasker: Pointer?, ctrl: Pointer?): Byte
    fun MaaTaskerInited(tasker: Pointer?): Byte

    /**
     * `MaaTaskId MaaTaskerPostTask(MaaTasker* tasker, const char* entry, const char* pipeline_override)`
     *
     * `pipeline_override` is a JSON object of `node -> partial node` patches applied for the
     * duration of this task. Pass `null` or `"{}"` for none.
     */
    fun MaaTaskerPostTask(tasker: Pointer?, entry: String?, pipelineOverride: String?): Long

    fun MaaTaskerStatus(tasker: Pointer?, id: Long): Int
    fun MaaTaskerWait(tasker: Pointer?, id: Long): Int
    fun MaaTaskerRunning(tasker: Pointer?): Byte
    fun MaaTaskerPostStop(tasker: Pointer?): Long
    fun MaaTaskerStopping(tasker: Pointer?): Byte
    fun MaaTaskerGetResource(tasker: Pointer?): Pointer?
    fun MaaTaskerGetController(tasker: Pointer?): Pointer?
    fun MaaTaskerClearCache(tasker: Pointer?): Byte
    fun MaaTaskerOverridePipeline(tasker: Pointer?, taskId: Long, pipelineOverride: String?): Byte

    /**
     * `MaaBool MaaTaskerGetLatestNode(const MaaTasker*, const char* node_name, MaaNodeId* latest_id)`
     *
     * `latest_id` stays 0 (`MaaInvalidId`) when the node has never run in the current session.
     */
    fun MaaTaskerGetLatestNode(tasker: Pointer?, nodeName: String?, latestId: LongByReference?): Byte

    // ==========================================================================
    // include/MaaAgentClient/MaaAgentClientAPI.h
    //
    // Agents are the out-of-process extension mechanism: a child executable calls
    // MaaAgentServerStartUp(identifier) and the framework connects back to it. MaaEnd needs this
    // for its Go and C++ agents.
    // ==========================================================================

    /** `MaaAgentClient* MaaAgentClientCreateV2(const MaaStringBuffer* identifier)` — note the buffer. */
    fun MaaAgentClientCreateV2(identifier: Pointer?): Pointer?
    fun MaaAgentClientDestroy(client: Pointer?)
    fun MaaAgentClientIdentifier(client: Pointer?, identifier: Pointer?): Byte
    fun MaaAgentClientBindResource(client: Pointer?, res: Pointer?): Byte
    fun MaaAgentClientRegisterResourceSink(client: Pointer?, res: Pointer?): Byte
    fun MaaAgentClientRegisterControllerSink(client: Pointer?, ctrl: Pointer?): Byte
    fun MaaAgentClientRegisterTaskerSink(client: Pointer?, tasker: Pointer?): Byte
    fun MaaAgentClientConnect(client: Pointer?): Byte
    fun MaaAgentClientDisconnect(client: Pointer?): Byte
    fun MaaAgentClientConnected(client: Pointer?): Byte
    fun MaaAgentClientAlive(client: Pointer?): Byte
    fun MaaAgentClientSetTimeout(client: Pointer?, milliseconds: Long): Byte
    fun MaaAgentClientGetCustomRecognitionList(client: Pointer?, buffer: Pointer?): Byte
    fun MaaAgentClientGetCustomActionList(client: Pointer?, buffer: Pointer?): Byte
}

/**
 * `typedef void(MAA_CALL* MaaEventCallback)(void* handle, const char* message, const char* details_json, void* trans_arg)`
 *
 * Invoked from framework worker threads, not from the thread that registered it. JNA attaches
 * those threads automatically, but **the callback object must be kept strongly reachable** for as
 * long as the sink is registered or the native side will call into freed memory — see
 * [MaaCallbackRegistry].
 */
fun interface MaaEventCallback : Callback {
    fun invoke(handle: Pointer?, message: String?, detailsJson: String?, transArg: Pointer?)
}
