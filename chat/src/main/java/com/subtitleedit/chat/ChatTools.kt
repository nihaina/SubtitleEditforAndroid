package com.subtitleedit.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Built-in local tools shared by the standalone chat and translation adapters. */
object ChatTools {
    fun create(context: Context): List<ChatBackend.ChatTool> {
        val historyStore = ChatHistoryStore(context.applicationContext)
        return listOf(
            ChatBackend.ChatTool(
                name = "recent_chats",
                description = "获取本地最近 AI 对话或翻译任务的摘要。仅返回本机保存的历史记录。",
                parameters = listParameters(
                    limitDescription = "返回数量，默认为 10，最大为 30。"
                ),
                execute = { arguments ->
                    val limit = arguments.optInt("limit", DEFAULT_TOOL_LIMIT)
                        .coerceIn(1, MAX_TOOL_LIMIT)
                    val type = requestedType(arguments)
                    JSONArray().apply {
                        historyStore.recent(limit, type).forEach { session ->
                            put(JSONObject()
                                .put("id", session.id)
                                .put("title", session.title)
                                .put("type", session.type)
                                .put("updated_at", session.updatedAt)
                            )
                        }
                    }.toString()
                }
            ),
            ChatBackend.ChatTool(
                name = "conversation_search",
                description = "全文搜索本地保存的 AI 对话和翻译回复，返回匹配消息的上下文片段。",
                parameters = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject()
                            .put("type", "string")
                            .put("description", "需要搜索的关键词或短语。")
                        )
                        put("limit", JSONObject()
                            .put("type", "integer")
                            .put("description", "返回数量，默认为 10，最大为 30。")
                            .put("minimum", 1)
                            .put("maximum", MAX_TOOL_LIMIT)
                        )
                        put("type", typeParameter())
                    })
                    put("required", JSONArray().put("query"))
                    put("additionalProperties", false)
                },
                execute = { arguments ->
                    val query = arguments.optString("query").trim()
                    if (query.isBlank()) {
                        JSONObject().put("error", "query 不能为空").toString()
                    } else {
                        val limit = arguments.optInt("limit", DEFAULT_TOOL_LIMIT)
                            .coerceIn(1, MAX_TOOL_LIMIT)
                        val type = requestedType(arguments)
                        JSONArray().apply {
                            historyStore.search(query, limit, type).forEach { result ->
                                put(JSONObject()
                                    .put("conversation_id", result.sessionId)
                                    .put("title", result.title)
                                    .put("type", result.type)
                                    .put("role", result.role)
                                    .put("position", result.position)
                                    .put("snippet", result.snippet)
                                    .put("updated_at", result.updatedAt)
                                )
                            }
                        }.toString()
                    }
                }
            )
        )
    }

    private fun listParameters(limitDescription: String): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("limit", JSONObject()
                .put("type", "integer")
                .put("description", limitDescription)
                .put("minimum", 1)
                .put("maximum", MAX_TOOL_LIMIT)
            )
            put("type", typeParameter())
        })
        put("additionalProperties", false)
    }

    private fun typeParameter(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray().put(ChatHistoryStore.TYPE_CHAT).put(ChatHistoryStore.TYPE_TRANSLATION))
        .put("description", "可选，只查询 chat 或 translation 类型。")

    private fun requestedType(arguments: JSONObject): String? = arguments.optString("type")
        .takeIf { it == ChatHistoryStore.TYPE_CHAT || it == ChatHistoryStore.TYPE_TRANSLATION }

    private const val DEFAULT_TOOL_LIMIT = 10
    private const val MAX_TOOL_LIMIT = 30
}
