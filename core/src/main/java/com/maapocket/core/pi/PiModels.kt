package com.maapocket.core.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Project Interface V2 数据模型。
 *
 * 对应 MaaFramework 的 `tools/interface.schema.json`（`interface_version = 2`）。
 * 只映射本项目真正用到的字段；未知字段全部忽略（见 [PiJson]）。
 *
 * 设计取舍：凡是 schema 里类型可能是 `string | array` 或未明确的字段，一律用 [JsonElement]
 * 承载并在 [PiNorm] 里归一化。这样上游资源项目（MaaEnd / 未来的星铁、绝区零包）改了形态
 * 也不会让整个 `interface.json` 反序列化失败。
 */
@Serializable
data class PiInterface(
    @SerialName("interface_version") val interfaceVersion: Int = 0,
    val name: String = "",
    val label: String? = null,
    val title: String? = null,
    val icon: String? = null,
    val version: String? = null,
    val github: String? = null,
    val contact: String? = null,
    val license: String? = null,
    val description: String? = null,
    val welcome: JsonElement? = null,
    /** 语言代码 -> 翻译文件相对路径（相对 interface.json 所在目录）。 */
    val languages: Map<String, String> = emptyMap(),
    val controller: List<PiController> = emptyList(),
    val resource: List<PiResource> = emptyList(),
    val group: List<PiGroup> = emptyList(),
    val task: List<PiTask> = emptyList(),
    val option: Map<String, PiOption> = emptyMap(),
    @SerialName("global_option") val globalOption: List<String> = emptyList(),
    val setting: List<PiSetting> = emptyList(),
    /** schema 允许 object 或 array；用 [PiNorm.agents] 归一。 */
    val agent: JsonElement? = null,
    /** schema 允许 object 或 array；用 [PiNorm.pretasks] 归一。 */
    val pretask: JsonElement? = null,
    val import: List<String> = emptyList(),
    val preset: List<JsonObject> = emptyList(),
)

@Serializable
data class PiController(
    val name: String,
    /** "Adb" | "Win32" | "MacOS" | "PlayCover" | "Gamepad" | "Linux"。 */
    val type: String = "",
    val label: String? = null,
    val description: String? = null,
    val icon: String? = null,
    @SerialName("display_short_side") val displayShortSide: JsonElement? = null,
    @SerialName("display_long_side") val displayLongSide: JsonElement? = null,
    @SerialName("display_expand") val displayExpand: JsonElement? = null,
    @SerialName("display_raw") val displayRaw: JsonElement? = null,
    @SerialName("permission_required") val permissionRequired: JsonElement? = null,
    @SerialName("attach_resource_path") val attachResourcePath: List<String> = emptyList(),
    val option: List<String> = emptyList(),
    val adb: JsonObject? = null,
    val win32: JsonObject? = null,
    val macos: JsonObject? = null,
    val playcover: JsonObject? = null,
    val gamepad: JsonObject? = null,
    val linux: JsonObject? = null,
)

@Serializable
data class PiResource(
    val name: String,
    /** 资源路径列表；schema 上是必填，但形态可能是 string 或 array -> [PiNorm.stringList]。 */
    val path: JsonElement? = null,
    val label: String? = null,
    val description: String? = null,
    val icon: String? = null,
    /** 该资源包适用于哪些 controller 名；空 = 全部。 */
    val controller: List<String> = emptyList(),
    val option: List<String> = emptyList(),
    val hash: String? = null,
)

@Serializable
data class PiTask(
    val name: String,
    /** pipeline 入口节点名。 */
    val entry: String = "",
    val label: String? = null,
    @SerialName("default_check") val defaultCheck: Boolean = false,
    val description: String? = null,
    val doc: String? = null,
    val desc: String? = null,
    val icon: String? = null,
    val group: List<String> = emptyList(),
    val resource: List<String> = emptyList(),
    val controller: List<String> = emptyList(),
    @SerialName("pipeline_override") val pipelineOverride: JsonObject? = null,
    val option: List<String> = emptyList(),
)

@Serializable
data class PiGroup(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val icon: String? = null,
    @SerialName("default_expand") val defaultExpand: Boolean = false,
)

@Serializable
data class PiSetting(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val option: List<String> = emptyList(),
    @SerialName("default_expand") val defaultExpand: Boolean = false,
)

/**
 * `option` 映射的值。PI-V2 里有三种形态（switch / input / hotkey），这里统一承载：
 * - switch：有 [cases] + [defaultCase]
 * - input / hotkey：有 [default]
 * 子选项通过 [option] 引用（一个 case 可以再展开若干子选项）。
 */
@Serializable
data class PiOption(
    val cases: List<PiOptionCase> = emptyList(),
    @SerialName("default_case") val defaultCase: String? = null,
    val default: JsonElement? = null,
    @SerialName("pipeline_type") val pipelineType: String? = null,
    val label: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val option: List<String> = emptyList(),
    /** input 形态的校验正则 / 提示。 */
    val verify: String? = null,
    @SerialName("pattern_msg") val patternMsg: String? = null,
    val password: Boolean = false,
)

@Serializable
data class PiOptionCase(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val option: List<String> = emptyList(),
    @SerialName("pipeline_override") val pipelineOverride: JsonObject? = null,
)

@Serializable
data class PiAgent(
    @SerialName("child_exec") val childExec: String = "",
    @SerialName("child_args") val childArgs: List<String> = emptyList(),
    /** 追加到命令行末尾的标识；缺省由 Client 生成。 */
    val identifier: String? = null,
)

@Serializable
data class PiPretask(
    val exec: String = "",
    val args: List<String> = emptyList(),
    val resource: List<String> = emptyList(),
    val controller: List<String> = emptyList(),
    val name: String? = null,
    val label: String? = null,
    val description: String? = null,
    val option: List<String> = emptyList(),
)

/** `interface.json` 的解析器。宽容优先：资源包更新不该把 App 弄崩。 */
val PiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    allowStructuredMapKeys = false
    encodeDefaults = false
}

/** 归一化辅助。 */
object PiNorm {

    fun stringList(el: JsonElement?): List<String> = when (el) {
        null -> emptyList()
        is JsonPrimitive -> if (el.isString) listOfNotNull(el.contentOrNull) else emptyList()
        else -> el.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    fun int(el: JsonElement?): Int? = (el as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    fun bool(el: JsonElement?, default: Boolean = false): Boolean =
        (el as? JsonPrimitive)?.booleanOrNull ?: default

    /** `agent` 既可能是单个对象，也可能是数组。 */
    fun agents(el: JsonElement?): List<PiAgent> = decodeOneOrMany(el)

    /** `pretask` 既可能是单个对象，也可能是数组。 */
    fun pretasks(el: JsonElement?): List<PiPretask> = decodeOneOrMany(el)

    /** `welcome` 既可能是字符串，也可能是字符串数组。 */
    fun welcome(el: JsonElement?): List<String> = stringList(el)

    private inline fun <reified T> decodeOneOrMany(el: JsonElement?): List<T> = when (el) {
        null -> emptyList()
        else -> when (el) {
            is kotlinx.serialization.json.JsonArray ->
                el.mapNotNull { runCatching { PiJson.decodeFromJsonElement(serializer<T>(), it) }.getOrNull() }
            is JsonObject ->
                listOfNotNull(runCatching { PiJson.decodeFromJsonElement(serializer<T>(), el) }.getOrNull())
            else -> emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> serializer() = kotlinx.serialization.serializer<T>()
}
