package com.subtitleedit.util

/** Punctuation choices shared by the ASR settings UI and segment post-processing. */
internal object AsrPunctuationOptions {
    val choices = listOf(
        "，", ",", "、", "。", "．", ".", "？", "?", "！", "!", "：", ":", "；", ";",
        "“", "”", "‘", "’", "「", "」", "『", "』", "\"", "'",
        "（", "）", "(", ")", "【", "】", "[", "]", "—", "–", "-", "…", "·",
    )
    val defaults: Set<String> = listOf(
        "，", ",", "、", "；", ";", "。", "．", ".", "？", "?", "！", "!",
    ).toSet()
    val defaultCodePoints: Set<Int> = defaults.mapTo(mutableSetOf()) { it.codePointAt(0) }

    fun codePoints(selection: Collection<String>): Set<Int> =
        selection.asSequence()
            .filter { it in choices }
            .mapTo(mutableSetOf()) { it.codePointAt(0) }
}
