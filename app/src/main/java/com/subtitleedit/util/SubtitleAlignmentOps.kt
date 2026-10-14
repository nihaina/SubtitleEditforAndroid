package com.subtitleedit.util

/**
 * Subtitle Edit compatible ASSA alignment tags.
 *
 * The picker uses the numpad mapping used by Subtitle Edit: an1..an3 are
 * bottom left/centre/right, an4..an6 middle left/centre/right and an7..an9
 * top left/centre/right.  These tags are supported by SRT/LRC text and are
 * also the internal representation used while converting WebVTT positions.
 */
object SubtitleAlignmentOps {
    /** The nine positions in the order used by the Subtitle Edit picker. */
    val alignments: List<Alignment> = listOf(
        Alignment(7, "上/左"), Alignment(8, "上/中"), Alignment(9, "上/右"),
        Alignment(4, "中/左"), Alignment(5, "中/中"), Alignment(6, "中/右"),
        Alignment(1, "下/左"), Alignment(2, "下/中"), Alignment(3, "下/右")
    )

    data class Alignment(val number: Int, val label: String) {
        init {
            require(number in 1..9) { "ASSA alignment must be 1..9" }
        }

        val tag: String get() = "an$number"
    }

    /** Returns true when [text] contains an ASSA alignment command. */
    fun has(text: String, alignment: Int? = null): Boolean {
        require(alignment == null || alignment in 1..9) {
            "ASSA alignment must be 1..9"
        }
        val number = alignment?.toString() ?: "[1-9]"
        return Regex("\\\\an$number(?=\\})").containsMatchIn(text) ||
            Regex("(?<![A-Za-z0-9])\\\\an$number(?![0-9])").containsMatchIn(text)
    }

    /** Removes standalone alignment tags exactly as Subtitle Edit does. */
    fun remove(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        for (number in 1..9) {
            result = result
                .replace("{\\an$number}", "")
                .replace("\\an$number", "")
        }
        return result
    }

    /**
     * Replaces an existing alignment and adds [alignment].  The default
     * bottom-centre position (an2) is omitted, matching Subtitle Edit's
     * default unless the caller explicitly requests that tag.
     */
    fun set(text: String, alignment: Int, writeAn2Tag: Boolean = false): String {
        require(alignment in 1..9) { "ASSA alignment must be 1..9" }
        var result = remove(text)
        if (result.isEmpty() || (alignment == 2 && !writeAn2Tag)) return result

        if (result.startsWith("{\\")) {
            // Keep existing ASSA override commands together, as Subtitle Edit
            // does (e.g. {\\i1}text -> {\\an8\\i1}text).
            return result.substring(0, 2) + "an$alignment\\" + result.substring(2)
        }
        return "{\\an$alignment}$result"
    }

    fun set(text: String, alignment: Alignment, writeAn2Tag: Boolean = false): String =
        set(text, alignment.number, writeAn2Tag)
}
