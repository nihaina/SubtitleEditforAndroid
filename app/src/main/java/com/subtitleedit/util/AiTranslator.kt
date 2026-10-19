package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import java.io.IOException
import java.util.Locale

internal const val DEFAULT_LOCAL_AI_SUBTITLES_PER_REQUEST = 20
internal const val MAX_LOCAL_AI_SUBTITLES_PER_REQUEST = 100
internal const val DEFAULT_REMOTE_AI_SUBTITLES_PER_REQUEST = 200
internal const val MAX_REMOTE_AI_SUBTITLES_PER_REQUEST = 1000
internal const val DEFAULT_AI_SUBTITLES_PER_REQUEST = DEFAULT_REMOTE_AI_SUBTITLES_PER_REQUEST

internal fun defaultAiSubtitlesPerRequest(provider: String): Int =
    if (provider == AiProviderConfig.LOCAL) {
        DEFAULT_LOCAL_AI_SUBTITLES_PER_REQUEST
    } else {
        DEFAULT_REMOTE_AI_SUBTITLES_PER_REQUEST
    }

internal fun normalizeAiSubtitlesPerRequest(provider: String, value: Int): Int =
    value.coerceIn(
        1,
        if (provider == AiProviderConfig.LOCAL) {
            MAX_LOCAL_AI_SUBTITLES_PER_REQUEST
        } else {
            MAX_REMOTE_AI_SUBTITLES_PER_REQUEST
        }
    )
private const val TRANSLATION_BLOCK_START = "start"
private const val TRANSLATION_BLOCK_END = "end"
private val TIMED_SUBTITLE_LINE = Regex(
    """^[ \t]*(\d{1,3}:\d{2}:\d{2}[,.]\d{3})[ \t]*(?:-->|->|—>|——>)[ \t]*(\d{1,3}:\d{2}:\d{2}[,.]\d{3})[ \t]*\r?$""",
    RegexOption.MULTILINE
)
private val TIMED_SUBTITLE_ARROW_LINE = Regex(
    """^[ \t]*([0-9][^\s]*:[^\s]*)[ \t]*(?:-->|->|—>|——>)[ \t]*([^\s]*)[ \t]*\r?$""",
    RegexOption.MULTILINE
)
private val TRANSLATION_TIMESTAMP =
    Regex("""^(\d{1,3}):(\d{2}):(\d{2})[,.](\d{3})$""")
private val SRT_SEQUENCE_BEFORE_CUE = Regex("""\n[ \t]*\d+[ \t]*\n[ \t]*$""")
private val MARKDOWN_FENCE_LINE = Regex("""(?m)^[ \t]*```[^\r\n]*\r?$""")
private val SUBTITLE_SEQUENCE_LINE = Regex("""^[ \t]*(\d+)[ \t]*\r?$""", RegexOption.MULTILINE)
private val INLINE_SUBTITLE_SEQUENCE_LINE =
    Regex("""^[ \t]*\[([0-9]+)](.*)$""", RegexOption.MULTILINE)
private val LRC_TIME_TAG = Regex("""\[-?\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?]""")
private val LRC_TAGGED_LINE = Regex("""^\s*\[[^\]\r\n]*]""", RegexOption.MULTILINE)
private val LRC_TIMED_LINE = Regex(
    """^\s*(?:\[-?\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?])+""",
    RegexOption.MULTILINE
)
private val VTT_TIMED_LINE = Regex(
    """^\s*([^\s]+)\s+-->\s+([^\s]+)(?:\s+(.*?))?\s*$""",
    RegexOption.MULTILINE
)
private val TRANSLATION_BLOCK_START_LINE = Regex(
    """^[ \t]*$TRANSLATION_BLOCK_START[ \t]*$""",
    setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
)
private val MARKED_TRANSLATION_BLOCK = Regex(
    """(?ms)^[ \t]*$TRANSLATION_BLOCK_START[ \t]*\r?\n(.*?)^[ \t]*$TRANSLATION_BLOCK_END[ \t]*(?:\r?\n|$)""",
    RegexOption.IGNORE_CASE
)

private fun buildTranslationInstruction(
    targetLanguage: String,
    customPrompt: String,
    indexTranslate: Boolean
): String =
    buildString {
        append("帮我翻译成${targetLanguage}")
        if (!indexTranslate) append("，以原格式输出")
        customPrompt.trim().takeIf { it.isNotEmpty() }?.let {
            append('\n')
            append(it)
        }
    }

private fun wrapTranslationBlock(content: String): String = buildString {
    append(TRANSLATION_BLOCK_START)
    append('\n')
    append(content)
    append('\n')
    append(TRANSLATION_BLOCK_END)
}

internal fun buildTimedSubtitleContent(
    subtitles: List<SubtitleEntry>,
    startPosition: Int = 1,
    includeTimestamps: Boolean = true,
    includeBlockMarkers: Boolean = true,
    inlineSequence: Boolean = false
): String {
    val content = subtitles.mapIndexed { offset, subtitle ->
        val sequence = subtitle.index.takeIf { it > 0 } ?: (startPosition + offset)
        buildString {
            if (inlineSequence) {
                append('[')
                append(sequence)
                append(']')
                append(normalizeSubtitleText(subtitle.text))
            } else {
                append(sequence)
                append('\n')
                if (includeTimestamps) {
                    append(subtitle.getTimeAxisSRT())
                    append('\n')
                }
                append(normalizeSubtitleText(subtitle.text))
            }
        }
    }.joinToString("\n\n")
    return if (includeBlockMarkers) wrapTranslationBlock(content) else content
}

