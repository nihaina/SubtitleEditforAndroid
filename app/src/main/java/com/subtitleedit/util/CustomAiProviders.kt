package com.subtitleedit.util

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stores identities and template references; credentials and settings stay scoped by identity. */
internal object CustomAiProviders {
    private val userIdPattern = Regex("user_[a-zA-Z0-9_-]+")

    fun create(name: String, templateId: String): AiProviderConfig.Provider {
        val template = AiProviderConfig.providers.firstOrNull { it.id == templateId }
        require(template != null) { "请选择已有的平台类型" }
        require(name.isNotBlank()) { "请输入平台名称" }
        return template.copy(
            id = "user_${UUID.randomUUID()}",
            displayName = name.trim(),
            templateId = template.id
        )
    }

    fun encode(providers: List<AiProviderConfig.Provider>): String = JSONArray().apply {
        providers.filter { it.isUserAdded }.forEach { provider ->
            put(JSONObject()
                .put("id", provider.id)
                .put("name", provider.displayName)
                .put("template", provider.templateId))
        }
    }.toString()

    fun decode(json: String): List<AiProviderConfig.Provider> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val ids = AiProviderConfig.providers.mapTo(mutableSetOf()) { it.id }
        val names = AiProviderConfig.providers.mapTo(mutableSetOf()) { it.displayName.lowercase() }
        return buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.optString("id")
                val name = entry.optString("name").trim()
                val template = AiProviderConfig.providers.firstOrNull {
                    it.id == entry.optString("template")
                } ?: continue
                if (!id.matches(userIdPattern) || name.isBlank() ||
                    id in ids || name.lowercase() in names
                ) continue
                ids += id
                names += name.lowercase()
                add(template.copy(id = id, displayName = name, templateId = template.id))
            }
        }
    }
}
