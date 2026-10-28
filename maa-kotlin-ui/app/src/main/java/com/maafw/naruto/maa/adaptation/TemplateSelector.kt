package com.maafw.naruto.maa.adaptation

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs

/**
 * 多分辨率模板集的选择器。
 *
 * 清单文件：`assets/resource/base/adaptation/template_sets.json`
 * ```json
 * {
 *   "baseline": [1280, 720],
 *   "sets": ["720p", "1080p"],
 *   "nodes": {
 *     "HSR_Daily_EnterMailbox": {
 *       "720p": "hsr/daily_claim/720p/mailbox_anchor.png",
 *       "1080p": "hsr/daily_claim/1080p/mailbox_anchor.png"
 *     }
 *   }
 * }
 * ```
 *
 * 清单由 `scripts/capture_templates.py` 生成；pipeline 里仍写不带分辨率目录的
 * 规范路径（`hsr/daily_claim/mailbox_anchor.png`），运行时由适配层替换成当前分辨率那一套。
 */
object TemplateSelector {

    private const val TAG = "TemplateSelector"
    private const val MANIFEST_ASSET = "resource/base/adaptation/template_sets.json"

    data class Manifest(
        val baselineWidth: Int,
        val baselineHeight: Int,
        val sets: List<String>,
        /** 节点名 -> (setKey -> 模板相对 image/ 的路径)。 */
        val nodes: Map<String, Map<String, String>>,
    )

    fun load(context: Context): Manifest? = runCatching {
        val text = context.assets.open(MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        parse(JSONObject(text))
    }.onFailure {
        Log.w(TAG, "模板集清单不可用（$MANIFEST_ASSET）：${it.message}")
    }.getOrNull()

    fun parse(json: JSONObject): Manifest {
        val baseline = json.optJSONArray("baseline")
        val sets = json.optJSONArray("sets")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()

        val nodes = LinkedHashMap<String, Map<String, String>>()
        json.optJSONObject("nodes")?.let { nodeObj ->
            nodeObj.keys().forEach { node ->
                val variants = nodeObj.optJSONObject(node) ?: return@forEach
                val map = LinkedHashMap<String, String>()
                variants.keys().forEach { key ->
                    variants.optString(key).takeIf { it.isNotBlank() }?.let { map[key] = it }
                }
                if (map.isNotEmpty()) nodes[node] = map
            }
        }

        return Manifest(
            baselineWidth = baseline?.optInt(0, ScreenProfile.BASELINE_WIDTH)
                ?: ScreenProfile.BASELINE_WIDTH,
            baselineHeight = baseline?.optInt(1, ScreenProfile.BASELINE_HEIGHT)
                ?: ScreenProfile.BASELINE_HEIGHT,
            sets = sets,
            nodes = nodes,
        )
    }

    /**
     * 选最接近当前分辨率的模板集。
     *
     * 优先精确命中 [ScreenProfile.setKey]；否则按短边数值找最接近的一套；一套都没有就返回 null。
     */
    fun pickSetKey(manifest: Manifest, profile: ScreenProfile, node: String? = null): String? {
        val available = if (node != null && manifest.nodes[node] != null) {
            manifest.nodes.getValue(node).keys.toList()
        } else {
            manifest.sets
        }
        if (available.isEmpty()) return null
        if (profile.setKey in available) return profile.setKey

        val target = profile.height
        return available.minByOrNull { key ->
            val short = key.removeSuffix("p").toIntOrNull() ?: ScreenProfile.BASELINE_HEIGHT
            abs(short - target)
        }
    }

    /** 取某个节点在当前分辨率下应使用的模板路径；没有登记则返回 null（保持 pipeline 原值）。 */
    fun resolve(manifest: Manifest, node: String, profile: ScreenProfile): String? {
        val variants = manifest.nodes[node] ?: return null
        val key = pickSetKey(manifest, profile, node) ?: return null
        return variants[key]
    }
}
