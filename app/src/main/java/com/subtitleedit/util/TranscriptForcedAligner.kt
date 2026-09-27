package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import java.io.File

/** Aligns supplied text in VAD guided audio windows without speech recognition. */
internal class TranscriptForcedAligner(context: Context) {
    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    private val appContext = context.applicationContext
    private val settings = SettingsManager.getInstance(appContext)

    fun align(
        pcmFile: File,
        transcript: String,
        language: String,
        onProgress: (Int, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<Segment> {
        require(transcript.any { it.isLetterOrDigit() }) { "文稿没有可对齐的文字" }
        val tokenizer = localFile(settings.getQwen3AsrTokenizerPath())
            ?.takeIf(File::isDirectory) ?: error("请先在模型管理中配置 Qwen3 tokenizer")
        val model = localFile(settings.getQwen3ForcedAlignerPath())
        check(Qwen3ForcedAlignerModelFiles.isConfigured(model, appContext.filesDir)) {
            "请先在模型管理中下载或导入 Qwen3 ForcedAligner 模型"
        }
        check(QwenHuggingFaceTokenizer.isAvailable()) { "未安装 Qwen tokenizer 原生库" }
        onProgress(3, "正在使用 VAD 检测语音")
        val speechWindows = VadTimestampGenerator(appContext).generateFixedSpeechWindows(pcmFile, 10)
        check(speechWindows.isNotEmpty()) { "VAD 未检测到可对齐的语音段" }
        if (isCancelled()) error("用户取消")
        val texts = TranscriptWindowAllocator.assign(transcript, speechWindows.map { it.voicedSamples })
        val windows = speechWindows.mapIndexed { index, window ->
            Window(window.startSample, window.endSample, texts[index])
        }
        onProgress(30, "已按 10 秒窗口分配文稿")
        val extractor = QwenLogMelExtractor()
        val output = mutableListOf<Segment>()
        Pcm16WavReader(pcmFile).use { reader ->
            Qwen3ForcedAlignmentTextEncoder(tokenizer).use { encoder ->
                onProgress(35, "正在加载 Qwen3 ForcedAligner")
                Qwen3ForcedAlignerOnnx(requireNotNull(model)).use { aligner ->
                    windows.forEachIndexed { index, window ->
                        if (isCancelled()) error("用户取消")
                        val samples = reader.readRange(
                            window.startSample, (window.endSample - window.startSample).toInt(),
                        )
                        val features = extractor.extract(samples)
                        val normalized = JapaneseNumberNormalizer.normalize(window.text, language)
                        val encoded = encoder.encode(normalized.textForAlignment, language, features.first().size)
                        val units = normalized.restore(aligner.align(Qwen3ForcedAlignerOnnx.Input(
                            inputIds = encoded.inputIds,
                            inputFeatures = features,
                            attentionMask = LongArray(encoded.inputIds.size) { 1L },
                            featureAttentionMask = LongArray(features.first().size) { 1L },
                            timestampPositions = encoded.timestampPositions,
                            units = encoded.units,
                        )))
                        output += toSubtitleSegments(units, window.text,
                            window.startSample * 1000L / SAMPLE_RATE,
                            window.endSample * 1000L / SAMPLE_RATE)
                        onProgress(35 + (index + 1) * 63 / windows.size,
                            "对齐文稿 ${index + 1}/${windows.size}")
                    }
                }
            }
        }
        check(output.isNotEmpty()) { "Qwen3 ForcedAligner 未生成有效字幕" }
        return output
    }

    private data class Window(val startSample: Long, val endSample: Long, val text: String)

    private fun toSubtitleSegments(
        units: List<ForcedAlignmentUnit>, source: String, offset: Long, endMs: Long,
    ): List<Segment> {
        val sorted = units.filter { it.text.isNotBlank() }.sortedBy { it.startTimeMs }
        if (sorted.isEmpty()) return emptyList()
        val positions = source.indices.filter { source[it].isLetterOrDigit() || source[it] == '\'' }
        val groups = mutableListOf<List<ForcedAlignmentUnit>>()
        var group = mutableListOf<ForcedAlignmentUnit>()
        var length = 0
        for (unit in sorted) {
            val previous = group.lastOrNull()
            if (previous != null && (unit.startTimeMs - previous.endTimeMs >= 350L ||
                unit.endTimeMs - group.first().startTimeMs > 5_000L || length >= 36)) {
                groups += group
                group = mutableListOf()
                length = 0
            }
            group += unit
            length += unit.text.length
        }
        if (group.isNotEmpty()) groups += group
        var sourceStart = 0
        var used = 0
        return groups.mapIndexedNotNull { index, item ->
            used += item.sumOf { unit -> unit.text.count { it.isLetterOrDigit() || it == '\'' } }
            val sourceEnd = if (index == groups.lastIndex || used >= positions.size) source.length else positions[used]
            val text = source.substring(sourceStart, sourceEnd).trim()
            sourceStart = sourceEnd
            if (text.isBlank()) null else {
                val start = (offset + item.first().startTimeMs - 100L).coerceIn(0L, endMs - 1L)
                Segment(start, (offset + item.last().endTimeMs + 100L).coerceIn(start + 1L, endMs), text)
            }
        }
    }

    private fun localFile(path: String): File? {
        if (path.isBlank()) return null
        val uri = Uri.parse(path)
        return if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") File(uri.path ?: path) else null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000L
    }
}
