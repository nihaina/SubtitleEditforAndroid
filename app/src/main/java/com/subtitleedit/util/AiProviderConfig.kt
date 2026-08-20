package com.subtitleedit.util

object AiProviderConfig {
    const val SILICONFLOW = "siliconflow"
    const val DEEPSEEK = "deepseek"
    const val OPENAI = "openai"
    const val CUSTOM = "custom"

    data class Provider(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val websiteUrl: String,
        val defaultModel: String,
        val models: List<String> = emptyList(),
        val customEndpoint: Boolean = false
    )

    val providers = listOf(
        Provider(
            id = SILICONFLOW,
            displayName = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            websiteUrl = "https://siliconflow.cn/",
            defaultModel = "deepseek-ai/DeepSeek-V3.2-Exp"
        ),
        Provider(
            id = DEEPSEEK,
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            websiteUrl = "https://platform.deepseek.com/",
            defaultModel = "deepseek-v4-flash",
            models = listOf("deepseek-v4-flash", "deepseek-v4-pro")
        ),
        Provider(
            id = OPENAI,
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            websiteUrl = "https://platform.openai.com/",
            defaultModel = "gpt-5.4-mini"
        ),
        Provider(
            id = CUSTOM,
            displayName = "自定义",
            baseUrl = "",
            websiteUrl = "https://docs.newapi.pro/",
            defaultModel = "gpt-4o-mini",
            customEndpoint = true
        )
    )

    fun getProvider(id: String): Provider {
        return providers.firstOrNull { it.id == id } ?: providers.first()
    }

    fun indexOf(id: String): Int {
        return providers.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
    }

    fun chatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotEmpty()) { "请先填写 API 请求地址" }
        return if (normalized.endsWith("/chat/completions", ignoreCase = true)) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }
}
