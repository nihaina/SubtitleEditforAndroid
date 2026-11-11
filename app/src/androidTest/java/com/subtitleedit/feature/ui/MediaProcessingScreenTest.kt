package com.subtitleedit.feature.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaProcessingPage
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaProcessingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directoryMuxChoiceOpensTheSecondPageAndBothPagesAreReachable() {
        compose.setContent { SubtitleEditComposeTheme {
            MediaProcessingScreen(MediaProcessingPage.MUX, true, {},
                convertContent = { Text("Existing format settings") }, muxContent = { Text("Shared mux queue") })
        } }
        compose.onNodeWithTag("media-processing-tab-mux").assertIsSelected()
        compose.onNodeWithText("Shared mux queue").assertExists()
        compose.onNodeWithTag("media-processing-tab-convert").performClick().assertIsSelected()
        compose.onNodeWithText("Existing format settings").assertExists()
        compose.onNodeWithTag("media-processing-tab-mux").performClick().assertIsSelected()
    }

    @Test fun normalToolEntryStartsAtConversionAndRunningTasksLockPageSwitching() {
        val enabled = mutableStateOf(true)
        compose.setContent { SubtitleEditComposeTheme {
            MediaProcessingScreen(MediaProcessingPage.CONVERT, enabled.value, {},
                convertContent = { Text("Conversion") }, muxContent = { Text("Mux") })
        } }
        compose.onNodeWithTag("media-processing-tab-convert").assertIsSelected()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("media-processing-tab-mux").assertIsNotEnabled()
        compose.onNodeWithTag("media-processing-tab-convert").assertIsNotEnabled().assertIsSelected()
    }
}
