package com.maapocket.core.maafw

import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference

/**
 * A MaaFramework tasker: binds one [MaaResource] to one [MaaController] and runs pipeline entries
 * on that pair.
 *
 * A tasker is the unit of "one game session". MaaPocket creates a fresh one per run and destroys it
 * afterwards, because the resource bundle set differs per game and the controller's virtual display
 * is torn down with the session.
 */
class MaaTasker internal constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) : AutoCloseable {

    private val sinkIds = ArrayList<Long>(2)
    private val contextSinkIds = ArrayList<Long>(2)

    /** `true` once a resource and a controller are bound and the resource has finished loading. */
    val inited: Boolean get() = api.MaaTaskerInited(handle).toBool()

    /** `true` while any posted task is still running. */
    val running: Boolean get() = api.MaaTaskerRunning(handle).toBool()

    val stopping: Boolean get() = api.MaaTaskerStopping(handle).toBool()

    // ------------------------------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------------------------------

    fun bindResource(resource: MaaResource): Boolean =
        api.MaaTaskerBindResource(handle, resource.handle).toBool()

    fun bindController(controller: MaaController): Boolean =
        api.MaaTaskerBindController(handle, controller.handle).toBool()

    // ------------------------------------------------------------------------------------------
    // Sinks
    // ------------------------------------------------------------------------------------------

    /** Node-level notifications for a specific task. */
    fun addSink(callback: MaaEventCallback): Long {
        val id = api.MaaTaskerAddSink(handle, callback, null)
        sinkIds.add(id)
        return id
    }

    fun removeSink(sinkId: Long, callback: MaaEventCallback) {
        api.MaaTaskerRemoveSink(handle, sinkId)
        sinkIds.remove(sinkId)
        MaaCallbackRegistry.release(callback)
    }

    /**
     * Context-level notifications, including the `Node.*` messages that carry recognition details.
     *
     * The framework emits these on a different channel than [addSink]; a progress UI wants both.
     */
    fun addContextSink(callback: MaaEventCallback): Long {
        val id = api.MaaTaskerAddContextSink(handle, callback, null)
        contextSinkIds.add(id)
        return id
    }

    fun removeContextSink(sinkId: Long, callback: MaaEventCallback) {
        api.MaaTaskerRemoveContextSink(handle, sinkId)
        contextSinkIds.remove(sinkId)
        MaaCallbackRegistry.release(callback)
    }

    // ------------------------------------------------------------------------------------------
    // Running tasks
    // ------------------------------------------------------------------------------------------

    /**
     * Posts a pipeline entry.
     *
     * [pipelineOverrideJson] is a `node -> partial node` patch scoped to this one task; pass `null`
     * for none. Prefer [MaaResource.overridePipeline] for patches that should apply to every task,
     * and this parameter for per-task tweaks.
     */
    fun postTask(entry: String, pipelineOverrideJson: String? = null): Long =
        api.MaaTaskerPostTask(handle, entry, pipelineOverrideJson)

    /**
     * Posts [entry] and blocks until it settles.
     *
     * Uses polling rather than `MaaTaskerWait` so that a wedged helper process surfaces as a
     * timeout instead of an indefinite hang. Returns the final `MaaStatus`
     * ([MaaDef.MaaStatus_Invalid] on timeout).
     */
    fun runTask(
        entry: String,
        pipelineOverrideJson: String? = null,
        timeoutMs: Long = 30 * 60_000L,
        onStart: (Long) -> Unit = {},
    ): Int {
        val id = postTask(entry, pipelineOverrideJson)
        onStart(id)
        return await(id, timeoutMs)
    }

    /** Polls until [id] settles. [MaaDef.MaaStatus_Invalid] means it timed out. */
    fun await(id: Long, timeoutMs: Long): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val status = api.MaaTaskerStatus(handle, id)
            if (MaaDef.isTerminal(status)) return status
            if (System.nanoTime() >= deadline) return MaaDef.MaaStatus_Invalid
            Thread.sleep(20L)
        }
    }

    /** Requests an orderly stop. The running task finishes its current node chain and returns. */
    fun postStop(): Long = api.MaaTaskerPostStop(handle)

    /** Stops and waits for the in-flight task to actually come to rest. */
    fun stopAndWait(timeoutMs: Long = 60_000L): Boolean {
        if (!running) return true
        postStop()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!stopping && !running) return true
            Thread.sleep(20L)
        }
        return false
    }

    // ------------------------------------------------------------------------------------------
    // Introspection
    // ------------------------------------------------------------------------------------------

    /**
     * The most recent `MaaNodeId` for [nodeName] in this session, or [MaaDef.MaaInvalidId] when the
     * node has never run — which is the fastest way to answer "did the pack actually reach the
     * mail screen?".
     */
    fun latestNode(nodeName: String): Long {
        val out = LongByReference(MaaDef.MaaInvalidId)
        return if (api.MaaTaskerGetLatestNode(handle, nodeName, out).toBool()) out.value else MaaDef.MaaInvalidId
    }

    /** Applies an additional patch to an already-posted task, mid-flight. */
    fun overridePipeline(taskId: Long, pipelineOverrideJson: String): Boolean =
        api.MaaTaskerOverridePipeline(handle, taskId, pipelineOverrideJson).toBool()

    /** Drops cached recognition results. Required after the game's UI changes underneath us. */
    fun clearCache(): Boolean = api.MaaTaskerClearCache(handle).toBool()

    override fun close() {
        runCatching { api.MaaTaskerClearSinks(handle) }
        sinkIds.clear()
        runCatching { api.MaaTaskerClearContextSinks(handle) }
        contextSinkIds.clear()
        api.MaaTaskerDestroy(handle)
    }

    companion object {
        fun create(api: MaaFrameworkApi): MaaTasker {
            val handle = api.MaaTaskerCreate()
                ?: throw IllegalStateException("MaaTaskerCreate() returned null")
            return MaaTasker(api, handle)
        }
    }
}
