package com.subtitleedit.localllm

/** One OpenAI-style chat message passed to the local model. */
data class LocalChatMessage(
    val role: String,
    val content: String,
)
