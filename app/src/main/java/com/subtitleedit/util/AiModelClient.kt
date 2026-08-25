package com.subtitleedit.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Loads model ids from OpenAI-compatible providers. */
object AiModelClient {
    private val client = OkHttpClient()

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(AiProviderConfig.modelsUrl(baseUrl))
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
                addHeader("Accept", "application/json")
            }
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("获取模型列表失败：${response.code} ${parseError(body)}")
            }
            parseModelIds(body)
        }
    }

    internal fun parseModelIds(body: String): List<String> {
        val root = JSONObject(body)
        val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val value = data.opt(index)
                val id = when (value) {
                    is JSONObject -> value.optString("id").ifBlank {
                        value.optString("name")
                    }
                    else -> value?.toString().orEmpty()
                }.trim()
                if (id.isNotEmpty()) add(id)
            }
        }.distinct().sorted()
    }

    private fun parseError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(300)
}
