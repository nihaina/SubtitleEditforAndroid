package com.subtitleedit.util

import kotlin.math.roundToLong

internal object SenseVoiceTimestampSegmenter {

    data class Segment(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String,
        val hardBoundaryBefore: Boolean = false
    )

    fun split(
        tokens: Array<String>,
        timestamps: FloatArray,
        audioStartTimeMs: Long,
        audioEndTimeMs: Long,
        splitGapMs: Int
    ): List<Segment> {
        if (tokens.isEmpty() || tokens.size != timestamps.size || audioEndTimeMs <= audioStartTimeMs) {
            return emptyList()
        }

        val gapThreshold = splitGapMs.coerceIn(100, 2000).toLong()
        val alignedTokens = tokens.indices.mapNotNull { index ->
            val token = tokens[index]
            val timestamp = timestamps[index]
            if (token.isEmpty() || !timestamp.isFinite()) {
                null
            } else {
                TimedToken(
                    text = token,
                    timeMs = (timestamp.coerceAtLeast(0f) * 1000f).roundToLong()
                )
            }
        }
        if (alignedTokens.isEmpty()) return emptyList()

        val maxLocalTimeMs = audioEndTimeMs - audioStartTimeMs
        var previousTimeMs = 0L
        val normalizedTokens = alignedTokens.map { token ->
            val normalizedTime = token.timeMs
                .coerceIn(0L, maxLocalTimeMs)
                .coerceAtLeast(previousTimeMs)
            previousTimeMs = normalizedTime
            token.copy(timeMs = normalizedTime)
        }

        val segments = mutableListOf<Segment>()
        var segmentStartTimeMs = audioStartTimeMs + normalizedTokens.first().timeMs
        var currentText = StringBuilder()
        var hardBoundaryBefore = false

        normalizedTokens.forEachIndexed { index, token ->
            currentText.append(token.text)
            val next = normalizedTokens.getOrNull(index + 1)
            if (next != null && next.timeMs - token.timeMs >= gapThreshold) {
                addSegment(
                    output = segments,
                    startTimeMs = segmentStartTimeMs,
                    endTimeMs = audioStartTimeMs + token.timeMs,
                    text = currentText.toString(),
                    hardBoundaryBefore = hardBoundaryBefore
                )
                currentText = StringBuilder()
                segmentStartTimeMs = audioStartTimeMs + next.timeMs
                hardBoundaryBefore = true
            }
        }

        val tailPaddingMs = (gapThreshold / 2L).coerceIn(100L, 500L)
        val lastTokenTimeMs = audioStartTimeMs + normalizedTokens.last().timeMs
        addSegment(
            output = segments,
            startTimeMs = segmentStartTimeMs,
            endTimeMs = minOf(audioEndTimeMs, lastTokenTimeMs + tailPaddingMs),
            text = currentText.toString(),
            hardBoundaryBefore = hardBoundaryBefore
        )
        return segments
    }

    fun mergeShortGaps(segments: List<Segment>, splitGapMs: Int): List<Segment> {
        if (segments.size < 2) return segments

        val gapThreshold = splitGapMs.coerceIn(100, 2000).toLong()
        val merged = mutableListOf<Segment>()
        var current = segments.first()
        for (next in segments.drop(1)) {
            val gapMs = next.startTimeMs - current.endTimeMs
            if (!next.hardBoundaryBefore && gapMs < gapThreshold) {
                current = current.copy(
                    endTimeMs = maxOf(current.endTimeMs, next.endTimeMs),
                    text = joinText(current.text, next.text)
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun addSegment(
        output: MutableList<Segment>,
        startTimeMs: Long,
        endTimeMs: Long,
        text: String,
        hardBoundaryBefore: Boolean
    ) {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return
        val safeEndTimeMs = endTimeMs.coerceAtLeast(startTimeMs + 1L)
        output += Segment(startTimeMs, safeEndTimeMs, normalizedText, hardBoundaryBefore)
    }

    private fun joinText(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val needsSpace = left.last().isLetterOrDigit() && right.first().isLetterOrDigit() &&
            left.last().code < 128 && right.first().code < 128
        return if (needsSpace) "$left $right" else left + right
    }

    private data class TimedToken(
        val text: String,
        val timeMs: Long
    )
}
