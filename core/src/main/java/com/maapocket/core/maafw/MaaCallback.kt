package com.maapocket.core.maafw

import com.sun.jna.Pointer

/**
 * Strong-reference holder for native event callbacks.
 *
 * JNA passes a callback into native code as a function pointer into a trampoline it owns. If the
 * Kotlin object backing that trampoline becomes garbage-collectable, the framework's worker
 * threads will eventually call into freed memory. Registering a sink with
 * `MaaControllerAddSink` / `MaaResourceAddSink` / `MaaTaskerAddSink` therefore has to keep a live
 * reference for exactly as long as the sink is registered — which is what this object is for.
 *
 * Call [release] after `Maa*RemoveSink` (or after the owning object is destroyed and its sinks are
 * guaranteed never to fire again). Forgetting to release leaks one small object per session; that
 * is the deliberate direction to err in.
 */
object MaaCallbackRegistry {

    private val refs = ArrayList<MaaEventCallback>(8)

    @Synchronized
    fun keep(callback: MaaEventCallback): MaaEventCallback {
        refs.add(callback)
        return callback
    }

    @Synchronized
    fun release(callback: MaaEventCallback) {
        refs.remove(callback)
    }

    /** Number of currently pinned callbacks — diagnostics only. */
    @Synchronized
    fun size(): Int = refs.size
}

/**
 * One framework notification, as delivered to a sink.
 *
 * [details] is the raw `details_json` string. It is kept as a string rather than parsed into a
 * tree because the shape differs per [message] and callers only ever pick a couple of fields;
 * [MaaNotification.field] does that lookup on demand.
 */
class MaaNotification(
    val message: String,
    val details: String,
) {
    /** Cheap substring probe, e.g. `field("\"task_id\":")`. */
    fun field(key: String): String? {
        val at = details.indexOf(key)
        if (at < 0) return null
        var i = details.indexOf(':', at + key.length).takeIf { it >= 0 } ?: return null
        i++
        while (i < details.length && details[i].isWhitespace()) i++
        if (i >= details.length) return null
        return when (details[i]) {
            '"' -> details.substring(i + 1, details.indexOf('"', i + 1).takeIf { it > i } ?: return null)
            else -> {
                var j = i
                while (j < details.length && details[j] !in ",}") j++
                details.substring(i, j).trim()
            }
        }
    }

    override fun toString(): String = "$message $details"
}

/** Convenience constructors for sinks that only need the message string. */
object MaaSinks {

    /**
     * Wraps [onEvent] in a pinned [MaaEventCallback].
     *
     * The callback body swallows every throwable: JNA propagates an exception thrown inside a
     * callback back through the native frame, which on Android means a hard abort with no useful
     * stack. Reporting a failed event is never worth aborting the engine for.
     */
    fun create(onEvent: (MaaNotification) -> Unit): MaaEventCallback {
        val callback = MaaEventCallback { _, message, details, _ ->
            runCatching { onEvent(MaaNotification(message ?: "", details ?: "")) }
                .onFailure { MaaCallbackLog.dropped(it) }
        }
        return MaaCallbackRegistry.keep(callback)
    }

    /** A sink that only records the message strings, for progress display. */
    fun messages(onMessage: (String) -> Unit): MaaEventCallback =
        create { onMessage(it.message) }
}

/**
 * Sink for failures raised *inside* a callback.
 *
 * Deliberately dependency-free: `timber` and `android.util.Log` are both fine in the app process,
 * but this class is also on the classpath of the bare `app_process` helper where the logging stack
 * may not be initialised yet.
 */
internal object MaaCallbackLog {
    @Volatile private var lastMessage: String? = null

    fun dropped(error: Throwable) {
        val text = "${error.javaClass.simpleName}: ${error.message}"
        if (text == lastMessage) return
        lastMessage = text
        runCatching { android.util.Log.w("MaaSink", "callback threw, event dropped: $text") }
    }
}

/** Marker so [Pointer] shows up in this file's imports even when only the alias below is used. */
internal typealias MaaHandle = Pointer
