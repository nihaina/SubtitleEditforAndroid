package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.util.Locale

/** Uses the editor's format handlers for lyric tags and timed video subtitles. */
internal object MediaConvertLyricText {
    /** Native lyric-tag writers consume LRC regardless of the picked subtitle format. */
    fun toLrc(content: String, fileName: String, durationSeconds: Double?): String {
        val text = content.removePrefix("\uFEFF")
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val format = when (extension) {
            "lrc" -> SubtitleParser.SubtitleFormat.LRC
            "txt" -> SubtitleParser.SubtitleFormat.TXT
            "srt", "vtt" -> SubtitleParser.detectFormat(text, fileName)
            else -> throw IllegalArgumentException("Convert this subtitle to SRT before preparing lyric tags")
        }
        require(format in setOf(SubtitleParser.SubtitleFormat.LRC, SubtitleParser.SubtitleFormat.TXT,
            SubtitleParser.SubtitleFormat.SRT, SubtitleParser.SubtitleFormat.VTT)) {
            "The subtitle format cannot be converted to LRC"
        }
        // A malformed timed subtitle must not acquire TXT's generated three-second timing.
        require(extension == "txt" || format != SubtitleParser.SubtitleFormat.TXT) {
            "The subtitle file has no usable timed lines"
        }
        val lrc = if (format == SubtitleParser.SubtitleFormat.LRC) text else
            SubtitleParser.convertFormat(text, format, SubtitleParser.SubtitleFormat.LRC)
        val document = SubtitleParser.parseDocument(lrc, format = SubtitleParser.SubtitleFormat.LRC)
        val entries = boundedEntries(document.entries, durationSeconds)
        // Keep existing LRC metadata, offsets, timestamp precision and repeated tags verbatim
        // unless a media boundary actually requires rewriting the document.
        return if (entries == document.entries) lrc else SubtitleParser.serialize(document.copy(entries = entries))
    }

    /** LRC has start times only; a muxed video track needs a finite final cue. */
    fun lrcToSrt(content: String, durationSeconds: Double?): String {
        val converted = SubtitleParser.convertFormat(content.removePrefix("\uFEFF"),
            SubtitleParser.SubtitleFormat.LRC, SubtitleParser.SubtitleFormat.SRT)
        return SubtitleParser.toSRT(boundedEntries(SubtitleParser.parseSRT(converted), durationSeconds))
    }

    private fun boundedEntries(entries: List<SubtitleEntry>, durationSeconds: Double?): List<SubtitleEntry> {
        val durationMs = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * 1_000).toLong().coerceAtLeast(1L) }
        val bounded = entries.mapNotNull { entry ->
            val start = entry.startTime.coerceAtLeast(0L)
            val end = durationMs?.let { minOf(entry.endTime, it) } ?: entry.endTime
            if (entry.text.isBlank() || end <= start || (durationMs != null && start >= durationMs)) null
            else entry.copy(startTime = start, endTime = end)
        }
        require(bounded.isNotEmpty()) { "The lyric file has no usable timed lines" }
        return bounded
    }
}
