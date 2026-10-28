package com.maafw.naruto.maa

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * JNA 内存辅助工具
 */
object MemoryUtil {

    fun bytesToPointer(bytes: ByteArray): Pointer {
        val mem = Memory(bytes.size.toLong())
        mem.write(0, bytes, 0, bytes.size)
        return mem
    }

    fun intArrayToPointer(array: IntArray): Pointer {
        val mem = Memory((array.size * 4).toLong())
        mem.write(0, array, 0, array.size)
        return mem
    }

    fun stringToPointer(str: String): Pointer {
        val bytes = str.toByteArray(Charsets.UTF_8)
        return bytesToPointer(bytes)
    }
}