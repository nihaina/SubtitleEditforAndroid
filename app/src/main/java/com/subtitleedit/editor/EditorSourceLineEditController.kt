package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser

/** Resolves a source-editor line change to one parsed subtitle row. */
internal class EditorSourceLineEditController(
    private val lineCount: () -> Int,
    private val lineText: (Int) -> String,
    private val currentFormat: () -> SubtitleParser.SubtitleFormat,
    private val entries: () -> List<SubtitleEntry>
) {
    data class Update(val entryIndex: Int, val entry: SubtitleEntry)

    private var cueIndexByLine: IntArray? = null
    private val lrcTimeTagPattern = Regex("\\[-?\\d{1,4}[:.]\\d{1,2}(?:[.:]\\d{1,3})?]")

    fun invalidateLineIndex() {
        cueIndexByLine = null
    }

    fun resolve(startLine: Int, oldLineCount: Int, newLineCount: Int): Update? {
        if (oldLineCount != newLineCount) return null
        val count = lineCount()
        if (startLine !in 0 until count) return null

        var blockStart = startLine
        while (blockStart > 0 && lineText(blockStart - 1).isNotBlank()) blockStart--
        var blockEnd = startLine
        while (blockEnd + 1 < count && lineText(blockEnd + 1).isNotBlank()) blockEnd++
        val block = buildString {
            for (line in blockStart..blockEnd) {
                if (line > blockStart) append('\n')
                append(lineText(line))
            }
        }
        val parsed = SubtitleParser.parseDocument(block, format = currentFormat()).entries
        if (parsed.size != 1) return null

        val index = cueIndexBeforeLine(blockStart)
        val current = entries().getOrNull(index) ?: return null
        return Update(index, parsed.single().copy(stableId = current.stableId))
    }

    private fun cueIndexBeforeLine(lineIndex: Int): Int {
        val count = lineCount()
        val map = cueIndexByLine ?: IntArray(count).also { result ->
            var cueCount = 0
            for (line in 0 until count) {
                result[line] = cueCount
                val text = lineText(line)
                cueCount += when (currentFormat()) {
                    SubtitleParser.SubtitleFormat.SRT,
                    SubtitleParser.SubtitleFormat.VTT -> if (text.contains("-->")) 1 else 0
                    SubtitleParser.SubtitleFormat.LRC -> lrcTimeTagPattern.findAll(text).count()
                    SubtitleParser.SubtitleFormat.TXT -> if (text.isNotBlank()) 1 else 0
                    else -> 0
                }
            }
        }.also { cueIndexByLine = it }
        return map.getOrElse(lineIndex) { entries().size }
    }
}