internal fun buildTranslationUserContent(
    subtitles: List<SubtitleEntry>,
    targetLanguage: String,
    customPrompt: String = "",
    startPosition: Int = 1,
    format: SubtitleFormat = SubtitleFormat.SRT,
    sequenceOnly: Boolean = false,
    includeBlockMarkers: Boolean = true,
    indexTranslate: Boolean = false
): String = buildString {
    append(buildTranslationInstruction(targetLanguage, customPrompt, indexTranslate))
    append("\n\n")
    append(
        if (sequenceOnly) {
            buildTimedSubtitleContent(
                subtitles,
                startPosition,
                includeTimestamps = false,
                inlineSequence = true,
                includeBlockMarkers = includeBlockMarkers
            )
        } else {
            buildSubtitleTranslationContent(subtitles, format, startPosition)
        }
    )
}

internal fun buildSubtitleTranslationContent(
    subtitles: List<SubtitleEntry>,
    format: SubtitleFormat,
    startPosition: Int = 1
): String = when (format) {
    SubtitleFormat.LRC -> wrapTranslationBlock(
        subtitles.mapIndexed { offset, subtitle ->
            val sequence = subtitle.index.takeIf { it > 0 } ?: (startPosition + offset)
            buildString {
                append(sequence)
                append('\n')
                append(TimeUtils.formatLRC(subtitle.startTime))
                append(normalizeSubtitleText(subtitle.text))
            }
        }.joinToString("\n\n")
    )

    SubtitleFormat.VTT -> wrapTranslationBlock(
        subtitles.mapIndexed { offset, subtitle ->
            val sequence = subtitle.index.takeIf { it > 0 } ?: (startPosition + offset)
            buildString {
                append(sequence)
                append('\n')
                if (subtitle.cueIdentifier.isNotBlank()) {
                    append(subtitle.cueIdentifier.trim())
                    append('\n')
                }
                append(formatVttTimestamp(subtitle.startTime))
                append(" --> ")
                append(formatVttTimestamp(subtitle.endTime))
                if (subtitle.cueSettings.isNotBlank()) {
                    append(' ')
                    append(subtitle.cueSettings.trim())
                }
                append('\n')
                append(normalizeSubtitleText(subtitle.text))
            }
        }.joinToString("\n\n")
    )

    else -> buildTimedSubtitleContent(subtitles, startPosition)
}

internal fun splitSubtitleTranslationBatches(
    subtitles: List<SubtitleEntry>,
    maxSubtitlesPerBatch: Int = DEFAULT_AI_SUBTITLES_PER_REQUEST
): List<List<SubtitleEntry>> {
    require(maxSubtitlesPerBatch > 0) { "每批字幕数量必须大于 0" }
    return subtitles.chunked(maxSubtitlesPerBatch)
}

internal fun parseTimedSubtitleTranslation(
    content: String,
    expectedSubtitles: List<SubtitleEntry>
): List<String> {
    val expectedKeys = expectedSubtitles.map(SubtitleEntry::translationTimeRange)
    val expectedCounts = expectedKeys.groupingBy { it }.eachCount()
    val allReturnedBlocks = extractTimedSubtitleBlocks(content)
    val returnedBlocks = allReturnedBlocks
        .filter { it.timeRange in expectedCounts }
    val returnedCounts = returnedBlocks.groupingBy { it.timeRange }.eachCount()

    val duplicateRange = returnedCounts.entries.firstOrNull { (timeRange, count) ->
        count > expectedCounts.getValue(timeRange)
    }
    if (duplicateRange != null) {
        throw IOException("翻译结果包含重复时间轴：${duplicateRange.key.format()}")
    }

    val remainingReturnedCounts = returnedCounts.toMutableMap()
    val missingSubtitles = expectedSubtitles.mapIndexedNotNull { index, subtitle ->
        val timeRange = expectedKeys[index]
        val remaining = remainingReturnedCounts.getOrDefault(timeRange, 0)
        if (remaining > 0) {
            remainingReturnedCounts[timeRange] = remaining - 1
            null
        } else {
            subtitle to timeRange
        }
    }
    if (missingSubtitles.isNotEmpty()) {
        val preview = missingSubtitles.take(10).joinToString("、") { (subtitle, timeRange) ->
            val label = subtitle.index.takeIf { it > 0 }?.let { "原字幕 $it" } ?: "原字幕"
            "$label（${timeRange.format()}）"
        }
        val suffix = if (missingSubtitles.size > 10) "等 ${missingSubtitles.size} 条" else ""
        val unexpectedCount = allReturnedBlocks.size - returnedBlocks.size
        throw IOException(
            "AI 返回 ${returnedBlocks.size}/${expectedSubtitles.size} 个匹配时间轴" +
                (if (unexpectedCount > 0) "，另有 $unexpectedCount 个非本批次时间轴" else "") +
                "；缺少：$preview$suffix。AI 序号仅供显示，匹配以时间轴为准"
        )
    }

    val translationsByTime = returnedBlocks.groupByTo(
        destination = mutableMapOf(),
        keySelector = TimedSubtitleBlock::timeRange,
        valueTransform = TimedSubtitleBlock::text
    ).mapValues { (_, translations) -> ArrayDeque(translations) }

    return expectedKeys.map { timeRange ->
        translationsByTime.getValue(timeRange).removeFirst()
    }
}

internal fun parseSubtitleTranslation(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    format: SubtitleFormat,
    expectedStartPosition: Int = 1,
    sequenceOnly: Boolean = false
): List<String> = if (sequenceOnly) {
    parseSequenceSubtitleTranslation(content, expectedSubtitles, expectedStartPosition)
} else {
    when (format) {
        SubtitleFormat.LRC,
        SubtitleFormat.VTT -> parseIndexedSubtitleTranslation(
            content,
            expectedSubtitles,
            format,
            expectedStartPosition
        )

        else -> parseTimedSubtitleTranslation(content, expectedSubtitles)
    }
}

/** The first subtitle in a response that violates the input protocol. */
internal data class TranslationProtocolIssue(
    val index: Int,
    val message: String
)

/**
 * Inspects a streamed or completed response without requiring the whole batch to be valid.
 * `finalResponse` enables missing-cue detection; while streaming, a missing cue is not known
 * until a later cue or the end of the response has arrived.
 */
