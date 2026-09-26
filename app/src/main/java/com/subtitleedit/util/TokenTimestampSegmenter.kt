package com.subtitleedit.util

import kotlin.math.roundToLong

internal object TokenTimestampSegmenter {

    data class Segment(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String
    )

    fun fromTokens(
        tokens: Array<String>,
        timestamps: FloatArray,
        durations: FloatArray,
        audioStartTimeMs: Long,
        audioEndTimeMs: Long
    ): List<Segment> {
        if (
            tokens.isEmpty() ||
            tokens.size != timestamps.size ||
            audioEndTimeMs <= audioStartTimeMs
        ) {
            return emptyList()
        }

        val alignedTokens = tokens.indices.mapNotNull { index ->
            val timestamp = timestamps[index]
            val duration = durations.getOrNull(index)
                ?.takeIf { it.isFinite() && it > 0f }
                ?: 0f
            if (!timestamp.isFinite()) {
                null
            } else {
                TimedToken(
                    text = normalizeToken(tokens[index]),
                    startTimeMs = (timestamp.coerceAtLeast(0f) * 1000f).roundToLong(),
                    durationMs = (duration * 1000f).roundToLong().coerceAtLeast(1L)
                )
            }
        }.filter { it.text.isNotEmpty() }
        if (alignedTokens.isEmpty()) return emptyList()

        val maxLocalTimeMs = audioEndTimeMs - audioStartTimeMs
        val boundaryContextMs = 100L
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

        normalizedTokens.forEachIndexed { index, token ->
            currentText.append(token.text)
            val next = normalizedTokens.getOrNull(index + 1)
            val blankGapMs = next?.let { it.startTimeMs - token.endTimeMs }
            if (next != null && blankGapMs != null && blankGapMs >= 0L) {
                val boundaryExtensionMs = minOf(boundaryContextMs, blankGapMs / 2L)
                addSegment(
                    output = segments,
                    startTimeMs = segmentStartTimeMs,
                    endTimeMs = audioStartTimeMs + token.endTimeMs + boundaryExtensionMs,
                    text = currentText.toString()
                )
                currentText = StringBuilder()
                segmentStartTimeMs = audioStartTimeMs + next.startTimeMs - boundaryExtensionMs
            }
        }

        addSegment(
            output = segments,
            startTimeMs = segmentStartTimeMs,
            endTimeMs = audioStartTimeMs + minOf(
                maxLocalTimeMs,
                normalizedTokens.last().endTimeMs + boundaryContextMs
            ),
            text = currentText.toString()
        )
        return segments
    }

    fun mergeSegments(segments: List<Segment>, maxGapMs: Int): List<Segment> {
        if (segments.size < 2) return segments

        val gapThreshold = maxGapMs.coerceIn(0, 5000).toLong()
        val merged = mutableListOf<Segment>()
        var current = segments.first()
        for (next in segments.drop(1)) {
            val gapMs = next.startTimeMs - current.endTimeMs
            if (gapMs <= gapThreshold) {
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
        text: String
    ) {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return
        output += Segment(
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs.coerceAtLeast(startTimeMs + 1L),
            text = normalizedText
        )
    }

    private fun joinText(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val needsSpace = left.last().isLetterOrDigit() && right.first().isLetterOrDigit() &&
            left.last().code < 128 && right.first().code < 128
        return if (needsSpace) "$left $right" else left + right
    }

    private fun normalizeToken(token: String): String = token
        .replace('\u2581', ' ')
        .replace('\u0120', ' ')

    private data class TimedToken(
        val text: String,
        val startTimeMs: Long,
        val durationMs: Long,
        val endTimeMs: Long = startTimeMs + durationMs
    )
}
