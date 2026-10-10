package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.WebVttPreviewWriter
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler
import com.subtitleedit.util.subtitle.toSubtitleLines
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EditorSubtitlePreviewController(
    cacheDir: File,
    private val scope: CoroutineScope,
    private val replaceTrack: (File?) -> Unit
) {
    private val sessionDir = File(cacheDir, "mpv-subtitles/${UUID.randomUUID()}")
    private var updateJob: Job? = null
    private var pendingRequest: PreviewRequest? = null
    private var requestGeneration = 0L

    private data class PreviewRequest(
        val format: SubtitleParser.SubtitleFormat,
        val entries: List<SubtitleEntry>,
        val sourceViewMode: Boolean,
        val sourceContent: String
    )

    fun schedule(
        format: SubtitleParser.SubtitleFormat,
        entries: List<SubtitleEntry>,
        sourceViewMode: Boolean,
        sourceContent: String
    ) {
        val generation = ++requestGeneration
        pendingRequest = PreviewRequest(format, entries, sourceViewMode, sourceContent)
        updateJob?.cancel()
        updateJob = scope.launch {
            delay(300L)
            val request = pendingRequest ?: return@launch
            pendingRequest = null
            val entrySnapshot = request.entries.map { it.copy() }
            val preview = withContext(Dispatchers.IO) {
                buildPreviewFile(
                    request.format,
                    entrySnapshot,
                    request.sourceViewMode,
                    request.sourceContent,
                    generation
                )
            }
            if (!isActive || generation != requestGeneration) {
                preview?.delete()
                return@launch
            }
            replaceTrack(preview)
        }
    }

    fun cancelPending() {
        requestGeneration++
        updateJob?.cancel()
        updateJob = null
        pendingRequest = null
    }

    fun release() {
        cancelPending()
        replaceTrack(null)
        sessionDir.deleteRecursively()
    }

    private fun buildPreviewFile(
        format: SubtitleParser.SubtitleFormat,
        entries: List<SubtitleEntry>,
        sourceViewMode: Boolean,
        sourceContent: String,
        generation: Long
    ): File? {
        val rawSourceFormat = format == SubtitleParser.SubtitleFormat.ASS ||
            format == SubtitleParser.SubtitleFormat.SSA
        val content = when {
            format == SubtitleParser.SubtitleFormat.VTT -> {
                // FFmpeg's WebVTT decoder ignores STYLE colors. Only the temporary
                // playback track uses ASS; the editable/saved document remains WebVTT.
                buildWebVttPreview(entries, sourceViewMode, sourceContent)
            }
            rawSourceFormat -> sourceContent
            sourceViewMode -> if (entries.isNotEmpty() || sourceContent.isBlank()) {
                SubtitleParser.toSRT(entries)
            } else {
                SubtitleParser.toSRT(SubtitleParser.parse(sourceContent, format))
            }
            else -> SubtitleParser.toSRT(entries)
        }
        if (content.isBlank()) return null

        sessionDir.mkdirs()
        val extension = when {
            format == SubtitleParser.SubtitleFormat.VTT -> "ass"
            rawSourceFormat -> format.name.lowercase()
            else -> "srt"
        }
        val destination = File(sessionDir, "live-$generation.$extension")
        val staging = File(sessionDir, "live-$generation.$extension.tmp")
        staging.writeText(content, StandardCharsets.UTF_8)
        if (destination.exists()) destination.delete()
        if (!staging.renameTo(destination)) {
            staging.copyTo(destination, overwrite = true)
            staging.delete()
        }
        return destination
    }

    private fun buildWebVttPreview(
        entries: List<SubtitleEntry>, sourceViewMode: Boolean, sourceContent: String
    ): String {
        val lines = sourceContent.toSubtitleLines()
        // Keep entities encoded until the renderer parses markup, so &lt;i&gt;
        // stays literal text instead of becoming an italic tag.
        val rawDocument = WebVttSubtitleFormatHandler.parse(lines, decodeEntities = false).document
        if (sourceViewMode) return WebVttPreviewWriter.write(rawDocument)
        if (listOf("&lt;", "&gt;", "&amp;").none { it in sourceContent }) {
            return WebVttPreviewWriter.write(rawDocument.copy(entries = entries))
        }
        val decoded = WebVttSubtitleFormatHandler.parse(lines).document.entries
        val raw = rawDocument.entries
        if (decoded.size != raw.size) return WebVttPreviewWriter.write(rawDocument.copy(entries = entries))

        val originalText = decoded.zip(raw).groupBy { (entry, _) -> CueKey(entry) }.mapValues { (_, cues) ->
            cues.map { it.second.text }.distinct().singleOrNull()
        }
        val previewEntries = entries.mapIndexed { index, entry ->
            val sameRow = entries.size == decoded.size && decoded[index].text == entry.text &&
                decoded[index].cueIdentifier == entry.cueIdentifier && decoded[index].cueSettings == entry.cueSettings
            val text = originalText[CueKey(entry)] ?: if (sameRow) raw[index].text else entry.text
            entry.copy(text = text)
        }
        return WebVttPreviewWriter.write(rawDocument.copy(entries = previewEntries))
    }

    private data class CueKey(
        val start: Long, val end: Long, val text: String, val identifier: String, val settings: String
    ) {
        constructor(entry: SubtitleEntry) : this(
            entry.startTime, entry.endTime, entry.text, entry.cueIdentifier, entry.cueSettings
        )
    }
}
