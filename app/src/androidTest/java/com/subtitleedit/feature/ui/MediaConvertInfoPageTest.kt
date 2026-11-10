package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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
    fun explicitCoverActionsReplaceTheHintAndDoNotOpenThePicker() {
        val original = MediaConvertMetadata(title = "Film")
        val metadata = mutableStateOf(original)
        var picks = 0
        var exports = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(true, metadata.value, { metadata.value = it },
                    onPickCover = { picks++ }, onExportCover = { exports++ })
            }
        }
        compose.onNodeWithText("（长按删除封面）").assertDoesNotExist()
        compose.onNodeWithText("导出").assertExists()
        compose.onNodeWithText("删除").assertExists()
        compose.onNodeWithTag("convert-cover-export").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, exports)
            assertEquals(0, picks)
            assertEquals(original, metadata.value)
        }
        compose.onNodeWithTag("convert-cover-delete").assertIsEnabled().performClick()
        compose.onNodeWithText("是否删除封面？保存后生效。").assertExists()
        compose.onNodeWithTag("convert-cover-delete-cancel").performClick()
        compose.runOnIdle {
            assertEquals(0, picks)
            assertEquals(original, metadata.value)
        }
    }

    @Test
    fun anUnavailableExportDoesNotDisableCoverSelectionOrDeletion() {
        var picks = 0
        var exports = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(true, MediaConvertMetadata(), {}, onPickCover = { picks++ },
                    onExportCover = { exports++ }, coverExportEnabled = false)
            }
        }
        compose.onNodeWithTag("convert-cover-export").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-delete").assertIsEnabled().performClick()
        compose.onNodeWithTag("convert-cover-delete-cancel").performClick()
        compose.onNodeWithTag("convert-info-cover").performClick()
        compose.runOnIdle {
            assertEquals(1, picks)
            assertEquals(0, exports)
        }
    }

    @Test
    fun confirmingCoverDeletionClearsDefaultAndCustomArtworkWithoutChangingTextTags() {
        val metadata = mutableStateOf(MediaConvertMetadata(title = "Film", author = "Director"))
        var picks = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(true, metadata.value, { metadata.value = it }, onPickCover = { picks++ })
            }
        }
        for (cover in listOf(MediaConvertCoverMode.DEFAULT, MediaConvertCoverMode.CUSTOM)) {
            val original = MediaConvertMetadata(cover = cover, title = "Film", author = "Director",
                coverPath = if (cover == MediaConvertCoverMode.CUSTOM) "cover.jpg" else null)
            compose.runOnIdle { metadata.value = original }
            compose.onNodeWithTag("convert-cover-delete").performClick()
            compose.onNodeWithText("删除封面").assertExists()
            compose.onNodeWithText("是否删除封面？保存后生效。").assertExists()
            compose.runOnIdle { assertEquals(original, metadata.value) }
            compose.onNodeWithTag("convert-cover-delete-confirm").performClick()
            compose.onNodeWithTag("convert-cover-delete-confirm").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(original.copy(cover = MediaConvertCoverMode.NONE, coverPath = null), metadata.value)
                assertEquals(0, picks)
            }
        }
    }

    @Test
    fun cancellingCoverDeletionPreservesTheSelectedImageWithoutOpeningThePicker() {
        val original = MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = "cover.jpg")
        val metadata = mutableStateOf(original)
        var picks = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(true, metadata.value, { metadata.value = it },
                    coverFileName = "cover.jpg", onPickCover = { picks++ })
            }
        }
        compose.onNodeWithTag("convert-cover-delete").performClick()
        compose.onNodeWithTag("convert-cover-delete-cancel").performClick()
        compose.onNodeWithTag("convert-cover-delete-confirm").assertDoesNotExist()
        compose.onNodeWithText("cover.jpg").assertExists()
        compose.runOnIdle {
            assertEquals(original, metadata.value)
            assertEquals(0, picks)
        }
    }

    @Test
    fun disabledOrUnsupportedCoverCannotOpenTheDeletionDialog() {
        val enabled = mutableStateOf(false)
        val coverEnabled = mutableStateOf(true)
        var changes = 0
        var picks = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(enabled.value, MediaConvertMetadata(), { changes++ },
                    coverEnabled = coverEnabled.value, onPickCover = { picks++ })
            }
        }
        compose.onNodeWithTag("convert-info-cover").performTouchInput { longClick() }
        compose.onNodeWithTag("convert-cover-export").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-delete").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-delete-confirm").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true; coverEnabled.value = false }
        compose.onNodeWithTag("convert-info-cover").performTouchInput { longClick() }
        compose.onNodeWithTag("convert-cover-export").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-delete").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-delete-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, changes)
            assertEquals(0, picks)
        }
    }

    @Test
    fun batchDeletionWarnsThatAllSelectedFilesWillLoseTheirArtwork() {
        val metadata = mutableStateOf(MediaConvertMetadata())
        var picks = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(true, metadata.value, { metadata.value = it },
                    batch = true, onPickCover = { picks++ })
            }
        }
        compose.onNodeWithTag("convert-info-cover").performTouchInput { longClick() }
        compose.onNodeWithText("是否删除所有已选文件的封面？保存后生效。").assertExists()
        compose.onNodeWithTag("convert-cover-batch-confirm").assertDoesNotExist()
        compose.onNodeWithTag("convert-cover-delete-confirm").performClick()
        compose.runOnIdle {
            assertEquals(MediaConvertCoverMode.NONE, metadata.value.cover)
            assertEquals(0, picks)
        }
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
        compose.onNodeWithTag("convert-cover-export").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-delete").assertIsNotEnabled()
        compose.onNodeWithTag("convert-info-title").assertIsEnabled()
        compose.onNodeWithTag("convert-info-cover-unsupported-hint").assertExists()
        compose.onNodeWithTag("convert-info-title").performClick()
        compose.onNodeWithTag("convert-metadata-title").assertExists()
    }

    @Test
    fun audioInformationHasExtendedFieldsAndWarnsBeforeBatchChanges() {
        val metadata = mutableStateOf(MediaConvertMetadata(genre = "Genre", artist = "Artist", album = "Album", year = "2026"))
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertInfoPage(
                    true, metadata.value, onMetadataChanged = { metadata.value = it },
                    isAudioOutput = true, batch = true
                )
            }
        }

        listOf("genre", "title", "artist", "album", "year", "album_artist", "composer",
            "track_number", "disc_number", "comment", "lyricist", "copyright", "language").forEach { field ->
            compose.onNodeWithTag("convert-info-$field").assertExists()
        }
        compose.onNodeWithTag("convert-info-author").assertDoesNotExist()
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
