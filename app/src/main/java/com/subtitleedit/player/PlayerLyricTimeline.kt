package com.subtitleedit.player

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.io.IOException
import java.util.Locale

/** Reads display-only lyric lines; the original subtitle remains the native playback source. */
internal object PlayerLyricTimeline {
    val supportedExtensions = setOf("srt", "lrc", "vtt", "ass", "ssa")

    fun read(file: File, durationMs: Long): List<PlayerLyricLine> {
        if (!file.isFile || !file.canRead()) throw IOException("无法读取字幕文件：${file.name}")
        return parse(FileUtils.readFile(file), file.name, durationMs)
    }

    @Suppress("UNUSED_PARAMETER")
    fun parse(text: String, fileName: String, durationMs: Long): List<PlayerLyricLine> {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in supportedExtensions) { "不支持的字幕格式：$extension" }
        val format = when (extension) {
            "lrc" -> SubtitleParser.SubtitleFormat.LRC
            "srt" -> SubtitleParser.SubtitleFormat.SRT
            "vtt" -> SubtitleParser.SubtitleFormat.VTT
            else -> null
        }
        // Select the existing timed handler explicitly: a malformed subtitle must not
        // fall back to TXT's generated times. Its entries otherwise remain untouched.
        val entries = if (format != null) {
            SubtitleParser.parseDocument(text, fileName, format).entries
        } else {
            assEntries(text)
        }
        require(entries.isNotEmpty()) { "字幕文件没有可用的时间轴内容" }
        return entries.map { PlayerLyricLine(it.startTime, it.endTime, it.text) }
    }

    /** Input retains document order, which need not be chronological. */
    fun activeLineIndex(lines: List<PlayerLyricLine>, positionMs: Long): Int {
        var active = -1
        lines.forEachIndexed { index, line ->
            if (positionMs >= line.startMs && positionMs < line.endMs &&
                (active < 0 || line.startMs >= lines[active].startMs)) {
                active = index
            }
        }
        return active
    }

    /** ASS/SSA are source-only editor documents, so read their declared Events columns here. */
    private fun assEntries(content: String): List<SubtitleEntry> {
        var inEvents = false
        var columns: List<String>? = null
        val entries = mutableListOf<SubtitleEntry>()
        for (raw in content.lineSequence()) {
            val line = raw.trimStart()
            if (line.startsWith('[') && line.trimEnd().endsWith(']')) {
                inEvents = line.trimEnd().equals("[Events]", true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", true)) {
                columns = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                continue
            }
            if (!line.startsWith("Dialogue:", true)) continue
            val fields = columns ?: continue
            val startIndex = fields.indexOf("start")
            val endIndex = fields.indexOf("end")
            val textIndex = fields.indexOf("text")
            // Text is the final field in ASS/SSA and may itself contain commas.
            if (startIndex < 0 || endIndex < 0 || textIndex != fields.lastIndex) continue
            val values = line.substringAfter(':').split(',', limit = fields.size)
            if (values.size != fields.size) continue
            val start = assTime(values[startIndex]) ?: continue
            val end = assTime(values[endIndex]) ?: continue
            entries += SubtitleEntry(entries.size + 1, start, end, values[textIndex])
        }
        return entries
    }

    private fun assTime(value: String): Long? {
        val match = assTimestamp.matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        if (minutes >= 60L || seconds >= 60L) return null
        val milliseconds = match.groupValues[4].padEnd(3, '0').toLong()
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L + milliseconds
    }

    private val assTimestamp = Regex("""(\d{1,4}):(\d{1,2}):(\d{1,2})\.(\d{1,3})""")
}
