package com.subtitleedit.feature.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaProcessingPage
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaProcessingChooserDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aSubtitleSelectionKeepsTheUnsupportedMuxChoiceVisibleAndDisabled() {
        var chosen: MediaProcessingPage? = null
        compose.setContent { SubtitleEditComposeTheme {
            MediaProcessingChooserDialog(true, false, {}, { chosen = it })
        } }
        compose.onNodeWithText("格式转换").assertExists()
        compose.onNodeWithText("混流处理").assertExists()
        compose.onNodeWithTag("media-processing-choice-mux").assertIsNotEnabled()
        compose.onNodeWithTag("media-processing-choice-convert").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(MediaProcessingPage.CONVERT, chosen) }
    }

    @Test fun mediaOnlySelectionCanChooseMuxWithoutLaunchingConversion() {
        val choices = mutableListOf<MediaProcessingPage>()
        compose.setContent { SubtitleEditComposeTheme {
            MediaProcessingChooserDialog(true, true, {}, { choices += it })
        } }
        compose.runOnIdle { assertTrue(choices.isEmpty()) }
        compose.onNodeWithTag("media-processing-choice-convert").assertIsEnabled()
        compose.onNodeWithTag("media-processing-choice-mux").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(MediaProcessingPage.MUX), choices) }
    }

    @Test fun cancellingTheChooserDoesNotSelectAnyPage() {
        var dismissed = false
        var chosen: MediaProcessingPage? = null
        compose.setContent { SubtitleEditComposeTheme {
            MediaProcessingChooserDialog(true, true, { dismissed = true }, { chosen = it })
        } }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertNull(chosen)
        }
    }
}
