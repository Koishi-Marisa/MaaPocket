package com.maafw.naruto.maa

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * MaaFramework C API 的 JNA 绑定
 * 对应 include/MaaFramework/MaaAPI.h
 */
interface MaaFrameworkLib : Library {

    companion object {
        val INSTANCE: MaaFrameworkLib by lazy {
            Native.load("MaaFramework", MaaFrameworkLib::class.java)
        }
    }

    // Global
    fun MaaGlobalSetOption(key: Int, value: Pointer?, valueSize: Long): Byte
    fun MaaGlobalLoadPlugin(libraryPath: String?): Byte
    fun MaaVersion(): String?

    // Buffer
    fun MaaStringBufferCreate(): Pointer
    fun MaaStringBufferDestroy(handle: Pointer)
    fun MaaStringBufferGet(handle: Pointer): String?
    fun MaaStringBufferSize(handle: Pointer): Long
    fun MaaStringBufferSet(handle: Pointer, str: String?): Byte
    fun MaaStringBufferSetEx(handle: Pointer, str: Pointer?, size: Long): Byte

    // ImageBuffer
    fun MaaImageBufferCreate(): Pointer
    fun MaaImageBufferDestroy(handle: Pointer)
    fun MaaImageBufferIsEmpty(handle: Pointer): Byte
    fun MaaImageBufferClear(handle: Pointer): Byte
    fun MaaImageBufferWidth(handle: Pointer): Int
    fun MaaImageBufferHeight(handle: Pointer): Int
    fun MaaImageBufferSetEncoded(handle: Pointer, data: Pointer?, size: Long): Byte
    fun MaaImageBufferGetEncodedSize(handle: Pointer): Long

    // Rect
    fun MaaRectCreate(): Pointer
    fun MaaRectDestroy(handle: Pointer)
    fun MaaRectSet(handle: Pointer, x: Int, y: Int, w: Int, h: Int): Byte
    fun MaaRectGetX(handle: Pointer): Int
    fun MaaRectGetY(handle: Pointer): Int
    fun MaaRectGetW(handle: Pointer): Int
    fun MaaRectGetH(handle: Pointer): Int

    // Resource
    fun MaaResourceCreate(): Pointer
    fun MaaResourceDestroy(res: Pointer)
    fun MaaResourcePostBundle(res: Pointer, path: String?): Long
    fun MaaResourcePostOcrModel(res: Pointer, path: String?): Long
    fun MaaResourcePostPipeline(res: Pointer, path: String?): Long
    fun MaaResourcePostImage(res: Pointer, path: String?): Long
    fun MaaResourceStatus(res: Pointer, id: Long): Int
    fun MaaResourceWait(res: Pointer, id: Long): Int
    fun MaaResourceLoaded(res: Pointer): Byte
    fun MaaResourceRegisterCustomAction(res: Pointer, name: String?, action: MaaCustomActionCallback?, transArg: Pointer?): Byte
    fun MaaResourceRegisterCustomRecognition(res: Pointer, name: String?, recognition: MaaCustomRecognitionCallback?, transArg: Pointer?): Byte
    fun MaaResourceClearCustomAction(res: Pointer): Byte

    // Controller
    fun MaaAndroidNativeControllerCreate(configJson: String?): Pointer
    fun MaaControllerDestroy(ctrl: Pointer)
    fun MaaControllerPostConnection(ctrl: Pointer): Long
    fun MaaControllerStatus(ctrl: Pointer, id: Long): Int
    fun MaaControllerWait(ctrl: Pointer, id: Long): Int
    fun MaaControllerConnected(ctrl: Pointer): Byte
    fun MaaControllerGetResolution(ctrl: Pointer, width: Pointer?, height: Pointer?): Byte
    fun MaaControllerPostClick(ctrl: Pointer, x: Int, y: Int): Long
    fun MaaControllerWaitClick(ctrl: Pointer, id: Long): Byte

    // Tasker
    fun MaaTaskerCreate(): Pointer
    fun MaaTaskerDestroy(tasker: Pointer)
    fun MaaTaskerBindResource(tasker: Pointer, res: Pointer): Byte
    fun MaaTaskerBindController(tasker: Pointer, ctrl: Pointer): Byte
    fun MaaTaskerInited(tasker: Pointer): Byte
    fun MaaTaskerPostTask(tasker: Pointer, entry: String?, pipelineOverride: String?): Long
    fun MaaTaskerStatus(tasker: Pointer, id: Long): Int
    fun MaaTaskerWait(tasker: Pointer, id: Long): Int
    fun MaaTaskerRunning(tasker: Pointer): Byte
    fun MaaTaskerPostStop(tasker: Pointer): Long
    fun MaaTaskerStopping(tasker: Pointer): Byte
    fun MaaTaskerClearCache(tasker: Pointer): Byte
    fun MaaTaskerGetRecognitionDetail(
        tasker: Pointer, recoId: Long,
        nodeName: Pointer?, algorithm: Pointer?, hit: IntByReference?, box: Pointer?,
        detailJson: Pointer?, raw: Pointer?, draws: Pointer?
    ): Byte

