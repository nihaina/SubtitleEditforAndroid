package com.subtitleedit

import com.subtitleedit.util.SubtitleParser
import java.util.Locale

/** Lyric tags have no FFmpeg stream index and must never be mapped as subtitle streams. */
internal data class MediaConvertEmbeddedLyric(
    val field: String,
    val title: String = "",
    val language: String = "",
    val text: String = "",
    val synced: Boolean = false
)

internal object MediaConvertEmbeddedLyrics {
    fun fromMetadata(metadata: List<Pair<String, String>>, extension: String): List<MediaConvertEmbeddedLyric> =
        metadata.mapNotNull { (key, value) ->
            val lower = key.lowercase(Locale.ROOT)
            val compact = lower.filter { it.isLetterOrDigit() }
            val namedId3 = lower.startsWith("lyrics-")
            if (!namedId3 && compact !in setOf("lyrics", "unsyncedlyrics", "uslt", "sylt", "lyr",
                    "wmlyrics", "wmlyricssynchronised", "wmlyricssynchronized")) return@mapNotNull null
            val text = value.trim('\u0000', '\uFEFF').trim()
            if (text.isBlank()) return@mapNotNull null
            val suffix = if (namedId3) key.substring(7) else ""
            val language = suffix.substringAfterLast('-', suffix).takeIf { it.length == 3 && it.all(Char::isLetter) }.orEmpty()
            val title = if (language.isNotEmpty()) suffix.dropLast(4) else suffix
            val field = when {
                compact == "sylt" -> "SYLT"
                namedId3 || compact in setOf("uslt", "unsyncedlyrics") || extension.lowercase(Locale.ROOT) in setOf("mp3", "wav") -> "USLT"
                extension.lowercase(Locale.ROOT) in setOf("m4a", "m4b", "mp4", "mov") -> "©lyr"
                compact.startsWith("wmlyrics") -> key
                else -> "LYRICS"
            }
            MediaConvertEmbeddedLyric(field, title, language, text,
                compact in setOf("sylt", "wmlyricssynchronised", "wmlyricssynchronized") ||
                    SubtitleParser.parseLRC(text).isNotEmpty())
        }.distinct()

    fun merge(metadata: List<MediaConvertEmbeddedLyric>, id3: List<MediaConvertEmbeddedLyric>): List<MediaConvertEmbeddedLyric> =
        (id3 + metadata.filterNot { tag -> id3.any { frame ->
            frame.field == tag.field && frame.title == tag.title &&
                (frame.language == tag.language || tag.language.isBlank())
        } }).distinct()
}