internal fun findTranslationProtocolIssue(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    format: SubtitleFormat,
    expectedStartPosition: Int = 1,
    sequenceOnly: Boolean = false,
    finalResponse: Boolean = false
): TranslationProtocolIssue? {
    if (expectedSubtitles.isEmpty()) return null
    if (content.isBlank()) {
        return if (finalResponse) {
            TranslationProtocolIssue(0, "AI 未返回字幕翻译结果")
        } else {
            null
        }
    }
    return if (sequenceOnly) {
        findSequenceProtocolIssue(content, expectedSubtitles, expectedStartPosition, finalResponse)
    } else if (format == SubtitleFormat.SRT) {
        findTimedProtocolIssue(content, expectedSubtitles, expectedStartPosition, finalResponse)
    } else {
        findIndexedProtocolIssue(content, expectedSubtitles, format, expectedStartPosition, finalResponse)
    }
}

private fun findSequenceProtocolIssue(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    expectedStartPosition: Int,
    finalResponse: Boolean
): TranslationProtocolIssue? {
    val expected = expectedSubtitles.mapIndexed { offset, subtitle ->
        subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset)
    }
    val expectedSet = expected.toSet()
    val seen = mutableSetOf<Int>()
    extractSequenceSubtitleBlocks(content).forEach { block ->
        if (block.sequence !in expectedSet) {
            val index = expected.indexOfFirst { it !in seen }
                .takeIf { it >= 0 } ?: expected.lastIndex
            return TranslationProtocolIssue(index, "AI 返回了非本批次序号 ${block.sequence}")
        }
        if (!seen.add(block.sequence)) {
            return TranslationProtocolIssue(
                expected.indexOf(block.sequence).coerceAtLeast(0),
                "翻译结果包含重复字幕序号：${block.sequence}"
            )
        }
        val returnedIndex = expected.indexOf(block.sequence)
        val skippedIndex = (0 until returnedIndex).firstOrNull { expected[it] !in seen }
        if (skippedIndex != null) {
            return TranslationProtocolIssue(
                skippedIndex,
                "AI 返回序号 ${block.sequence} 前缺少原字幕序号 ${expected[skippedIndex]}"
            )
        }
    }
    if (finalResponse) {
        val missing = expected.indexOfFirst { it !in seen }
        if (missing >= 0) {
            return TranslationProtocolIssue(
                missing,
                "AI 返回 ${seen.size}/${expected.size} 个匹配字幕；缺少：原字幕 ${expected[missing]}"
            )
        }
    }
    return null
}

private fun findTimedProtocolIssue(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    expectedStartPosition: Int,
    finalResponse: Boolean
): TranslationProtocolIssue? {
    val expected = expectedSubtitles.map(SubtitleEntry::translationTimeRange)
    val expectedCounts = expected.groupingBy { it }.eachCount()
    val seenCounts = mutableMapOf<TranslationTimeRange, Int>()
    val normalized = markedTranslationContent(content)
        ?: limitToSrtCodeBlock(normalizeSubtitleText(content))
    // Match timestamp-like lines even when their shape is malformed, so streaming can stop
    // as soon as a bad timestamp arrives instead of waiting for final missing-cue detection.
    val timeMatches = TIMED_SUBTITLE_ARROW_LINE.findAll(normalized)
        .filter { match ->
            finalResponse ||
                (match.range.last + 1 < normalized.length &&
                    normalized[match.range.last + 1] == '\n')
        }
        .toList()
    val usedExpected = mutableSetOf<Int>()
    timeMatches.forEachIndexed { index, match ->
        val startTime = parseTranslationTimestamp(match.groupValues[1])
        val endTime = parseTranslationTimestamp(match.groupValues[2])
        if (startTime == null || endTime == null) {
            return TranslationProtocolIssue(
                index.coerceAtMost(expected.lastIndex),
                "字幕时间戳无法解析：${match.groupValues[1]} --> ${match.groupValues[2]}"
            )
        }
        val range = TranslationTimeRange(startTime, endTime)
        val expectedIndex = expected.indices.firstOrNull {
            it !in usedExpected && expected[it] == range
        }
        if (expectedIndex == null) {
            val firstMissing = expected.indices.firstOrNull { it !in usedExpected }
                ?: index.coerceAtMost(expected.lastIndex)
            return TranslationProtocolIssue(firstMissing, "翻译结果包含非本批次时间轴：${range.format()}")
        }
        val skippedIndex = (0 until expectedIndex).firstOrNull { it !in usedExpected }
        if (skippedIndex != null) {
            return TranslationProtocolIssue(
                skippedIndex,
                "返回时间轴 ${range.format()} 前缺少原字幕时间轴 ${expected[skippedIndex].format()}"
            )
        }
        usedExpected += expectedIndex
        val sequenceLine = normalized.substring(0, match.range.first)
            .lineSequence()
            .lastOrNull { it.isNotBlank() }
            ?.trim()
        val returnedSequence = sequenceLine?.toIntOrNull()
        val expectedSequence = expectedSubtitles[expectedIndex].index
            .takeIf { it > 0 } ?: (expectedStartPosition + expectedIndex)
        if (returnedSequence == null || returnedSequence != expectedSequence) {
            return TranslationProtocolIssue(
                expectedIndex,
                "序号 ${returnedSequence ?: "缺失"} 与原字幕序号 $expectedSequence 不一致"
            )
        }
    }
    extractTimedSubtitleBlocks(content).forEach { block ->
        val expectedCount = expectedCounts[block.timeRange]
        if (expectedCount == null) {
            val index = expected.indices.firstOrNull { seenCounts.getOrDefault(expected[it], 0) < expectedCounts.getValue(expected[it]) }
                ?: 0
            return TranslationProtocolIssue(index, "翻译结果包含非本批次时间轴：${block.timeRange.format()}")
        }
        val count = seenCounts.getOrDefault(block.timeRange, 0)
        if (count >= expectedCount) {
            val index = expected.indexOf(block.timeRange).coerceAtLeast(0)
            return TranslationProtocolIssue(index, "翻译结果包含重复时间轴：${block.timeRange.format()}")
        }
        seenCounts[block.timeRange] = count + 1
    }
    if (finalResponse) {
        val missing = expected.indices.firstOrNull {
            seenCounts.getOrDefault(expected[it], 0) < expectedCounts.getValue(expected[it])
        }
        if (missing != null) {
            return TranslationProtocolIssue(
                missing,
                "AI 返回 ${seenCounts.values.sum()}/${expected.size} 个匹配时间轴；缺少：原字幕"
            )
        }
    }
    return null
}

