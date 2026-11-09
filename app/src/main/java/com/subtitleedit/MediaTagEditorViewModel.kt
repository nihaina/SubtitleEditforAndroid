package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.util.PickedUriAccess
import com.subtitleedit.util.UriDisplayName
import com.subtitleedit.util.FfprobeMediaInformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal data class MediaTagEditorState(
    val fileNames: List<String> = emptyList(),
    val selection: MediaConvertMetadataSelection = MediaConvertMetadataSelection.READING,
    val metadata: MediaConvertMetadata = MediaConvertMetadata(),
    val coverFileName: String? = null,
    val coverSupported: Boolean = false,
    val hasSourceCover: Boolean = false,
    val isReadingCover: Boolean = false,
    val isExportingCover: Boolean = false,
    val isSaving: Boolean = false,
    val progress: Float = 0f,
    val message: String = ""
) {
    val canSave: Boolean get() = selection.editable && !isReadingCover && !isSaving && !isExportingCover &&
        (metadata.editedFields.isNotEmpty() || metadata.cover != MediaConvertCoverMode.DEFAULT)
    val canExportCover: Boolean get() = selection.editable && !isSaving && !isReadingCover && !isExportingCover &&
        (metadata.cover == MediaConvertCoverMode.CUSTOM || (metadata.cover == MediaConvertCoverMode.DEFAULT && hasSourceCover))
}

