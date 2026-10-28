package com.maafw.naruto.model

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

object AssetLoader {
    private const val TAG = "AssetLoader"
    private val gson = Gson()

    fun loadInterface(context: Context): MaaInterface? {
        return try {
            context.assets.open("interface.json").use { stream ->
                val json = stream.bufferedReader().readText()
                gson.fromJson(json, MaaInterface::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "interface.json read failed: ${e.message}")
            null
        }
    }

    fun loadPipeline(context: Context, fileName: String): Map<String, JsonObject>? {
        return try {
            context.assets.open("resource/base/pipeline/$fileName").use { stream ->
                val type = object : TypeToken<Map<String, JsonObject>>() {}.type
                gson.fromJson<Map<String, JsonObject>>(stream.bufferedReader(), type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "pipeline read failed: $fileName ${e.message}")
            null
        }
    }

    fun listPipelineFiles(context: Context): List<String> {
        return try {
            context.assets.list("resource/base/pipeline")?.filter {
                it.endsWith(".json", ignoreCase = true)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "pipeline list failed: ${e.message}")
            emptyList()
        }
    }
}