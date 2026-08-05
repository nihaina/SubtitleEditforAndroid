package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EditorSubtitlePreviewController(
    cacheDir: File,
    private val scope: CoroutineScope,
    private val replaceTrack: (File?) -> Unit
) {
    private val sessionDir = File(cacheDir, "mpv-subtitles/${UUID.randomUUID()}")
    private var updateJob: Job? = null

    fun schedule(
        format: SubtitleParser.SubtitleFormat,
        entries: List<SubtitleEntry>,
        sourceViewMode: Boolean,
        sourceContent: String
    ) {
        updateJob?.cancel()
        val entrySnapshot = entries.map { it.copy() }
        val sourceSnapshot = sourceContent
        updateJob = scope.launch {
            delay(300L)
            val preview = withContext(Dispatchers.IO) {
                buildPreviewFile(format, entrySnapshot, sourceViewMode, sourceSnapshot)
            }
            replaceTrack(preview)
        }
    }

    fun release() {
        updateJob?.cancel()
        replaceTrack(null)
        sessionDir.deleteRecursively()
    }

    private fun buildPreviewFile(
        format: SubtitleParser.SubtitleFormat,
        entries: List<SubtitleEntry>,
        sourceViewMode: Boolean,
        sourceContent: String
    ): File? {
        val rawSourceFormat = format == SubtitleParser.SubtitleFormat.ASS ||
            format == SubtitleParser.SubtitleFormat.SSA ||
            format == SubtitleParser.SubtitleFormat.VTT
        val content = when {
            rawSourceFormat -> sourceContent
            sourceViewMode -> SubtitleParser.toSRT(SubtitleParser.parse(sourceContent, format))
            else -> SubtitleParser.toSRT(entries)
        }
        if (content.isBlank()) return null

        sessionDir.mkdirs()
        val extension = if (rawSourceFormat) format.name.lowercase() else "srt"
        val destination = File(sessionDir, "live.$extension")
        val staging = File(sessionDir, "live.$extension.tmp")
        staging.writeText(content, StandardCharsets.UTF_8)
        if (destination.exists()) destination.delete()
        if (!staging.renameTo(destination)) {
            staging.copyTo(destination, overwrite = true)
            staging.delete()
        }
        return destination
    }
}
