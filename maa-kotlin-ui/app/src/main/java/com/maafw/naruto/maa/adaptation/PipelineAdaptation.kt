package com.maafw.naruto.maa.adaptation

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机端适配层：任务启动前把 pipeline 的 roi / template 改写成当前虚拟屏能用的值。
 *
 * 只做两件事，且全部通过 MaaFramework 的 `pipeline_override` 下发，**不改资源文件本身**：
 * 1. `roi`：按 [ScreenProfile] 从 720p 基线等比缩放到当前分辨率；
 * 2. `template`：按 [TemplateSelector] 选择当前分辨率那一套模板。
 *
 * 适配结果以 override 的形式与选项 override 浅合并（节点级覆盖），
 * 因此不会破坏 interface.json 里已有的 option 开关。
 */
object PipelineAdaptation {

    private const val TAG = "PipelineAdaptation"
    private const val PIPELINE_ASSET = "resource/base/pipeline"

    data class Result(
        val override: JSONObject,
        val summary: String,
        /** 每个被改写节点的「节点名 / 识别类型 / template / threshold / roi」，用于失败排查。 */
        val details: List<String>,
    )

    /** 每个进程只扫一次 assets；pipeline 随 APK 固定，不随任务变化。 */
    private val pipelineCache = AtomicReference<Map<String, JSONObject>?>(null)

    fun apply(context: Context, profile: ScreenProfile, baseOverride: String?): Result {
        val override = runCatching {
            JSONObject(baseOverride?.takeIf { it.isNotBlank() } ?: "{}")
        }.getOrDefault(JSONObject())

        val manifest = TemplateSelector.load(context)
            ?: return Result(override, "适配层：无模板集清单，按资源包原值运行（$profile）", emptyList())

        // 资源包声明了自己的基线（清单里的 baseline），以它为准，而不是写死 720p。
        val effective = profile.copy(
            baselineWidth = manifest.baselineWidth,
            baselineHeight = manifest.baselineHeight,
        )

        val nodes = pipelineCache.get() ?: loadPipelineNodes(context).also { pipelineCache.set(it) }

        var templateHits = 0
        var roiHits = 0
        val details = mutableListOf<String>()

        // 1) template：只改清单里登记过、且确实存在于 pipeline 的节点
        manifest.nodes.keys.forEach { name ->
            val pipelineNode = nodes[name] ?: return@forEach
            val template = TemplateSelector.resolve(manifest, name, effective) ?: return@forEach
            nodeJson(override, name).put("template", template)
            templateHits++
            details += diagnose(name, pipelineNode, override.optJSONObject(name))
        }

        // 2) roi：所有带非全屏 roi 的节点都按比例换算
        nodes.forEach { (name, node) ->
            val roi = node.optJSONArray("roi") ?: return@forEach
            if (roi.length() != 4) return@forEach
            val original = IntArray(4) { roi.optInt(it, 0) }
            val scaled = RoiScaler.scale(original, effective)
            if (scaled.contentEquals(original)) return@forEach
            nodeJson(override, name).put("roi", JSONArray(scaled.toList()))
            roiHits++
            if (details.none { it.startsWith("$name ") }) {
                details += diagnose(name, node, override.optJSONObject(name))
            }
        }

        val summary = String.format(
            Locale.US,
            "适配层：%s，模板节点=%d，roi缩放=%d",
            effective, templateHits, roiHits,
        )
        if (templateHits == 0 && roiHits == 0) {
            Log.i(TAG, "$summary（清单节点=${manifest.nodes.size}，pipeline 节点=${nodes.size}）")
        }
        return Result(override, summary, details)
    }

    /** 一个被改写节点的排查信息，格式与 RECAPTURE/日志约定一致。 */
    private fun diagnose(name: String, pipelineNode: JSONObject, overrideNode: JSONObject?): String {
        val recognition = pipelineNode.optString("recognition", "TemplateMatch")
        val template = overrideNode?.optString("template")
            ?: pipelineNode.optString("template", "-")
        val threshold = overrideNode?.optDouble("threshold", pipelineNode.optDouble("threshold", 0.7))
            ?: pipelineNode.optDouble("threshold", 0.7)
        val roi = overrideNode?.optJSONArray("roi") ?: pipelineNode.optJSONArray("roi")
        val roiText = roi?.let { arr -> "[${(0 until arr.length()).joinToString(",") { arr.optInt(it).toString() }}]" }
            ?: "[0,0,0,0]"
        return String.format(
            Locale.US,
            "%s %s template=%s threshold=%.2f roi=%s",
            name, recognition, template, threshold, roiText,
        )
    }

    private fun nodeJson(root: JSONObject, name: String): JSONObject =
        root.optJSONObject(name) ?: JSONObject().also { root.put(name, it) }

    /**
     * 递归读取 `assets/resource/base/pipeline` 下所有 JSON，返回 节点名 -> 节点对象。
     * 后读到的同名节点覆盖先读到的（与 MaaFramework 的加载语义近似）。
     */
    private fun loadPipelineNodes(context: Context): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        runCatching { walk(context.assets, PIPELINE_ASSET, out) }
            .onFailure { Log.w(TAG, "读取 pipeline assets 失败：${it.message}") }
        return out
    }

    private fun walk(assets: AssetManager, path: String, out: MutableMap<String, JSONObject>) {
        val children = runCatching { assets.list(path) }.getOrNull() ?: return
        if (children.isEmpty()) {
            if (!path.endsWith(".json", ignoreCase = true)) return
            val text = runCatching { assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull() ?: return
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            obj.keys().forEach { key ->
                obj.optJSONObject(key)?.let { out[key] = it }
            }
            return
        }
        children.forEach { child -> walk(assets, "$path/$child", out) }
    }
}
