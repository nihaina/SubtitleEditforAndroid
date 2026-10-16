package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Normalized subtitle anchor coordinates used by the alignment picker. */
data class SubtitleCoordinate(val xPercent: Float, val yPercent: Float) {
    init {
        require(xPercent in 0f..100f && yPercent in 0f..100f)
    }

    fun asText(): String = "${format(xPercent)}%, ${format(yPercent)}%"

    companion object {
        private fun format(value: Float): String =
            String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
    }
}

/** Applies exact coordinates while keeping the document format's native representation. */
object SubtitleCoordinateOps {
    // The temporary ASS preview uses the same PlayRes as WebVttPreviewWriter.
    const val PREVIEW_PLAY_RES_WIDTH = 384
    const val PREVIEW_PLAY_RES_HEIGHT = 288

    private val posTag = Regex("\\\\pos\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)", RegexOption.IGNORE_CASE)

    fun fromText(text: String): SubtitleCoordinate? {
        val match = posTag.find(text) ?: return null
        val x = match.groupValues[1].toFloatOrNull() ?: return null
        val y = match.groupValues[2].toFloatOrNull() ?: return null
        return SubtitleCoordinate(
            (x * 100f / PREVIEW_PLAY_RES_WIDTH).coerceIn(0f, 100f),
            (y * 100f / PREVIEW_PLAY_RES_HEIGHT).coerceIn(0f, 100f)
        )
    }

    /** Removes explicit ASS positioning overrides and an empty leading override block. */
    fun remove(text: String): String = posTag.replace(text, "")
        .replaceFirst(Regex("^\\{\\}"), "")

    fun set(text: String, coordinate: SubtitleCoordinate): String {
        if (text.isEmpty()) return text
        val x = (coordinate.xPercent * PREVIEW_PLAY_RES_WIDTH / 100f).roundToInt()
        val y = (coordinate.yPercent * PREVIEW_PLAY_RES_HEIGHT / 100f).roundToInt()
        val withoutExisting = remove(SubtitleAlignmentOps.remove(text))
        val tag = "\\pos($x,$y)"
        val withPosition = if (withoutExisting.startsWith("{\\") && withoutExisting.indexOf('}') > 0) {
            withoutExisting.substring(0, 1) + tag + withoutExisting.substring(1)
        } else {
            "{$tag}$withoutExisting"
        }
        val alignment = alignmentFor(coordinate)
        if (alignment == 2) return withPosition
        return if (withPosition.startsWith("{\\") && withPosition.indexOf('}') > 0) {
            withPosition.substring(0, 2) + "an$alignment\\" + withPosition.substring(2)
        } else {
            "{\\an$alignment}$withPosition"
        }
    }

    /** Maps an exact coordinate to the same derived ASS anchor used by WebVTT. */
    private fun alignmentFor(coordinate: SubtitleCoordinate): Int {
        val column = when {
            coordinate.xPercent < 25f -> 1
            coordinate.xPercent > 75f -> 3
            else -> 2
        }
        val row = when {
            coordinate.yPercent < 25f -> 2
            coordinate.yPercent < 75f -> 1
            else -> 0
        }
        return row * 3 + column
    }

    fun fromEntry(entry: SubtitleEntry, format: SubtitleFormat): SubtitleCoordinate? =
        if (format == SubtitleFormat.VTT) {
            fromVttSettings(entry.cueSettings) ?: alignmentFromText(entry.text)
        } else {
            fromText(entry.text) ?: alignmentFromText(entry.text)
        }

    /** Returns only coordinates explicitly stored in the entry, without deriving them from alignment. */
    fun fromExplicitEntry(entry: SubtitleEntry, format: SubtitleFormat): SubtitleCoordinate? =
        if (format == SubtitleFormat.VTT) {
            fromVttSettings(entry.cueSettings)
        } else {
            fromText(entry.text)
        }

    fun fromVttSettings(settings: String): SubtitleCoordinate? {
        val x = Regex("(?:^|\\s)position:(\\d+(?:\\.\\d+)?)%", RegexOption.IGNORE_CASE)
            .find(settings)?.groupValues?.get(1)?.toFloatOrNull()
        val y = Regex("(?:^|\\s)line:(\\d+(?:\\.\\d+)?)%", RegexOption.IGNORE_CASE)
            .find(settings)?.groupValues?.get(1)?.toFloatOrNull()
        if (x == null && y == null) return null
        return runCatching { SubtitleCoordinate(x ?: 50f, y ?: 80f) }.getOrNull()
    }

    fun alignmentFromText(text: String): SubtitleCoordinate? {
        val number = Regex("\\\\an([1-9])").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 2
        return defaultForAlignment(number)
    }

    fun defaultForAlignment(alignment: Int): SubtitleCoordinate = when (alignment) {
        1 -> SubtitleCoordinate(20f, 80f)
        2 -> SubtitleCoordinate(50f, 80f)
        3 -> SubtitleCoordinate(80f, 80f)
        4 -> SubtitleCoordinate(20f, 50f)
        5 -> SubtitleCoordinate(50f, 50f)
        6 -> SubtitleCoordinate(80f, 50f)
        7 -> SubtitleCoordinate(20f, 20f)
        8 -> SubtitleCoordinate(50f, 20f)
        9 -> SubtitleCoordinate(80f, 20f)
        else -> error("ASSA alignment must be 1..9")
    }
}
