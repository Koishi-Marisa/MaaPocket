package com.maapocket.core.maafw

import android.util.Log
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * Process-wide entry point for `libMaaFramework.so`.
 *
 * ### Where this runs
 *
 * MaaFramework is loaded **inside the privileged helper process**, not in the app process. The
 * app talks to it over the remote session (see `com.maapocket.core.privilege`). That matters here
 * only for one thing: `jna.tmpdir` has to be a directory the helper's uid can actually write, so
 * [ensureLoaded] prefers `/data/local/tmp` when it is writable and otherwise falls back to the
 * caller-supplied directory.
 *
 * ### Why a manual `Native.load` rather than a `@JvmStatic` singleton
 *
 * `Native.load` throws `UnsatisfiedLinkError` when the `.so` is missing or when a JNI symbol
 * named in `RegisterNatives` cannot be resolved. Callers need to surface that as a build/packaging
 * error message ("the MaaFramework `.so` set was not installed") rather than a crash, so [ensureLoaded]
 * captures the throwable once and reports it on every later call.
 */
object MaaFw {

    private const val TAG = "MaaFw"

    /** Library name passed to [Native.load]; the framework's SONAME is `libMaaFramework.so`. */
    const val LIBRARY_NAME = "MaaFramework"

    /** The external-lib name our own `core/src/main/cpp` builds, referenced by the controller config. */
    const val BRIDGE_LIBRARY_NAME = "bridge"

    @Volatile private var instance: MaaFrameworkApi? = null
    @Volatile private var loadFailure: Throwable? = null

    /** The bound library. Throws if [ensureLoaded] has not succeeded. */
    val api: MaaFrameworkApi
        get() = instance ?: throw IllegalStateException(
            "libMaaFramework.so is not loaded. Call MaaFw.ensureLoaded() from the helper process first.",
            loadFailure,
        )

    /** `true` once [ensureLoaded] has succeeded. Safe to call from anywhere. */
    val isLoaded: Boolean get() = instance != null

    /** The failure from the most recent [ensureLoaded] attempt, if any. */
    val lastFailure: Throwable? get() = loadFailure

    /**
     * Sets up JNA's scratch directory and binds `libMaaFramework.so`.
     *
     * Idempotent and thread-safe; the second and later calls return the memoised result without
     * touching the filesystem. [nativeLibraryDir] is used only as a fallback scratch directory —
     * the actual `.so` search path is the loader's, which already contains the app's native lib dir
     * because `packaging.jniLibs.useLegacyPackaging = true` makes those real files on disk.
     */
    @Synchronized
    fun ensureLoaded(nativeLibraryDir: File?, tmpDir: File? = null): Result<Unit> {
        instance?.let { return Result.success(Unit) }
        loadFailure?.let { return Result.failure(it) }

        return runCatching {
            System.setProperty("jna.tmpdir", resolveTmpDir(nativeLibraryDir, tmpDir).absolutePath)
            // The helper process is a bare `app_process` started from su/Shizuku, so its
            // java.library.path does NOT contain the APK's native lib dir. Point JNA at it
            // explicitly, otherwise Native.load() cannot find libMaaFramework.so (which lives in
            // /data/app/~~<hash>/<pkg>-<hash>/lib/<abi>/ because useLegacyPackaging extracts it).
            nativeLibraryDir?.let { dir ->
                if (dir.isDirectory) {
                    val existing = System.getProperty("jna.library.path")
                    if (existing.isNullOrEmpty()) {
                        System.setProperty("jna.library.path", dir.absolutePath)
                    } else if (!existing.contains(dir.absolutePath)) {
                        System.setProperty("jna.library.path", "$existing:${dir.absolutePath}")
                    }
                }
            }
            Log.i(
                TAG,
                "loading lib$LIBRARY_NAME.so (jna.tmpdir=${System.getProperty("jna.tmpdir")}, " +
                    "jna.library.path=${System.getProperty("jna.library.path")})",
            )
            instance = Native.load(LIBRARY_NAME, MaaFrameworkApi::class.java)
            Log.i(TAG, "lib$LIBRARY_NAME.so loaded, version=${MaaVersion()}")
            Unit
        }.onFailure {
            loadFailure = it
            Log.e(TAG, "failed to load lib$LIBRARY_NAME.so", it)
        }
    }

    /**
     * Picks JNA's scratch directory.
     *
     * `/data/local/tmp` is owned by the shell uid (2000) and is what the privileged helper runs as,
     * so it works there. Under root it works too. When neither applies we fall back to the app's
     * own directory.
     */
    private fun resolveTmpDir(nativeLibraryDir: File?, preferred: File?): File {
        val candidates = listOfNotNull(preferred, File("/data/local/tmp"), nativeLibraryDir)
        val usable = candidates.firstOrNull { it.isDirectory && it.canWrite() }
            ?: candidates.firstOrNull { it.isDirectory }
            ?: File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        if (!usable.isDirectory) usable.mkdirs()
        return usable
    }

