package com.subtitleedit.util

import kotlin.math.max
import kotlin.math.min

/** A text unit returned by a forced aligner. Times are relative to the input window. */
data class ForcedAlignmentUnit(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

/**
 * Converts word/character spans from an external aligner to the same segment shape used by
 * the token timestamp experiment. This deliberately accepts words or characters; it does not
 * assume that the aligner's units are the ASR tokenizer's tokens.
 */
internal object ForcedAlignmentSegmenter {
    data class Segment(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String,
        val hardBoundaryBefore: Boolean = false,
        val alignedStartTimeMs: Long = startTimeMs,
        val alignedEndTimeMs: Long = endTimeMs,
    )

    fun split(
        units: List<ForcedAlignmentUnit>,
        audioStartTimeMs: Long,
        audioEndTimeMs: Long,
        splitGapMs: Int,
    ): List<Segment> {
        if (units.isEmpty() || audioEndTimeMs <= audioStartTimeMs) return emptyList()

        val maxLocalTimeMs = audioEndTimeMs - audioStartTimeMs
        val gapThreshold = splitGapMs.coerceIn(0, 5000).toLong()
        val normalized = units.mapNotNull { unit ->
            val text = unit.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val start = unit.startTimeMs.coerceIn(0L, max(0L, maxLocalTimeMs - 1L))
            val end = unit.endTimeMs.coerceIn(start + 1L, maxLocalTimeMs)
            ForcedAlignmentUnit(text, start, end)
        }.sortedWith(compareBy<ForcedAlignmentUnit> { it.startTimeMs }.thenBy { it.endTimeMs })
        if (normalized.isEmpty()) return emptyList()

        val contextMs = (gapThreshold / 2L).coerceIn(100L, 500L)
        val output = mutableListOf<Segment>()
        var currentText = StringBuilder()
        var segmentStart = max(0L, normalized.first().startTimeMs - contextMs)
        var alignedSegmentStart = normalized.first().startTimeMs
        var hardBoundaryBefore = false

        normalized.forEachIndexed { index, unit ->
            if (currentText.isNotEmpty()) currentText.append(joiner(currentText.last(), unit.text.first()))
            currentText.append(unit.text)

            val next = normalized.getOrNull(index + 1)
            val gap = next?.let { it.startTimeMs - unit.endTimeMs }
            if (next != null && gap != null &&
                (gap >= gapThreshold || isSentenceBoundary(unit.text))) {
                val extension = min(contextMs, gap / 2L)
                addSegment(
                    output = output,
                    audioStartTimeMs = audioStartTimeMs,
                    startTimeMs = segmentStart,
                    endTimeMs = unit.endTimeMs + extension,
                    alignedStartTimeMs = alignedSegmentStart,
                    alignedEndTimeMs = unit.endTimeMs,
                    text = currentText.toString(),
                    hardBoundaryBefore = hardBoundaryBefore,
                )
                currentText = StringBuilder()
                segmentStart = next.startTimeMs - extension
                alignedSegmentStart = next.startTimeMs
                hardBoundaryBefore = true
            }
        }

        addSegment(
            output = output,
            audioStartTimeMs = audioStartTimeMs,
            startTimeMs = segmentStart,
            endTimeMs = min(maxLocalTimeMs, normalized.last().endTimeMs + contextMs),
            alignedStartTimeMs = alignedSegmentStart,
            alignedEndTimeMs = normalized.last().endTimeMs,
            text = currentText.toString(),
            hardBoundaryBefore = hardBoundaryBefore,
        )
        return output
    }

    fun mergeSegments(segments: List<Segment>, maxGapMs: Int): List<Segment> {
        if (segments.size < 2) return segments
        val threshold = maxGapMs.coerceIn(0, 5000).toLong()
        val merged = mutableListOf<Segment>()
        var current = segments.first()
        for (next in segments.drop(1)) {
            if (next.alignedStartTimeMs - current.alignedEndTimeMs <= threshold) {
                current = current.copy(
                    endTimeMs = max(current.endTimeMs, next.endTimeMs),
                    alignedEndTimeMs = max(current.alignedEndTimeMs, next.alignedEndTimeMs),
                    text = current.text + mergeJoiner(current.text.last(), next.text.first()) + next.text,
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
        audioStartTimeMs: Long,
        startTimeMs: Long,
        endTimeMs: Long,
        alignedStartTimeMs: Long,
        alignedEndTimeMs: Long,
        text: String,
        hardBoundaryBefore: Boolean,
    ) {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return
        val start = audioStartTimeMs + startTimeMs.coerceAtLeast(0L)
        val end = audioStartTimeMs + endTimeMs.coerceAtLeast(startTimeMs + 1L)
        output += Segment(
            start,
            max(start + 1L, end),
            normalizedText,
            hardBoundaryBefore,
            audioStartTimeMs + alignedStartTimeMs,
            audioStartTimeMs + alignedEndTimeMs,
        )
    }

    private fun joiner(left: Char, right: Char): String =
        if (left.isLetterOrDigit() && right.isLetterOrDigit() && left.code < 128 && right.code < 128) {
            " "
        } else {
            ""
        }

    private fun mergeJoiner(left: Char, right: Char): String =
        if (right.isLetterOrDigit() && right.code < 128 &&
            left.code < 128 && (left.isLetterOrDigit() || left in ".!?;,:")) {
            " "
        } else {
            ""
        }

    private fun isSentenceBoundary(text: String): Boolean =
        text.lastOrNull()?.let { it in charArrayOf('。', '！', '？', '；', '.', '!', '?', ';') } == true
}
