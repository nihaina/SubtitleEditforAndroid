package com.subtitleedit.player

import android.os.ParcelFileDescriptor
import com.subtitleedit.MediaConvertEmbeddedLyric
import com.subtitleedit.MediaConvertEmbeddedLyricReader
import com.subtitleedit.MediaConvertEmbeddedLyrics
import com.subtitleedit.MediaTagNative
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Bridges lyric tags (which are not demuxed subtitle streams) into mpv's subtitle pipeline. */
internal object PlayerEmbeddedLyrics {
    internal data class PreparedLyric(val source: MediaConvertEmbeddedLyric, val srt: String)

    /** The caller supplies a private session directory and performs this work off the UI thread. */
    fun load(
        file: File,
        cacheDir: File,
        durationMs: Long,
        labelPrefix: String = "内嵌歌词"
    ): List<PlayerSubtitleOption> {
        val id3 = try {
            file.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
        } catch (_: Exception) {
            emptyList()
        }
        val metadata = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val properties = MediaTagNative.read(descriptor.fd).orEmpty()
                if (properties.size % 2 != 0) emptyList() else properties.toList().chunked(2).map { it[0] to it[1] }
            }
        } catch (_: Exception) {
            emptyList()
        } catch (_: LinkageError) {
            emptyList()
        }
        val lyrics = MediaConvertEmbeddedLyrics.merge(
            MediaConvertEmbeddedLyrics.fromMetadata(metadata, file.extension), id3
        )
        return try {
            writePrepared(prepare(lyrics, durationMs), cacheDir, labelPrefix)
        } catch (_: Exception) {
            // A damaged lyric or a full cache must not prevent otherwise playable audio.
            emptyList()
        }
    }

    /** Timed tags use the editor's entries unchanged; untimed tags keep their complete text. */
    internal fun prepare(lyrics: List<MediaConvertEmbeddedLyric>, durationMs: Long): List<PreparedLyric> =
        lyrics.mapNotNull { lyric ->
            val text = lyric.text
            if (text.isBlank()) return@mapNotNull null
            // A timestamp-looking line in SRT/VTT dialogue is still dialogue. Give
            // explicit document syntax priority over the tolerant LRC reader.
            val vtt = text.lineSequence().firstOrNull()?.trimStart { it.isWhitespace() || it == '\uFEFF' }
                ?.startsWith("WEBVTT", ignoreCase = true) == true
            val format = SubtitleParser.detectFormat(text, if (vtt) "lyrics.vtt" else "lyrics.srt")
            val lrc = if (format in setOf(SubtitleParser.SubtitleFormat.SRT, SubtitleParser.SubtitleFormat.VTT))
                emptyList() else SubtitleParser.parseLRC(text)
            val entries = if (lrc.isNotEmpty()) lrc else {
                when (format) {
                    SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.parseSRT(text)
                    SubtitleParser.SubtitleFormat.VTT -> SubtitleParser.parseVTT(text)
                    SubtitleParser.SubtitleFormat.TXT -> {
                        if (lyric.synced || looksTimed(text) || durationMs <= 0L) return@mapNotNull null
                        listOf(SubtitleEntry(1, 0L, durationMs, text))
                    }
                    else -> return@mapNotNull null
                }
            }
            if (entries.isEmpty()) null else PreparedLyric(lyric, SubtitleParser.toSRT(entries))
        }.fold(mutableListOf<PreparedLyric>()) { unique, prepared ->
            // ID3 often stores USLT and SYLT copies of the same named version.
            // Keep independent language/name versions, rather than raw tag representations.
            val duplicate = unique.indexOfFirst { previous: PreparedLyric ->
                previous.source.title.trim() == prepared.source.title.trim() &&
                    (languageKey(previous.source.language) == languageKey(prepared.source.language) ||
                        languageKey(previous.source.language).isBlank() || languageKey(prepared.source.language).isBlank()) &&
                    sameDocument(previous, prepared)
            }
            if (duplicate < 0) unique += prepared
            else if (unique[duplicate].source.field == "SYLT" && prepared.source.field == "USLT") {
                // When both ID3 representations exist, keep the original text document.
                unique[duplicate] = prepared
            } else if (languageKey(unique[duplicate].source.language).isBlank() && languageKey(prepared.source.language).isNotBlank()) {
                unique[duplicate] = prepared
            }
            unique
        }

    /** Content-derived names survive a second discovery and keep explicit selections stable. */
    internal fun writePrepared(
        lyrics: List<PreparedLyric>,
        cacheDir: File,
        labelPrefix: String
    ): List<PlayerSubtitleOption> {
        if (lyrics.isEmpty()) return emptyList()
        check(cacheDir.isDirectory || cacheDir.mkdirs()) { "Cannot create embedded lyric cache" }
        return lyrics.mapIndexed { index, prepared ->
            val source = prepared.source
            val digest = MessageDigest.getInstance("SHA-256").digest(
                "${source.title.trim()}\u0000${languageKey(source.language)}\u0000${prepared.srt}".toByteArray(Charsets.UTF_8)
            ).joinToString("") { "%02x".format(it) }
            val target = File(cacheDir, "lyric-$digest.srt")
            if (!target.isFile) {
                val temporary = File.createTempFile("lyric-", ".tmp", cacheDir)
                try {
                    temporary.writeText(prepared.srt, Charsets.UTF_8)
                    check(temporary.renameTo(target) || target.isFile) { "Cannot prepare embedded lyrics" }
                } finally {
                    temporary.delete()
                }
            }
            val description = listOf(source.title.trim(), languageKey(source.language)).filter { it.isNotBlank() }.joinToString(" · ")
            val suffix = description.ifBlank { if (lyrics.size > 1) (index + 1).toString() else "" }
            PlayerSubtitleOption(
                id = "external:${target.absolutePath}",
                label = if (suffix.isBlank()) labelPrefix else "$labelPrefix · $suffix",
                kind = PlayerSubtitleKind.EXTERNAL,
                path = target.absolutePath
            )
        }
    }

    private fun sameDocument(first: PreparedLyric, second: PreparedLyric): Boolean {
        if (first.srt == second.srt) return true
        val fields = setOf(first.source.field, second.source.field)
        if (fields != setOf("USLT", "SYLT")) return false
        val synchronized = if (first.source.field == "SYLT") first else second
        // The shared ID3 reader serializes a synthetic end after the final SYLT
        // event. Match paired representations without rewriting either document.
        if (!synchronized.source.openEnded) return false
        fun cues(lyric: PreparedLyric) = SubtitleParser.parseSRT(lyric.srt).map { it.startTime to it.text }
        return cues(first) == cues(second)
    }

    private fun looksTimed(text: String): Boolean = text.contains("-->") || text.startsWith("WEBVTT", true) ||
        Regex("""(?m)^\s*\[-?\d{1,4}:""").containsMatchIn(text)

    private fun languageKey(language: String): String = language.trim().lowercase(Locale.ROOT)
        .takeUnless { it == "und" }.orEmpty()
}