    // Context（custom action 内使用）
    fun MaaContextRunTask(context: Pointer, entry: String?, pipelineOverride: String?): Long
    fun MaaContextRunRecognition(context: Pointer, entry: String?, pipelineOverride: String?, image: Pointer?): Long
    fun MaaContextRunRecognitionDirect(context: Pointer, recoType: String?, recoParam: String?, image: Pointer?): Long
    fun MaaContextRunAction(context: Pointer, entry: String?, pipelineOverride: String?, box: Pointer?, recoDetail: String?): Long
    fun MaaContextRunActionDirect(context: Pointer, actionType: String?, actionParam: String?, box: Pointer?, recoDetail: String?): Long
    fun MaaContextWaitFreezes(context: Pointer, time: Long, box: Pointer?, waitFreezesParam: String?): Byte
    fun MaaContextOverridePipeline(context: Pointer, pipelineOverride: String?): Byte
    fun MaaContextOverrideNext(context: Pointer, nodeName: String?, next: Pointer?): Byte
    fun MaaContextGetTaskId(context: Pointer): Long
    fun MaaContextGetTasker(context: Pointer): Pointer
    fun MaaContextClearHitCount(context: Pointer, nodeName: String?): Byte
    fun MaaContextGetHitCount(context: Pointer, nodeName: String?, count: IntByReference?): Byte
    fun MaaContextGetNodeData(context: Pointer, nodeName: String?, outData: Pointer?): Byte
    fun MaaContextGetAnchor(context: Pointer, anchorName: String?, outNodeName: Pointer?): Byte

    // StringListBuffer（OverrideNext 等需要）
    fun MaaStringListBufferCreate(): Pointer
    fun MaaStringListBufferDestroy(handle: Pointer)
    fun MaaStringListBufferAppend(handle: Pointer, str: String?): Byte

    // Callback sink (optional, used to receive events)
fun MaaTaskerAddSink(tasker: Pointer, sink: MaaEventCallback?, transArg: Pointer?): Long
fun MaaTaskerClearSinks(tasker: Pointer)
fun MaaTaskerAddContextSink(tasker: Pointer, sink: MaaEventCallback?, transArg: Pointer?): Long
fun MaaTaskerClearContextSinks(tasker: Pointer)
fun MaaControllerAddSink(ctrl: Pointer, sink: MaaEventCallback?, transArg: Pointer?): Long
    fun MaaControllerClearSinks(ctrl: Pointer)
    fun MaaResourceAddSink(res: Pointer, sink: MaaEventCallback?, transArg: Pointer?): Long
    fun MaaResourceClearSinks(res: Pointer)
}

/**
 * MaaFramework 事件回调
 * C 签名：void (*)(void* handle, const char* message, const char* details_json, void* trans_arg)
 */
interface MaaEventCallback : com.sun.jna.Callback {
    fun invoke(handle: Pointer?, message: String?, detailsJson: String?, transArg: Pointer?)
}

/**
 * MaaFramework 自定义动作回调
 * C 签名：
 * MaaBool (*)(MaaContext* context, MaaTaskId task_id, const char* node_name,
 *             const char* custom_action_name, const char* custom_action_param,
 *             MaaRecoId reco_id, const MaaRect* box, void* trans_arg)
 * 返回 true = 动作成功，false = 动作失败（会导致 pipeline 节点失败）
 */
interface MaaCustomActionCallback : com.sun.jna.Callback {
    fun invoke(
        context: Pointer?,
        taskId: Long,
        nodeName: String?,
        actionName: String?,
        actionParam: String?,
        recoId: Long,
        box: Pointer?,
        transArg: Pointer?
    ): Byte
}

/**
 * MaaFramework 自定义识别回调
 * C 签名：
 * MaaBool (*)(MaaContext* context, MaaTaskId task_id, const char* node_name,
 *             const char* custom_recognition_name, const char* custom_recognition_param,
 *             MaaImageBufferHandle image, MaaRectHandle box, void* trans_arg,
 *             MaaRectHandle out_box, MaaStringBufferHandle out_detail)
 * 返回 true = 识别命中（应设置 out_box），false = 未命中
 */
interface MaaCustomRecognitionCallback : com.sun.jna.Callback {
    fun invoke(
        context: Pointer?,
        taskId: Long,
        nodeName: String?,
        recoName: String?,
        recoParam: String?,
        image: Pointer?,
        box: Pointer?,
        transArg: Pointer?,
        outBox: Pointer?,
        outDetail: Pointer?
    ): Byte
}