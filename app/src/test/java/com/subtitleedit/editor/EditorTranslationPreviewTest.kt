package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.AiTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTranslationPreviewTest {

    private val selectedEntries = listOf(
        SubtitleEntry(text = "one") to 2,
        SubtitleEntry(text = "two") to 5,
        SubtitleEntry(text = "three") to 8
    )

    @Test
    fun partialTranslations_leaveRemainingItemsBlankAndUnchecked() {
        val items = buildTranslationPreviewItems(selectedEntries, listOf("一", "二"))

        assertEquals(listOf(2, 5, 8), items.map { it.entryPosition })
        assertEquals(listOf("一", "二", ""), items.map { it.translatedText })
        assertTrue(items[0].apply)
        assertTrue(items[1].apply)
        assertFalse(items[2].apply)
        assertFalse(items[2].suspectedProblem)
    }

    @Test
    fun completeTranslations_keepAllItemsChecked() {
        val items = buildTranslationPreviewItems(selectedEntries, listOf("一", "二", "三"))

        assertTrue(items.all { it.apply })
    }

    @Test
    fun translationCancellation_carriesCompletedBatchState() {
        val conversationState = AiTranslator.ConversationState(
            listOf(AiTranslator.ChatMessage("system", "系统提示"))
        )
        val exception = AiTranslator.TranslationCancelledException(
            translations = listOf("一", "二"),
            conversationState = conversationState,
            message = "用户取消翻译"
        )

        assertEquals(listOf("一", "二"), exception.translations)
        assertEquals(conversationState, exception.conversationState)
    }

    @Test
    fun blankReturnedTranslation_isAppliedAndMarkedAsSuspect() {
        val items = buildTranslationPreviewItems(selectedEntries, listOf("一", "", "三"))

        assertTrue(items[1].apply)
        assertTrue(items[1].suspectedProblem)
        assertFalse(items[0].suspectedProblem)
    }
}
