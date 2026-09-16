package com.subtitleedit.util

/** Keep every returned block; a long response can contain several fenced sections. */
internal fun extractSemanticMergeResponse(response: String): String {
    val marked = Regex("(?is)\\[\\[PUNCTUATED_TEXT\\]\\](.*?)\\[\\[/PUNCTUATED_TEXT\\]\\]")
        .findAll(response).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
    if (marked.isNotEmpty()) return marked.joinToString("\n")

    val fenced = Regex("(?is)```(?:text)?\\s*(.*?)```")
        .findAll(response).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
    if (fenced.isNotEmpty()) return fenced.joinToString("\n")

    return response.trim()
}
