package com.subtitleedit

import android.app.Application
import android.content.Intent
import com.subtitleedit.util.PickedUriAccess
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StreamInformation
import com.subtitleedit.feature.ui.MediaConvertDialog
import com.subtitleedit.mpv.MpvSubtitleProbe
import com.subtitleedit.task.LongTaskController
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SourceOutputDirectory
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SubtitleFontManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal data class MediaConvertFileUi(
    val uri: Uri,
    val fileName: String,
    val mediaInfo: String,
    val videoSize: MediaConvertVideoSize? = null,
    val videoBitrateBps: Long? = null,
    val audioBitrateBps: Long? = null,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val audioChannelLayout: String? = null,
    val videoPixelFormat: String? = null,
    val fileSizeBytes: Long? = null,
    val hasVideo: Boolean = false
)

internal data class MediaConvertSubtitleFile(val uri: Uri, val fileName: String)

internal data class MediaConvertUiState(
    val selectedFiles: List<MediaConvertFileUi> = emptyList(),
    val selectedFormat: String? = null,
    val videoOptions: MediaConvertOptions = MediaConvertOptions(),
    val audioOptions: MediaConvertOptions = MediaConvertOptions(),
    val isAudioOutput: Boolean = false,
    val subtitleFiles: List<MediaConvertSubtitleFile> = emptyList(),
    val sourceSubtitleTracks: List<MediaConvertSourceSubtitle> = emptyList(),
    val sourceSubtitleProbeFailed: Boolean = false,
    val sourceEmbeddedLyrics: List<MediaConvertEmbeddedLyric> = emptyList(),
    val sourceLyricsProbeFailed: Boolean = false,
    val isReadingSourceSubtitles: Boolean = false,
    val subtitleType: MediaConvertSubtitleType = MediaConvertSubtitleType.SOFT,
    val subtitleBurnInAvailable: Boolean = false,
    val availableEncoders: Set<String> = MediaConvertFormats.baselineEncoders,
    val availableAudioEncoders: Set<String> = MediaConvertAudioCodecs.baselineEncoders,
    val videoPixelFormats: Map<String, List<String>> = emptyMap(),
    val hardwareCodecNames: Map<String, String> = emptyMap(),
    val outputDirectory: String = "",
    val saveToSourceDirectory: Boolean = false,
    val progress: Int = 0,
    val log: String = "",
    val dialog: MediaConvertDialog = MediaConvertDialog.NONE,
    val outputUris: List<Uri> = emptyList(),
    val isConverting: Boolean = false
) {
    val options: MediaConvertOptions get() = if (isAudioOutput) audioOptions else videoOptions
    /** Compatibility view for callers that still display a single selected subtitle. */
    val subtitleFile: MediaConvertSubtitleFile? get() = subtitleFiles.singleOrNull()
}

internal sealed interface MediaConvertEvent {
    data class ShareOutputs(val uris: List<Uri>) : MediaConvertEvent
}