    /** `MaaVersion()` — e.g. `"v5.14.2"`. Empty string when unloaded. */
    fun MaaVersion(): String = instance?.MaaVersion()?.takeIf { it.isNotEmpty() } ?: "unknown"

    // ------------------------------------------------------------------------------------------
    // MaaGlobalSetOption, typed
    //
    // These are process-global and must run before the first resource or controller is created.
    // ------------------------------------------------------------------------------------------

    /** `MaaGlobalOption_LogDir` — all framework logs (`maa.log`, `debug/` dumps) land here. */
    fun setLogDir(path: File): Boolean = setGlobalString(MaaDef.MaaGlobalOption_LogDir, path.absolutePath)

    /** `MaaGlobalOption_SaveDraw` — dump every recognition's overlay image into `debug/`. */
    fun setSaveDraw(enabled: Boolean): Boolean = setGlobalBool(MaaDef.MaaGlobalOption_SaveDraw, enabled)

    /** `MaaGlobalOption_SaveOnError` — dump the overlay image only for failed recognitions. */
    fun setSaveOnError(enabled: Boolean): Boolean = setGlobalBool(MaaDef.MaaGlobalOption_SaveOnError, enabled)

    /** `MaaGlobalOption_DebugMode` — keep every captured frame on disk for offline replay. */
    fun setDebugMode(enabled: Boolean): Boolean = setGlobalBool(MaaDef.MaaGlobalOption_DebugMode, enabled)

    fun setStdoutLevel(level: Int): Boolean = setGlobalInt(MaaDef.MaaGlobalOption_StdoutLevel, level)

    /** `MaaGlobalOption_DrawQuality` — JPEG quality of the overlay dumps, 0..100 (default 85). */
    fun setDrawQuality(quality: Int): Boolean =
        setGlobalInt(MaaDef.MaaGlobalOption_DrawQuality, quality.coerceIn(0, 100))

    /** `MaaGlobalOption_RecoImageCacheLimit` — `size_t`, count of cached recognition frames. */
    fun setRecoImageCacheLimit(count: Long): Boolean {
        val mem = Memory(8)
        mem.setLong(0, count)
        return instance?.MaaGlobalSetOption(MaaDef.MaaGlobalOption_RecoImageCacheLimit, mem, 8)
            .toBool() ?: false
    }

    private fun setGlobalBool(option: Int, value: Boolean): Boolean {
        val mem = Memory(1)
        mem.setByte(0, if (value) 1 else 0)
        return instance?.MaaGlobalSetOption(option, mem, 1).toBool() ?: false
    }

    private fun setGlobalInt(option: Int, value: Int): Boolean {
        val mem = Memory(4)
        mem.setInt(0, value)
        return instance?.MaaGlobalSetOption(option, mem, 4).toBool() ?: false
    }

    private fun setGlobalString(option: Int, value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val mem = Memory((bytes.size + 1).toLong())
        mem.write(0, bytes, 0, bytes.size)
        mem.setByte(bytes.size.toLong(), 0)
        // val_size is the string length *excluding* the NUL; the framework copies that many bytes.
        return instance?.MaaGlobalSetOption(option, mem, bytes.size.toLong()).toBool() ?: false
    }

    // ------------------------------------------------------------------------------------------
    // Convenience factories
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the JSON config for `MaaAndroidNativeControllerCreate`.
     *
     * The `screen_resolution` recorded here is not merely informational: the framework clamps the
     * Android Native controller's touch space to it and **fails screencap outright** if the
     * external lib hands back a frame of a different size. It must therefore equal what
     * `core/src/main/cpp/bridge_capture.cpp` actually creates.
     */
    fun androidNativeControllerConfig(
        bridgeLibraryPath: String,
        width: Int,
        height: Int,
        displayId: Int = 0,
        forceStop: Boolean = false,
    ): String = buildString {
        append('{')
        append("\"library_path\":").append(quote(bridgeLibraryPath)).append(',')
        append("\"screen_resolution\":{")
        append("\"width\":").append(width).append(',')
        append("\"height\":").append(height)
        append("},")
        append("\"display_id\":").append(displayId).append(',')
        append("\"force_stop\":").append(if (forceStop) "true" else "false")
        append('}')
    }

    /** Minimal JSON string escaping — the paths here are app-generated and never contain quotes. */
    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/**
 * `MaaBool` is a 1-byte `uint8_t`. JNA hands it back as a signed Kotlin [Byte]; `-1` and `1` are
 * both "true" in practice depending on whether the framework filled the whole register.
 */
internal fun Byte?.toBool(): Boolean = this != null && this.toInt() != 0

/** Alias that reads better at call sites where the value came from a `MaaBool`-returning function. */
internal fun Pointer?.isNull(): Boolean = this == null || Pointer.nativeValue(this) == 0L
