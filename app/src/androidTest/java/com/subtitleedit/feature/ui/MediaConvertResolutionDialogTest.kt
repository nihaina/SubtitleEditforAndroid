package com.subtitleedit.feature.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertFitMode
import com.subtitleedit.MediaConvertResolutionSettings
import com.subtitleedit.MediaConvertVideoSize
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertResolutionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun linkedCustomDimensionsAndFitRuleCommitOnlyOnDone() {
        val saved = mutableListOf<MediaConvertResolutionSettings>()
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertResolutionDialog(MediaConvertResolutionSettings(), MediaConvertVideoSize(1080, 1920), false, {}, { saved += it })
            }
        }
        compose.onNodeWithTag("resolution-lock").performClick()
        compose.onNodeWithTag("resolution-width").performTextReplacement("721")
        compose.onNodeWithTag("resolution-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("resolution-width").performTextReplacement("720")
        compose.onNodeWithTag("resolution-height").assertTextContains("1280")
        compose.onNodeWithTag("resolution-fit-rules").performClick()
        compose.onNodeWithText("拉伸（画面变形）").performClick()
        compose.onNodeWithTag("resolution-fit-confirm").performClick()
        assertTrue(saved.isEmpty())
        compose.onNodeWithTag("resolution-confirm").performClick()
        assertEquals(listOf(MediaConvertResolutionSettings(-1, 720, 1280, true, MediaConvertFitMode.STRETCH)), saved)
    }

    @Test
    fun cancellingDiscardsCustomInput() {
        var dismissed = false
        val saved = mutableListOf<MediaConvertResolutionSettings>()
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertResolutionDialog(MediaConvertResolutionSettings(), null, false, { dismissed = true }, { saved += it })
            }
        }
        compose.onNodeWithTag("resolution-width").performTextReplacement("640")
        compose.onNodeWithTag("resolution-height").performTextReplacement("480")
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertTrue(saved.isEmpty())
    }
}