private fun findIndexedProtocolIssue(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    format: SubtitleFormat,
    expectedStartPosition: Int,
    finalResponse: Boolean
): TranslationProtocolIssue? {
    val expected = expectedSubtitles.mapIndexed { offset, subtitle ->
        IndexedSubtitleKey(
            sequence = subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset),
            startTimeMs = normalizedIndexedStartTime(subtitle, format),
            endTimeMs = subtitle.endTime.takeIf { format == SubtitleFormat.VTT }
        )
    }
    val bySequence = expected.groupBy { it.sequence }
    val seen = mutableSetOf<Int>()
    val expectedCueIdentifiers = expectedSubtitles.mapIndexed { offset, subtitle ->
        (subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset)) to subtitle.cueIdentifier
    }.toMap()
    extractIndexedSubtitleBlocks(
        content,
        format,
        finalResponse,
        expectedCueIdentifiers
    ).forEach { block ->
        val expectedKey = bySequence[block.sequence]?.firstOrNull()
        if (expectedKey == null) {
            val index = expected.indexOfFirst { it.sequence !in seen }
                .takeIf { it >= 0 } ?: expected.lastIndex
            return TranslationProtocolIssue(index, "AI 返回了非本批次序号 ${block.sequence}")
        }
        if (!seen.add(block.sequence)) {
            return TranslationProtocolIssue(
                expected.indexOfFirst { it.sequence == block.sequence }.coerceAtLeast(0),
                "翻译结果包含重复字幕序号：${block.sequence}"
            )
        }
        val returnedIndex = expected.indexOf(expectedKey)
        val skippedIndex = (0 until returnedIndex).firstOrNull {
            expected[it].sequence !in seen
        }
        if (skippedIndex != null) {
            return TranslationProtocolIssue(
                skippedIndex,
                "AI 返回序号 ${block.sequence} 前缺少原字幕序号 ${expected[skippedIndex].sequence}"
            )
        }
        if (block.startTimeMs == null || (format == SubtitleFormat.VTT && block.endTimeMs == null)) {
            return TranslationProtocolIssue(
                expected.indexOf(expectedKey).coerceAtLeast(0),
                "序号 ${block.sequence} 的原格式时间轴无法解析"
            )
        }
        if (block.startTimeMs != expectedKey.startTimeMs ||
            (format == SubtitleFormat.VTT && block.endTimeMs != expectedKey.endTimeMs)
        ) {
            return TranslationProtocolIssue(
                expected.indexOf(expectedKey).coerceAtLeast(0),
                "序号 ${block.sequence} 的时间轴与原字幕不一致"
            )
        }
    }
    if (finalResponse) {
        val missing = expected.indexOfFirst { it.sequence !in seen }
        if (missing >= 0) {
            return TranslationProtocolIssue(
                missing,
                "AI 返回 ${seen.size}/${expected.size} 个匹配字幕；缺少：原字幕 ${expected[missing].sequence}"
            )
        }
    }
    return null
}

private data class SequenceSubtitleBlock(
    val sequence: Int,
    val text: String
)

internal fun parseSequenceSubtitleTranslation(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    expectedStartPosition: Int = 1
): List<String> {
    val expectedSequences = expectedSubtitles.mapIndexed { offset, subtitle ->
        subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset)
    }
    if (expectedSequences.toSet().size != expectedSequences.size) {
        throw IOException("AI 翻译序号匹配失败：原字幕序号重复")
    }
    val expectedBySequence = expectedSubtitles.mapIndexed { index, subtitle ->
        expectedSequences[index] to subtitle
    }.toMap()
    val blocks = extractSequenceSubtitleBlocks(content)
    if (blocks.isEmpty()) {
        throw IOException("AI 未返回带序号的字幕块")
    }

    val unexpected = blocks.firstOrNull { it.sequence !in expectedBySequence }
    if (unexpected != null) {
        throw IOException("AI 返回了非本批次序号 ${unexpected.sequence}")
    }
    val duplicate = blocks.groupingBy { it.sequence }.eachCount()
        .entries.firstOrNull { it.value > 1 }
    if (duplicate != null) {
        throw IOException("翻译结果包含重复字幕序号：${duplicate.key}")
    }
    val blocksBySequence = blocks.associateBy { it.sequence }
    val missing = expectedSequences.filter { it !in blocksBySequence }
    if (missing.isNotEmpty()) {
        val preview = missing.take(10).joinToString("、") { "原字幕 $it" }
        val suffix = if (missing.size > 10) "等 ${missing.size} 条" else ""
        throw IOException("AI 返回 ${blocks.size}/${expectedSequences.size} 个匹配字幕；缺少：$preview$suffix")
    }
    return expectedSequences.map { blocksBySequence.getValue(it).text }
}

