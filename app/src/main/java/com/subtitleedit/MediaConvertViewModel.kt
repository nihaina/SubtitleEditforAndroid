package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.feature.ui.MediaConvertDialog
import com.subtitleedit.task.LongTaskController
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal data class MediaConvertFormatInfo(
    val extension: String,
    val displayName: String,
    val formatName: String,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val isAudioOnly: Boolean = false
)

internal data class MediaConvertFileUi(
    val uri: Uri,
    val fileName: String,
    val mediaInfo: String
)

internal data class MediaConvertUiState(
    val selectedFiles: List<MediaConvertFileUi> = emptyList(),
    val selectedFormat: String? = null,
    val selectedVideoCodec: String = "",
    val selectedAudioCodec: String = "",
    val resolutionIndex: Int = 0,
    val videoBitrate: String = "",
    val selectedQualityId: String = "-1",
    val customQuality: String = "",
    val audioBitrate: String = "",
    val sampleRateIndex: Int = 0,
    val channelIndex: Int = 0,
    val advancedExpanded: Boolean = false,
    val outputDirectory: String = "",
    val progress: Int = 0,
    val log: String = "",
    val dialog: MediaConvertDialog = MediaConvertDialog.NONE,
    val outputUris: List<Uri> = emptyList(),
    val isConverting: Boolean = false
)

internal sealed interface MediaConvertEvent {
    data class ShareOutputs(val uris: List<Uri>) : MediaConvertEvent
}

