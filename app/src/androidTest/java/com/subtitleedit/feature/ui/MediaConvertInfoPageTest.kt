package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertInfoPageTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun metadataRowsAreReadOnlyWhenNotASelectedSingleVideo() {
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(false, MediaConvertMetadata(title = "Source title"), {})
            }
        }

        compose.onNodeWithTag("convert-info-title").assertIsNotEnabled()
        compose.onNodeWithText("Source title").assertExists()
        compose.onNodeWithTag("convert-info-single-video-hint").assertExists()
    }

    @Test
    fun editsAreReturnedToTheCaller() {
        val metadata = mutableStateOf(MediaConvertMetadata(title = "Source title", author = "Source author"))
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(true, metadata.value) { metadata.value = it }
            }
        }

        compose.onNodeWithTag("convert-info-title").performClick()
        compose.onNodeWithTag("convert-metadata-title").performTextReplacement("Edited title")
        compose.onNodeWithTag("convert-metadata-confirm").performClick()
        compose.runOnIdle { assertEquals("Edited title", metadata.value.title) }
    }
}