private fun extractSequenceSubtitleBlocks(content: String): List<SequenceSubtitleBlock> {
    val normalizedContent = markedTranslationContent(content)
        ?: limitToSequenceCodeBlock(normalizeSubtitleText(content))
    val inlineMatches = INLINE_SUBTITLE_SEQUENCE_LINE.findAll(normalizedContent).toList()
    if (inlineMatches.isNotEmpty()) {
        return inlineMatches.mapIndexed { index, match ->
            val sequence = match.groupValues[1].toInt()
            val nextSequence = inlineMatches.getOrNull(index + 1)
            val blockEnd = nextSequence?.range?.first ?: normalizedContent.length
            val firstLine = match.groupValues[2].trimEnd('\r')
            val continuation = normalizedContent.substring(match.range.last + 1, blockEnd)
                .trim('\n', '\r')
            val text = when {
                continuation.isEmpty() -> firstLine
                firstLine.isEmpty() -> continuation
                else -> "$firstLine\n$continuation"
            }
            SequenceSubtitleBlock(
                sequence,
                text.removeTrailingMarkdownFence().trim('\n', '\r')
            )
        }
    }
    val sequenceMatches = SUBTITLE_SEQUENCE_LINE.findAll(normalizedContent).toList()
    return sequenceMatches.mapIndexedNotNull { index, match ->
        val sequence = match.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
        val nextSequence = sequenceMatches.getOrNull(index + 1)
        val blockEnd = nextSequence?.range?.first ?: normalizedContent.length
        val text = normalizedContent.substring(match.range.last + 1, blockEnd)
            .trim('\n', '\r')
            .removeTrailingMarkdownFence()
            .trim('\n', '\r')
        SequenceSubtitleBlock(sequence, text)
    }
}

private fun limitToSequenceCodeBlock(content: String): String {
    val fences = MARKDOWN_FENCE_LINE.findAll(content).toList()
    if (fences.size < 2) return content
    val openingFence = fences.first()
    val closingFence = fences.firstOrNull { it.range.first > openingFence.range.last }
        ?: return content
    return content.substring(openingFence.range.last + 1, closingFence.range.first)
}

private data class IndexedSubtitleKey(
    val sequence: Int,
    val startTimeMs: Long,
    val endTimeMs: Long? = null
)

private data class IndexedSubtitleBlock(
    val sequence: Int,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val text: String
)

private fun parseIndexedSubtitleTranslation(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    format: SubtitleFormat,
    expectedStartPosition: Int
): List<String> {
    val expectedKeys = expectedSubtitles.mapIndexed { offset, subtitle ->
        IndexedSubtitleKey(
            sequence = subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset),
            startTimeMs = normalizedIndexedStartTime(subtitle, format),
            endTimeMs = subtitle.endTime.takeIf { format == SubtitleFormat.VTT }
        )
    }
    val expectedBySequence = expectedKeys.associateBy { it.sequence }
    val expectedCueIdentifiers = expectedSubtitles.mapIndexed { offset, subtitle ->
        (subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset)) to subtitle.cueIdentifier
    }.toMap()
    val blocks = extractIndexedSubtitleBlocks(
        content,
        format,
        expectedCueIdentifiers = expectedCueIdentifiers
    )
    if (blocks.isEmpty()) {
        throw IOException("AI 未返回带序号的 ${format.name} 字幕块")
    }

    val unexpected = blocks.firstOrNull { it.sequence !in expectedBySequence }
    if (unexpected != null) {
        throw IOException("AI 返回了非本批次序号 ${unexpected.sequence}")
    }

    val duplicate = blocks.groupingBy { it.sequence }.eachCount()
        .entries.firstOrNull { it.value > 1 }
    if (duplicate != null) {
        throw IOException("翻译结果包含重复字幕序号：${duplicate.key}")
    }

    blocks.forEach { block ->
        val expected = expectedBySequence.getValue(block.sequence)
        val returnedStart = block.startTimeMs
            ?: throw IOException("序号 ${block.sequence} 的原格式时间轴无法解析")
        val returnedEnd = block.endTimeMs
        if (format == SubtitleFormat.VTT && returnedEnd == null) {
            throw IOException("序号 ${block.sequence} 的原格式时间轴无法解析")
        }
        if (returnedStart != expected.startTimeMs ||
            (format == SubtitleFormat.VTT && returnedEnd != expected.endTimeMs)
        ) {
            val returnedTime = if (format == SubtitleFormat.VTT) {
                "${formatVttTimestamp(returnedStart)} --> ${formatVttTimestamp(returnedEnd!!)}"
            } else {
                TimeUtils.formatLRC(block.startTimeMs)
            }
            val expectedTime = if (format == SubtitleFormat.VTT) {
                "${formatVttTimestamp(expected.startTimeMs)} --> " +
                    formatVttTimestamp(expected.endTimeMs ?: expected.startTimeMs)
            } else {
                TimeUtils.formatLRC(expected.startTimeMs)
            }
            throw IOException(
                "序号 ${block.sequence} 的时间轴与原字幕不一致：返回 $returnedTime，原字幕为 $expectedTime"
            )
        }
    }

    val blocksBySequence = blocks.associateBy { it.sequence }
    val missing = expectedKeys.filter { it.sequence !in blocksBySequence }
    if (missing.isNotEmpty()) {
        val preview = missing.take(10).joinToString("、") { key ->
            "原字幕 ${key.sequence}（${formatIndexedTime(key, format)}）"
        }
        val suffix = if (missing.size > 10) "等 ${missing.size} 条" else ""
        throw IOException("AI 返回 ${blocks.size}/${expectedKeys.size} 个匹配字幕；缺少：$preview$suffix")
    }

    return expectedKeys.map { blocksBySequence.getValue(it.sequence).text }
}