/** Owns format conversion state and FFmpeg work across Activity recreation. */
internal class MediaConvertViewModel(application: Application) :
    AppViewModel<MediaConvertUiState, MediaConvertEvent>(
        application,
        MediaConvertUiState(
            selectedVideoCodec = application.getString(R.string.media_convert_codec_video_required),
            selectedAudioCodec = application.getString(R.string.media_convert_codec_format_required)
        )
    ) {

    companion object {
        private const val OUTPUT_DIRECTORY_KEY = "media_convert"
    }

    private data class SelectedMediaFile(val uri: Uri, val fileName: String, val mediaInfo: String)
    private data class SourceProbe(val hasVideo: Boolean, val durationSeconds: Double?)
    private data class OutputResult(val uri: Uri, val fileName: String)

    val formatList = listOf(
        MediaConvertFormatInfo("mp4", "MP4", "mp4", listOf("mpeg4", "libx264", "copy"), listOf("aac", "libmp3lame", "copy")),
        MediaConvertFormatInfo("mkv", "MKV", "matroska", listOf("mpeg4", "libx264", "libvpx-vp9", "copy"), listOf("aac", "libmp3lame", "libopus", "libvorbis", "flac", "ac3", "copy")),
        MediaConvertFormatInfo("avi", "AVI", "avi", listOf("mpeg4", "libx264", "copy"), listOf("libmp3lame", "aac", "ac3", "copy")),
        MediaConvertFormatInfo("mov", "MOV", "mov", listOf("mpeg4", "libx264", "copy"), listOf("aac", "libmp3lame", "ac3", "copy")),
        MediaConvertFormatInfo("webm", "WebM", "webm", listOf("libvpx", "libvpx-vp9", "copy"), listOf("libvorbis", "libopus", "copy")),
        MediaConvertFormatInfo("flv", "FLV", "flv", listOf("libx264", "flv1", "mpeg4", "copy"), listOf("aac", "libmp3lame", "copy")),
        MediaConvertFormatInfo("ts", "TS", "mpegts", listOf("libx264", "mpeg2video", "mpeg4", "copy"), listOf("aac", "libmp3lame", "ac3", "copy")),
        MediaConvertFormatInfo("m4v", "M4V", "mp4", listOf("mpeg4", "libx264", "copy"), listOf("aac", "libmp3lame", "copy")),
        MediaConvertFormatInfo("3gp", "3GP", "3gp", listOf("mpeg4", "libx264", "copy"), listOf("aac", "libmp3lame", "copy")),
        MediaConvertFormatInfo("wmv", "WMV", "asf", listOf("wmv2", "msmpeg4v3", "copy"), listOf("wmav2", "copy")),
        MediaConvertFormatInfo("mp3", "MP3", "mp3", emptyList(), listOf("libmp3lame"), true),
        MediaConvertFormatInfo("aac", "AAC", "adts", emptyList(), listOf("aac"), true),
        MediaConvertFormatInfo("m4a", "M4A", "ipod", emptyList(), listOf("aac"), true),
        MediaConvertFormatInfo("wav", "WAV", "wav", emptyList(), listOf("pcm_s16le", "pcm_s24le", "pcm_f32le"), true),
        MediaConvertFormatInfo("flac", "FLAC", "flac", emptyList(), listOf("flac"), true),
        MediaConvertFormatInfo("ogg", "OGG", "ogg", emptyList(), listOf("vorbis", "opus"), true),
        MediaConvertFormatInfo("opus", "OPUS", "opus", emptyList(), listOf("opus"), true),
        MediaConvertFormatInfo("wma", "WMA", "asf", emptyList(), listOf("wmav2"), true),
        MediaConvertFormatInfo("ac3", "AC3", "ac3", emptyList(), listOf("ac3"), true)
    )
    val resolutions = listOf(
        application.getString(R.string.media_convert_resolution_original),
        application.getString(R.string.media_convert_resolution_4k),
        application.getString(R.string.media_convert_resolution_2k),
        application.getString(R.string.media_convert_resolution_1080p),
        application.getString(R.string.media_convert_resolution_720p),
        application.getString(R.string.media_convert_resolution_480p),
        application.getString(R.string.media_convert_resolution_360p),
        application.getString(R.string.media_convert_resolution_240p)
    )
    val sampleRates = listOf(
        application.getString(R.string.media_convert_sample_rate_original),
        "48000 Hz", "44100 Hz", "22050 Hz", "16000 Hz", "8000 Hz"
    )
    val channels = listOf(
        application.getString(R.string.media_convert_channel_original),
        application.getString(R.string.media_convert_channel_stereo),
        application.getString(R.string.media_convert_channel_mono)
    )
    val qualityLabels = listOf(
        application.getString(R.string.media_convert_quality_original),
        application.getString(R.string.media_convert_quality_high),
        application.getString(R.string.media_convert_quality_high_medium),
        application.getString(R.string.media_convert_quality_medium),
        application.getString(R.string.media_convert_quality_low),
        application.getString(R.string.media_convert_quality_custom)
    )
    val qualityValues = listOf("-1", "18", "23", "28", "33", "custom")
    private val audioExtensions = setOf("mp3", "aac", "m4a", "m4b", "wav", "flac", "ogg", "oga", "opus", "wma", "ac3", "amr", "aif", "aiff", "ape", "mka", "alac", "tta", "wv", "mid", "midi", "3ga")
    private val settingsManager = SettingsManager.getInstance(application)
    private val taskController = LongTaskController(dependencies.taskStateStore, "media-convert")
    private var selectedMediaFiles: List<SelectedMediaFile> = emptyList()
    private var outputDirectoryUri: Uri? = null
    private var currentSession: FFmpegSession? = null
    private var conversionJob: Job? = null
    private var probeJob: Job? = null

    val currentOutputDirectoryUri: Uri? get() = outputDirectoryUri
    val isRunning: Boolean get() = currentState.isConverting

    init { restoreOutputDirectory() }

    fun selectFiles(uris: List<Uri>) {
        if (isRunning) return
        probeJob?.cancel()
        selectedMediaFiles = uris.distinct().map { uri ->
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            SelectedMediaFile(uri, getFileName(uri), app.getString(R.string.media_convert_reading_info))
        }
        setState {
            copy(
                selectedFiles = selectedMediaFiles.map { MediaConvertFileUi(it.uri, it.fileName, it.mediaInfo) },
                selectedFormat = null,
                selectedVideoCodec = string(R.string.media_convert_codec_video_required),
                selectedAudioCodec = string(R.string.media_convert_codec_format_required),
                log = ""
            )
        }
        probeSelectedFiles()
    }

    fun selectOutputDirectory(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { toast(R.string.directory_permission_save_failed, it.message.orEmpty(), duration = android.widget.Toast.LENGTH_LONG) }
        outputDirectoryUri = uri
        settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, uri)) }
    }

    fun selectFormat(extension: String) {
        val format = if (currentState.selectedFormat == extension) null else formatList.firstOrNull { it.extension == extension }
        setState {
            copy(
                selectedFormat = format?.extension,
                selectedVideoCodec = format?.videoCodecs?.firstOrNull()
                    ?: string(R.string.media_convert_codec_video_required),
                selectedAudioCodec = format?.audioCodecs?.firstOrNull()
                    ?: string(R.string.media_convert_codec_format_required)
            )
        }
    }
    fun setVideoCodec(value: String) = setState { copy(selectedVideoCodec = value) }
    fun setAudioCodec(value: String) = setState { copy(selectedAudioCodec = value) }
    fun setResolution(value: Int) = setState { copy(resolutionIndex = value) }
    fun setVideoBitrate(value: String) = setState { copy(videoBitrate = value) }
    fun setQuality(value: String) = setState { copy(selectedQualityId = value) }
    fun setCustomQuality(value: String) = setState { copy(customQuality = value) }
    fun setAudioBitrate(value: String) = setState { copy(audioBitrate = value) }
    fun setSampleRate(value: Int) = setState { copy(sampleRateIndex = value) }
    fun setChannel(value: Int) = setState { copy(channelIndex = value) }
    fun toggleAdvanced() = setState { copy(advancedExpanded = !advancedExpanded) }
    fun dismissDialog() = setState { copy(dialog = MediaConvertDialog.NONE) }

    fun startConversion() {
        val format = currentState.selectedFormat?.let { id -> formatList.first { it.extension == id } }
        if (selectedMediaFiles.isEmpty()) return toast(R.string.activity_media_convert_text_02)
        if (format == null) return toast(R.string.media_convert_select_format_required)
        val output = outputDirectoryUri ?: return toast(R.string.activity_media_convert_text_04)
        val desired = selectedMediaFiles.map { desiredOutputName(it.fileName, format) }
        if (desired.groupingBy { it.lowercase(Locale.ROOT) }.eachCount().any { it.value > 1 } || desired.any { outputFileExists(output, it) }) {
            setState { copy(dialog = MediaConvertDialog.OUTPUT_CONFLICT) }
        } else beginConversion(false)
    }

    fun overwrite() { dismissDialog(); beginConversion(true) }
    fun rename() { dismissDialog(); beginConversion(false) }

    fun cancelConversion() {
        if (!isRunning) return
        taskController.cancel()
        currentSession?.cancel()
        appendLog(string(R.string.media_convert_cancel_started))
    }

    fun share() { currentState.outputUris.takeIf { it.isNotEmpty() }?.let { sendEvent(MediaConvertEvent.ShareOutputs(it)) } }

    private fun restoreOutputDirectory() {
        val saved = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)?.let(Uri::parse)
        if (saved != null) {
            outputDirectoryUri = saved
            setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, saved)) }
        } else {
            val dir = File(com.subtitleedit.util.ModelDirectoryManager.softwareDirectory(), "Convert").apply { mkdirs() }
            outputDirectoryUri = Uri.fromFile(dir)
            setState { copy(outputDirectory = string(R.string.media_convert_output_directory, dir.absolutePath)) }
        }
    }

    private fun probeSelectedFiles() {
        val snapshot = selectedMediaFiles.map { it.uri }
        probeJob = viewModelScope.launch {
            snapshot.forEachIndexed { index, uri ->
                val selected = selectedMediaFiles.firstOrNull { it.uri == uri } ?: return@forEachIndexed
                val info = withContext(Dispatchers.IO) { probeUri(uri) }
                val current = selectedMediaFiles.indexOfFirst { it.uri == uri }
                if (current < 0) return@forEachIndexed
                selectedMediaFiles = selectedMediaFiles.toMutableList().also { list -> list[current] = list[current].copy(mediaInfo = info) }
                setState {
                    copy(
                        selectedFiles = selectedMediaFiles.map { MediaConvertFileUi(it.uri, it.fileName, it.mediaInfo) },
                        log = (log + string(R.string.media_convert_probe_summary, index + 1, snapshot.size, selected.fileName))
                            .takeLast(16000)
                    )
                }
            }
        }
    }

    private fun probeUri(uri: Uri): String {
        var input: String? = null
        return try {
            input = FFmpegKitConfig.getSafParameterForRead(app, uri)
            readMediaInfo(input) ?: string(R.string.media_convert_probe_failed)
        } catch (e: Exception) {
            string(R.string.media_convert_probe_exception, e.message ?: string(R.string.media_convert_unknown))
        }
        finally { input?.let(FFmpegKitConfig::unregisterSafProtocolUrl) }
    }

    private fun readMediaInfo(path: String): String? {
        val info = FFprobeKit.getMediaInformation(path).getMediaInformation() ?: return null
        return buildString {
            append(string(R.string.media_convert_duration, formatDuration(info.getDuration()?.toDoubleOrNull() ?: 0.0)))
            append('\n')
            append(string(R.string.media_convert_bitrate, info.getBitrate() ?: string(R.string.media_convert_unknown)))
            append('\n')
            info.getStreams().forEach { stream ->
                when (stream.getType()) {
                    "video" -> append(
                        string(
                            R.string.media_convert_video_stream,
                            stream.getCodec() ?: "",
                            stream.getWidth() ?: 0,
                            stream.getHeight() ?: 0,
                            stream.getAverageFrameRate() ?: ""
                        )
                    ).append('\n')
                    "audio" -> append(
                        string(
                            R.string.media_convert_audio_stream,
                            stream.getCodec() ?: "",
                            stream.getSampleRate() ?: "",
                            stream.getChannelLayout() ?: ""
                        )
                    ).append('\n')
                }
            }
        }.trimEnd()
    }

    private fun beginConversion(overwriteOutput: Boolean) {
        if (isRunning || taskController.isRunning) return
        val format = currentState.selectedFormat?.let { id -> formatList.first { it.extension == id } } ?: return
        val files = selectedMediaFiles.toList()
        val output = outputDirectoryUri ?: return
        setState { copy(isConverting = true, outputUris = emptyList(), progress = 0, log = "") }
        conversionJob = taskController.launch(viewModelScope) { task ->
            task.onCancel { currentSession?.cancel() }
            val reserved = mutableSetOf<String>(); val failures = mutableListOf<String>(); var success = 0
            try {
                files.forEachIndexed { index, file ->
                    task.ensureActive()
                    val desiredName = desiredOutputName(file.fileName, format)
                    val result = convertFile(file, index, files.size, format, desiredName, overwriteOutput && reserved.add(desiredName.lowercase(Locale.ROOT)), reserved, output)
                    result.onSuccess { success++; setState { copy(outputUris = outputUris + it.uri) } }
                        .onFailure {
                            failures += file.fileName
                            appendLog(
                                string(
                                    R.string.media_convert_file_failed,
                                    file.fileName,
                                    it.message ?: string(R.string.media_convert_error)
                                )
                            )
                        }
                }
                if (!task.isCancellationRequested) {
                    setState { copy(progress = 100) }
                    appendLog(string(R.string.media_convert_summary, success, failures.size))
                    if (failures.isNotEmpty()) {
                        appendLog(string(R.string.media_convert_failed_files, failures.joinToString("、")))
                    }
                }
            } catch (_: CancellationException) { appendLog(string(R.string.media_convert_cancelled)) }
            finally { currentSession = null; setState { copy(isConverting = false) } }
        }
    }

    private suspend fun convertFile(file: SelectedMediaFile, index: Int, total: Int, format: MediaConvertFormatInfo, desiredName: String, overwrite: Boolean, reserved: MutableSet<String>, outputUri: Uri): Result<OutputResult> {
        val cache = createTaskCache("convert"); var input: String? = null
        return try {
            val prefix = "[${index + 1}/$total]"
            appendLog(string(R.string.media_convert_start_file, prefix, file.fileName))
            val sourceProbe = if (format.isAudioOnly) null else withContext(Dispatchers.IO) { probeSourceUri(file.uri)?.let { if (isLikelyAudioFile(file)) SourceProbe(false, it.durationSeconds) else it } ?: SourceProbe(true, null) }
            input = withContext(Dispatchers.IO) { FFmpegKitConfig.getSafParameterForRead(app, file.uri) }
            if (sourceProbe?.hasVideo == false) {
                appendLog(string(R.string.media_convert_no_video_stream, prefix))
            }
            appendLog(string(R.string.media_convert_converting, prefix))
            val output = File(cache, "output.${format.extension}")
            val session = executeConversion(input, output, format, sourceProbe, index, total)
            if (!ReturnCode.isSuccess(session.getReturnCode()) || !output.isFile || output.length() <= 0L) {
                return Result.failure(
                    IllegalStateException(
                        session.getAllLogsAsString().takeLast(800)
                            .ifBlank { string(R.string.media_convert_ffmpeg_invalid_output) }
                    )
                )
            }
            val copied = withContext(Dispatchers.IO) { copyFileToOutputDirectory(output, desiredName, overwrite, reserved, outputUri) }
                ?: return Result.failure(IllegalStateException(string(R.string.media_convert_output_write_failed)))
            appendLog(string(R.string.media_convert_file_completed, prefix, copied.fileName))
            Result.success(copied)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
        finally { input?.let(FFmpegKitConfig::unregisterSafProtocolUrl); withContext(NonCancellable + Dispatchers.IO) { cache.deleteRecursively() } }
    }

    private suspend fun executeConversion(input: String, output: File, format: MediaConvertFormatInfo, probe: SourceProbe?, index: Int, total: Int): FFmpegSession {
        val command = buildFfmpegCommand(input, output.absolutePath, format, probe)
        appendLog(string(R.string.media_convert_execute_command, command))
        val lastProgressAt = AtomicLong(SystemClock.elapsedRealtime()); var local = 0
        val session = withContext(Dispatchers.IO) {
            FFmpegKit.executeAsync(command, { }, { log -> appendLog(log.message) }, { statistics ->
                if (statistics.time > 0) { lastProgressAt.set(SystemClock.elapsedRealtime()); local = (local + 1).coerceAtMost(95); setState { copy(progress = ((index * 100L + local) / total).toInt().coerceIn(0, 99)) } }
            })
        }
        currentSession = session
        while (session.getState().name !in setOf("COMPLETED", "FAILED")) {
            delay(100)
            if (SystemClock.elapsedRealtime() - lastProgressAt.get() > 120_000L) {
                session.cancel()
                throw IllegalStateException(string(R.string.media_convert_timeout))
            }
        }
        return session
    }

    private fun buildFfmpegCommand(input: String, output: String, format: MediaConvertFormatInfo, probe: SourceProbe?): String {
        val state = currentState; val synthetic = !format.isAudioOnly && probe?.hasVideo == false
        val selectedVideo = if (!format.isAudioOnly) state.selectedVideoCodec.ifBlank { "mpeg4" } else ""
        val video = if (synthetic && selectedVideo == "copy") fallbackVideoCodec(format) else selectedVideo
        val audio = state.selectedAudioCodec.ifBlank { format.audioCodecs.firstOrNull() ?: "aac" }
        val command = StringBuilder("-y -hide_banner")
        if (synthetic) { val size = if (state.resolutionIndex > 0) resolutions[state.resolutionIndex].substringBefore(' ') else "1280x720"; command.append(" -f lavfi -i \"color=c=black:s=$size:r=30\" -i \"$input\"") } else command.append(" -i \"$input\"")
        if (format.isAudioOnly) command.append(" -map 0:a:0 -vn -c:a $audio")
        else if (synthetic) { command.append(" -map 0:v:0 -map 1:a:0 -shortest -c:v $video -c:a $audio"); probe?.durationSeconds?.takeIf { it > 0 }?.let { command.append(" -t ${String.format(Locale.US, "%.3f", it)}") } }
        else command.append(" -map 0:v:0 -c:v $video -map 0:a:0? -c:a $audio")
        if (!format.isAudioOnly) { command.append(" -sn -dn"); if (video != "copy") { selectedQuality()?.let { q -> when (video) { "libx264" -> command.append(" -crf $q"); "libvpx", "libvpx-vp9" -> command.append(" -crf $q -b:v 0"); else -> command.append(" -q:v $q") } }; if (!synthetic && state.resolutionIndex > 0) command.append(" -vf scale=${resolutions[state.resolutionIndex].substringBefore(' ')}"); state.videoBitrate.trim().toIntOrNull()?.takeIf { it > 0 }?.let { command.append(" -b:v ${it}k") } } }
        if (audio != "copy") { if (audio == "opus" || audio == "vorbis") command.append(" -strict -2"); state.audioBitrate.trim().toIntOrNull()?.takeIf { it > 0 }?.let { command.append(" -b:a ${it}k") }; if (state.sampleRateIndex > 0) command.append(" -ar ${sampleRates[state.sampleRateIndex].substringBefore(' ')}"); if (state.channelIndex > 0) command.append(" -ac ${if (state.channelIndex == 1) 2 else 1}") }
        command.append(" -f ${format.formatName} \"$output\""); return command.toString()
    }
    private fun selectedQuality(): Int? { val state = currentState; val number = if (state.selectedQualityId == "custom") state.customQuality.toIntOrNull() else state.selectedQualityId.toIntOrNull(); return number?.coerceIn(1, 31)?.takeIf { state.selectedQualityId != "-1" } }
    private fun fallbackVideoCodec(format: MediaConvertFormatInfo) = format.videoCodecs.firstOrNull { it != "copy" } ?: "mpeg4"
    private fun probeSourceUri(uri: Uri): SourceProbe? { var input: String? = null; return try { input = FFmpegKitConfig.getSafParameterForRead(app, uri); val info = FFprobeKit.getMediaInformation(input).getMediaInformation() ?: return null; SourceProbe(info.getStreams().any { it.getType() == "video" }, info.getDuration()?.toDoubleOrNull()?.takeIf { it > 0 }) } catch (_: Exception) { null } finally { input?.let(FFmpegKitConfig::unregisterSafProtocolUrl) } }
    private fun isLikelyAudioFile(file: SelectedMediaFile) = app.contentResolver.getType(file.uri)?.startsWith("audio/") == true || file.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions
    private fun desiredOutputName(name: String, format: MediaConvertFormatInfo) = "${name.substringBeforeLast('.', name).ifBlank { "media_file" }}.${format.extension}"
    private fun outputFileExists(directoryUri: Uri, fileName: String): Boolean =
        if (directoryUri.scheme == "file") {
            directoryUri.path?.let { File(it, fileName).exists() } == true
        } else {
            DocumentFile.fromTreeUri(app, directoryUri)?.findFile(fileName)?.exists() == true
        }
    private fun uniqueOutputName(directoryUri: Uri, requestedName: String, reserved: Set<String>): String { if (!outputFileExists(directoryUri, requestedName) && requestedName.lowercase(Locale.ROOT) !in reserved) return requestedName; val stem = requestedName.substringBeforeLast('.'); val ext = requestedName.substringAfterLast('.', ""); var i = 1; while (true) { val candidate = "$stem ($i)${if (ext.isBlank()) "" else ".${ext}"}"; if (!outputFileExists(directoryUri, candidate) && candidate.lowercase(Locale.ROOT) !in reserved) return candidate; i++ } }
    private fun copyFileToOutputDirectory(
        source: File,
        requested: String,
        overwrite: Boolean,
        reserved: MutableSet<String>,
        directoryUri: Uri
    ): OutputResult? = runCatching {
        val name = if (overwrite) requested else uniqueOutputName(directoryUri, requested, reserved)
        if (directoryUri.scheme == "file") {
            val dir = File(directoryUri.path ?: error(string(R.string.media_convert_invalid_output_directory)))
            if (!dir.exists() && !dir.mkdirs()) error(string(R.string.media_convert_create_output_directory_failed))
            val target = File(dir, name)
            FileInputStream(source).use { input -> FileOutputStream(target, false).use(input::copyTo) }
            if (!target.isFile || target.length() <= 0) error(string(R.string.media_convert_output_empty))
            OutputResult(Uri.fromFile(target), name)
        } else {
            val dir = DocumentFile.fromTreeUri(app, directoryUri)
                ?: error(string(R.string.media_convert_output_access_failed))
            if (overwrite) dir.findFile(name)?.delete()
            val target = dir.createFile(getMimeType(name), name)
                ?: error(string(R.string.media_convert_create_output_failed, name))
            (
                app.contentResolver.openOutputStream(target.uri, "wt")
                    ?: error(string(R.string.media_convert_write_output_failed, name))
                ).use { out -> FileInputStream(source).use { it.copyTo(out) } }
            if (target.length() == 0L) {
                target.delete()
                error(string(R.string.media_convert_output_empty))
            }
            OutputResult(target.uri, target.name ?: name)
        }
    }.getOrNull()
    private fun getMimeType(name: String) = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) { "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"; "webm" -> "video/webm"; "flv" -> "video/x-flv"; "ts" -> "video/mp2t"; "3gp" -> "video/3gpp"; "wmv" -> "video/x-ms-wmv"; "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"; "m4a" -> "audio/mp4"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; "opus" -> "audio/opus"; "wma" -> "audio/x-ms-wma"; "ac3" -> "audio/ac3"; else -> "application/octet-stream" }
    private fun createTaskCache(purpose: String) = File(app.cacheDir, "media_convert_${purpose}_${System.currentTimeMillis()}_${System.nanoTime()}").apply { mkdirs() }
    private fun getFileName(uri: Uri): String = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null } ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "media_file"
    private fun formatDuration(seconds: Double): String { val h = (seconds / 3600).toInt(); val m = ((seconds % 3600) / 60).toInt(); val s = (seconds % 60).toInt(); return if (h > 0) "%d:%02d:%02d".format(Locale.getDefault(), h, m, s) else "%d:%02d".format(Locale.getDefault(), m, s) }
    private fun appendLog(message: String) { if (message.isNotBlank()) setState { copy(log = (log + message).takeLast(16000)) } }

    override fun onCleared() { taskController.cancel(); currentSession?.cancel(); probeJob?.cancel(); conversionJob?.cancel(); super.onCleared() }
}
