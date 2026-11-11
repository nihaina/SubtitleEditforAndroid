package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.task.LongTaskController
import com.subtitleedit.task.TaskProgress
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FfprobeMediaInformation
import com.subtitleedit.util.ModelDirectoryManager
import com.subtitleedit.util.PickedUriAccess
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.UriDisplayName
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Independent copy-only mux/demux session; format-conversion state is never changed here. */
internal class MediaMuxViewModel(application: Application) :
    AppViewModel<MediaMuxUiState, Unit>(application, MediaMuxUiState()) {
    private val outputStore = MediaMuxOutputStore(application)
    private val settings = SettingsManager.getInstance(application)
    private val tasks = LongTaskController(dependencies.taskStateStore, "media-mux")
    private var initialized = false
    private var importing: Job? = null
    private var conflictAnswer: CompletableDeferred<Pair<MediaMuxAudioAction, Boolean>>? = null
    @Volatile private var session: FFmpegSession? = null
    private var outputRoot: Uri? = null
    val currentOutputDirectoryUri: Uri? get() = outputRoot

    init {
        val saved = settings.getPersistedOutputDirectory(OUTPUT_KEY)?.let(Uri::parse)
        val initial = saved ?: Uri.fromFile(File(ModelDirectoryManager.softwareDirectory(), "Convert").apply { mkdirs() })
        outputRoot = initial
        setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(application, initial)) }
    }

    fun initializeSelection(uris: List<Uri>) {
        if (initialized) return
        initialized = true
        if (uris.isNotEmpty()) addFiles(uris)
    }

    fun addFiles(uris: List<Uri>) {
        if (currentState.isRunning || currentState.isImporting || uris.isEmpty()) return
        setState { copy(isImporting = true) }
        importing = viewModelScope.launch {
            val added = mutableListOf<MediaMuxFile>()
            try {
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val name = UriDisplayName.of(app, uri, "media")
                        val probe = withContext(Dispatchers.IO) { probe(uri) }
                        val kind = when {
                            probe.format.split(',').any { it.endsWith("_pipe") || it == "image2" } -> error("不支持图片文件")
                            probe.videos.isNotEmpty() -> MediaMuxColumn.VIDEO
                            probe.audios.isNotEmpty() -> MediaMuxColumn.AUDIO
                            else -> error("文件没有可用的音视频轨")
                        }
                        runCatching { PickedUriAccess.retainPermission(app, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        added += MediaMuxFile(UUID.randomUUID().toString(), uri.toString(), name, kind)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        appendLog("${uri.lastPathSegment.orEmpty()}：${error.message.orEmpty()}")
                    }
                }
                setState { copy(rows = MediaMuxQueue.add(rows, added)) }
            } finally { setState { copy(isImporting = false) } }
        }
    }

    fun addEmptyRow() {
        if (!editable()) return
        setState { copy(rows = rows + MediaMuxRow(UUID.randomUUID().toString())) }
    }

    fun remove(rowId: String, column: MediaMuxColumn) {
        if (!editable()) return
        if (column == MediaMuxColumn.VIDEO) currentState.rows.firstOrNull { it.id == rowId }?.video?.let { outputStore.forget(it.id) }
        setState { copy(rows = MediaMuxQueue.remove(rows, rowId, column)) }
    }

    fun removeRow(rowId: String) {
        if (!editable()) return
        currentState.rows.firstOrNull { it.id == rowId }?.video?.let { outputStore.forget(it.id) }
        setState { copy(rows = rows.filterNot { it.id == rowId }) }
    }

    /** Removing a displayed result retains its files and the queued video's output folder. */
    fun removeOutput(rowId: String) {
        if (editable()) setState { copy(rows = MediaMuxQueue.removeOutput(rows, rowId)) }
    }

    fun move(column: MediaMuxColumn, from: Int, to: Int) {
        if (editable()) setState { copy(rows = MediaMuxQueue.move(rows, column, from, to)) }
    }

    fun setMode(mode: MediaMuxMode) { if (editable()) setState { copy(mode = mode) } }

    fun selectOutputDirectory(uri: Uri) {
        if (!editable()) return
        runCatching { PickedUriAccess.retainPermission(app, uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        outputRoot = uri
        settings.setPersistedOutputDirectory(OUTPUT_KEY, uri.toString())
        setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, uri)) }
    }

    fun startProcessing() {
        if (!currentState.canProcess) return
        val root = outputRoot ?: return
        val mode = currentState.mode
        val rows = currentState.rows.filter { it.eligible(mode) }
        setState { copy(isRunning = true, progress = 0, log = "", conflict = null) }
        tasks.launch(viewModelScope) { task ->
            var succeeded = 0
            var failed = 0
            var applyAll: MediaMuxAudioAction? = null
            task.onCancel { session?.cancel(); conflictAnswer?.cancel() }
            try {
                for ((index, row) in rows.withIndex()) {
                    task.ensureActive()
                    val video = requireNotNull(row.video)
                    tasks.progress(TaskProgress(video.fileName))
                    appendLog("[${index + 1}/${rows.size}] ${video.fileName}")
                    val cache = withContext(Dispatchers.IO) {
                        File(app.cacheDir, "media_mux_${UUID.randomUUID()}").apply { check(mkdirs()) }
                    }
                    try {
                        val source = withContext(Dispatchers.IO) { probe(Uri.parse(video.uri)) }
                        require(source.videos.isNotEmpty()) { "左列文件没有视频轨" }
                        val action = if (mode == MediaMuxMode.MERGE && source.audios.isNotEmpty()) {
                            applyAll ?: askAudioAction(video.fileName).let { (answer, all) ->
                                if (all) applyAll = answer
                                answer
                            }
                        } else MediaMuxAudioAction.REPLACE
                        val prepared = withContext(Dispatchers.IO) {
                            prepare(row, mode, action, source, cache, index, rows.size)
                        }
                        task.ensureActive()
                        withContext(Dispatchers.IO) {
                            val published = outputStore.publish(root, video, mode, prepared)
                            // Update before returning across the cancellable IO boundary: the
                            // committed row must stay visible if cancel arrives just after rename.
                            val result = published.first()
                            val audio = published.getOrNull(1)?.let {
                                MediaMuxFile(UUID.randomUUID().toString(), it.uri, it.fileName, MediaMuxColumn.AUDIO)
                            }
                            setState { copy(rows = this.rows.map { current ->
                                if (current.video?.id == video.id) current.copy(output = result,
                                    audio = if (mode == MediaMuxMode.SPLIT) audio else current.audio) else current
                            }) }
                            succeeded++
                            appendLog("已输出：${result.uri}")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failed++
                        task.recordFailure(error)
                        appendLog("${video.fileName}：${error.message.orEmpty()}")
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { cache.deleteRecursively() }
                    }
                    setState { copy(progress = ((index + 1) * 100 / rows.size)) }
                }
                appendLog("处理完成：成功 $succeeded，失败 $failed")
            } catch (cancelled: CancellationException) {
                appendLog("已取消，已完成 $succeeded 个文件")
                throw cancelled
            } finally {
                conflictAnswer = null
                setState { copy(isRunning = false, conflict = null) }
            }
        }
    }

    fun cancel() {
        tasks.cancel()
        importing?.cancel()
        session?.cancel()
        conflictAnswer?.cancel()
    }

    fun resolveAudioConflict(action: MediaMuxAudioAction?, applyToAll: Boolean) {
        if (action == null) cancel() else conflictAnswer?.complete(action to applyToAll)
    }

    private suspend fun askAudioAction(name: String): Pair<MediaMuxAudioAction, Boolean> {
        val answer = CompletableDeferred<Pair<MediaMuxAudioAction, Boolean>>()
        conflictAnswer = answer
        setState { copy(conflict = MediaMuxConflict(name)) }
        return try { answer.await() } finally {
            conflictAnswer = null
            setState { copy(conflict = null) }
        }
    }

    private suspend fun prepare(row: MediaMuxRow, mode: MediaMuxMode, action: MediaMuxAudioAction,
                                source: MediaMuxProbe, cache: File, index: Int, total: Int): List<Pair<String, File>> {
        val video = requireNotNull(row.video)
        val muxer = MediaMuxCommand.videoMuxer(video.fileName, source.format)
        val videoOutput = File(cache, "video.part")
        val videoInput = input(Uri.parse(video.uri))
        var audioInput: String? = null
        try {
            if (mode == MediaMuxMode.MERGE) {
                val audio = requireNotNull(row.audio)
                val external = probe(Uri.parse(audio.uri))
                audioInput = input(Uri.parse(audio.uri))
                execute(MediaMuxCommand.merge(videoInput, audioInput, videoOutput.path, muxer, source, external, action),
                    source.durationSeconds, index, total)
                val expectedAudio = external.audios.size + if (action == MediaMuxAudioAction.ADD) source.audios.size else 0
                verify(videoOutput, source.videos.size, expectedAudio)
                return listOf(video.fileName to videoOutput)
            }
            val audioFormat = MediaMuxCommand.audioFormat(source.audios.map { it.codec })
            val audioName = video.fileName.substringBeforeLast('.', video.fileName) + ".${audioFormat.extension}"
            val audioOutput = File(cache, "audio.part")
            execute(MediaMuxCommand.splitVideo(videoInput, videoOutput.path, muxer, source), source.durationSeconds, index, total)
            execute(MediaMuxCommand.splitAudio(videoInput, audioOutput.path, audioFormat.muxer, source), source.durationSeconds, index, total)
            verify(videoOutput, source.videos.size, 0)
            verify(audioOutput, 0, source.audios.size)
            return listOf(video.fileName to videoOutput, audioName to audioOutput)
        } finally {
            release(videoInput)
            release(audioInput)
        }
    }

    private fun probe(uri: Uri): MediaMuxProbe {
        val path = input(uri)
        return try {
            val info = FfprobeMediaInformation.read(path, app.cacheDir)
            MediaMuxProbe(info.getFormat().orEmpty(), info.getStreams().map { stream ->
                MediaMuxStream(requireNotNull(stream.getIndex()) { "缺少音视频轨编号" }, stream.getType().orEmpty(), stream.getCodec().orEmpty(),
                    stream.getAllProperties()?.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1)
            }, info.getDuration()?.toDoubleOrNull())
        } finally { release(path) }
    }

    private fun verify(file: File, videos: Int, audios: Int) {
        check(file.isFile && file.length() > 0L) { "输出文件为空" }
        val info = probe(Uri.fromFile(file))
        check(info.videos.size == videos && info.audios.size == audios) { "输出音视频轨与预期不一致" }
    }

    private suspend fun execute(arguments: List<String>, duration: Double?, index: Int, total: Int) {
        val lastProgress = AtomicLong(SystemClock.elapsedRealtime())
        val active = withContext(NonCancellable) {
            FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray(), {}, {}, { statistics ->
                if (statistics.time > 0) {
                    lastProgress.set(SystemClock.elapsedRealtime())
                    if (duration != null && duration > 0) setState {
                        copy(progress = ((index + (statistics.time / (duration * 1000)).coerceIn(0.0, 0.99)) * 100 / total).toInt())
                    }
                }
            })
        }
        session = active
        try {
            while (active.getState().name !in TERMINAL_STATES) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() - lastProgress.get() < 120_000L) { "处理超时" }
                delay(75)
            }
            currentCoroutineContext().ensureActive()
            check(ReturnCode.isSuccess(active.getReturnCode())) {
                active.getAllLogsAsString().takeLast(2000).ifBlank { "当前封装格式不支持这些音视频轨" }
            }
        } finally {
            withContext(NonCancellable) {
                if (active.getState().name !in TERMINAL_STATES) active.cancel()
                while (active.getState().name !in TERMINAL_STATES) delay(50)
            }
            if (session === active) session = null
        }
    }

    private fun input(uri: Uri): String = PickedUriAccess.file(uri)?.absolutePath ?: FFmpegKitConfig.getSafParameterForRead(app, uri)
    private fun release(path: String?) {
        if (path?.startsWith("saf:") == true) runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(path) }
    }
    private fun editable() = !currentState.isRunning && !currentState.isImporting
    private fun appendLog(value: String) = setState { copy(log = (log + "\n" + value).trimStart().takeLast(20_000)) }

    override fun onCleared() { cancel(); super.onCleared() }

    companion object {
        private const val OUTPUT_KEY = "media_mux"
        private val TERMINAL_STATES = setOf("COMPLETED", "FAILED")
    }
}