private fun extractIndexedSubtitleBlocks(
    content: String,
    format: SubtitleFormat,
    finalResponse: Boolean = true,
    expectedCueIdentifiers: Map<Int, String> = emptyMap()
): List<IndexedSubtitleBlock> {
    val normalizedContent = markedTranslationContent(content)
        ?: limitToIndexedCodeBlock(normalizeSubtitleText(content), format)
    if (format == SubtitleFormat.VTT) {
        return extractIndexedVttSubtitleBlocks(
            normalizedContent,
            finalResponse,
            expectedCueIdentifiers
        )
    }
    val sequenceMatches = SUBTITLE_SEQUENCE_LINE.findAll(normalizedContent)
        .filter { sequenceMatch ->
            normalizedContent
                .substring(sequenceMatch.range.last + 1)
                .lineSequence()
                .dropWhile { it.isBlank() }
                .firstOrNull()
                ?.let { line ->
                    if (format == SubtitleFormat.LRC) {
                        LRC_TAGGED_LINE.containsMatchIn(line)
                    } else {
                        LRC_TIMED_LINE.containsMatchIn(line)
                    }
                } == true
        }
        .toList()
    return sequenceMatches.mapNotNull { sequenceMatch ->
        val sequence = sequenceMatch.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val nextSequence = sequenceMatches.firstOrNull { it.range.first > sequenceMatch.range.first }
        val blockEnd = nextSequence?.range?.first ?: normalizedContent.length
        val body = normalizedContent.substring(sequenceMatch.range.last + 1, blockEnd)
            .trim('\n', '\r')
            .removeTrailingMarkdownFence()
        when (format) {
            SubtitleFormat.LRC -> parseIndexedLrcBlock(sequence, body)
            SubtitleFormat.VTT -> parseIndexedVttBlock(sequence, body)
            else -> null
        }
    }
}

private fun extractIndexedVttSubtitleBlocks(
    content: String,
    finalResponse: Boolean,
    expectedCueIdentifiers: Map<Int, String>
): List<IndexedSubtitleBlock> {
    val allSequenceMatches = SUBTITLE_SEQUENCE_LINE.findAll(content).toList()
    val identifierLineStarts = mutableSetOf<Int>()
    allSequenceMatches.forEach { sequenceMatch ->
        val sequence = sequenceMatch.groupValues[1].toIntOrNull() ?: return@forEach
        val expectedIdentifier = expectedCueIdentifiers[sequence]
            ?.takeIf(String::isNotBlank) ?: return@forEach
        val nextLineStart = content.indexOf('\n', sequenceMatch.range.last + 1)
            .takeIf { it >= 0 }?.plus(1) ?: return@forEach
        var candidateLineStart = nextLineStart
        while (candidateLineStart < content.length) {
            val candidateLineEnd = content.indexOf('\n', candidateLineStart)
            if (candidateLineEnd < 0) break
            val candidateLine = content.substring(candidateLineStart, candidateLineEnd)
                .trimEnd('\r')
            if (candidateLine.isNotBlank()) {
                if (candidateLine == expectedIdentifier) {
                    allSequenceMatches.firstOrNull {
                        it.range.first == candidateLineStart
                    }?.let { identifierLineStarts += candidateLineStart }
                }
                break
            }
            candidateLineStart = candidateLineEnd + 1
        }
    }
    val sequenceMatches = allSequenceMatches.filter { it.range.first !in identifierLineStarts }
    val timeMatches = VTT_TIMED_LINE.findAll(content)
        .filter { match ->
            finalResponse || content.indexOf('\n', match.range.first) >= 0
        }
        .toList()
    val blocks = mutableListOf<IndexedSubtitleBlock>()
    var sequenceIndex = 0
    while (sequenceIndex < sequenceMatches.size) {
        val sequenceMatch = sequenceMatches[sequenceIndex]
        val sequence = sequenceMatch.groupValues[1].toIntOrNull()
        if (sequence == null) {
            sequenceIndex++
            continue
        }
        val nextSequence = sequenceMatches.firstOrNull { it.range.first > sequenceMatch.range.first }
        val timeMatch = timeMatches.firstOrNull {
            it.range.first > sequenceMatch.range.last &&
                (nextSequence == null || it.range.first < nextSequence.range.first)
        }
        if (timeMatch == null) {
            if (!finalResponse && nextSequence == null) {
                fun nextCompletedNonBlankLine(from: Int): Pair<String, Int>? {
                    var lineStart = from
                    while (lineStart < content.length) {
                        val lineEnd = content.indexOf('\n', lineStart)
                        if (lineEnd < 0) return null
                        val line = content.substring(lineStart, lineEnd).trimEnd('\r')
                        if (line.isNotBlank()) return line to lineStart
                        lineStart = lineEnd + 1
                    }
                    return null
                }
                val identifier = expectedCueIdentifiers[sequence]?.takeIf(String::isNotBlank)
                val firstLine = nextCompletedNonBlankLine(sequenceMatch.range.last + 1)
                if (firstLine == null) return blocks
                val nextLine = if (firstLine.first == identifier) {
                    nextCompletedNonBlankLine(content.indexOf('\n', firstLine.second) + 1)
                        ?: return blocks
                } else {
                    firstLine
                }
                if (nextLine.first.isBlank()) return blocks
            }
            blocks += IndexedSubtitleBlock(sequence, null, null, "")
            sequenceIndex++
            continue
        }
        val nextSequenceAfterTime = sequenceMatches.firstOrNull {
            it.range.first > timeMatch.range.last
        }
        val sectionEnd = nextSequenceAfterTime?.range?.first ?: content.length
        val body = content.substring(sequenceMatch.range.last + 1, sectionEnd)
            .trim('\n', '\r')
            .removeTrailingMarkdownFence()
        val block = parseIndexedVttBlock(sequence, body)
            ?: IndexedSubtitleBlock(sequence, null, null, "")
        blocks += block
        sequenceIndex = sequenceMatches.indexOfFirst { it.range.first >= sectionEnd }
            .takeIf { it >= 0 } ?: sequenceMatches.size
    }
    return blocks
}

