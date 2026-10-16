package com.chatyue.chat.backend

import org.json.JSONArray
import org.json.JSONObject

/**
 * 推理运行模式。
 *
 * 2.0 起为双模型双模式架构，两种模式一对一绑定各自的模型，不允许混用。
 */
enum class ChatMode {
    /** 标准思考模式（默认）：保留完整深度思考推理流程。 */
    THINKING,

    /** 快速模式：跳过前置思考环节，直接输出应答。 */
    FAST,
    ;

    companion object {
        fun fromName(value: String?): ChatMode {
            return entries.firstOrNull { it.name == value } ?: THINKING
        }
    }
}

/**
 * 2.0 版本唯一合法可用的模型清单。
 *
 * 该清单同时写入本地配置，并与服务端 `GET /v1/models` 返回的内容保持一致；
 * 旧版 R1 模型及其所有调用入口已在 2.0 中永久移除。
 */
object ModelCatalog {
    /** 思考模式专属模型。 */
    const val THINKING_MODEL = "GLM-4.1V-Thinking-Flash"

    /** 快速模式专属模型。 */
    const val FAST_MODEL = "glm-4.7-flash"

    /** 清单创建时间戳（固定值，由服务端固化）。 */
    private const val CREATED_AT = 1700000000L

    private const val OWNER = "deepseekold"

    /** 模式与模型的严格一对一绑定。 */
    fun modelFor(mode: ChatMode): String = when (mode) {
        ChatMode.THINKING -> THINKING_MODEL
        ChatMode.FAST -> FAST_MODEL
    }

    /** 模式对应的界面名称。 */
    fun displayName(mode: ChatMode): String = when (mode) {
        ChatMode.THINKING -> "思考"
        ChatMode.FAST -> "快速"
    }

    /** 模式对应的说明文案。 */
    fun description(mode: ChatMode): String = when (mode) {
        ChatMode.THINKING -> "深度思考推理，回答更严谨"
        ChatMode.FAST -> "跳过思考，直接输出答案"
    }

    /** 全部合法模型 id。 */
    val modelIds: List<String> = listOf(THINKING_MODEL, FAST_MODEL)

    /** 判断某个模型 id 是否属于 2.0 的合法模型。 */
    fun isSupported(modelId: String?): Boolean = modelId != null && modelIds.contains(modelId)

    /** 固化的模型清单 JSON（与 `GET /v1/models` 的返回结构一致）。 */
    fun toJson(): JSONObject {
        val data = JSONArray()
        modelIds.forEach { id ->
            data.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "model")
                    .put("created", CREATED_AT)
                    .put("owned_by", OWNER),
            )
        }
        return JSONObject()
            .put("object", "list")
            .put("data", data)
    }

    /** 清单的原始文本，便于写入配置文件或做展示。 */
    fun toPrettyJson(): String = toJson().toString(2)
}
