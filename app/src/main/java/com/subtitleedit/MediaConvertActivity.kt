package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.feature.ui.MediaConvertDialog
import com.subtitleedit.feature.ui.MediaConvertScreen
import com.subtitleedit.feature.ui.MediaFormatOption
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.task.LongTaskController
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

/**
 * 音视频格式转换。
 * 输入通过 FFmpegKit 的 SAF 协议直接交给 FFmpeg/FFprobe 处理，输出只使用
 * 应用缓存中的临时文件，避免复制用户选择的源文件。
 */
class MediaConvertActivity : AppCompatActivity() {

    private companion object {
        const val OUTPUT_DIRECTORY_KEY = "media_convert"
    }

    data class FormatInfo(
        val extension: String,
        val displayName: String,
        val formatName: String,
        val videoCodecs: List<String>,
        val audioCodecs: List<String>,
        val isAudioOnly: Boolean = false
    )

    private data class SelectedMediaFile(
        val uri: Uri,
        val fileName: String,
        val mediaInfo: String = "正在读取媒体信息..."
    )

    private data class SourceProbe(
        val hasVideo: Boolean,
        val durationSeconds: Double?
    )

    private data class OutputResult(val uri: Uri, val fileName: String)

    /* 外部编码器与本地 FFmpegKit 8.1.0-mpv1 AAR 的实际构建能力保持一致。 */
    private val formatList = listOf(
        FormatInfo("mp4", "MP4", "mp4", listOf("mpeg4", "libx264", "copy"), listOf("aac", "libmp3lame", "copy")),
        FormatInfo("mkv", "MKV", "matroska", listOf("mpeg4", "libx264", "libvpx-vp9", "copy"), listOf("aac", "libmp3lame", "libopus", "libvorbis", "flac", "ac3", "copy")),
        FormatInfo("avi", "AVI", "avi", listOf("mpeg4", "libx264", "copy"), listOf("libmp3lame", "aac", "ac3", "copy")),
        FormatInfo("mov", "MOV", "mov", listOf("mpeg4", "libx264", "copy"), listOf("aac", "libmp3lame", "ac3", "copy")),
        FormatInfo("webm", "WebM", "webm", listOf("libvpx", "libvpx-vp9", "copy"), listOf("libvorbis", "libopus", "copy")),
        FormatInfo("flv", "FLV", "flv", listOf("libx264", "flv1", "mpeg4", "copy"), listOf("aac", "libmp3lame", "copy")),
        FormatInfo("ts", "TS", "mpegts", listOf("libx264", "mpeg2video", "mpeg4", "copy"), listOf("aac", "libmp3lame", "ac3", "copy")),
        FormatInfo("m4v", "M4V", "mp4", listOf("mpeg4", "libx264", "copy"), listOf("aac", "libmp3lame", "copy")),
        FormatInfo("3gp", "3GP", "3gp", listOf("mpeg4", "libx264", "copy"), listOf("aac", "libmp3lame", "copy")),
        FormatInfo("wmv", "WMV", "asf", listOf("wmv2", "msmpeg4v3", "copy"), listOf("wmav2", "copy")),
        FormatInfo("mp3", "MP3", "mp3", emptyList(), listOf("libmp3lame"), isAudioOnly = true),
        FormatInfo("aac", "AAC", "adts", emptyList(), listOf("aac"), isAudioOnly = true),
        FormatInfo("m4a", "M4A", "ipod", emptyList(), listOf("aac"), isAudioOnly = true),
        FormatInfo("wav", "WAV", "wav", emptyList(), listOf("pcm_s16le", "pcm_s24le", "pcm_f32le"), isAudioOnly = true),
        FormatInfo("flac", "FLAC", "flac", emptyList(), listOf("flac"), isAudioOnly = true),
        FormatInfo("ogg", "OGG", "ogg", emptyList(), listOf("vorbis", "opus"), isAudioOnly = true),
        FormatInfo("opus", "OPUS", "opus", emptyList(), listOf("opus"), isAudioOnly = true),
        FormatInfo("wma", "WMA", "asf", emptyList(), listOf("wmav2"), isAudioOnly = true),
        FormatInfo("ac3", "AC3", "ac3", emptyList(), listOf("ac3"), isAudioOnly = true)
    )