private fun parseIndexedLrcBlock(sequence: Int, body: String): IndexedSubtitleBlock? {
    val lines = body.lines()
    val timedLineIndex = lines.indexOfFirst { LRC_TIMED_LINE.containsMatchIn(it) }
    if (timedLineIndex < 0) {
        return IndexedSubtitleBlock(sequence, null, null, "")
    }
    val timedLine = lines[timedLineIndex]
    val tags = LRC_TIME_TAG.findAll(timedLine).toList()
    if (tags.isEmpty()) return IndexedSubtitleBlock(sequence, null, null, "")
    val text = buildString {
        append(timedLine.substring(tags.last().range.last + 1))
        lines.drop(timedLineIndex + 1).forEach {
            append('\n')
            append(it)
        }
    }.trim('\n', '\r')
    return IndexedSubtitleBlock(
        sequence = sequence,
        startTimeMs = TimeUtils.parseLRC(tags.first().value),
        endTimeMs = null,
        text = text
    )
}

private fun parseIndexedVttBlock(sequence: Int, body: String): IndexedSubtitleBlock? {
    val lines = body.lines()
    val timedLineIndex = lines.indexOfFirst { VTT_TIMED_LINE.matches(it) }
    if (timedLineIndex < 0) {
        return IndexedSubtitleBlock(sequence, null, null, "")
    }
    val match = VTT_TIMED_LINE.matchEntire(lines[timedLineIndex])
        ?: return IndexedSubtitleBlock(sequence, null, null, "")
    val text = lines.drop(timedLineIndex + 1).joinToString("\n").trim('\n', '\r')
    return IndexedSubtitleBlock(
        sequence = sequence,
        startTimeMs = parseVttTranslationTimestamp(match.groupValues[1]),
        endTimeMs = parseVttTranslationTimestamp(match.groupValues[2]),
        text = text
    )
}

private fun limitToIndexedCodeBlock(content: String, format: SubtitleFormat): String {
    val marker = when (format) {
        SubtitleFormat.LRC -> LRC_TIMED_LINE.find(content)?.range?.first
        SubtitleFormat.VTT -> VTT_TIMED_LINE.find(content)?.range?.first
        else -> null
    } ?: return content
    val fences = MARKDOWN_FENCE_LINE.findAll(content).toList()
    val openingFence = fences.lastOrNull { it.range.first < marker }
    val closingFence = fences.firstOrNull { it.range.first > marker }
    return if (openingFence != null && closingFence != null) {
        content.substring(0, closingFence.range.first)
    } else {
        content
    }
}

private fun formatIndexedTime(key: IndexedSubtitleKey, format: SubtitleFormat): String =
    if (format == SubtitleFormat.VTT) {
        "${formatVttTimestamp(key.startTimeMs)} --> " +
            formatVttTimestamp(key.endTimeMs ?: key.startTimeMs)
    } else {
        TimeUtils.formatLRC(key.startTimeMs)
    }

private fun normalizedIndexedStartTime(
    subtitle: SubtitleEntry,
    format: SubtitleFormat
): Long = if (format == SubtitleFormat.LRC) {
    TimeUtils.parseLRC(TimeUtils.formatLRC(subtitle.startTime))
} else {
    subtitle.startTime
}

/** Returns only complete consecutive blocks; the trailing block may still be streaming. */
internal fun parseCompletedTimedTranslationPrefix(
    content: String,
    expectedSubtitles: List<SubtitleEntry>
): List<String> {
    val expectedKeys = expectedSubtitles.map(SubtitleEntry::translationTimeRange)
    val expectedKeySet = expectedKeys.toSet()
    val extractedBlocks = extractTimedSubtitleBlocks(content)
    val completedBlocks = if (hasCompleteTranslationBlock(content)) {
        extractedBlocks
    } else {
        extractedBlocks.dropLast(1)
    }
        .filter { it.timeRange in expectedKeySet }
    val translationsByTime = completedBlocks.groupByTo(
        destination = mutableMapOf(),
        keySelector = TimedSubtitleBlock::timeRange,
        valueTransform = TimedSubtitleBlock::text
    ).mapValues { (_, translations) -> ArrayDeque(translations) }

    return buildList {
        for (timeRange in expectedKeys) {
            val translations = translationsByTime[timeRange]
            if (translations == null || translations.isEmpty()) break
            add(translations.removeFirst())
        }
    }
}

internal fun parseCompletedIndexedTranslationPrefix(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    format: SubtitleFormat,
    expectedStartPosition: Int
): List<String> {
    val expectedKeys = expectedSubtitles.mapIndexed { offset, subtitle ->
        IndexedSubtitleKey(
            sequence = subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset),
            startTimeMs = normalizedIndexedStartTime(subtitle, format),
            endTimeMs = subtitle.endTime.takeIf { format == SubtitleFormat.VTT }
        )
    }
    val expectedCueIdentifiers = expectedSubtitles.mapIndexed { offset, subtitle ->
        (subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset)) to subtitle.cueIdentifier
    }.toMap()
    val extractedBlocks = extractIndexedSubtitleBlocks(
        content,
        format,
        expectedCueIdentifiers = expectedCueIdentifiers
    )
    val completedBlocks = if (hasCompleteTranslationBlock(content)) {
        extractedBlocks
    } else {
        extractedBlocks.dropLast(1)
    }
        .filter { block ->
            val expected = expectedKeys.firstOrNull { it.sequence == block.sequence } ?: return@filter false
            block.startTimeMs == expected.startTimeMs &&
                (format != SubtitleFormat.VTT || block.endTimeMs == expected.endTimeMs)
        }
        .associateBy { it.sequence }

    return buildList {
        for (key in expectedKeys) {
            val block = completedBlocks[key.sequence] ?: break
            add(block.text)
        }
    }
}

internal fun parseCompletedSequenceTranslationPrefix(
    content: String,
    expectedSubtitles: List<SubtitleEntry>,
    expectedStartPosition: Int
): List<String> {
    val expectedSequences = expectedSubtitles.mapIndexed { offset, subtitle ->
        subtitle.index.takeIf { it > 0 } ?: (expectedStartPosition + offset)
    }
    val extractedBlocks = extractSequenceSubtitleBlocks(content)
    val completedBlocks = if (hasCompleteTranslationBlock(content) ||
        hasCompleteInlineSequenceBlock(content)
    ) {
        extractedBlocks
    } else {
        extractedBlocks.dropLast(1)
    }.filter { it.sequence in expectedSequences }
        .associateBy { it.sequence }

    return buildList {
        for (sequence in expectedSequences) {
            val block = completedBlocks[sequence] ?: break
            add(block.text)
        }
    }
}

