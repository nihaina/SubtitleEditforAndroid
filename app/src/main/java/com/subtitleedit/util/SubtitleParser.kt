package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.subtitle.LrcSubtitleFormatHandler
import com.subtitleedit.util.subtitle.SrtSubtitleFormatHandler
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.SubtitleFormatHandler
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler
import com.subtitleedit.util.subtitle.toSubtitleLines
import java.io.BufferedReader
import java.io.StringReader
import java.nio.charset.Charset

/**
 * Subtitle Edit 风格的字幕解析入口。
 *
 * 格式探测、加载和写出由同一个 SubtitleFormatHandler 负责；扩展名只提高探测优先级，
 * 最终仍通过 isMine 验证内容。
 */
object SubtitleParser {
    enum class SubtitleFormat {
        SRT,
        LRC,
        TXT,
        ASS,
        SSA,
        VTT,
        UNKNOWN;

        val isSourceOnly: Boolean
            get() = this == TXT || this == ASS || this == SSA
    }

    private val handlers: List<SubtitleFormatHandler> = listOf(
        SrtSubtitleFormatHandler,
        LrcSubtitleFormatHandler,
        WebVttSubtitleFormatHandler
    )

    @Suppress("UNUSED_PARAMETER")
    fun parse(content: String, charset: Charset = Charsets.UTF_8): List<SubtitleEntry> =
        parseDocument(content).entries

    fun parse(content: String, format: SubtitleFormat): List<SubtitleEntry> =
        parseDocument(content, format = format).entries

    fun parseDocument(
        content: String,
        fileName: String? = null,
        format: SubtitleFormat? = null
    ): SubtitleDocument {
        val lines = content.toSubtitleLines()
        val selectedFormat = format ?: detectFormat(content, fileName)
        val handler = handlerFor(selectedFormat)
        if (handler != null) return handler.load(lines, fileName)

        return when (selectedFormat) {
            SubtitleFormat.TXT -> SubtitleDocument(selectedFormat, parseTXT(content))
            else -> SubtitleDocument(selectedFormat, emptyList(), header = content)
        }
    }

    fun detectFormat(content: String, fileName: String? = null): SubtitleFormat {
        val lines = content.toSubtitleLines()
        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()

        handlers.asSequence()
            .filter { extension in it.extensions }
            .firstOrNull { it.isMine(lines, fileName) }
            ?.let { return it.format }

        val trimmed = content.trim().trimStart('\uFEFF')
        when (extension) {
            "ass" -> if (trimmed.isNotEmpty()) return SubtitleFormat.ASS
            "ssa" -> if (trimmed.isNotEmpty()) return SubtitleFormat.SSA
            "txt" -> if (trimmed.isNotEmpty()) return SubtitleFormat.TXT
        }

        handlers.firstOrNull { it.isMine(lines, fileName) }?.let { return it.format }

        if (trimmed.contains("[V4+ Styles]", ignoreCase = true) ||
            trimmed.contains("ScriptType: v4.00+", ignoreCase = true)
        ) {
            return SubtitleFormat.ASS
        }
        if (trimmed.contains("[V4 Styles]", ignoreCase = true) ||
            trimmed.contains("ScriptType: v4.00", ignoreCase = true)
        ) {
            return SubtitleFormat.SSA
        }
        if (trimmed.contains("[Script Info]", ignoreCase = true) ||
            trimmed.contains("[Events]", ignoreCase = true)
        ) {
            return SubtitleFormat.ASS
        }
        return if (trimmed.isEmpty()) SubtitleFormat.UNKNOWN else SubtitleFormat.TXT
    }

    fun parseSRT(content: String): List<SubtitleEntry> =
        SrtSubtitleFormatHandler.load(content.toSubtitleLines()).entries

    fun parseLRC(content: String): List<SubtitleEntry> =
        LrcSubtitleFormatHandler.load(content.toSubtitleLines()).entries

    fun parseVTT(content: String): List<SubtitleEntry> =
        WebVttSubtitleFormatHandler.load(content.toSubtitleLines()).entries

    fun toSRT(entries: List<SubtitleEntry>): String =
        SrtSubtitleFormatHandler.write(SubtitleDocument(SubtitleFormat.SRT, entries))

    fun toLRC(entries: List<SubtitleEntry>, header: String = ""): String =
        LrcSubtitleFormatHandler.write(SubtitleDocument(SubtitleFormat.LRC, entries, header))

    fun toVTT(
        entries: List<SubtitleEntry>,
        header: String = "WEBVTT",
        footer: String = ""
    ): String = WebVttSubtitleFormatHandler.write(
        SubtitleDocument(SubtitleFormat.VTT, entries, header, footer)
    )

    fun serialize(document: SubtitleDocument): String {
        val handler = handlerFor(document.format)
        return when {
            handler != null -> handler.write(document)
            document.format == SubtitleFormat.TXT -> toTXT(document.entries)
            else -> document.header
        }
    }

    fun parseTXT(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val reader = BufferedReader(StringReader(content))
        var currentTime = 0L
        while (true) {
            val text = reader.readLine()?.trim() ?: break
            if (text.isNotEmpty()) {
                entries += SubtitleEntry(
                    index = entries.size + 1,
                    startTime = currentTime,
                    endTime = currentTime + 3_000,
                    text = text
                )
                currentTime += 3_000
            }
        }
        return entries
    }

    fun toTXT(entries: List<SubtitleEntry>): String = buildString {
        entries.forEach { appendLine(it.text) }
    }

    fun convertFormat(content: String, from: SubtitleFormat, to: SubtitleFormat): String {
        val targetHandler = handlerFor(to)
        if (targetHandler == null && to != SubtitleFormat.TXT) return content

        val source = parseDocument(content, format = from)
        val converted = source.copy(
            format = to,
            header = if (from == to) source.header else "",
            footer = if (from == to) source.footer else ""
        )
        return if (to == SubtitleFormat.TXT) {
            toTXT(converted.entries)
        } else {
            targetHandler!!.write(converted)
        }
    }

    private fun handlerFor(format: SubtitleFormat): SubtitleFormatHandler? =
        handlers.firstOrNull { it.format == format }
}
