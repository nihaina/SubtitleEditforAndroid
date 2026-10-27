package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
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
    private val requestLock = Any()
    private var updateJob: Job? = null
    @Volatile private var requestGeneration = 0L
    @Volatile private var released = false
    @Volatile private var installedPreviewFile: File? = null
    @Volatile private var previousInstalledPreviewFile: File? = null
    // Preview serialization prevents canceled-but-already-running full subtitle writes from
    // overlapping when a large document is edited repeatedly.
    private val previewDispatcher = Dispatchers.IO.limitedParallelism(1)

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
        sourceContent: String,
        debounceMs: Long = 300L
    ) {
        if (released) return
        val generation = ++requestGeneration
        // Keep the list structure stable for the worker, but defer the expensive per-entry copy
        // until the serialized IO dispatcher.  Previously this map { copy() } ran on the main
        // thread after the debounce and caused a visible pause for large subtitle files.
        val request = PreviewRequest(format, entries.toList(), sourceViewMode, sourceContent)
        updateJob?.cancel()
        updateJob = scope.launch {
            delay(debounceMs.coerceAtLeast(0L))
            val preview = withContext(previewDispatcher) {
                val entrySnapshot = request.entries.map { it.copy() }
                // The list is intentionally shallow-captured to keep large edits off the main
                // thread. If a mutable row changed while it was being copied, the schedule that
                // accompanied that mutation advanced requestGeneration; discard this mixed
                // snapshot instead of sending it to mpv.
                if (generation != requestGeneration || released) return@withContext null
                buildPreviewFile(
                    request.format,
                    entrySnapshot,
                    request.sourceViewMode,
                    request.sourceContent,
                    generation
                )
            }
            if (!isActive || generation != requestGeneration || released) {
                preview?.delete()
                return@launch
            }
            val accepted = synchronized(requestLock) {
                if (released || generation != requestGeneration) {
                    false
                } else {
                    // Serialize the final generation check with cancellation. This closes the
                    // narrow window where a media/document switch could invalidate a request
                    // after the check but before replaceTrack() queues it to mpv.
                    replaceTrack(preview)
                    if (preview != null) {
                        previousInstalledPreviewFile = installedPreviewFile
                        installedPreviewFile = preview
                    } else {
                        previousInstalledPreviewFile = installedPreviewFile
                        installedPreviewFile = null
                    }
                    true
                }
            }
            if (!accepted) {
                preview?.delete()
            }
        }
    }

    fun cancelPending() {
        synchronized(requestLock) {
            requestGeneration++
            updateJob?.cancel()
            updateJob = null
        }
    }

    fun release() {
        synchronized(requestLock) {
            released = true
            requestGeneration++
            updateJob?.cancel()
            updateJob = null
            replaceTrack(null)
            installedPreviewFile = null
            previousInstalledPreviewFile = null
        }
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
            format == SubtitleParser.SubtitleFormat.SRT || format == SubtitleParser.SubtitleFormat.LRC -> {
                // Subtitle Edit hands every non-ASS preview to libass as a temporary ASS
                // track.  Besides making SRT/LRC rendering use the same renderer as VTT,
                // this is what makes inline <font face="..."> tags become ASS \fn
                // overrides instead of being left to mpv's format-specific parser.
                val previewEntries = if (sourceViewMode && entries.isEmpty() && sourceContent.isNotBlank()) {
                    SubtitleParser.parse(sourceContent, format)
                } else {
                    entries
                }
                WebVttPreviewWriter.write(
                    SubtitleDocument(SubtitleParser.SubtitleFormat.VTT, previewEntries)
                )
            }
            sourceViewMode -> if (entries.isNotEmpty() || sourceContent.isBlank()) {
                SubtitleParser.toSRT(entries)
            } else {
                SubtitleParser.toSRT(SubtitleParser.parse(sourceContent, format))
            }
            else -> SubtitleParser.toSRT(entries)
        }
        if (content.isBlank()) return null

        if (released) return null

        sessionDir.mkdirs()
        val extension = when {
            format == SubtitleParser.SubtitleFormat.VTT ||
                format == SubtitleParser.SubtitleFormat.SRT ||
                format == SubtitleParser.SubtitleFormat.LRC -> "ass"
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
        if (released) {
            runCatching { destination.delete() }
            runCatching { sessionDir.deleteRecursively() }
            return null
        }
        cleanupPreviewFiles(
            keep = listOf(destination, installedPreviewFile, previousInstalledPreviewFile)
                .filterNotNull()
                .mapTo(mutableSetOf()) { it.absolutePath }
        )
        return destination
    }

    /** Keep the active and immediately previous tracks, removing abandoned generations. */
    private fun cleanupPreviewFiles(keep: Set<String>) {
        sessionDir.listFiles()?.forEach { file ->
            if (file.absolutePath in keep) return@forEach
            if (file.name.startsWith("live-")) {
                runCatching { file.delete() }
            }
        }
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