/**
 * In the marker-free local protocol, a blank line terminates an inline cue.
 * Treat that trailing delimiter as completion so streaming progress can advance
 * before the next sequence marker arrives.
 */
private fun hasCompleteInlineSequenceBlock(content: String): Boolean {
    val normalized = normalizeSubtitleText(content)
    return INLINE_SUBTITLE_SEQUENCE_LINE.containsMatchIn(normalized) &&
        Regex("""\n[ \t]*\n[ \t]*$""").containsMatchIn(normalized)
}

private data class TranslationTimeRange(val startTimeMs: Long, val endTimeMs: Long) {
    fun format(): String =
        "${TimeUtils.formatSRT(startTimeMs)} --> ${TimeUtils.formatSRT(endTimeMs)}"
}

private data class TimedSubtitleBlock(
    val timeRange: TranslationTimeRange,
    val text: String
)

private fun SubtitleEntry.translationTimeRange() = TranslationTimeRange(startTime, endTime)

private fun extractTimedSubtitleBlocks(content: String): List<TimedSubtitleBlock> {
    val normalizedContent = markedTranslationContent(content)
        ?: limitToSrtCodeBlock(normalizeSubtitleText(content))
    val matches = TIMED_SUBTITLE_LINE.findAll(normalizedContent).toList()
    return matches.mapIndexedNotNull { index, match ->
        val startTime = parseTranslationTimestamp(match.groupValues[1])
            ?: return@mapIndexedNotNull null
        val endTime = parseTranslationTimestamp(match.groupValues[2])
            ?: return@mapIndexedNotNull null
        val blockEnd = matches.getOrNull(index + 1)?.range?.first ?: normalizedContent.length
        val blockText = normalizedContent.substring(match.range.last + 1, blockEnd)
            .removePrefix("\n")
            .replace(SRT_SEQUENCE_BEFORE_CUE, "\n")
            .trim('\n')
            .removeTrailingMarkdownFence()
        TimedSubtitleBlock(TranslationTimeRange(startTime, endTime), blockText)
    }
}

/**
 * Returns only complete protocol blocks when the response contains the block markers.
 * A null result means the response uses the legacy format or a stream whose final `end`
 * marker has not arrived yet; those responses use the existing prefix parser.
 */
internal fun markedTranslationContent(content: String): String? {
    val normalized = normalizeSubtitleText(content)
    if (!TRANSLATION_BLOCK_START_LINE.containsMatchIn(normalized)) return null
    val completeBlocks = MARKED_TRANSLATION_BLOCK.findAll(normalized)
        .map { it.groupValues[1].trim('\n', '\r') }
        .toList()
    return completeBlocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

private fun hasCompleteTranslationBlock(content: String): Boolean =
    MARKED_TRANSLATION_BLOCK.containsMatchIn(normalizeSubtitleText(content))

private fun limitToSrtCodeBlock(content: String): String {
    val timeStart = TIMED_SUBTITLE_LINE.find(content)?.range?.first ?: return content
    val fences = MARKDOWN_FENCE_LINE.findAll(content).toList()
    val openingFence = fences.lastOrNull { it.range.first < timeStart }
    val closingFence = fences.firstOrNull { it.range.first > timeStart }
    return if (openingFence != null && closingFence != null) {
        content.substring(0, closingFence.range.first)
    } else {
        content
    }
}

private fun timedSubtitleLineRange(line: String): TranslationTimeRange? {
    val match = TIMED_SUBTITLE_LINE.matchEntire(line.trimEnd('\r')) ?: return null
    val startTime = parseTranslationTimestamp(match.groupValues[1]) ?: return null
    val endTime = parseTranslationTimestamp(match.groupValues[2]) ?: return null
    return TranslationTimeRange(startTime, endTime)
}

private fun parseTranslationTimestamp(value: String): Long? {
    val match = TRANSLATION_TIMESTAMP.matchEntire(value) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: return null
    val minutes = match.groupValues[2].toLongOrNull()?.takeIf { it < 60 } ?: return null
    val seconds = match.groupValues[3].toLongOrNull()?.takeIf { it < 60 } ?: return null
    val millis = match.groupValues[4].toLongOrNull() ?: return null
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

private fun parseVttTranslationTimestamp(value: String): Long? {
    val parts = value.trim().replace(',', '.').split(":")
    if (parts.size !in 2..3) return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    val minutesIndex = if (parts.size == 3) 1 else 0
    val minutes = parts[minutesIndex].toLongOrNull()?.takeIf { it < 60 } ?: return null
    val secondsPart = parts[minutesIndex + 1].split('.')
    if (secondsPart.size != 2) return null
    val seconds = secondsPart[0].toLongOrNull()?.takeIf { it < 60 } ?: return null
    val millis = secondsPart[1].take(3).padEnd(3, '0').toLongOrNull() ?: return null
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

private fun formatVttTimestamp(timeMs: Long): String {
    val safeTime = timeMs.coerceAtLeast(0L)
    val hours = safeTime / 3_600_000L
    val minutes = safeTime % 3_600_000L / 60_000L
    val seconds = safeTime % 60_000L / 1_000L
    val millis = safeTime % 1_000L
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
}

private fun String.removeTrailingMarkdownFence(): String {
    val lines = lines().toMutableList()
    while (lines.lastOrNull()?.trim() == "```") {
        lines.removeAt(lines.lastIndex)
    }
    return lines.joinToString("\n").trimEnd('\n')
}

private fun normalizeSubtitleText(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')