internal class MediaTagEditorViewModel(application: Application) :
    AppViewModel<MediaTagEditorState, Unit>(application, MediaTagEditorState()) {
    private data class Source(
        val file: File,
        val muxer: String,
        val streams: List<MediaTagStream>,
        val media: MediaConvertSource,
        val metadata: MediaConvertMetadata,
        val covers: List<MediaTagCover>,
        val size: Long,
        val modified: Long
    )

    private var initialized = false
    private var sources: List<Source> = emptyList()
    private var coverUri: Uri? = null
    private var coverJob: Job? = null
    private var coverRequestId = 0L
    private var saveJob: Job? = null
    private var exportJob: Job? = null
    @Volatile private var session: FFmpegSession? = null

    fun initialize(paths: List<String>, initialCoverPath: String? = null) {
        if (initialized) return
        initialized = true
        val files = paths.distinct().map(::File)
        setState { copy(fileNames = files.map { it.name }) }
        viewModelScope.launch {
            val probed = withContext(Dispatchers.IO) { files.map { runCatching { probe(it) }.getOrNull() } }
            sources = probed.filterNotNull()
            val selection = MediaConvertMetadataSelection.fromSources(probed.map { it?.media }, pending = false)
            setState {
                copy(selection = selection,
                    metadata = probed.singleOrNull()?.metadata ?: MediaConvertMetadata(),
                    coverSupported = sources.isNotEmpty() && sources.all { MediaTagEditorCommand.supportsCover(it.muxer) },
                    hasSourceCover = sources.any { it.covers.isNotEmpty() })
            }
            initialCoverPath?.let { selectCover(Uri.fromFile(File(it))) }
        }
    }

    fun setMetadata(metadata: MediaConvertMetadata) {
        if (currentState.selection.editable && !currentState.isSaving && !currentState.isReadingCover && !currentState.isExportingCover) {
            if (metadata.cover != MediaConvertCoverMode.CUSTOM) coverUri = null
            setState { copy(metadata = metadata, coverFileName = coverFileName.takeIf { metadata.cover == MediaConvertCoverMode.CUSTOM }) }
        }
    }

    fun selectCover(uri: Uri) {
        if (!currentState.selection.editable || !currentState.coverSupported || currentState.isSaving || currentState.isExportingCover) return
        val requestId = ++coverRequestId
        coverJob?.cancel()
        setState { copy(isReadingCover = true) }
        coverJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { MediaConvertCoverImages.validate(app, uri) }
                runCatching { PickedUriAccess.retainPermission(app, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                coverUri = uri
                setState { copy(metadata = metadata.copy(cover = MediaConvertCoverMode.CUSTOM),
                    coverFileName = UriDisplayName.of(app, uri, "cover")) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toast(R.string.media_convert_cover_image_invalid)
            } finally {
                if (requestId == coverRequestId) setState { copy(isReadingCover = false) }
            }
        }
    }

    fun exportCover(directory: Uri) {
        if (!currentState.canExportCover || exportJob?.isActive == true) return
        val snapshot = currentState
        val selectedCover = coverUri
        val selectedSources = sources.toList()
        setState { copy(isExportingCover = true, message = string(R.string.media_tag_editor_exporting_cover)) }
        exportJob = viewModelScope.launch {
            val cache = File(app.cacheDir, "media_cover_export_${System.nanoTime()}")
            var exported = 0
            val failures = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val target = requireNotNull(PickedUriAccess.file(directory)) { string(R.string.media_convert_invalid_output_directory) }
                    check(target.isDirectory) { string(R.string.media_convert_invalid_output_directory) }
                    if (snapshot.metadata.cover == MediaConvertCoverMode.CUSTOM) {
                        val prepared = MediaConvertCoverImages.prepare(app, requireNotNull(selectedCover), cache)
                        currentCoroutineContext().ensureActive()
                        val name = snapshot.coverFileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "cover"
                        MediaTagCoverExport.copyUnique(prepared, target, "$name.jpg")
                        exported++
                    } else {
                        if (!cache.isDirectory && !cache.mkdirs()) error(string(R.string.media_convert_create_output_directory_failed))
                        for ((sourceIndex, source) in selectedSources.withIndex()) {
                            for ((coverIndex, cover) in source.covers.withIndex()) {
                                currentCoroutineContext().ensureActive()
                                val extracted = File(cache, "$sourceIndex-$coverIndex.${cover.extension}")
                                try {
                                    check(source.file.length() == source.size && source.file.lastModified() == source.modified) {
                                        string(R.string.media_tag_editor_source_changed)
                                    }
                                    extractCover(source.file, extracted, cover)
                                    currentCoroutineContext().ensureActive()
                                    val suffix = if (coverIndex == 0) "" else "_${coverIndex + 1}"
                                    MediaTagCoverExport.copyUnique(extracted, target, "${source.file.nameWithoutExtension}_cover$suffix.${cover.extension}")
                                    exported++
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    failures += "${source.file.name}：${error.message.orEmpty()}"
                                } finally { extracted.delete() }
                            }
                        }
                    }
                }
                val result = string(R.string.media_tag_editor_export_cover_result, exported, failures.size)
                if (exported == 0 && failures.isEmpty()) toast(R.string.media_tag_editor_export_cover_none)
                else toast(result)
                setState { copy(message = if (failures.isEmpty()) "" else (listOf(result) + failures).joinToString("\n")) }
            } catch (cancelled: CancellationException) {
                setState { copy(message = "") }
                throw cancelled
            } catch (error: Exception) {
                setState { copy(message = string(R.string.media_tag_editor_export_cover_failed, error.message.orEmpty())) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { cache.deleteRecursively() }
                setState { copy(isExportingCover = false) }
            }
        }
    }

    private suspend fun extractCover(source: File, output: File, cover: MediaTagCover) {
        var running: FFmpegSession? = null
        try {
            running = withContext(NonCancellable) {
                FFmpegKit.executeWithArgumentsAsync(MediaTagCoverExport.arguments(source.absolutePath, output.absolutePath, cover)
                    .toTypedArray(), {}, {}, {})
            }
            session = running
            val deadline = SystemClock.elapsedRealtime() + 120_000L
            while (running.getState().name !in setOf("COMPLETED", "FAILED")) {
                delay(50)
                check(SystemClock.elapsedRealtime() < deadline) { string(R.string.media_convert_timeout) }
            }
            currentCoroutineContext().ensureActive()
            check(ReturnCode.isSuccess(running.getReturnCode()) && output.isFile && output.length() > 0) {
                running.getAllLogsAsString().takeLast(2000).ifBlank { string(R.string.media_tag_editor_export_cover_none) }
            }
        } finally {
            withContext(NonCancellable) {
                running?.let { active ->
                    if (active.getState().name !in setOf("COMPLETED", "FAILED")) active.cancel()
                    while (active.getState().name !in setOf("COMPLETED", "FAILED")) delay(50)
                }
            }
            session = null
        }
    }

    fun save() {
        if (!currentState.canSave || saveJob?.isActive == true) return
        val snapshot = currentState
        val selectedCover = coverUri
        setState { copy(isSaving = true, progress = 0f, message = "") }
        saveJob = viewModelScope.launch {
            val failures = mutableListOf<String>()
            var saved = 0
            val cache = File(app.cacheDir, "media_tags_${System.nanoTime()}")
            try {
                val metadata = withContext(Dispatchers.IO) {
                    if (snapshot.metadata.cover == MediaConvertCoverMode.CUSTOM) {
                        val image = MediaConvertCoverImages.prepare(app, requireNotNull(selectedCover), cache)
                        snapshot.metadata.copy(coverPath = image.absolutePath)
                    } else snapshot.metadata
                }
                for ((index, source) in sources.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    setState { copy(message = source.file.name, progress = index.toFloat() / sources.size) }
                    try {
                        saveSource(source, metadata, snapshot.selection.audio, index, sources.size) { saved++ }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failures += "${source.file.name}：${error.message.orEmpty()}"
                    }
                }
                val result = string(R.string.media_tag_editor_result, saved, failures.size)
                if (failures.isEmpty()) {
                    toast(result)
                    sendEvent(Unit)
                } else {
                    setState { copy(message = (listOf(result) + failures).joinToString("\n")) }
                }
            } catch (error: CancellationException) {
                setState { copy(message = string(R.string.media_tag_editor_cancelled, saved)) }
                throw error
            } catch (error: Exception) {
                setState { copy(message = error.message ?: string(R.string.media_tag_editor_failed)) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { cache.deleteRecursively() }
                setState { copy(isSaving = false, progress = 0f) }
            }
        }
    }

    fun cancel() {
        session?.cancel()
        saveJob?.cancel()
        exportJob?.cancel()
    }

    /** A sibling staging file allows the successful remux to replace its source atomically. */
    private suspend fun saveSource(source: Source, metadata: MediaConvertMetadata, isAudio: Boolean,
                                   index: Int, total: Int, onCommitted: () -> Unit) = withContext(Dispatchers.IO) {
        check(source.file.isFile && source.file.canWrite()) { string(R.string.media_tag_editor_not_writable) }
        check(source.file.length() == source.size && source.file.lastModified() == source.modified) {
            string(R.string.media_tag_editor_source_changed)
        }
        val staging = File.createTempFile(".media_tags_", ".${source.file.extension}", source.file.parentFile)
        var running: FFmpegSession? = null
        try {
            val arguments = MediaTagEditorCommand.build(source.file.absolutePath, staging.absolutePath,
                source.muxer, source.streams, metadata, isAudio)
            val lastProgress = AtomicLong(SystemClock.elapsedRealtime())
            running = withContext(NonCancellable) {
                FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray(), {}, {}, { statistics ->
                    if (statistics.time > 0) {
                        lastProgress.set(SystemClock.elapsedRealtime())
                        val durationMs = (source.media.durationSeconds ?: 0.0) * 1000
                        if (durationMs > 0) setState {
                            copy(progress = (index + (statistics.time / durationMs).coerceIn(0.0, 0.99)).toFloat() / total)
                        }
                    }
                })
            }
            session = running
            while (running.getState().name !in setOf("COMPLETED", "FAILED")) {
                delay(100)
                check(SystemClock.elapsedRealtime() - lastProgress.get() <= 120_000L) {
                    string(R.string.media_convert_timeout)
                }
            }
            currentCoroutineContext().ensureActive()
            check(ReturnCode.isSuccess(running.getReturnCode()) && staging.length() > 0L) {
                running.getAllLogsAsString().takeLast(2000).ifBlank { string(R.string.media_tag_editor_failed) }
            }
            val rewritten = probe(staging)
            fun mediaStreams(value: Source) = value.streams.filter {
                it.type in setOf("audio", "video", "subtitle") && !it.attachedPicture
            }.groupingBy { it.type }.eachCount()
            check(mediaStreams(rewritten) == mediaStreams(source)) {
                string(R.string.media_convert_ffmpeg_invalid_output)
            }
            check(source.file.length() == source.size && source.file.lastModified() == source.modified) {
                string(R.string.media_tag_editor_source_changed)
            }
            currentCoroutineContext().ensureActive()
            Os.rename(staging.absolutePath, source.file.absolutePath)
            sources = sources.map { if (it.file == source.file) rewritten.copy(file = source.file) else it }
            onCommitted()
        } finally {
            // Wait for cancellation to release the file before removing an unfinished output.
            withContext(NonCancellable) {
                running?.let { active ->
                    if (active.getState().name !in setOf("COMPLETED", "FAILED")) active.cancel()
                    while (active.getState().name !in setOf("COMPLETED", "FAILED")) delay(100)
                }
                staging.delete()
            }
            session = null
        }
    }

    private fun probe(file: File): Source {
        check(file.isFile)
        val size = file.length()
        val modified = file.lastModified()
        val info = FfprobeMediaInformation.read(file.absolutePath, app.cacheDir)
        val streams = info.getStreams().map { stream ->
            val tags = stream.getTags()
            val filename = tags?.optString("filename").orEmpty().lowercase(Locale.ROOT)
            val mime = tags?.optString("mimetype").orEmpty()
            MediaTagStream(requireNotNull(stream.getIndex()), requireNotNull(stream.getType()),
                attachedPicture = stream.getAllProperties()?.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1,
                coverAttachment = stream.getType() == "attachment" && mime.startsWith("image/") &&
                    filename.substringBeforeLast('.') in setOf("cover", "cover_land", "small_cover", "small_cover_land"))
        }
        val video = streams.any { it.type == "video" && !it.attachedPicture }
        val audio = streams.any { it.type == "audio" }
        val primaryVideo = info.getStreams().firstOrNull { stream ->
            stream.getType() == "video" && streams.none { it.index == stream.getIndex() && it.attachedPicture }
        }
        val primaryAudio = info.getStreams().firstOrNull { it.getType() == "audio" }
        val tags = listOfNotNull(info.getTags(), primaryVideo?.getTags(), primaryAudio?.getTags())
        val metadata = MediaConvertMetadata.fromTags { key ->
            tags.firstNotNullOfOrNull { tag ->
                tag.keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) }
                    ?.let { tag.optString(it).takeIf { value -> value.isNotBlank() && !value.equals("null", true) } }
            }
        }
        val formats = info.getFormat().orEmpty().split(',')
        val extension = file.extension.lowercase(Locale.ROOT)
        val muxer = when {
            "mp4" in formats || "mov" in formats -> when (extension) {
                "m4a", "m4b" -> "ipod"
                "mov" -> "mov"
                "3gp" -> "3gp"
                "3g2" -> "3g2"
                else -> "mp4"
            }
            "matroska" in formats -> if (extension == "webm") "webm" else "matroska"
            else -> formats.first()
        }
        check(file.length() == size && file.lastModified() == modified) { string(R.string.media_tag_editor_source_changed) }
        val covers = info.getStreams().mapNotNull { stream ->
            val description = streams.first { it.index == stream.getIndex() }
            MediaTagCoverExport.cover(description, stream.getCodec(), stream.getTags()?.optString("filename"))
        }
        return Source(file, muxer, streams, MediaConvertSource(video, info.getDuration()?.toDoubleOrNull(), hasAudio = audio),
            metadata, covers, size, modified)
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}
