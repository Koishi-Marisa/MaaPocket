package com.maapocket.core.maafw

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.io.File

/**
 * A MaaFramework resource: the pipeline/image/model bundles that describe *what to do*.
 *
 * ### Bundle layering
 *
 * `MaaResourcePostBundle` appends a bundle root to an ordered chain; **later bundles override
 * earlier ones**, per node and per image. MaaPocket relies on this: the game pack ships the base
 * tree, then each `attach_resource_path` overlay from the interface descriptor is posted after it.
 * The order comes from [com.maapocket.core.pi.PiSelection.resolve], which puts the shared
 * `resource` entries first and the controller-specific overlays last.
 *
 * Bundles must be posted and awaited **before** the tasker starts, because the framework resolves
 * the pipeline graph at load time.
 */
class MaaResource internal constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) : AutoCloseable {

    private val sinkIds = ArrayList<Long>(2)

    /** `true` once at least one bundle has finished loading. */
    val loaded: Boolean get() = api.MaaResourceLoaded(handle).toBool()

    // ------------------------------------------------------------------------------------------
    // Sinks
    // ------------------------------------------------------------------------------------------

    fun addSink(callback: MaaEventCallback): Long {
        val id = api.MaaResourceAddSink(handle, callback, null)
        sinkIds.add(id)
        return id
    }

    fun removeSink(sinkId: Long, callback: MaaEventCallback) {
        api.MaaResourceRemoveSink(handle, sinkId)
        sinkIds.remove(sinkId)
        MaaCallbackRegistry.release(callback)
    }

    // ------------------------------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------------------------------

    /** Queues a bundle root (a directory containing `pipeline/`, `image/`, `model/`). */
    fun postBundle(root: File): Long = api.MaaResourcePostBundle(handle, root.absolutePath)

    fun postOcrModel(root: File): Long = api.MaaResourcePostOcrModel(handle, root.absolutePath)
    fun postPipeline(root: File): Long = api.MaaResourcePostPipeline(handle, root.absolutePath)
    fun postImage(root: File): Long = api.MaaResourcePostImage(handle, root.absolutePath)

    /**
     * Posts every bundle in [roots], in order, and waits for each to settle before posting the
     * next.
     *
     * Sequential on purpose: the framework merges bundles in the order the loads *complete*, and
     * posting them all up front would make overlay precedence depend on thread scheduling.
     *
     * @return the first root that failed to load, or `null` when all of them loaded.
     */
    fun loadAll(roots: List<File>, timeoutMs: Long = 600_000L): File? {
        for (root in roots) {
            val id = postBundle(root)
            if (await(id, timeoutMs) != MaaDef.MaaStatus_Succeeded) return root
        }
        return null
    }

    /** Polls until [id] settles. [MaaDef.MaaStatus_Invalid] means it timed out. */
    fun await(id: Long, timeoutMs: Long = 600_000L): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val status = api.MaaResourceStatus(handle, id)
            if (MaaDef.isTerminal(status)) return status
            if (System.nanoTime() >= deadline) return MaaDef.MaaStatus_Invalid
            Thread.sleep(10L)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Overrides
    // ------------------------------------------------------------------------------------------

    /**
     * Applies a `node -> partial node` JSON patch to the loaded pipeline.
     *
     * This is the on-device equivalent of a Project Interface `pipeline_override`. MaaPocket applies
     * the patches from [com.maapocket.core.pi.PiSelection.resolve] one at a time and in order, so a
     * later option can still adjust a node an earlier option already touched.
     */
    fun overridePipeline(pipelineOverrideJson: String): Boolean =
        api.MaaResourceOverridePipeline(handle, pipelineOverrideJson).toBool()

    /** Replaces a node's `next` list. */
    fun overrideNext(nodeName: String, next: List<String>): Boolean =
        MaaStringListBuffer.of(api, next).use { list ->
            api.MaaResourceOverrideNext(handle, nodeName, list.handle).toBool()
        }

    /** Replaces a template image by name with an in-memory buffer. */
    fun overrideImage(imageName: String, image: MaaImageBuffer): Boolean =
        api.MaaResourceOverrideImage(handle, imageName, image.handle).toBool()

    // ------------------------------------------------------------------------------------------
    // Inspection
    // ------------------------------------------------------------------------------------------

    /** Every node name known to the loaded bundles — the first thing to check when a task "does nothing". */
    fun nodeList(): List<String> = MaaStringListBuffer.create(api).use { list ->
        if (api.MaaResourceGetNodeList(handle, list.handle).toBool()) list.toList() else emptyList()
    }

    /** The recognition/action names contributed by in-process registrations (not agents). */
    fun customRecognitionList(): List<String> = MaaStringListBuffer.create(api).use { list ->
        if (api.MaaResourceGetCustomRecognitionList(handle, list.handle).toBool()) list.toList() else emptyList()
    }

    fun customActionList(): List<String> = MaaStringListBuffer.create(api).use { list ->
        if (api.MaaResourceGetCustomActionList(handle, list.handle).toBool()) list.toList() else emptyList()
    }

    /** Serialised definition of one node, for bug reports. */
    fun nodeData(nodeName: String): String = MaaStringBuffer.scoped(api) { buffer ->
        if (api.MaaResourceGetNodeData(handle, nodeName, buffer.handle).toBool()) buffer.get() else ""
    }

    /** A content hash of the loaded bundles; changes whenever the pack changes. */
    fun hash(): String = MaaStringBuffer.scoped(api) { buffer ->
        if (api.MaaResourceGetHash(handle, buffer.handle).toBool()) buffer.get() else ""
    }

    fun setInferenceDevice(deviceId: Int): Boolean {
        val mem = Memory(4)
        mem.setInt(0, deviceId)
        return api.MaaResourceSetOption(handle, MaaDef.MaaResOption_InferenceDevice, mem, 4).toBool()
    }

    fun setInferenceExecutionProvider(provider: Int): Boolean {
        val mem = Memory(4)
        mem.setInt(0, provider)
        return api.MaaResourceSetOption(handle, MaaDef.MaaResOption_InferenceExecutionProvider, mem, 4).toBool()
    }

    /** Unloads everything. The next task needs a fresh [loadAll]. */
    fun clear(): Boolean = api.MaaResourceClear(handle)

    override fun close() {
        api.MaaResourceClearSinks(handle)
        sinkIds.clear()
        api.MaaResourceDestroy(handle)
    }

    companion object {
        fun create(api: MaaFrameworkApi): MaaResource {
            val handle = api.MaaResourceCreate()
                ?: throw IllegalStateException("MaaResourceCreate() returned null")
            return MaaResource(api, handle)
        }
    }
}
