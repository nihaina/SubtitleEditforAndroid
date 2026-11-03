package com.subtitleedit

import com.subtitleedit.util.SubtitleCoordinate
import com.subtitleedit.util.SubtitleFormattingTagOps
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttStyleOps
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler
import com.subtitleedit.util.subtitle.WebVttVoiceOps

/** Format tools prepare owned changed rows; the format session commits the result once. */
internal sealed interface EditorVttEdit {
    val description: String
    fun prepare(document: SubtitleDocument): SubtitleDocument

    data class SaveStyle(val oldName: String?, val name: String, val declarations: Map<String, String>) : EditorVttEdit {
        override val description = "编辑 VTT 样式"
        override fun prepare(document: SubtitleDocument): SubtitleDocument = WebVttStyleOps.upsert(
            oldName?.let { WebVttStyleOps.rename(document, it, name) } ?: document, name, declarations
        )
    }
    data class ApplyStyle(val positions: Set<Int>, val name: String) : EditorVttEdit {
        override val description = "应用 VTT 样式"
        override fun prepare(document: SubtitleDocument) = WebVttStyleOps.apply(document, positions, name)
    }
    data class DeleteStyle(val name: String) : EditorVttEdit {
        override val description = "删除 VTT 样式"
        override fun prepare(document: SubtitleDocument) = WebVttStyleOps.delete(document, name, true)
    }
    data class Voice(val positions: Set<Int>, val voice: String?) : EditorVttEdit {
        override val description = "设置说话人"
        override fun prepare(document: SubtitleDocument) = WebVttVoiceOps.set(document, positions, voice)
    }
    data class RemoveTags(val positions: Set<Int>, val kind: SubtitleFormattingTagOps.Kind) : EditorVttEdit {
        override val description = "删除格式化标签"
        override fun prepare(document: SubtitleDocument) = document.copy(entries = document.entries.mapIndexed { index, entry ->
            if (index !in positions) entry else entry.copy().also { copy ->
                copy.text = SubtitleFormattingTagOps.remove(copy.text, kind)
                WebVttSubtitleFormatHandler.clearCueAlignment(copy)
            }
        })
    }
    data class Placement(val positions: Set<Int>, val alignment: Int?, val coordinate: SubtitleCoordinate?) : EditorVttEdit {
        override val description = if (coordinate == null) "排列" else "排列和坐标"
        override fun prepare(document: SubtitleDocument) = document.copy(entries = document.entries.mapIndexed { index, entry ->
            if (index !in positions) entry else entry.copy().also { copy ->
                alignment?.let { WebVttSubtitleFormatHandler.updateCueAlignment(copy, it) }
                coordinate?.let { WebVttSubtitleFormatHandler.updateCueCoordinate(copy, it.xPercent, it.yPercent) }
            }
        })
    }
}
