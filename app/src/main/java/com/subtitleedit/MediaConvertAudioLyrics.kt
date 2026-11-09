package com.subtitleedit

import com.subtitleedit.util.SubtitleParser

/** Native lyric tags are separate from the timed subtitle tracks supported by MKA. */
internal object MediaConvertAudioLyrics {
    private val textTagMuxers = setOf("flac", "ogg", "opus", "spx", "ipod", "asf", "wv")

    /** A single-value tag cannot safely store several independently named lyric versions. */
    fun maxFiles(format: MediaConvertFormatInfo?): Int = when {
        format == null || !format.isAudioOnly -> 0
        format.formatName in setOf("mp3", "adts", "wav", "matroska") -> Int.MAX_VALUE
        format.formatName in textTagMuxers -> 1
        else -> 0
    }

    fun needsFileRewrite(format: MediaConvertFormatInfo): Boolean =
        format.isAudioOnly && format.formatName in setOf("mp3", "adts", "wav")

    fun usesSubtitleTracks(format: MediaConvertFormatInfo): Boolean =
        format.isAudioOnly && format.formatName == "matroska"

    /** LRC text is passed as one argument, so line breaks and shell characters remain data. */
    fun arguments(format: MediaConvertFormatInfo, lyrics: List<MediaConvertLyric>): List<String> {
        if (lyrics.isEmpty()) return emptyList()
        val maximum = maxFiles(format)
        require(maximum > 0 && lyrics.size <= maximum) { "The output format cannot store this many lyric files" }
        require(lyrics.all { it.lrc.isNotBlank() }) { "Cannot embed empty lyrics" }
        return when (format.formatName) {
            "mp3" -> listOf("-id3v2_version", "3") // USLT/SYLT frames are added to the completed staging file.
            "adts" -> listOf("-write_id3v2", "1") // Preserve metadata before adding USLT/SYLT to the ADTS staging file.
            "wav", "matroska" -> emptyList()
            "ipod" -> listOf("-metadata", "lyrics=${plainText(lyrics.single().lrc)}")
            "asf" -> listOf("-metadata", "WM/Lyrics=${lyrics.single().lrc}")
            "ogg", "opus", "spx" -> listOf(
                "-metadata", "LYRICS=${lyrics.single().lrc}",
                "-metadata:s:a:0", "LYRICS=${lyrics.single().lrc}"
            )
            "flac" -> listOf("-metadata", "LYRICS=${lyrics.single().lrc}")
            "wv" -> listOf("-metadata", "Lyrics=${lyrics.single().lrc}")
            else -> error("This audio container has no supported lyric tag")
        }
    }

    fun plainText(lrc: String): String {
        val entries = SubtitleParser.parseLRC(lrc).filter { it.text.isNotBlank() }
        require(entries.isNotEmpty()) { "The lyric file has no usable timed lines" }
        return entries.joinToString("\n") { it.text }
    }
}
