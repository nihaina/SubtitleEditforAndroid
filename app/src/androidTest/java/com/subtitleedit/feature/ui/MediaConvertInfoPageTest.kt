package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertCoverMode
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
    fun metadataRowsAreReadOnlyForMixedOrUnresolvedInputs() {
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(false, MediaConvertMetadata(title = "Source title"), {})
            }
        }

        compose.onNodeWithTag("convert-info-title").assertIsNotEnabled()
        compose.onNodeWithTag("convert-info-cover").assertIsNotEnabled()
        compose.onNodeWithText("Source title").assertExists()
        compose.onNodeWithTag("convert-info-unavailable-hint").assertExists()
    }

    @Test
    fun onlyTheClickedTextFieldIsEdited() {
        val metadata = mutableStateOf(MediaConvertMetadata(title = "Source title", author = "Source author"))
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(true, metadata.value, onMetadataChanged = { metadata.value = it })
            }
        }

        compose.onNodeWithTag("convert-info-title").performClick()
        compose.onNodeWithTag("convert-metadata-title").performTextReplacement("Edited title")
        compose.onNodeWithTag("convert-metadata-author").assertDoesNotExist()
        compose.onNodeWithTag("convert-metadata-comment").assertDoesNotExist()
        compose.onNodeWithTag("convert-metadata-confirm").performClick()
        compose.runOnIdle {
            assertEquals("Edited title", metadata.value.title)
            assertEquals("Source author", metadata.value.author)
        }
    }

    @Test
    fun coverRowInvokesTheFilePickerWithoutOpeningATextEditor() {
        var picks = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(
                    true, MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM), {},
                    coverFileName = "cover.jpg", onPickCover = { picks++ }
                )
            }
        }

        compose.onNodeWithText("cover.jpg").assertExists()
        compose.onNodeWithTag("convert-info-cover").performClick()
        compose.onNodeWithTag("convert-metadata-title").assertDoesNotExist()
        compose.onNodeWithTag("convert-metadata-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, picks) }
    }

    @Test
    fun batchCoverWarnsBeforeOpeningTheFilePicker() {
        var picks = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(true, MediaConvertMetadata(), {}, batch = true, onPickCover = { picks++ })
            }
        }
        compose.onNodeWithTag("convert-info-cover").performClick()
        compose.runOnIdle { assertEquals(0, picks) }
        compose.onNodeWithTag("convert-cover-batch-confirm").performClick()
        compose.runOnIdle { assertEquals(1, picks) }
    }

    @Test
    fun unsupportedArtworkDoesNotDisableTextEditing() {
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(
                    true, MediaConvertMetadata(), {}, isAudioOutput = true,
                    coverEnabled = false, coverUnsupportedHint = "当前格式不支持封面"
                )
            }
        }

        compose.onNodeWithTag("convert-info-cover").assertIsNotEnabled()
        compose.onNodeWithTag("convert-info-title").assertIsEnabled()
        compose.onNodeWithTag("convert-info-cover-unsupported-hint").assertExists()
        compose.onNodeWithTag("convert-info-title").performClick()
        compose.onNodeWithTag("convert-metadata-title").assertExists()
    }

    @Test
    fun audioInformationHasReferenceFieldsAndWarnsBeforeBatchChanges() {
        val metadata = mutableStateOf(MediaConvertMetadata(genre = "Genre", artist = "Artist", album = "Album", year = "2026"))
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(
                    true, metadata.value, onMetadataChanged = { metadata.value = it },
                    isAudioOutput = true, batch = true
                )
            }
        }

        listOf("genre", "title", "artist", "album", "year").forEach { field ->
            compose.onNodeWithTag("convert-info-$field").assertExists()
        }
        compose.onNodeWithTag("convert-info-author").assertDoesNotExist()
        compose.onNodeWithTag("convert-info-comment").assertDoesNotExist()
        compose.onNodeWithTag("convert-info-batch-warning").assertExists()
        compose.onNodeWithTag("convert-info-artist").performClick()
        compose.onNodeWithTag("convert-metadata-batch-warning").assertExists()
        compose.onNodeWithTag("convert-metadata-artist").performTextReplacement("Edited artist")
        compose.onNodeWithTag("convert-metadata-confirm").performClick()
        compose.runOnIdle {
            assertEquals("Edited artist", metadata.value.artist)
            assertEquals("Genre", metadata.value.genre)
            assertEquals("Album", metadata.value.album)
        }
    }
}
