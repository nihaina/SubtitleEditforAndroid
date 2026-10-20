package com.subtitleedit.util

import java.io.IOException

private val TXT_RESPONSE_BLOCK = Regex("(?is)<start>(.*?)<end>")

internal class InvalidTxtAiResponseException(message: String) : IOException(message)

internal const val TXT_TAG_RESPONSE_RETRIES = 3

/** TXT output is accepted only from complete, non-empty tagged blocks. */
internal fun extractTaggedTxtAiResponse(response: String): String {
    val blocks = TXT_RESPONSE_BLOCK.findAll(response)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (blocks.isEmpty()) {
        throw InvalidTxtAiResponseException("AI 返回的文本缺少有效 <start>/<end> 标签或内容为空")
    }
    return blocks.joinToString("\n")
}

/** Retry only invalid tagged TXT replies; network errors and cancellation keep their own policy. */
internal suspend fun requestTxtAiWithTagRetries(
    request: suspend () -> String,
    onRetry: suspend (Int, InvalidTxtAiResponseException) -> Unit = { _, _ -> }
): String {
    var retries = 0
    while (true) {
        try {
            return request()
        } catch (error: InvalidTxtAiResponseException) {
            if (retries >= TXT_TAG_RESPONSE_RETRIES) {
                throw InvalidTxtAiResponseException(
                    "AI 响应缺少有效 <start>/<end> 标签，重试 $TXT_TAG_RESPONSE_RETRIES 次后仍未获得文本内容，请重试"
                )
            }
            retries++
            onRetry(retries, error)
        }
    }
}

/** Keep every returned block; a long response can contain several fenced sections. */
internal fun extractSubtitleAiResponse(response: String): String {
    val txtMatches = TXT_RESPONSE_BLOCK.findAll(response).toList()
    if (txtMatches.isNotEmpty()) {
        return txtMatches.joinToString("\n") { it.groupValues[1].trim() }
    }

    val marked = Regex("(?is)\\[\\[PUNCTUATED_TEXT\\]\\](.*?)\\[\\[/PUNCTUATED_TEXT\\]\\]")
        .findAll(response).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
    if (marked.isNotEmpty()) return marked.joinToString("\n")

    val fenced = Regex("(?is)```(?:text)?\\s*(.*?)```")
        .findAll(response).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
    if (fenced.isNotEmpty()) return fenced.joinToString("\n")

    return response.trim()
}
