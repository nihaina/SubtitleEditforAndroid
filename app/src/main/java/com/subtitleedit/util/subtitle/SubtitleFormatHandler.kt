package com.subtitleedit.util.subtitle

import com.subtitleedit.util.SubtitleParser

/** Subtitle Edit 风格的格式策略：探测、加载、序列化由同一个实现负责。 */
interface SubtitleFormatHandler {
    val format: SubtitleParser.SubtitleFormat
    val extensions: Set<String>

    fun isMine(lines: List<String>, fileName: String? = null): Boolean

    fun load(lines: List<String>, fileName: String? = null): SubtitleDocument

    fun write(document: SubtitleDocument): String
}

internal fun String.toSubtitleLines(): List<String> =
    replace("\r\n", "\n").replace('\r', '\n').split('\n')
