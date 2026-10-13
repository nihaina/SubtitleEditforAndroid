package com.subtitleedit.util

/** A source text unit and its original display/alignment boundaries. */
data class TimedTextPart(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val alignedStartTimeMs: Long = startTimeMs,
    val alignedEndTimeMs: Long = endTimeMs,
)

internal object TimedTextSplitter {
    data class Slice(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val alignedStartTimeMs: Long,
        val alignedEndTimeMs: Long,
        val parts: List<TimedTextPart>,
    )

    fun sourceParts(
        text: String,
        startTimeMs: Long,
        endTimeMs: Long,
        alignedStartTimeMs: Long = startTimeMs,
        alignedEndTimeMs: Long = endTimeMs,
        parts: List<TimedTextPart> = emptyList(),
    ): List<TimedTextPart> {
        val source = parts.takeIf { it.isNotEmpty() && it.joinToString("") { part -> part.text } == text }
            ?: return listOf(TimedTextPart(text, startTimeMs, endTimeMs, alignedStartTimeMs, alignedEndTimeMs))
        // VAD can clip or extend a subtitle after decoding. Preserve those outer bounds
        // when source units are later split, while keeping internal alignment times.
        return source.mapIndexed { index, part ->
            val partStart = if (index == 0) startTimeMs else {
                part.startTimeMs.coerceIn(startTimeMs, endTimeMs - 1L)
            }
            val partEnd = if (index == source.lastIndex) endTimeMs else {
                part.endTimeMs.coerceIn(partStart + 1L, endTimeMs)
            }
            part.copy(startTimeMs = partStart, endTimeMs = partEnd)
        }
    }

    fun join(
        left: List<TimedTextPart>,
        right: List<TimedTextPart>,
        separator: String,
    ): List<TimedTextPart> = left + right.mapIndexed { index, part ->
        if (index == 0) part.copy(text = separator + part.text) else part
    }

    fun trim(parts: List<TimedTextPart>): List<TimedTextPart> {
        val result = parts.toMutableList()
        while (result.isNotEmpty()) {
            val first = result.first().copy(text = result.first().text.trimStart())
            if (first.text.isEmpty()) result.removeAt(0) else {
                result[0] = first
                break
            }
        }
        while (result.isNotEmpty()) {
            val last = result.last().copy(text = result.last().text.trimEnd())
            if (last.text.isEmpty()) result.removeAt(result.lastIndex) else {
                result[result.lastIndex] = last
                break
            }
        }
        return result
    }

    fun split(parts: List<TimedTextPart>, splitIndex: Int): Pair<Slice, Slice> {
        val fullText = parts.joinToString("") { it.text }
        require(splitIndex in 1 until fullText.length)
        require(!(fullText[splitIndex - 1].isHighSurrogate() && fullText[splitIndex].isLowSurrogate()))
        val left = mutableListOf<TimedTextPart>()
        val right = mutableListOf<TimedTextPart>()
        var position = 0
        parts.forEach { part ->
            when {
                position >= splitIndex -> right += part
                position + part.text.length <= splitIndex -> left += part
                else -> {
                    val localIndex = splitIndex - position
                    val leftText = part.text.substring(0, localIndex)
                    val rightText = part.text.substring(localIndex)
                    // A boundary between original units keeps their exact times. Only a
                    // split inside one unit needs an estimate, based on Unicode code points.
                    val leftCount = leftText.codePointCount(0, leftText.length)
                    val totalCount = part.text.codePointCount(0, part.text.length)
                    val display = divideTime(part.startTimeMs, part.endTimeMs, leftCount, totalCount)
                    val aligned = divideTime(part.alignedStartTimeMs, part.alignedEndTimeMs, leftCount, totalCount)
                    left += part.copy(text = leftText, endTimeMs = display.first, alignedEndTimeMs = aligned.first)
                    right += part.copy(text = rightText, startTimeMs = display.second, alignedStartTimeMs = aligned.second)
                }
            }
            position += part.text.length
        }
        return slice(trim(left)) to slice(trim(right))
    }

    private fun slice(parts: List<TimedTextPart>): Slice {
        require(parts.isNotEmpty())
        val start = parts.first().startTimeMs
        val alignedStart = parts.first().alignedStartTimeMs
        return Slice(
            text = parts.joinToString("") { it.text },
            startTimeMs = start,
            endTimeMs = parts.maxOf { it.endTimeMs }.coerceAtLeast(start + 1L),
            alignedStartTimeMs = alignedStart,
            alignedEndTimeMs = parts.maxOf { it.alignedEndTimeMs }.coerceAtLeast(alignedStart + 1L),
            parts = parts,
        )
    }

    private fun divideTime(start: Long, end: Long, leftCount: Int, totalCount: Int): Pair<Long, Long> {
        if (end - start <= 1L) return end.coerceAtLeast(start + 1L) to start
        val boundary = (start + (end - start) * leftCount / totalCount).coerceIn(start + 1L, end - 1L)
        return boundary to boundary
    }
}
