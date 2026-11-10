package com.subtitleedit

import com.arthenica.ffmpegkit.MediaInformation
import com.subtitleedit.util.SubtitleParser
import java.util.Locale
import org.json.JSONObject

/** Lyric tags have no FFmpeg stream index and must never be mapped as subtitle streams. */
internal data class MediaConvertEmbeddedLyric(
    val field: String,
    val title: String = "",
    val language: String = "",
    val text: String = "",
    val synced: Boolean = false
)

internal object MediaConvertEmbeddedLyrics {
    /** Ogg/Vorbis comments can live on an audio stream instead of the container metadata. */
    fun fromMediaInformation(info: MediaInformation, extension: String): List<MediaConvertEmbeddedLyric> {
        val container = fromMetadata(info.getTags().pairs(), extension)
        val streams = info.getStreams().filter { it.getType() == "audio" }.flatMap { stream ->
            val tags = stream.getTags()
            val language = tags.pairs().firstOrNull { it.first.equals("language", ignoreCase = true) }
                ?.second?.trim().orEmpty()
            fromMetadata(tags.pairs(), extension).map { lyric ->
                if (lyric.language.isBlank() && language.isNotBlank()) lyric.copy(language = language) else lyric
            }
        }
        return distinctLyrics(container + streams)
    }

    fun fromMetadata(metadata: List<Pair<String, String>>, extension: String): List<MediaConvertEmbeddedLyric> =
        distinctLyrics(metadata.mapNotNull { (rawKey, value) ->
            val key = rawKey.trim()
            val lower = key.lowercase(Locale.ROOT)
            val compact = lower.filter { it.isLetterOrDigit() }
            val namedId3 = lower.startsWith("lyrics-")
            // These describe where lyrics can be found, rather than containing a lyric document.
            if (lower in setOf("lyrics-url", "lyrics-uri", "lyrics-link", "lyrics-website")) return@mapNotNull null
            if (!namedId3 && compact !in setOf("lyrics", "unsyncedlyrics", "uslt", "sylt", "lyr",
                    "wmlyrics", "wmlyricssynchronised", "wmlyricssynchronized")) return@mapNotNull null
            val text = value.trim('\u0000', '\uFEFF').trim()
            if (text.isBlank()) return@mapNotNull null
            val suffix = if (namedId3) key.substring(7) else ""
            val language = suffix.substringAfterLast('-', suffix).takeIf { it.length == 3 && it.all(Char::isLetter) }.orEmpty()
            val title = if (language.isNotEmpty()) suffix.dropLast(4) else suffix
            val field = when {
                compact == "sylt" -> "SYLT"
                namedId3 || compact in setOf("uslt", "unsyncedlyrics") || extension.lowercase(Locale.ROOT) in setOf("mp3", "aac", "wav") -> "USLT"
                extension.lowercase(Locale.ROOT) in setOf("m4a", "m4b", "mp4", "mov") -> "©lyr"
                compact == "wmlyrics" -> "WM/Lyrics"
                compact in setOf("wmlyricssynchronised", "wmlyricssynchronized") -> "WM/Lyrics_Synchronised"
                else -> "LYRICS"
            }
            MediaConvertEmbeddedLyric(field, title, language, text,
                compact in setOf("sylt", "wmlyricssynchronised", "wmlyricssynchronized") ||
                    SubtitleParser.parseLRC(text).isNotEmpty())
        })

    fun merge(metadata: List<MediaConvertEmbeddedLyric>, id3: List<MediaConvertEmbeddedLyric>): List<MediaConvertEmbeddedLyric> =
        distinctLyrics(id3 + metadata.filterNot { tag -> id3.any { frame ->
            (frame.field.equals(tag.field, ignoreCase = true) ||
                (frame.field == "USLT" && tag.field == "LYRICS" && normalizedText(frame.text) == normalizedText(tag.text))) &&
                languagesMatch(frame.language, tag.language) &&
                (frame.title == tag.title || (tag.title.isBlank() && normalizedText(frame.text) == normalizedText(tag.text)))
        } })

    private fun JSONObject?.pairs(): List<Pair<String, String>> = this?.let { tags ->
        tags.keys().asSequence().mapNotNull { key ->
            (tags.opt(key) as? String)?.let { key to it }
        }.toList()
    }.orEmpty()

    private fun languagesMatch(first: String, second: String): Boolean =
        first.isBlank() || second.isBlank() || first.equals(second, ignoreCase = true)

    private fun normalizedText(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim('\u0000', '\uFEFF').trim()

    /** Collapse container/stream/player copies while preserving independently named/language versions. */
    private fun distinctLyrics(lyrics: List<MediaConvertEmbeddedLyric>): List<MediaConvertEmbeddedLyric> {
        val result = mutableListOf<MediaConvertEmbeddedLyric>()
        lyrics.forEach { lyric ->
            val duplicate = result.indexOfFirst { previous ->
                previous.field.equals(lyric.field, ignoreCase = true) && previous.title == lyric.title &&
                    languagesMatch(previous.language, lyric.language) && normalizedText(previous.text) == normalizedText(lyric.text)
            }
            if (duplicate < 0) result += lyric
            else if (result[duplicate].language.isBlank() && lyric.language.isNotBlank()) result[duplicate] = lyric
        }
        return result
    }
}