/** Owns format conversion state and FFmpeg work across Activity recreation. */
internal class MediaConvertViewModel(application: Application) :
    AppViewModel<MediaConvertUiState, MediaConvertEvent>(
        application,
        MediaConvertUiState()
    ) {

    companion object {
        private const val OUTPUT_DIRECTORY_KEY = "media_convert"
    }

    private data class SelectedMediaFile(val uri: Uri, val fileName: String, val mediaInfo: String, val videoSize: MediaConvertVideoSize? = null, val videoBitrateBps: Long? = null, val audioBitrateBps: Long? = null, val audioSampleRate: Int? = null, val audioChannels: Int? = null, val audioChannelLayout: String? = null, val videoPixelFormat: String? = null, val fileSizeBytes: Long? = null, val source: MediaConvertSource? = null, val subtitleProbeFailed: Boolean = false, val subtitleTracks: List<MediaConvertSourceSubtitle> = emptyList(), val embeddedLyrics: List<MediaConvertEmbeddedLyric> = emptyList(), val lyricsProbeFailed: Boolean = false)
    private data class SourceDescription(val text: String, val videoSize: MediaConvertVideoSize? = null, val videoBitrateBps: Long? = null, val audioBitrateBps: Long? = null, val audioSampleRate: Int? = null, val audioChannels: Int? = null, val audioChannelLayout: String? = null, val videoPixelFormat: String? = null, val fileSizeBytes: Long? = null, val source: MediaConvertSource? = null, val subtitleProbeFailed: Boolean = false, val subtitleTracks: List<MediaConvertSourceSubtitle> = emptyList(), val embeddedLyrics: List<MediaConvertEmbeddedLyric> = emptyList(), val lyricsProbeFailed: Boolean = false)
    private data class DirectFileOutput(val staging: File, val target: File, val fileName: String)
    private data class OutputResult(val uri: Uri, val fileName: String)
    private data class PreparedSubtitles(
        val streams: List<MediaConvertSubtitleInput> = emptyList(),
        val lyrics: List<MediaConvertLyric> = emptyList()
    )

    val formatList: List<MediaConvertFormatInfo>
        get() = MediaConvertFormats.create(currentState.availableEncoders, currentState.availableAudioEncoders)
    val sampleRates = MediaConvertCommand.sampleRates.map { rate ->
        if (rate == 0) application.getString(R.string.media_convert_sample_rate_original) else "$rate Hz"
    }
    val channels = listOf(
        application.getString(R.string.media_convert_channel_original),
        application.getString(R.string.media_convert_channel_stereo),
        application.getString(R.string.media_convert_channel_mono),
        "2.1", application.getString(R.string.media_convert_channel_four), "5.0", "5.1", "7.0", "7.1"
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
    private val audioExtensions = setOf("mp3", "aac", "m4a", "m4b", "wav", "flac", "ogg", "oga", "opus", "wma", "ac3", "amr", "aif", "aiff", "ape", "mka", "alac", "tta", "wv", "mid", "midi", "3ga",
        "awb", "mp2", "gsm", "dts", "au", "snd", "caf", "w64", "voc", "spx", "ircam", "eac3", "mlp", "thd")
    private val settingsManager = SettingsManager.getInstance(application)
    private val taskController = LongTaskController(dependencies.taskStateStore, "media-convert")
    private var selectedMediaFiles: List<SelectedMediaFile> = emptyList()
    private var outputDirectoryUri: Uri? = null
    private var currentSession: FFmpegSession? = null
    private var conversionJob: Job? = null
    private var probeJob: Job? = null
    private var probeGeneration = 0L

    val currentOutputDirectoryUri: Uri? get() = outputDirectoryUri
    val isRunning: Boolean get() = currentState.isConverting
    val selectedSourceIsAudio: Boolean get() = selectedMediaFiles.singleOrNull()?.let { file ->
        file.source?.let { !it.hasVideo } ?: isLikelyAudioFile(file)
    } ?: false
    val maxSubtitleFiles: Int get() {
        val format = formatList.firstOrNull { it.extension == currentState.selectedFormat } ?: return 0
        return when {
            format.isAudioOnly -> MediaConvertAudioLyrics.maxFiles(format)
            currentState.subtitleType == MediaConvertSubtitleType.BURN_IN -> 1
            else -> Int.MAX_VALUE
        }
    }
    val allowedSubtitleExtensions: Set<String> get() {
        if (currentState.selectedFiles.size != 1) return emptySet()
        val format = formatList.firstOrNull { it.extension == currentState.selectedFormat }
        return MediaConvertSubtitles.allowedExtensions(format, currentState.subtitleType,
            format?.takeUnless { it.isAudioOnly }?.videoEncoder(currentState.options.selectedVideoCodec).orEmpty(),
            currentState.subtitleBurnInAvailable)
    }

    init {
        setState { copy(saveToSourceDirectory = settingsManager.isSaveToSourceDirectoryEnabled(OUTPUT_DIRECTORY_KEY)) }
        restoreOutputDirectory()
        viewModelScope.launch {
            val (videoEncoders, audioEncoders, burnInAvailable) = withContext(Dispatchers.IO) {
                runCatching {
                    val log = FFmpegKit.execute("-hide_banner -encoders").getAllLogsAsString()
                    Triple(MediaConvertFormats.parseEncoders(log), MediaConvertAudioCodecs.parseEncoders(log),
                        MediaConvertSubtitles.hasBurnInFilter(FFmpegKit.execute("-hide_banner -filters").getAllLogsAsString()))
                }.getOrDefault(Triple(emptySet<String>(), emptySet<String>(), false))
            }
            // h264_mediacodec/hevc_mediacodec are present in every ffmpeg-kit build; only keep them when a real
            // hardware encoder exists on this device, so the UI doesn't offer a fallback-to-software disguise.
            val hardwareNames = withContext(Dispatchers.IO) { MediaConvertHardwareCodecs.detect() }
            val filteredEncoders = videoEncoders - (MediaConvertVideoSettingsSupport.hardwareCodecs - hardwareNames.keys)
            setState { copy(availableEncoders = availableEncoders + filteredEncoders, availableAudioEncoders = availableAudioEncoders + audioEncoders,
                subtitleBurnInAvailable = burnInAvailable, hardwareCodecNames = hardwareNames) }
            val offeredVideoEncoders = formatList.flatMap { it.videoCodecs }.distinct().filter { it in filteredEncoders }
            val pixels = withContext(Dispatchers.IO) {
                offeredVideoEncoders.associateWith { codec ->
                    runCatching { MediaConvertVideoSettingsSupport.parsePixelFormats(FFmpegKit.execute("-hide_banner -h encoder=$codec").getAllLogsAsString()) }.getOrDefault(emptyList())
                }
            }
            setState { copy(videoPixelFormats = pixels) }
        }
    }

    fun selectFiles(uris: List<Uri>) {
        if (isRunning) return
        probeGeneration++
        probeJob?.cancel()
        selectedMediaFiles = uris.distinct().map { uri ->
            runCatching { PickedUriAccess.retainPermission(app, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            SelectedMediaFile(uri, getFileName(uri), app.getString(R.string.media_convert_reading_info))
        }
        setState {
            copy(
                selectedFiles = selectedMediaFiles.map { MediaConvertFileUi(it.uri, it.fileName, it.mediaInfo, it.videoSize, it.videoBitrateBps, it.audioBitrateBps, it.audioSampleRate, it.audioChannels, it.audioChannelLayout) },
                subtitleFiles = emptyList(),
                sourceSubtitleTracks = emptyList(),
                sourceSubtitleProbeFailed = false,
                sourceEmbeddedLyrics = emptyList(),
                sourceLyricsProbeFailed = false,
                isReadingSourceSubtitles = selectedMediaFiles.isNotEmpty(),
                subtitleType = MediaConvertSubtitleType.SOFT,
                log = ""
            )
        }
        probeSelectedFiles()
    }

    fun selectOutputDirectory(uri: Uri) {
        runCatching { PickedUriAccess.retainPermission(app, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { toast(R.string.directory_permission_save_failed, it.message.orEmpty(), duration = android.widget.Toast.LENGTH_LONG) }
        outputDirectoryUri = uri
        settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, uri)) }
    }

    fun selectFormat(extension: String) {
        if (isRunning) return
        val format = formatList.firstOrNull { it.extension == extension } ?: return
        val oldSubtitleCount = currentState.subtitleFiles.size
        setState {
            val previous = if (format.isAudioOnly) audioOptions else videoOptions
            val next = previous.copy(
                selectedVideoCodec = previous.selectedVideoCodec.takeIf { it in format.videoCodecs } ?: "auto",
                selectedAudioCodec = previous.selectedAudioCodec.takeIf { it in format.audioCodecs } ?: "auto"
            )
            val nextType = if (format.isAudioOnly) MediaConvertSubtitleType.SOFT else subtitleType
            val allowed = MediaConvertSubtitles.allowedExtensions(format, nextType,
                if (format.isAudioOnly) "" else format.videoEncoder(next.selectedVideoCodec), subtitleBurnInAvailable)
            copy(
                selectedFormat = format.extension,
                isAudioOutput = format.isAudioOnly,
                subtitleType = nextType,
                subtitleFiles = subtitleFiles.filter { MediaConvertSubtitles.extension(it.fileName) in allowed }
                    .take(if (format.isAudioOnly) MediaConvertAudioLyrics.maxFiles(format)
                        else if (nextType == MediaConvertSubtitleType.BURN_IN) 1 else Int.MAX_VALUE),
                videoOptions = if (format.isAudioOnly) videoOptions else next,
                audioOptions = if (format.isAudioOnly) next else audioOptions
            )
        }
        if (currentState.subtitleFiles.size < oldSubtitleCount) toast(R.string.media_convert_subtitle_selection_adjusted)
    }

    private fun updateOptions(update: MediaConvertOptions.() -> MediaConvertOptions) {
        if (isRunning) return
        setState {
            if (isAudioOutput) copy(audioOptions = audioOptions.update())
            else copy(videoOptions = videoOptions.update())
        }
    }

    fun setSaveToSourceDirectory(enabled: Boolean) {
        settingsManager.setSaveToSourceDirectoryEnabled(OUTPUT_DIRECTORY_KEY, enabled)
        setState { copy(saveToSourceDirectory = enabled) }
    }
    fun setVideoCodec(value: String) {
        if (isRunning) return
        updateOptions { copy(selectedVideoCodec = value) }
        discardIncompatibleSubtitle()
    }

    fun selectSubtitles(uris: List<Uri>) {
        if (isRunning || currentState.selectedFiles.size != 1 || uris.isEmpty()) return
        val allowed = allowedSubtitleExtensions
        val files = uris.distinct().map { MediaConvertSubtitleFile(it, getFileName(it)) }
        if (files.any { MediaConvertSubtitles.extension(it.fileName) !in allowed }) {
            return toast(R.string.media_convert_subtitle_incompatible)
        }
        val maximum = maxSubtitleFiles
        if (maximum == 0) return
        val limited = files.take(maximum)
        limited.forEach { runCatching { PickedUriAccess.retainPermission(app, it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        setState {
            copy(subtitleFiles = if (maximum == 1) limited
                else (subtitleFiles + limited).distinctBy { it.uri }.take(maximum))
        }
        if (limited.size < files.size) toast(R.string.media_convert_subtitle_selection_adjusted)
    }

    fun selectSubtitle(uri: Uri) = selectSubtitles(listOf(uri))

    fun setSubtitleType(value: MediaConvertSubtitleType) {
        if (isRunning || currentState.selectedFiles.size != 1 || currentState.isAudioOutput || currentState.selectedFormat == null) return
        setState { copy(subtitleType = value) }
        discardIncompatibleSubtitle()
    }

    fun removeSubtitle(index: Int) {
        if (!isRunning) setState { copy(subtitleFiles = subtitleFiles.filterIndexed { position, _ -> position != index }) }
    }

    private fun discardIncompatibleSubtitle() {
        val original = currentState.subtitleFiles
        if (original.isEmpty()) return
        val allowed = allowedSubtitleExtensions
        val compatible = original.filter { MediaConvertSubtitles.extension(it.fileName) in allowed }.take(maxSubtitleFiles)
        if (compatible.size == original.size) return
        setState { copy(subtitleFiles = compatible) }
        toast(R.string.media_convert_subtitle_selection_adjusted)
    }
    fun setAudioCodec(value: String) = updateOptions { copy(selectedAudioCodec = value) }
    fun setResolution(value: MediaConvertResolutionSettings) {
        if (value.isValid) updateOptions { copy(resolution = value) }
    }
    fun setVideoBitrate(value: MediaConvertVideoBitrateSettings) {
        if (value.isValid) updateOptions { copy(videoBitrate = value) }
    }
    fun setQuality(value: String) = updateOptions { copy(selectedQualityId = value) }
    fun setCustomQuality(value: String) = updateOptions { copy(customQuality = value) }
    fun setAudioBitrate(value: String) = updateOptions { copy(audioBitrate = value) }
    fun setSampleRate(value: Int) = updateOptions { copy(sampleRateIndex = value) }
    fun setChannel(value: Int) = updateOptions { copy(channelIndex = value) }
    fun setAudioVolume(value: Int) {
        if (value in MediaConvertAudioVolume.MIN..MediaConvertAudioVolume.MAX) updateOptions { copy(audioVolumePercent = value) }
    }
    fun setAudioVbrQuality(value: Int) {
        val format = formatList.firstOrNull { it.extension == currentState.selectedFormat } ?: return
        if (format.isAudioOnly && MediaConvertAudioVbr.supports(format.audioEncoder(currentState.options.selectedAudioCodec)) &&
            MediaConvertAudioVbr.isValid(value)) updateOptions { copy(audioVbrQuality = value) }
    }
    fun setVideoSettings(value: MediaConvertVideoSettings) {
        if (!currentState.isAudioOutput && value.isValid) updateOptions { copy(videoSettings = value) }
    }
    fun dismissDialog() = setState { copy(dialog = MediaConvertDialog.NONE) }

    fun startConversion() {
        val format = currentState.selectedFormat?.let { id -> formatList.first { it.extension == id } }
        if (selectedMediaFiles.isEmpty()) return toast(R.string.activity_media_convert_text_02)
        if (format == null) return toast(R.string.media_convert_select_format_required)
        val output = outputDirectoryUri ?: return toast(R.string.activity_media_convert_text_04)
        val desired = selectedMediaFiles.map { desiredOutputName(it.fileName, format) }
        val hasConflict = runCatching {
            val targets = selectedMediaFiles.zip(desired).map { (file, name) ->
                outputDirectoryFor(file.uri, output) to name
            }
            targets.groupingBy { (directory, name) -> "${directory}::${name.lowercase(Locale.ROOT)}" }
                .eachCount().any { it.value > 1 } ||
                targets.any { (directory, name) -> outputFileExists(directory, name) }
        }.getOrElse { error ->
            toast(R.string.save_to_source_folder_failed, error.message.orEmpty())
            return
        }
        if (hasConflict) {
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
        val generation = probeGeneration
        probeJob = viewModelScope.launch {
            snapshot.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                if (generation != probeGeneration) return@launch
                val selected = selectedMediaFiles.firstOrNull { it.uri == uri } ?: return@forEachIndexed
                val info = withContext(Dispatchers.IO) {
                    probeUri(uri).let { if (isLikelyAudioFile(selected)) it.copy(videoBitrateBps = null) else it }
                }
                currentCoroutineContext().ensureActive()
                if (generation != probeGeneration) return@launch
                val current = selectedMediaFiles.indexOfFirst { it.uri == uri }
                if (current < 0) return@forEachIndexed
                val source = info.source?.let { probed ->
                    if (isLikelyAudioFile(selected)) probed.copy(hasVideo = false, videoBitrateBps = null) else probed
                }
                selectedMediaFiles = selectedMediaFiles.toMutableList().also { list -> list[current] = list[current].copy(mediaInfo = info.text, videoSize = info.videoSize, videoBitrateBps = info.videoBitrateBps, audioBitrateBps = info.audioBitrateBps, audioSampleRate = info.audioSampleRate, audioChannels = info.audioChannels, audioChannelLayout = info.audioChannelLayout, videoPixelFormat = info.videoPixelFormat, fileSizeBytes = info.fileSizeBytes, source = source, subtitleProbeFailed = info.subtitleProbeFailed, subtitleTracks = info.subtitleTracks, embeddedLyrics = info.embeddedLyrics, lyricsProbeFailed = info.lyricsProbeFailed) }
                setState {
                    copy(
                        selectedFiles = selectedMediaFiles.map { MediaConvertFileUi(it.uri, it.fileName, it.mediaInfo, it.videoSize, it.videoBitrateBps, it.audioBitrateBps, it.audioSampleRate, it.audioChannels, it.audioChannelLayout, it.videoPixelFormat, it.fileSizeBytes, it.source?.hasVideo == true) },
                        sourceSubtitleTracks = selectedMediaFiles.singleOrNull()?.subtitleTracks.orEmpty(),
                        sourceSubtitleProbeFailed = selectedMediaFiles.singleOrNull()?.subtitleProbeFailed == true,
                        sourceEmbeddedLyrics = selectedMediaFiles.singleOrNull()?.embeddedLyrics.orEmpty(),
                        sourceLyricsProbeFailed = selectedMediaFiles.singleOrNull()?.lyricsProbeFailed == true,
                        isReadingSourceSubtitles = index < snapshot.lastIndex,
                        log = (log + string(R.string.media_convert_probe_summary, index + 1, snapshot.size, selected.fileName))
                            .takeLast(16000)
                    )
                }
            }
        }
    }

    private suspend fun probeUri(uri: Uri): SourceDescription {
        var input: String? = null
        val size = sourceSizeBytes(uri)
        val description = try {
            input = ffmpegInput(uri)
            readMediaInfo(input) ?: SourceDescription(string(R.string.media_convert_probe_failed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceDescription(string(R.string.media_convert_probe_exception, e.message ?: string(R.string.media_convert_unknown)), fileSizeBytes = size)
        }
        finally { releaseFfmpegInput(input) }
        currentCoroutineContext().ensureActive()
        // Subtitle discovery must also run when the separate media-info probe fails.
        val probed = try {
            MpvSubtitleProbe.readDetails(app, uri)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("MediaConvert", "mpv subtitle detection failed", error)
            null
        } catch (error: LinkageError) {
            Log.w("MediaConvert", "mpv subtitle runtime unavailable", error)
            null
        }
        currentCoroutineContext().ensureActive()
        val extension = MediaConvertSubtitles.extension(getFileName(uri))
        val id3Lyrics = if (extension in setOf("mp3", "wav")) try {
            val stream = PickedUriAccess.file(uri)?.inputStream() ?: app.contentResolver.openInputStream(uri)
                ?: error("Cannot open media for lyric detection")
            stream.buffered().use { MediaConvertId3LyricsReader.read(it, wav = extension == "wav") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("MediaConvert", "ID3 lyric detection failed", error)
            null
        } else null
        val subtitleTracks = probed?.subtitleTracks?.map { track ->
            MediaConvertSourceSubtitle(track.ffIndex, track.codec, track.language, track.title, track.isDefault)
        }
        val lyrics = MediaConvertEmbeddedLyrics.merge(
            MediaConvertEmbeddedLyrics.fromMetadata(probed?.lyricMetadata.orEmpty(), extension), id3Lyrics.orEmpty())
        return description.copy(fileSizeBytes = size,
            source = description.source?.copy(fileSizeBytes = size, subtitleStreams = subtitleTracks.orEmpty()),
            subtitleProbeFailed = subtitleTracks == null, subtitleTracks = subtitleTracks.orEmpty(),
            embeddedLyrics = lyrics, lyricsProbeFailed = probed == null && id3Lyrics == null)
    }

    private fun readMediaInfo(path: String): SourceDescription? {
        val info = FFprobeKit.getMediaInformation(path).getMediaInformation() ?: return null
        val text = buildString {
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
        val videoStreams = info.getStreams().filter { it.getType() == "video" }
        val attachedPicture = videoStreams.firstOrNull(::isAttachedPicture)
        val video = videoStreams.firstOrNull { !isAttachedPicture(it) }
        val audio = info.getStreams().firstOrNull { it.getType() == "audio" }
        val rate = video?.getAverageFrameRate()?.split('/')?.let { parts ->
            val numerator = parts.firstOrNull()?.toDoubleOrNull()
            val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            if (numerator != null && denominator > 0) (numerator / denominator).takeIf { it > 0 } else null
        }
        val source = MediaConvertSource(video != null, info.getDuration()?.toDoubleOrNull()?.takeIf { it > 0 }, rate,
            video?.let(::streamBitrateBps), audio?.getSampleRate()?.toIntOrNull(), audio?.getNumberProperty("channels")?.toInt(),
            audio?.let(::streamBitrateBps), audio?.getChannelLayout(), video?.getStringProperty("pix_fmt"), audio != null,
            videoCodecName = video?.getCodec(), audioCodecName = audio?.getCodec(),
            videoWidth = video?.getWidth()?.toInt(), videoHeight = video?.getHeight()?.toInt(),
            videoStreamIndex = video?.getIndex(), attachedPictureStreamIndex = attachedPicture?.getIndex())
        return SourceDescription(text, video?.let(::videoSize), video?.let(::streamBitrateBps), audio?.let(::streamBitrateBps),
            audio?.getSampleRate()?.toIntOrNull()?.takeIf { it > 0 }, audio?.getNumberProperty("channels")?.toInt(),
            audio?.getChannelLayout(), video?.getStringProperty("pix_fmt"), source = source)
    }

    private fun streamBitrateBps(stream: StreamInformation): Long? = MediaConvertVideoBitrate.sourceBitsPerSecond(
        stream.getBitrate(), stream.getTags()?.optString("BPS"), stream.getTags()?.optString("BPS-eng")
    )

    private fun isAttachedPicture(stream: StreamInformation): Boolean =
        stream.getAllProperties()?.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1

    private fun videoSize(stream: StreamInformation): MediaConvertVideoSize? {
        val properties = stream.getAllProperties()
        val sideData = properties?.optJSONArray("side_data_list")
        var rotation = stream.getTags()?.optString("rotate")?.toDoubleOrNull() ?: 0.0
        if (sideData != null) for (index in 0 until sideData.length()) {
            val entry = sideData.optJSONObject(index) ?: continue
            if (entry.has("rotation")) { rotation = entry.optDouble("rotation", 0.0); break }
        }
        return MediaConvertVideoSize.fromStream(
            stream.getWidth()?.toInt() ?: return null,
            stream.getHeight()?.toInt() ?: return null,
            stream.getSampleAspectRatio(), rotation
        )
    }

    private fun beginConversion(overwriteOutput: Boolean) {
        if (isRunning || taskController.isRunning) return
        val format = currentState.selectedFormat?.let { id -> formatList.first { it.extension == id } } ?: return
        val output = outputDirectoryUri ?: return
        setState { copy(isConverting = true, outputUris = emptyList(), progress = 0, log = "") }
        conversionJob = taskController.launch(viewModelScope) { task ->
            task.onCancel { currentSession?.cancel() }
            val reservedByDirectory = mutableMapOf<String, MutableSet<String>>(); val failures = mutableListOf<String>(); var success = 0
            try {
                probeJob?.join()
                val files = selectedMediaFiles.toList()
                files.forEachIndexed { index, file ->
                    task.ensureActive()
                    val desiredName = desiredOutputName(file.fileName, format)
                    val fileOutput = outputDirectoryFor(file.uri, output)
                    val reserved = reservedByDirectory.getOrPut(fileOutput.toString()) { mutableSetOf() }
                    val result = convertFile(file, index, files.size, format, desiredName, overwriteOutput && reserved.add(desiredName.lowercase(Locale.ROOT)), reserved, fileOutput)
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
        var directSafOutput: Triple<String, Uri, DocumentFile>? = null
        var directFileOutput: DirectFileOutput? = null
        var outputFile: File? = null
        var outputPath = ""
        var outputCommitted = false
        return try {
            val prefix = "[${index + 1}/$total]"
            appendLog(string(R.string.media_convert_start_file, prefix, file.fileName))
            var sourceProbe = file.source ?: withContext(Dispatchers.IO) {
                probeSourceUri(file.uri)?.let { if (isLikelyAudioFile(file)) it.copy(hasVideo = false, videoBitrateBps = null) else it }
            } ?: MediaConvertSource(true, null)
            val retainSourceSubtitles = format.isAudioOnly || currentState.subtitleType == MediaConvertSubtitleType.SOFT
            if (!retainSourceSubtitles) {
                sourceProbe = sourceProbe.copy(subtitleStreams = emptyList())
            } else if (file.subtitleProbeFailed && MediaConvertSubtitles.sourceEncoder(format, "subrip") != null) {
                // A failed probe is not proof that the source has no subtitles.
                // Retry before an output that promises to retain compatible tracks.
                sourceProbe = sourceProbe.copy(subtitleStreams = readSourceSubtitleTracks(file.uri))
            }
            val options = currentState.options
            val videoWillCopy = MediaConvertStreamCopy.copiesVideo(format, options, sourceProbe) ||
                (options.selectedVideoCodec == "copy" && sourceProbe.hasVideo)
            if (!format.isAudioOnly && !videoWillCopy && options.videoBitrate.mode == MediaConvertBitrateMode.ORIGINAL &&
                options.videoSettings.sizeLimitPercent == null && sourceProbe.videoBitrateBps == null) {
                return Result.failure(IllegalStateException(string(R.string.media_convert_bitrate_source_unavailable)))
            }
            val audioCodec = format.audioEncoder(options.selectedAudioCodec)
            val audioWillCopy = MediaConvertStreamCopy.copiesAudio(format, options, sourceProbe)
            if (options.audioBitrate == MediaConvertAudioBitrate.ORIGINAL && !audioWillCopy && audioCodec != "copy" &&
                MediaConvertAudioVbr.activeQuality(format, options) == null &&
                MediaConvertAudioCodecs.profile(audioCodec, format.formatName).adjustableBitrate && sourceProbe.audioBitrateBps == null) {
                return Result.failure(IllegalStateException(string(R.string.media_convert_audio_bitrate_source_unavailable)))
            }
            val prepared = prepareSubtitles(cache, format, options, sourceProbe.durationSeconds)
            val subtitles = prepared.streams
            val lyricTags = prepared.lyrics
            val rewriteLyricFile = lyricTags.isNotEmpty() && MediaConvertAudioLyrics.needsFileRewrite(format)
            input = withContext(Dispatchers.IO) { ffmpegInput(file.uri) }
            if (!format.isAudioOnly && !sourceProbe.hasVideo) {
                appendLog(string(R.string.media_convert_no_video_stream, prefix))
            }
            appendLog(string(R.string.media_convert_converting, prefix))
            // Write to a temporary sibling for file outputs so a failed conversion never replaces a good file.
            val desiredOutputName = if (overwrite) desiredName else uniqueOutputName(outputUri, desiredName, reserved)
            if (outputUri.scheme == "file") {
                val dir = File(outputUri.path ?: error(string(R.string.media_convert_invalid_output_directory)))
                if (!dir.exists() && !dir.mkdirs()) error(string(R.string.media_convert_create_output_directory_failed))
                val target = File(dir, desiredOutputName)
                val staging = File(dir, ".${desiredOutputName}.${System.nanoTime()}.part")
                check(staging.createNewFile()) { string(R.string.media_convert_create_output_failed, staging.name) }
                directFileOutput = DirectFileOutput(staging, target, desiredOutputName)
                outputFile = staging
                outputPath = staging.absolutePath
            } else {
                directSafOutput = if (rewriteLyricFile) null else runCatching {
                    val dir = DocumentFile.fromTreeUri(app, outputUri) ?: return@runCatching null
                    if (overwrite) dir.findFile(desiredOutputName)?.delete()
                    val target = dir.createFile(getMimeType(desiredOutputName), desiredOutputName) ?: return@runCatching null
                    val safPath = runCatching { FFmpegKitConfig.getSafParameterForWrite(app, target.uri) }
                        .getOrElse { target.delete(); throw it }
                    Triple(safPath, target.uri, target)
                }.getOrNull()
                if (directSafOutput != null) {
                    outputPath = directSafOutput?.first ?: error(string(R.string.media_convert_output_write_failed))
                } else {
                    val cachedOutput = File(cache, "output.${format.extension}")
                    outputFile = cachedOutput
                    outputPath = cachedOutput.absolutePath
                }
            }
            val codec = if (format.isAudioOnly) "" else format.videoEncoder(options.selectedVideoCodec)
            val durationMs = sourceProbe.durationSeconds?.let { (it * 1000.0).toLong() } ?: 0L
            val hardwareAttempted = MediaConvertCommand.usesHardwareEncoder(format, options, sourceProbe, subtitles = subtitles)
            val passLogPath = File(cache, "video-pass").absolutePath
            val commands = try {
                MediaConvertCommand.plan(input, outputPath, format, options, sourceProbe, passLogPath,
                    currentState.videoPixelFormats[codec], allowHardware = true,
                    hardwareCodecNames = currentState.hardwareCodecNames, subtitles = subtitles, lyrics = lyricTags)
            } catch (e: MediaConvertVideoSizeException) {
                val message = when (e.reason) {
                    MediaConvertVideoSizeError.UNKNOWN_SOURCE_SIZE -> R.string.media_convert_size_source_required
                    MediaConvertVideoSizeError.UNKNOWN_DURATION -> R.string.media_convert_size_duration_required
                    MediaConvertVideoSizeError.UNKNOWN_AUDIO_BITRATE -> R.string.media_convert_size_audio_bitrate_required
                    MediaConvertVideoSizeError.INSUFFICIENT_SIZE -> R.string.media_convert_size_out_of_range
                }
                return Result.failure(IllegalStateException(string(message)))
            } catch (e: IllegalArgumentException) {
                return Result.failure(IllegalStateException(string(R.string.media_convert_video_settings_invalid, e.message.orEmpty())))
            }
            suspend fun runCommands(cmds: List<List<String>>): FFmpegSession? {
                var last: FFmpegSession? = null
                for ((passIndex, command) in cmds.withIndex()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (cmds.size > 1) appendLog(string(R.string.media_convert_video_pass, passIndex + 1, cmds.size))
                    last = executeConversion(command.toTypedArray(), index, total, passIndex, cmds.size, durationMs)
                    if (!ReturnCode.isSuccess(last.getReturnCode())) break
                }
                return last
            }
            var session = runCommands(commands)
            // MediaCodec codec/profile init failure exits non-zero but not by user cancel; retry in software.
            if (hardwareAttempted && session != null && !ReturnCode.isSuccess(session.getReturnCode()) &&
                !ReturnCode.isCancel(session.getReturnCode()) && !taskController.isCancellationRequested) {
                appendLog(string(R.string.media_convert_hardware_fallback))
                outputFile?.delete()
                directSafOutput?.let { (safPath, _, document) ->
                    releaseFfmpegOutput(safPath)
                    document.delete()
                    directSafOutput = null
                    val cachedOutput = File(cache, "output.${format.extension}")
                    outputFile = cachedOutput
                    outputPath = cachedOutput.absolutePath
                }
                val softwareCommands = MediaConvertCommand.plan(input, outputPath, format, options, sourceProbe, passLogPath,
                    currentState.videoPixelFormats[codec], allowHardware = false,
                    hardwareCodecNames = currentState.hardwareCodecNames, subtitles = subtitles, lyrics = lyricTags)
                session = runCommands(softwareCommands)
            }
            checkNotNull(session)
            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                return Result.failure(
                    IllegalStateException(
                        session.getAllLogsAsString().takeLast(800)
                            .ifBlank { string(R.string.media_convert_ffmpeg_invalid_output) }
                    )
                )
            }
            // SAF direct write: verify output and return result directly
            if (directSafOutput != null) {
                val (_, resultUri, resultDoc) = directSafOutput ?: error(string(R.string.media_convert_output_write_failed))
                if (resultDoc.length() == 0L) {
                    return Result.failure(IllegalStateException(string(R.string.media_convert_output_empty)))
                }
                appendLog(string(R.string.media_convert_file_completed, prefix, desiredOutputName))
                outputCommitted = true
                return Result.success(OutputResult(resultUri, resultDoc.name ?: desiredOutputName))
            }
            val encodedFile = checkNotNull(outputFile)
            if (!encodedFile.isFile || encodedFile.length() <= 0L) {
                return Result.failure(IllegalStateException(string(R.string.media_convert_ffmpeg_invalid_output)))
            }
            if (rewriteLyricFile) withContext(Dispatchers.IO) {
                when (format.formatName) {
                    "mp3" -> MediaConvertId3Lyrics.writeMp3(encodedFile, lyricTags)
                    "wav" -> MediaConvertId3Lyrics.writeWav(encodedFile, lyricTags)
                }
            }
            currentCoroutineContext().ensureActive()
            val directFile = directFileOutput
            val output = if (directFile != null) {
                withContext(Dispatchers.IO) { publishDirectFileOutput(directFile, overwrite) }
            } else {
                withContext(Dispatchers.IO) { copyFileToOutputDirectory(encodedFile, desiredName, overwrite, reserved, outputUri) }
            } ?: return Result.failure(IllegalStateException(string(R.string.media_convert_output_write_failed)))
            outputCommitted = true
            appendLog(string(R.string.media_convert_file_completed, prefix, output.fileName))
            Result.success(output)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
        finally {
            directSafOutput?.let { (safPath, _, document) ->
                if (!outputCommitted) document.delete()
                releaseFfmpegOutput(safPath)
            }
            if (!outputCommitted) directFileOutput?.staging?.delete()
            releaseFfmpegInput(input)
            withContext(NonCancellable + Dispatchers.IO) { cache.deleteRecursively() }
        }
    }

    private suspend fun prepareSubtitles(cache: File, format: MediaConvertFormatInfo, options: MediaConvertOptions,
                                         durationSeconds: Double?): PreparedSubtitles {
        val selected = currentState.subtitleFiles
        if (selected.isEmpty()) return PreparedSubtitles()
        check(currentState.selectedFiles.size == 1) { string(R.string.media_convert_subtitle_single_only) }
        check(currentState.subtitleType != MediaConvertSubtitleType.BURN_IN || selected.size == 1) {
            string(R.string.media_convert_subtitle_single_burn)
        }
        check(selected.all { MediaConvertSubtitles.extension(it.fileName) in allowedSubtitleExtensions }) {
            string(R.string.media_convert_subtitle_incompatible)
        }
        return withContext(Dispatchers.IO) {
            val fonts = if (!format.isAudioOnly && currentState.subtitleType == MediaConvertSubtitleType.BURN_IN) {
                SubtitleFontManager.writeFontConfig(app).absolutePath
            } else null
            val lyrics = mutableListOf<MediaConvertLyric>()
            val streams = selected.mapIndexedNotNull { index, subtitle ->
                val extension = MediaConvertSubtitles.extension(subtitle.fileName)
                var content = FileUtils.readUri(app, subtitle.uri).removePrefix("\uFEFF")
                var preparedName = subtitle.fileName
                if (format.isAudioOnly && extension in setOf("ass", "ssa")) {
                    val original = File(cache, "original_subtitle_$index.$extension").apply { writeText(content, Charsets.UTF_8) }
                    val normalized = File(cache, "normalized_subtitle_$index.srt")
                    val session = FFmpegKit.executeWithArguments(arrayOf("-y", "-i", original.absolutePath,
                        "-map", "0:s:0", "-c:s", "srt", normalized.absolutePath))
                    check(ReturnCode.isSuccess(session.getReturnCode())) { string(R.string.media_convert_subtitle_invalid) }
                    content = normalized.readText(Charsets.UTF_8)
                    preparedName = normalized.name
                }
                if (format.isAudioOnly) {
                    // Tags keep the complete lyric document; only timed tracks are clipped below.
                    val lrc = MediaConvertLyricText.toLrc(content, preparedName, null)
                    val lyric = MediaConvertLyric(subtitle.fileName.substringBeforeLast('.', subtitle.fileName), lrc)
                    if (!MediaConvertAudioLyrics.usesSubtitleTracks(format)) {
                        lyrics += lyric
                        return@mapIndexedNotNull null
                    }
                    content = lrc
                    preparedName = "${lyric.title}.lrc"
                }
                val timedLrc = MediaConvertSubtitles.extension(preparedName) == "lrc"
                val preparedExtension = if (timedLrc) "srt" else MediaConvertSubtitles.extension(preparedName)
                val file = File(cache, "subtitle_$index.$preparedExtension")
                file.writeText(if (timedLrc) MediaConvertLyricText.lrcToSrt(content, durationSeconds) else content, Charsets.UTF_8)
                val stream = FFprobeKit.getMediaInformation(file.absolutePath).getMediaInformation()?.getStreams()
                    ?.firstOrNull { it.getType() == "subtitle" }
                check(stream?.getCodec() in setOf("ass", "ssa", "subrip", "srt", "webvtt")) { string(R.string.media_convert_subtitle_invalid) }
                MediaConvertSubtitleInput(file.absolutePath, "${subtitle.fileName.substringBeforeLast('.') }.$preparedExtension",
                    currentState.subtitleType, fonts).also { MediaConvertSubtitles.validate(format, options, it) }
            }
            PreparedSubtitles(streams, lyrics)
        }
    }

    private suspend fun executeConversion(arguments: Array<String>, index: Int, total: Int, passIndex: Int, passCount: Int, durationMs: Long): FFmpegSession {
        appendLog(string(R.string.media_convert_execute_command, arguments.joinToString(" ") { "\"$it\"" }))
        val lastProgressAt = AtomicLong(SystemClock.elapsedRealtime())
        val session = withContext(NonCancellable + Dispatchers.IO) {
            FFmpegKit.executeWithArgumentsAsync(arguments, { }, { log -> appendLog(log.message) }, { statistics ->
                // statistics.time is milliseconds of media processed and durationMs is the
                // ffprobe total, so progress = processed / total rather than a counted fake step.
                val processedMs = statistics.time.toDouble()
                if (processedMs > 0 && durationMs > 0) {
                    lastProgressAt.set(SystemClock.elapsedRealtime())
                    val passProgress = (processedMs * 100.0 / durationMs).toLong().coerceIn(0L, 99L)
                    setState { copy(progress = ((index * 100L + (passIndex * 100 + passProgress) / passCount) / total).toInt().coerceIn(0, 99)) }
                } else if (processedMs > 0) {
                    lastProgressAt.set(SystemClock.elapsedRealtime())
                }
            })
        }
        currentSession = session
        if (taskController.isCancellationRequested) session.cancel()
        while (session.getState().name !in setOf("COMPLETED", "FAILED")) {
            delay(100)
            if (SystemClock.elapsedRealtime() - lastProgressAt.get() > 120_000L) {
                session.cancel()
                throw IllegalStateException(string(R.string.media_convert_timeout))
            }
        }
        return session
    }

    private fun ffmpegInput(uri: Uri): String = PickedUriAccess.file(uri)?.absolutePath
        ?: FFmpegKitConfig.getSafParameterForRead(app, uri)
    private fun releaseFfmpegInput(input: String?) {
        if (input?.startsWith("saf:") == true) FFmpegKitConfig.unregisterSafProtocolUrl(input)
    }
    private fun releaseFfmpegOutput(output: String?) {
        if (output?.startsWith("saf:") == true) runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(output) }
    }
    private suspend fun probeSourceUri(uri: Uri): MediaConvertSource? {
        var input: String? = null
        return try {
            input = ffmpegInput(uri)
            val info = FFprobeKit.getMediaInformation(input).getMediaInformation() ?: return null
            val videoStreams = info.getStreams().filter { it.getType() == "video" }
            val attachedPicture = videoStreams.firstOrNull(::isAttachedPicture)
            val video = videoStreams.firstOrNull { !isAttachedPicture(it) }
            val rate = video?.getAverageFrameRate()?.split('/')?.let { parts ->
                val numerator = parts.firstOrNull()?.toDoubleOrNull()
                val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
                if (numerator != null && denominator > 0) (numerator / denominator).takeIf { it > 0 } else null
            }
            val audio = info.getStreams().firstOrNull { it.getType() == "audio" }
            MediaConvertSource(video != null, info.getDuration()?.toDoubleOrNull()?.takeIf { it > 0 }, rate, video?.let(::streamBitrateBps),
                audio?.getSampleRate()?.toIntOrNull(), audio?.getNumberProperty("channels")?.toInt(), audio?.let(::streamBitrateBps), audio?.getChannelLayout(),
                video?.getStringProperty("pix_fmt"), audio != null, sourceSizeBytes(uri), video?.getCodec(), audio?.getCodec(),
                video?.getWidth()?.toInt(), video?.getHeight()?.toInt(), video?.getIndex(), attachedPicture?.getIndex(),
                readSourceSubtitleTracks(uri))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { null }
        finally { releaseFfmpegInput(input) }
    }
    private suspend fun readSourceSubtitleTracks(uri: Uri): List<MediaConvertSourceSubtitle> =
        MpvSubtitleProbe.read(app, uri).map { track ->
            MediaConvertSourceSubtitle(track.ffIndex, track.codec, track.language, track.title, track.isDefault)
        }
    private fun sourceSizeBytes(uri: Uri): Long? {
        PickedUriAccess.file(uri)?.let { return it.length().takeIf { size -> size > 0 } }
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
            }
        }.getOrNull() ?: runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { size -> size > 0 } }
        }.getOrNull()
    }
    private fun isLikelyAudioFile(file: SelectedMediaFile) = app.contentResolver.getType(file.uri)?.startsWith("audio/") == true || file.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions
    private fun desiredOutputName(name: String, format: MediaConvertFormatInfo) = "${name.substringBeforeLast('.', name).ifBlank { "media_file" }}.${format.extension}"
    private fun outputDirectoryFor(sourceUri: Uri, fallback: Uri): Uri =
        if (settingsManager.isSaveToSourceDirectoryEnabled(OUTPUT_DIRECTORY_KEY)) {
            SourceOutputDirectory.resolve(app, sourceUri)
        } else fallback
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
    private fun publishDirectFileOutput(output: DirectFileOutput, overwrite: Boolean): OutputResult? = runCatching {
        if (!overwrite && output.target.exists()) error(string(R.string.media_convert_output_write_failed))
        Os.rename(output.staging.absolutePath, output.target.absolutePath)
        if (!output.target.isFile || output.target.length() <= 0L) error(string(R.string.media_convert_output_empty))
        OutputResult(Uri.fromFile(output.target), output.fileName)
    }.getOrNull()
    private fun getMimeType(name: String) = formatList.firstOrNull {
        it.extension == name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }?.mimeType ?: "application/octet-stream"
    private fun createTaskCache(purpose: String) = File(app.cacheDir, "media_convert_${purpose}_${System.currentTimeMillis()}_${System.nanoTime()}").apply { mkdirs() }
    private fun getFileName(uri: Uri): String = com.subtitleedit.util.UriDisplayName.of(app, uri, "media_file")
    private fun formatDuration(seconds: Double): String { val h = (seconds / 3600).toInt(); val m = ((seconds % 3600) / 60).toInt(); val s = (seconds % 60).toInt(); return if (h > 0) "%d:%02d:%02d".format(Locale.getDefault(), h, m, s) else "%d:%02d".format(Locale.getDefault(), m, s) }
    private fun appendLog(message: String) { if (message.isNotBlank()) setState { copy(log = (log + message).takeLast(16000)) } }

    override fun onCleared() { taskController.cancel(); currentSession?.cancel(); probeJob?.cancel(); conversionJob?.cancel(); super.onCleared() }
}