    private val resolutions = listOf(
        "原始分辨率", "3840x2160 (4K)", "2560x1440 (2K)", "1920x1080 (1080p)",
        "1280x720 (720p)", "854x480 (480p)", "640x360 (360p)", "426x240 (240p)"
    )
    private val sampleRates = listOf("原始采样率", "48000 Hz", "44100 Hz", "22050 Hz", "16000 Hz", "8000 Hz")
    private val channels = listOf("原始声道", "立体声 (2ch)", "单声道 (1ch)")
    private val qualityLabels = listOf("原始质量", "高质量 (18)", "较高质量 (23)", "中等质量 (28)", "低质量 (33)", "自定义")
    private val qualityValues = listOf("-1", "18", "23", "28", "33", "custom")
    private val audioExtensions = setOf(
        "mp3", "aac", "m4a", "m4b", "wav", "flac", "ogg", "oga", "opus", "wma", "ac3",
        "amr", "aif", "aiff", "ape", "mka", "alac", "tta", "wv", "mid", "midi", "3ga"
    )

    private var selectedMediaFiles by mutableStateOf<List<SelectedMediaFile>>(emptyList())
    private var selectedFormat by mutableStateOf<FormatInfo?>(null)
    private var selectedVideoCodec by mutableStateOf("（请先选择视频格式）")
    private var selectedAudioCodec by mutableStateOf("（请先选择格式）")
    private var resolutionIndex by mutableStateOf(0)
    private var videoBitrate by mutableStateOf("")
    private var qualityIndex by mutableStateOf(0)
    private var customQuality by mutableStateOf("")
    private var audioBitrate by mutableStateOf("")
    private var sampleRateIndex by mutableStateOf(0)
    private var channelIndex by mutableStateOf(0)
    private var advancedExpanded by mutableStateOf(false)
    private var outputDirectoryLabel by mutableStateOf("")
    private var sourceInfoText by mutableStateOf("")
    private var conversionProgress by mutableStateOf(0)
    private var logText by mutableStateOf("")
    private var dialogState by mutableStateOf(MediaConvertDialog.NONE)
    private var outputDirectoryUri: Uri? = null
    private lateinit var settingsManager: SettingsManager
    private var outputUris = mutableListOf<Uri>()
    private var conversionJob: Job? = null
    private var probeJob: Job? = null
    private var currentSession: FFmpegSession? = null
    private var isConverting by mutableStateOf(false)
    private val taskController by lazy {
        LongTaskController(
            (application as SubtitleEditApplication).dependencies.taskStateStore,
            "media-convert"
        )
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) handleSelectedFiles(uris)
    }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { handleSelectedOutputDir(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager.getInstance(this)
        setupInitialOutputDirectory()
        setContent {
            SubtitleEditComposeTheme {
                MediaConvertScreen(
                    sourceSummary = if (selectedMediaFiles.isEmpty()) "" else "已选择 ${selectedMediaFiles.size} 个文件",
                    sourceInfo = sourceInfoText,
                    outputDirectory = outputDirectoryLabel,
                    videoFormats = formatList.filterNot { it.isAudioOnly }.map {
                        MediaFormatOption(it.extension, it.displayName, it.isAudioOnly)
                    },
                    audioFormats = formatList.filter { it.isAudioOnly }.map {
                        MediaFormatOption(it.extension, it.displayName, it.isAudioOnly)
                    },
                    selectedFormat = selectedFormat?.extension,
                    videoCodecs = selectedFormat?.videoCodecs.orEmpty(),
                    selectedVideoCodec = selectedVideoCodec,
                    audioCodecs = selectedFormat?.audioCodecs.orEmpty(),
                    selectedAudioCodec = selectedAudioCodec,
                    resolutions = resolutions,
                    resolutionIndex = resolutionIndex,
                    videoBitrate = videoBitrate,
                    qualityLabels = qualityLabels,
                    qualityIndex = qualityIndex,
                    customQuality = customQuality,
                    audioBitrate = audioBitrate,
                    sampleRates = sampleRates,
                    sampleRateIndex = sampleRateIndex,
                    channels = channels,
                    channelIndex = channelIndex,
                    advancedExpanded = advancedExpanded,
                    isConverting = isConverting,
                    canConvert = selectedMediaFiles.isNotEmpty() && selectedFormat != null,
                    canShare = outputUris.isNotEmpty(),
                    progress = conversionProgress,
                    log = logText,
                    dialog = dialogState,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onPickFiles = { pickFileLauncher.launch(arrayOf("video/*", "audio/*")) },
                    onSelectOutputDirectory = { directoryPickerLauncher.launch(outputDirectoryUri) },
                    onFormatSelected = ::selectFormat,
                    onVideoCodecSelected = { selectedVideoCodec = it },
                    onAudioCodecSelected = { selectedAudioCodec = it },
                    onResolutionSelected = { resolutionIndex = it },
                    onVideoBitrateChange = { videoBitrate = it },
                    onQualitySelected = { qualityIndex = it },
                    onCustomQualityChange = { customQuality = it },
                    onAudioBitrateChange = { audioBitrate = it },
                    onSampleRateSelected = { sampleRateIndex = it },
                    onChannelSelected = { channelIndex = it },
                    onToggleAdvanced = { advancedExpanded = !advancedExpanded },
                    onConvert = ::startConversionRequest,
                    onCancel = ::cancelConversion,
                    onShare = ::shareOutputs,
                    onOverwrite = {
                        dialogState = MediaConvertDialog.NONE
                        beginConversion(true)
                    },
                    onAutoRename = {
                        dialogState = MediaConvertDialog.NONE
                        beginConversion(false)
                    },
                    onDismissDialog = { dialogState = MediaConvertDialog.NONE }
                )
            }
        }
    }

    override fun onDestroy() {
        probeJob?.cancel()
        taskController.cancel()
        currentSession?.cancel()
        super.onDestroy()
    }

    private fun selectFormat(extension: String) {
        val format = if (selectedFormat?.extension == extension) {
            null
        } else {
            formatList.firstOrNull { it.extension == extension }
        }
        selectedFormat = format
        selectedVideoCodec = format?.videoCodecs?.firstOrNull() ?: "（请先选择视频格式）"
        selectedAudioCodec = format?.audioCodecs?.firstOrNull() ?: "（请先选择格式）"
    }

    private fun setupDefaultOutputDirectory() {
        val directory = File(FileUtils.getDownloadDirectory(), "SubtitleEdit/Convert")
        if (!directory.exists()) directory.mkdirs()
        outputDirectoryUri = Uri.fromFile(directory)
        outputDirectoryLabel = "输出目录：${directory.absolutePath}"
    }

    private fun setupInitialOutputDirectory() {
        val savedUri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
        if (savedUri != null) {
            outputDirectoryUri = savedUri
            outputDirectoryLabel = "输出目录：${DirectoryDisplayPath.fromUri(this, savedUri)}"
        } else {
            setupDefaultOutputDirectory()
        }
    }

    private fun handleSelectedOutputDir(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputDirectoryUri = uri
            outputDirectoryLabel = "输出目录：${DirectoryDisplayPath.fromUri(this, uri)}"
            settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        } catch (error: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择目录失败：${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedFiles(uris: List<Uri>) {
        probeJob?.cancel()
        selectedMediaFiles = uris.distinct().map { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // 某些 provider 不允许持久化权限，当前任务仍可使用临时授权。
            }
            SelectedMediaFile(uri, getFileName(uri))
        }
        selectedFormat = null
        selectedVideoCodec = "（请先选择视频格式）"
        selectedAudioCodec = "（请先选择格式）"
        sourceInfoText = sourceInfoForSelectedFiles()
        probeSelectedFiles()
    }

    private fun probeSelectedFiles() {
        val snapshotUris = selectedMediaFiles.map { it.uri }
        probeJob = lifecycleScope.launch {
            snapshotUris.forEachIndexed { index, uri ->
                if (selectedMediaFiles.none { it.uri == uri }) return@forEachIndexed
                val file = selectedMediaFiles.first { it.uri == uri }
                val info = withContext(Dispatchers.IO) { probeUri(file.uri) }
                val currentIndex = selectedMediaFiles.indexOfFirst { it.uri == uri }
                if (currentIndex < 0) return@forEachIndexed
                selectedMediaFiles = selectedMediaFiles.toMutableList().also { files ->
                    files[currentIndex] = files[currentIndex].copy(mediaInfo = info)
                }
                sourceInfoText = sourceInfoForSelectedFiles()
                appendLog("媒体信息 ${index + 1}/${snapshotUris.size}：${file.fileName}\n")
            }
        }
    }

    private fun probeUri(uri: Uri): String {
        var safInput: String? = null
        return try {
            val input = FFmpegKitConfig.getSafParameterForRead(this, uri)
            safInput = input
            readMediaInfo(input) ?: "无法读取媒体信息：FFprobe 无法解析"
        } catch (error: Exception) {
            "无法读取媒体信息：${error.message ?: "未知错误"}"
        } finally {
            safInput?.let { FFmpegKitConfig.unregisterSafProtocolUrl(it) }
        }
    }

    private fun sourceInfoForSelectedFiles(): String = selectedMediaFiles.mapIndexed { index, file ->
            "${index + 1}. ${file.fileName}\n${file.mediaInfo}"
        }.joinToString("\n\n")

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) return cursor.getString(index)
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "media_file"
    }

    private fun startConversionRequest() {
        val format = selectedFormat
        if (selectedMediaFiles.isEmpty()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "请先选择音视频文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (format == null) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "请选择输出格式", Toast.LENGTH_SHORT).show()
            return
        }
        val outputDir = outputDirectoryUri ?: run {
            com.subtitleedit.util.OverwritingToast.makeText(this, "输出目录未设置", Toast.LENGTH_SHORT).show()
            return
        }
        val desiredNames = selectedMediaFiles.map { desiredOutputName(it.fileName, format) }
        val duplicateNames = desiredNames.groupingBy { it.lowercase(Locale.ROOT) }.eachCount().any { it.value > 1 }
        val existingNames = desiredNames.any { outputFileExists(outputDir, it) }
        if (duplicateNames || existingNames) {
            dialogState = MediaConvertDialog.OUTPUT_CONFLICT
        } else {
            beginConversion(false)
        }
    }

    private fun beginConversion(overwriteOutput: Boolean) {
        if (isConverting || taskController.isRunning) return
        val format = selectedFormat ?: return
        isConverting = true
        outputUris.clear()
        conversionProgress = 0
        logText = ""
        conversionJob = taskController.launch(lifecycleScope) { task ->
            task.onCancel { currentSession?.cancel() }
            val reservedNames = mutableSetOf<String>()
            val failures = mutableListOf<String>()
            var successCount = 0
            try {
                selectedMediaFiles.toList().forEachIndexed { index, file ->
                    if (!isConverting) return@forEachIndexed
                    val desiredName = desiredOutputName(file.fileName, format)
                    val overwrite = overwriteOutput && reservedNames.add(desiredName.lowercase(Locale.ROOT))
                    val result = convertFile(file, index, selectedMediaFiles.size, format, desiredName, overwrite, reservedNames)
                    result.onSuccess {
                        successCount++
                        outputUris += it.uri
                    }.onFailure {
                        failures += file.fileName
                        appendLog("\n❌ ${file.fileName}：${it.message ?: "转换失败"}\n")
                    }
                }
                if (isConverting) {
                    conversionProgress = 100
                    appendLog("\n处理完成：成功 $successCount，失败 ${failures.size}\n")
                    if (failures.isNotEmpty()) appendLog("失败文件：${failures.joinToString("、")}\n")
                }
            } catch (_: CancellationException) {
                appendLog("\n⚠️ 已取消转换\n")
            } finally {
                currentSession = null
                isConverting = false
            }
        }
    }

    private suspend fun convertFile(
        file: SelectedMediaFile,
        index: Int,
        total: Int,
        format: FormatInfo,
        desiredName: String,
        overwrite: Boolean,
        reservedNames: MutableSet<String>
    ): Result<OutputResult> {
        val cache = createTaskCache("convert")
        var safInput: String? = null
        return try {
            val prefix = "[${index + 1}/$total]"
            appendLog("\n$prefix 开始处理：${file.fileName}\n")
            val sourceProbe = if (format.isAudioOnly) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    val probed = probeSourceUri(file.uri)
                    if (isLikelyAudioFile(file)) {
                        SourceProbe(hasVideo = false, durationSeconds = probed?.durationSeconds)
                    } else {
                        probed ?: SourceProbe(hasVideo = true, durationSeconds = null)
                    }
                }
            }
            val input = withContext(Dispatchers.IO) {
                FFmpegKitConfig.getSafParameterForRead(this@MediaConvertActivity, file.uri)
            }
            safInput = input
            if (sourceProbe?.hasVideo == false) {
                appendLog("$prefix 未检测到视频流，将生成黑色画面并保留音频。\n")
            }
            appendLog("$prefix 正在转换...\n")
            val output = File(cache, "output.${format.extension}")
            val session = executeConversion(input, output, format, sourceProbe) { local ->
                val overall = ((index * 100L + local) / total).toInt().coerceIn(0, 99)
                if (!isDestroyed) runOnUiThread { conversionProgress = overall }
            }
            if (!ReturnCode.isSuccess(session.getReturnCode()) || !output.isFile || output.length() <= 0L) {
                val detail = session.getAllLogsAsString().takeLast(800).ifBlank { "FFmpeg 未返回有效输出" }
                return Result.failure(IllegalStateException(detail))
            }
            val copied = withContext(Dispatchers.IO) {
                copyFileToOutputDirectory(output, desiredName, overwrite, reservedNames)
            } ?: return Result.failure(IllegalStateException("无法写入输出目录"))
            appendLog("$prefix 完成：${copied.fileName}\n")
            Result.success(copied)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            safInput?.let { FFmpegKitConfig.unregisterSafProtocolUrl(it) }
            withContext(NonCancellable + Dispatchers.IO) { cache.deleteRecursively() }
        }
    }

    private suspend fun executeConversion(
        input: String,
        output: File,
        format: FormatInfo,
        sourceProbe: SourceProbe?,
        onProgress: (Int) -> Unit
    ): FFmpegSession {
        val command = buildFfmpegCommand(input, output.absolutePath, format, sourceProbe)
        appendLog("执行命令：ffmpeg $command\n")
        var lastProgress = 0
        val lastProgressAt = AtomicLong(SystemClock.elapsedRealtime())
        val session = withContext(Dispatchers.IO) {
            FFmpegKit.executeAsync(
                command,
                { },
                { log -> runOnUiThread { appendLog(log.message) } },
                { statistics ->
                    if (statistics.time > 0) {
                        lastProgressAt.set(SystemClock.elapsedRealtime())
                        lastProgress = (lastProgress + 1).coerceAtMost(95)
                        onProgress(lastProgress)
                    }
                }
            )
        }
        currentSession = session
        while (session.getState().name !in setOf("COMPLETED", "FAILED")) {
            delay(100)
            if (SystemClock.elapsedRealtime() - lastProgressAt.get() > 120_000L) {
                session.cancel()
                var attempts = 0
                while (session.getState().name !in setOf("COMPLETED", "FAILED") && attempts < 50) {
                    delay(100)
                    attempts++
                }
                throw IllegalStateException("FFmpeg 超过 120 秒没有读取到有效媒体数据，可能是源文件损坏或无法 seek")
            }
        }
        return session
    }

    private fun buildFfmpegCommand(
        input: String,
        output: String,
        format: FormatInfo,
        sourceProbe: SourceProbe? = SourceProbe(hasVideo = true, durationSeconds = null)
    ): String {
        val syntheticVideo = !format.isAudioOnly && sourceProbe?.hasVideo == false
        val selectedVideoCodec = if (!format.isAudioOnly) {
            this.selectedVideoCodec.ifBlank { "mpeg4" }
        } else {
            ""
        }
        val videoCodec = if (syntheticVideo && selectedVideoCodec == "copy") {
            fallbackVideoCodec(format)
        } else {
            selectedVideoCodec
        }
        val audioCodec = selectedAudioCodec.ifBlank { format.audioCodecs.firstOrNull() ?: "aac" }
        val resolutionPosition = resolutionIndex
        val command = StringBuilder("-y -hide_banner")
        if (syntheticVideo) {
            val size = if (resolutionPosition > 0) {
                resolutions[resolutionPosition].substringBefore(' ')
            } else {
                "1280x720"
            }
            command.append(" -f lavfi -i \"color=c=black:s=$size:r=30\"")
            command.append(" -i \"$input\"")
        } else {
            command.append(" -i \"$input\"")
        }
        if (format.isAudioOnly) {
            command.append(" -map 0:a:0 -vn -c:a $audioCodec")
        } else if (syntheticVideo) {
            command.append(" -map 0:v:0 -map 1:a:0 -shortest -c:v $videoCodec -c:a $audioCodec")
            sourceProbe?.durationSeconds?.takeIf { it > 0.0 }?.let { duration ->
                command.append(" -t ${formatFfmpegDuration(duration)}")
            }
        } else {
            command.append(" -map 0:v:0 -c:v $videoCodec")
            command.append(" -map 0:a:0? -c:a $audioCodec")
        }
        if (!format.isAudioOnly) {
            command.append(" -sn -dn")
            if (videoCodec != "copy") {
                selectedQuality()?.let { quality ->
                    when (videoCodec) {
                        "libx264" -> command.append(" -crf $quality")
                        "libvpx", "libvpx-vp9" -> command.append(" -crf $quality -b:v 0")
                        else -> command.append(" -q:v $quality")
                    }
                }
                if (!syntheticVideo && resolutionPosition > 0) {
                    command.append(" -vf scale=${resolutions[resolutionPosition].substringBefore(' ')}")
                }
                videoBitrate.trim().toIntOrNull()?.takeIf { it > 0 }?.let { command.append(" -b:v ${it}k") }
            }
        }
        if (audioCodec != "copy") {
            if (audioCodec == "opus" || audioCodec == "vorbis") command.append(" -strict -2")
            audioBitrate.trim().toIntOrNull()?.takeIf { it > 0 }?.let { command.append(" -b:a ${it}k") }
            val sampleRate = sampleRateIndex
            if (sampleRate > 0) command.append(" -ar ${sampleRates[sampleRate].substringBefore(' ')}")
            val channel = channelIndex
            if (channel > 0) command.append(" -ac ${if (channel == 1) 2 else 1}")
        }
        command.append(" -f ${format.formatName} \"$output\"")
        return command.toString()
    }

    private fun fallbackVideoCodec(format: FormatInfo): String {
        return format.videoCodecs.firstOrNull { it != "copy" } ?: "mpeg4"
    }

    private fun probeSourceUri(uri: Uri): SourceProbe? {
        var safInput: String? = null
        return try {
            val input = FFmpegKitConfig.getSafParameterForRead(this, uri)
            safInput = input
            val info = FFprobeKit.getMediaInformation(input).getMediaInformation() ?: return null
            SourceProbe(
                hasVideo = info.getStreams().any { it.getType() == "video" },
                durationSeconds = info.getDuration()?.toDoubleOrNull()?.takeIf { it > 0.0 }
            )
        } catch (_: Exception) {
            null
        } finally {
            safInput?.let { FFmpegKitConfig.unregisterSafProtocolUrl(it) }
        }
    }

    private fun formatFfmpegDuration(seconds: Double): String =
        String.format(Locale.US, "%.3f", seconds)

    private fun isLikelyAudioFile(file: SelectedMediaFile): Boolean {
        if (runCatching { contentResolver.getType(file.uri) }.getOrNull()?.startsWith("audio/") == true) return true
        return file.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions
    }

    private fun selectedQuality(): Int? {
        val value = qualityValues.getOrNull(qualityIndex) ?: return null
        val number = if (value == "custom") customQuality.toIntOrNull() else value.toIntOrNull()
        return number?.coerceIn(1, 31)?.takeIf { value != "-1" }
    }

    private fun desiredOutputName(sourceName: String, format: FormatInfo): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "media_file" }
        return "$base.${format.extension}"
    }

    private fun readMediaInfo(path: String): String? {
        val session = FFprobeKit.getMediaInformation(path)
        val info = session.getMediaInformation() ?: return null
        return buildString {
            append("时长：${formatDuration(info.getDuration()?.toDoubleOrNull() ?: 0.0)}\n")
            append("比特率：${info.getBitrate() ?: "未知"} kb/s\n")
            info.getStreams().forEach { stream ->
                when (stream.getType()) {
                    "video" -> append("视频：${stream.getCodec()} ${stream.getWidth()}x${stream.getHeight()} @${stream.getAverageFrameRate()} fps\n")
                    "audio" -> append("音频：${stream.getCodec()} ${stream.getSampleRate()} Hz ${stream.getChannelLayout()}\n")
                }
            }
        }.trimEnd()
    }

    private fun formatDuration(seconds: Double): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = (seconds % 60).toInt()
        return if (h > 0) "%d:%02d:%02d".format(Locale.getDefault(), h, m, s)
        else "%d:%02d".format(Locale.getDefault(), m, s)
    }

    private fun outputFileExists(directoryUri: Uri, fileName: String): Boolean {
        if (directoryUri.scheme == "file") return File(directoryUri.path ?: return false, fileName).exists()
        return DocumentFile.fromTreeUri(this, directoryUri)?.findFile(fileName)?.exists() == true
    }

    private fun uniqueOutputName(directoryUri: Uri, requestedName: String, reservedNames: Set<String>): String {
        if (!outputFileExists(directoryUri, requestedName) && requestedName.lowercase(Locale.ROOT) !in reservedNames) return requestedName
        val stem = requestedName.substringBeforeLast('.')
        val extension = requestedName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = "$stem ($index)${if (extension.isBlank()) "" else ".$extension"}"
            if (!outputFileExists(directoryUri, candidate) && candidate.lowercase(Locale.ROOT) !in reservedNames) return candidate
            index++
        }
    }

    private fun copyFileToOutputDirectory(
        sourceFile: File,
        requestedName: String,
        overwrite: Boolean,
        reservedNames: MutableSet<String>
    ): OutputResult? = runCatching {
        val directoryUri = outputDirectoryUri ?: return null
        val outputName = if (overwrite) requestedName else uniqueOutputName(directoryUri, requestedName, reservedNames)
        if (directoryUri.scheme == "file") {
            val directory = File(directoryUri.path ?: throw IllegalStateException("输出目录无效"))
            if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("无法创建输出目录")
            val target = File(directory, outputName)
            FileInputStream(sourceFile).use { input -> FileOutputStream(target, false).use { input.copyTo(it) } }
            if (!target.isFile || target.length() <= 0L) throw IllegalStateException("输出文件为空")
            return OutputResult(Uri.fromFile(target), outputName)
        }
        val directory = DocumentFile.fromTreeUri(this, directoryUri)
            ?: throw IllegalStateException("无法访问输出目录")
        if (overwrite) directory.findFile(outputName)?.delete()
        val target = directory.createFile(getMimeType(outputName), outputName)
            ?: throw IllegalStateException("无法创建输出文件：$outputName")
        val stream = contentResolver.openOutputStream(target.uri, "wt")
            ?: throw IllegalStateException("无法写入输出文件：$outputName")
        stream.use { output -> FileInputStream(sourceFile).use { it.copyTo(output) } }
        if (target.length() == 0L) {
            target.delete()
            throw IllegalStateException("输出文件为空")
        }
        OutputResult(target.uri, target.name ?: outputName)
    }.getOrNull()

    private fun getMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "flv" -> "video/x-flv"
        "ts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wma" -> "audio/x-ms-wma"
        "ac3" -> "audio/ac3"
        else -> "application/octet-stream"
    }

    private fun createTaskCache(purpose: String): File = File(
        cacheDir, "media_convert_${purpose}_${System.currentTimeMillis()}_${System.nanoTime()}"
    ).apply { mkdirs() }

    private fun cancelConversion() {
        if (!isConverting) return
        taskController.cancel()
        currentSession?.cancel()
        appendLog("\n⚠️ 正在取消...\n")
    }

    private fun shareOutputs() {
        if (outputUris.isEmpty()) return
        val shareUris = outputUris.mapNotNull { uri ->
            if (uri.scheme == "file") {
                uri.path?.let { path -> androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", File(path)) }
            } else uri
        }
        if (shareUris.isEmpty()) return
        val intent = if (shareUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, shareUris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shareUris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "分享转换结果"))
    }

    private fun appendLog(message: String) {
        if (message.isBlank() || isDestroyed) return
        runOnUiThread {
            logText = (logText + message).takeLast(16000)
        }
    }
}
