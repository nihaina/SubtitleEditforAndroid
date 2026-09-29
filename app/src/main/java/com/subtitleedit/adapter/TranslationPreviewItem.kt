package com.subtitleedit.adapter

data class TranslationPreviewItem(
    val entryPosition: Int,
    val originalText: String,
    var translatedText: String,
    var apply: Boolean = true,
    var suspectedProblem: Boolean = false
)
