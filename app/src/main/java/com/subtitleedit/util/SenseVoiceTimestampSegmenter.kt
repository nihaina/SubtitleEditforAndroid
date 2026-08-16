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
        durations: FloatArray,
        audioStartTimeMs: Long,
        audioEndTimeMs: Long,
        splitGapMs: Int
    ): List<Segment> {
        if (
            tokens.isEmpty() ||
            tokens.size != timestamps.size ||
            tokens.size != durations.size ||
            audioEndTimeMs <= audioStartTimeMs
        ) {
            return emptyList()
        }

        val gapThreshold = splitGapMs.coerceIn(100, 2000).toLong()
        val alignedTokens = tokens.indices.mapNotNull { index ->
            val token = tokens[index]
            val timestamp = timestamps[index]
            val duration = durations[index]
            if (token.isEmpty() || !timestamp.isFinite() || !duration.isFinite() || duration <= 0f) {
                null
            } else {
                TimedToken(
                    text = token,
                    startTimeMs = (timestamp.coerceAtLeast(0f) * 1000f).roundToLong(),
                    durationMs = (duration * 1000f).roundToLong().coerceAtLeast(1L)
                )
            }
        }
        if (alignedTokens.isEmpty()) return emptyList()

        val maxLocalTimeMs = audioEndTimeMs - audioStartTimeMs
        // CTC tokens are usually single-frame spikes. Keep their exact blank-gap
        // boundaries for splitting, but give each resulting segment limited context.
        val boundaryContextMs = (gapThreshold / 2L).coerceIn(100L, 500L)
        var previousStartTimeMs = 0L
        val normalizedTokens = alignedTokens.map { token ->
            val normalizedStartTimeMs = token.startTimeMs
                .coerceIn(0L, maxLocalTimeMs - 1L)
                .coerceAtLeast(previousStartTimeMs)
            val normalizedEndTimeMs = (normalizedStartTimeMs + token.durationMs)
                .coerceIn(normalizedStartTimeMs + 1L, maxLocalTimeMs)
            previousStartTimeMs = normalizedStartTimeMs
            token.copy(
                startTimeMs = normalizedStartTimeMs,
                endTimeMs = normalizedEndTimeMs
            )
        }

        val segments = mutableListOf<Segment>()
        var segmentStartTimeMs = audioStartTimeMs +
            (normalizedTokens.first().startTimeMs - boundaryContextMs).coerceAtLeast(0L)
        var currentText = StringBuilder()
        var hardBoundaryBefore = false

        normalizedTokens.forEachIndexed { index, token ->
            currentText.append(token.text)
            val next = normalizedTokens.getOrNull(index + 1)
            val blankGapMs = next?.let { it.startTimeMs - token.endTimeMs }
            if (next != null && blankGapMs != null && blankGapMs >= gapThreshold) {
                val boundaryExtensionMs = minOf(boundaryContextMs, blankGapMs / 2L)
                addSegment(
                    output = segments,
                    startTimeMs = segmentStartTimeMs,
                    endTimeMs = audioStartTimeMs + token.endTimeMs + boundaryExtensionMs,
                    text = currentText.toString(),
                    hardBoundaryBefore = hardBoundaryBefore
                )
                currentText = StringBuilder()
                segmentStartTimeMs =
                    audioStartTimeMs + next.startTimeMs - boundaryExtensionMs
                hardBoundaryBefore = true
            }
        }

        addSegment(
            output = segments,
            startTimeMs = segmentStartTimeMs,
            endTimeMs = audioStartTimeMs + minOf(
                maxLocalTimeMs,
                normalizedTokens.last().endTimeMs + boundaryContextMs
            ),
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
        val startTimeMs: Long,
        val durationMs: Long,
        val endTimeMs: Long = startTimeMs + durationMs
    )
}
