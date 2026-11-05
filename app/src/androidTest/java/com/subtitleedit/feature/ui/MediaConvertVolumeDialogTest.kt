package com.subtitleedit.feature.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertVolumeDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sliderChangesOnlyCommitOnDoneAndResetRestoresOriginalVolume() {
        val saved = mutableListOf<Int>()
        compose.setContent { SubtitleEditComposeTheme { MediaConvertVolumeDialog(100, {}, { saved += it }) } }
        compose.onNodeWithTag("volume-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.onNodeWithText("550%").assertExists()
        assertTrue(saved.isEmpty())
        compose.onNodeWithTag("volume-reset").performClick()
        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithTag("volume-confirm").performClick()
        assertEquals(listOf(100), saved)
    }

    @Test
    fun cancelDiscardsTheChangedVolume() {
        var dismissed = false
        val saved = mutableListOf<Int>()
        compose.setContent { SubtitleEditComposeTheme { MediaConvertVolumeDialog(200, { dismissed = true }, { saved += it }) } }
        compose.onNodeWithTag("volume-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertTrue(saved.isEmpty())
    }
}
