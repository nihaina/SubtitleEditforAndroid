package com.subtitleedit.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local, API-key-free conversation archive shared by chat and translation callers. */
class ChatHistoryStore(context: Context) {
    data class Session(
        val id: String,
        val title: String,
        val type: String,
        val updatedAt: Long,
        val messages: List<ChatBackend.ChatMessage>
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun list(): List<Session> = readSessions().sortedByDescending(Session::updatedAt)

    fun save(
        id: String? = null,
        title: String,
        type: String,
        messages: List<ChatBackend.ChatMessage>
    ): String {
        if (messages.none { it.role == "user" || it.role == "assistant" }) return id.orEmpty()
        val sessionId = id ?: UUID.randomUUID().toString()
        val updated = Session(
            id = sessionId,
            title = title.take(TITLE_MAX_CHARS).ifBlank { "未命名对话" },
            type = type,
            updatedAt = System.currentTimeMillis(),
            messages = messages.map(::truncateMessage)
        )
        val sessions = readSessions().filterNot { it.id == sessionId }.toMutableList()
        sessions += updated
        writeSessions(sessions)
        return sessionId
    }

    fun load(id: String): Session? = readSessions().firstOrNull { it.id == id }

    fun delete(id: String) {
        writeSessions(readSessions().filterNot { it.id == id })
    }

    fun clear() {
        preferences.edit().remove(KEY_SESSIONS).apply()
    }

    private fun readSessions(): List<Session> = runCatching {
        val root = JSONArray(preferences.getString(KEY_SESSIONS, "[]"))
        buildList {
            for (index in 0 until root.length()) {
                root.optJSONObject(index)?.let(::sessionFromJson)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun writeSessions(source: List<Session>) {
        val sessions = source.sortedByDescending(Session::updatedAt)
            .take(MAX_SESSIONS)
            .toMutableList()
        while (sessions.isNotEmpty() && serializedSize(sessions) > MAX_SERIALIZED_CHARS) {
            sessions.removeLast()
        }
        preferences.edit().putString(
            KEY_SESSIONS,
            JSONArray().apply { sessions.forEach { put(it.toJson()) } }.toString()
        ).apply()
    }

    private fun serializedSize(sessions: List<Session>): Int = sessions.sumOf { it.toJson().toString().length }

    private fun truncateMessage(message: ChatBackend.ChatMessage): ChatBackend.ChatMessage = message.copy(
        content = message.content.take(MESSAGE_MAX_CHARS),
        reasoningContent = message.reasoningContent.take(MESSAGE_MAX_CHARS),
        toolCalls = message.toolCalls.map { call -> call.copy(arguments = call.arguments.take(MESSAGE_MAX_CHARS)) }
    )

    private fun Session.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("type", type)
        put("updatedAt", updatedAt)
        put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
    }

    private fun sessionFromJson(json: JSONObject): Session? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        val messages = buildList {
            json.optJSONArray("messages")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::messageFromJson)?.let(::add)
                }
            }
        }
        return Session(
            id = id,
            title = json.optString("title", "未命名对话"),
            type = json.optString("type", TYPE_CHAT),
            updatedAt = json.optLong("updatedAt"),
            messages = messages
        )
    }

    private fun ChatBackend.ChatMessage.toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
        put("reasoning", reasoningContent)
        put("toolCallId", toolCallId)
        put("toolName", toolName)
        if (toolCalls.isNotEmpty()) {
            put("toolCalls", JSONArray().apply {
                toolCalls.forEach { call ->
                    put(JSONObject()
                        .put("id", call.id)
                        .put("name", call.name)
                        .put("arguments", call.arguments)
                    )
                }
            })
        }
    }

    private fun messageFromJson(json: JSONObject): ChatBackend.ChatMessage? {
        val role = json.optString("role")
        if (role.isBlank()) return null
        val toolCalls = buildList {
            json.optJSONArray("toolCalls")?.let { array ->
                for (index in 0 until array.length()) {
                    val call = array.optJSONObject(index) ?: continue
                    add(ChatBackend.ToolCall(
                        id = call.optString("id"),
                        name = call.optString("name"),
                        arguments = call.optString("arguments")
                    ))
                }
            }
        }
        return ChatBackend.ChatMessage(
            role = role,
            content = json.optString("content"),
            reasoningContent = json.optString("reasoning"),
            toolCalls = toolCalls,
            toolCallId = json.optString("toolCallId"),
            toolName = json.optString("toolName")
        )
    }

    companion object {
        const val TYPE_CHAT = "chat"
        const val TYPE_TRANSLATION = "translation"
        private const val PREFERENCES_NAME = "subtitle_edit_chat_history"
        private const val KEY_SESSIONS = "sessions"
        private const val MAX_SESSIONS = 20
        private const val MAX_SERIALIZED_CHARS = 1_500_000
        private const val MESSAGE_MAX_CHARS = 120_000
        private const val TITLE_MAX_CHARS = 64
    }
}
