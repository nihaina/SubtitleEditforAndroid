package com.subtitleedit.feature.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryMediaProcessingMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mediaProcessingFollowsTagEditingAndOnlyInvokesItsOwnAction() {
        var edits = 0
        var processing = 0
        compose.setContent { SubtitleEditComposeTheme {
            SelectionActions(null, {}, {}, {}, {}, canConvert = false, canCompress = true,
                canEditTags = true, canClearCache = true, onConvert = {}, onCompress = {},
                onEditTags = { edits++ }, onClearCache = {}, onProperties = {},
                onConfirmDestination = {}, onCancelDestination = {}, canProcessMedia = true,
                onProcessMedia = { processing++ })
        } }
        compose.onNodeWithText("更多").performClick()
        val tags = compose.onNodeWithText("编辑标签").fetchSemanticsNode().boundsInRoot
        val media = compose.onNodeWithText("音视频处理").fetchSemanticsNode().boundsInRoot
        val cache = compose.onNodeWithText("清除缓存").fetchSemanticsNode().boundsInRoot
        assertTrue(tags.top < media.top)
        assertTrue(media.top < cache.top)
        compose.onNodeWithText("音视频处理").performClick()
        compose.onNodeWithText("音视频处理").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, processing); assertEquals(0, edits) }
    }

    @Test fun mediaWithSubtitlesCanProcessEvenWhenTagEditingIsUnavailable() {
        var processing = 0
        compose.setContent { SubtitleEditComposeTheme {
            SelectionActions(null, {}, {}, {}, {}, canConvert = false, canCompress = true,
                canEditTags = false, canClearCache = false, onConvert = {}, onCompress = {},
                onEditTags = {}, onClearCache = {}, onProperties = {},
                onConfirmDestination = {}, onCancelDestination = {}, canProcessMedia = true,
                onProcessMedia = { processing++ })
        } }
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("编辑标签").assertDoesNotExist()
        compose.onNodeWithText("音视频处理").performClick()
        compose.runOnIdle { assertEquals(1, processing) }
    }

    @Test fun selectionsWithoutMediaDoNotOfferMediaProcessing() {
        compose.setContent { SubtitleEditComposeTheme {
            SelectionActions(null, {}, {}, {}, {}, canConvert = true, canCompress = true,
                canEditTags = false, canClearCache = false, onConvert = {}, onCompress = {},
                onEditTags = {}, onClearCache = {}, onProperties = {},
                onConfirmDestination = {}, onCancelDestination = {})
        } }
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("音视频处理").assertDoesNotExist()
    }
}
