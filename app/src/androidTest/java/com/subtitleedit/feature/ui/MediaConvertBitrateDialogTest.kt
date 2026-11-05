package com.subtitleedit.feature.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertBitrateMode
import com.subtitleedit.MediaConvertVideoBitrateSettings
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertBitrateDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rangeValidationAndPresetChangesOnlyCommitOnDone() {
        val saved = mutableListOf<MediaConvertVideoBitrateSettings>()
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertBitrateDialog(MediaConvertVideoBitrateSettings(), 14993000, true, false, {}, { saved += it })
            }
        }
        compose.onNodeWithTag("bitrate-custom-input").performTextReplacement("127")
        compose.onNodeWithTag("bitrate-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("bitrate-custom-input").performTextReplacement("3000")
        assertTrue(saved.isEmpty())
        compose.onNodeWithTag("bitrate-preset-5000").performScrollTo().performClick()
        assertTrue(saved.isEmpty())
        compose.onNodeWithTag("bitrate-confirm").performClick()
        assertEquals(listOf(MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.PRESET, "3000", 5000)), saved)
    }

    @Test
    fun unavailableOriginalIsDisabledAndCancelDiscardsInput() {
        var dismissed = false
        val saved = mutableListOf<MediaConvertVideoBitrateSettings>()
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertBitrateDialog(MediaConvertVideoBitrateSettings(), null, false, true, { dismissed = true }, { saved += it })
            }
        }
        compose.onNodeWithTag("bitrate-original").assertIsNotEnabled()
        compose.onNodeWithTag("bitrate-custom-input").performTextReplacement("5000")
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertTrue(saved.isEmpty())
    }
}
