package com.subtitleedit.util

object AiProviderConfig {
    const val SILICONFLOW = "siliconflow"
    const val DEEPSEEK = "deepseek"
    const val OPENAI = "openai"
    const val CUSTOM = "custom"

    /** DeepSeek V4 models expose a one-million-token context window. */
    const val DEFAULT_DEEPSEEK_CONTEXT_WINDOW_TOKENS = 1_000_000

    enum class ReasoningLevel(val displayName: String, val effort: String, val budgetTokens: Int) {
        OFF("关闭", "none", 0),
        AUTO("自动", "auto", -1),
        LOW("低", "low", 1_000),
        MEDIUM("中", "medium", 2_000),
        HIGH("高", "high", 8_000),
        XHIGH("极高", "xhigh", 16_000),
        MAX("最大", "max", 32_000)
    }

    /** Request features shared by the common OpenAI-compatible model families. */
    data class ModelCapabilities(
        val reasoning: Boolean,
        val tools: Boolean
    )

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
            baseUrl = "https://api.deepseek.com/v1",
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

    fun defaultContextWindowTokens(provider: String): Int =
        if (provider == DEEPSEEK) {
            DEFAULT_DEEPSEEK_CONTEXT_WINDOW_TOKENS
        } else {
            DEFAULT_AI_CONTEXT_WINDOW_TOKENS
        }

    fun defaultReasoningLevel(provider: String): ReasoningLevel =
        if (provider == DEEPSEEK) ReasoningLevel.MEDIUM else ReasoningLevel.AUTO

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

    fun modelsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotEmpty()) { "请先填写 API 请求地址" }
        return when {
            normalized.endsWith("/models", ignoreCase = true) -> normalized
            normalized.endsWith("/chat/completions", ignoreCase = true) ->
                normalized.removeSuffix("/chat/completions") + "/models"
            else -> "$normalized/models"
        }
    }

    fun modelCapabilities(provider: String, model: String): ModelCapabilities {
        val id = model.lowercase()
        val reasoning = listOf(
            "deepseek", "reasoner", "r1", "qwen3", "qwen2.5-thinking", "kimi-k2",
            "glm-4.5", "glm-4.6", "glm-4.7", "glm-5", "o1", "o3", "o4", "gpt-5",
            "gemini-2.5", "gemini-3", "claude-3-7", "claude-4", "magistral", "mimo", "minimax"
        ).any(id::contains)
        val tools = listOf(
            "gpt", "claude", "gemini", "qwen", "deepseek", "glm", "kimi", "moonshot",
            "mistral", "minimax", "command", "hunyuan"
        ).any(id::contains)
        return when (provider) {
            DEEPSEEK -> ModelCapabilities(reasoning = true, tools = true)
            else -> ModelCapabilities(reasoning, tools)
        }
    }
}
