package com.maapocket.core.maafw

import com.sun.jna.Pointer
import java.io.File

/**
 * A MaaFramework agent client — the host half of the out-of-process extension mechanism.
 *
 * ### Why MaaPocket needs this
 *
 * The three migrated packs are not pure pipeline JSON. MaaEnd in particular dispatches ~40% of its
 * action nodes to two agent executables (`agent/go-service`, a Go program, and `agent/cpp-algo`, a
 * C++/OpenCV/ONNX program). Those run as **child processes** and register their custom recognitions
 * and actions back into the framework over a socket. Without an agent client the pipeline loads
 * fine and then stalls on the first `"type": "Custom"` node.
 *
 * ### Handshake
 *
 * 1. Create the client. Passing `null` makes the framework mint an identifier (a socket path).
 * 2. Read it back with [identifier].
 * 3. Spawn the agent executable with that identifier as its **last argument**.
 * 4. Call [connect] and wait for [alive].
 *
 * The agent side calls `MaaAgentServerStartUp(identifier)` then `MaaAgentServerJoin()`. The command
 * line is assembled by [com.maapocket.core.pi.PiSelection] from the interface descriptor's
 * `agent[].child_exec` / `child_args`.
 */
class MaaAgentClient internal constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) : AutoCloseable {

    /** The socket identifier the agent process must be handed. */
    fun identifier(): String = MaaStringBuffer.scoped(api) { buffer ->
        if (api.MaaAgentClientIdentifier(handle, buffer.handle).toBool()) buffer.get() else ""
    }

    /**
     * Binds the resource the agent will extend.
     *
     * Must be called **before** [connect]: the agent registers its custom actions against a bound
     * resource and they are lost otherwise.
     */
    fun bindResource(resource: MaaResource): Boolean =
        api.MaaAgentClientBindResource(handle, resource.handle).toBool()

    fun registerResourceSink(resource: MaaResource): Boolean =
        api.MaaAgentClientRegisterResourceSink(handle, resource.handle).toBool()

    fun registerControllerSink(controller: MaaController): Boolean =
        api.MaaAgentClientRegisterControllerSink(handle, controller.handle).toBool()

    fun registerTaskerSink(tasker: MaaTasker): Boolean =
        api.MaaAgentClientRegisterTaskerSink(handle, tasker.handle).toBool()

    /** Connects to the already-spawned agent. Blocking; give it a few seconds. */
    fun connect(): Boolean = api.MaaAgentClientConnect(handle).toBool()

    fun disconnect(): Boolean = api.MaaAgentClientDisconnect(handle).toBool()

    val connected: Boolean get() = api.MaaAgentClientConnected(handle).toBool()

    /** `true` while the child process is still answering. Poll this to detect a crashed agent. */
    val alive: Boolean get() = api.MaaAgentClientAlive(handle).toBool()

    /** Connection timeout, in milliseconds. */
    fun setTimeout(milliseconds: Long): Boolean =
        api.MaaAgentClientSetTimeout(handle, milliseconds).toBool()

    /** Custom recognition names the agent registered — empty means the handshake did not complete. */
    fun customRecognitionList(): List<String> = MaaStringListBuffer.create(api).use { list ->
        if (api.MaaAgentClientGetCustomRecognitionList(handle, list.handle).toBool()) list.toList()
        else emptyList()
    }

    fun customActionList(): List<String> = MaaStringListBuffer.create(api).use { list ->
        if (api.MaaAgentClientGetCustomActionList(handle, list.handle).toBool()) list.toList()
        else emptyList()
    }

    override fun close() {
        runCatching { if (connected) disconnect() }
        api.MaaAgentClientDestroy(handle)
    }

    companion object {
        /**
         * Creates a client.
         *
         * @param identifier `null` to let the framework mint one (read it back with [identifier]),
         *   or an explicit socket path / port string.
         */
        fun create(api: MaaFrameworkApi, identifier: String? = null): MaaAgentClient {
            val handle = if (identifier.isNullOrEmpty()) {
                api.MaaAgentClientCreateV2(null)
            } else {
                MaaStringBuffer.create(api).use { buffer ->
                    buffer.set(identifier)
                    api.MaaAgentClientCreateV2(buffer.handle)
                }
            } ?: throw IllegalStateException("MaaAgentClientCreateV2() returned null")
            return MaaAgentClient(api, handle)
        }

        /**
         * Creates a client and returns both it and the resolved identifier, which is what the agent
         * child process has to be launched with.
         */
        fun createWithIdentifier(api: MaaFrameworkApi): Pair<MaaAgentClient, String> {
            val client = create(api)
            return client to client.identifier()
        }
    }
}

/**
 * Layout of one agent executable inside the installed pack.
 *
 * MaaFwApp's convention, which MaaPocket follows because the packs are authored against it: an agent
 * is a single-file ELF whose file name must start with `lib` and end in `.so` (so it can live in
 * `jniLibs/`), optionally accompanied by a `bundle/` directory of interpreter trees.
 */
data class MaaAgentRuntime(
    /** Absolute path of the executable. */
    val executable: File,
    /** Arguments from the interface descriptor, in order. */
    val args: List<String>,
    /** Directory to set as `LD_LIBRARY_PATH` / `MAAFW_BINARY_PATH`; usually the native lib dir. */
    val nativeLibDir: File? = null,
    /** Working directory; the pack root, so relative asset paths resolve. */
    val workingDir: File? = null,
)
