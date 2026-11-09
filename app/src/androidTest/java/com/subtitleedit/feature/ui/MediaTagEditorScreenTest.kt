package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadataSelection
import com.subtitleedit.MediaTagEditorState
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTagEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editingAnAudioTagEnablesSaveAndRetainsUntouchedValues() {
        val state = mutableStateOf(MediaTagEditorState(
            fileNames = listOf("song.mp3"), selection = MediaConvertMetadataSelection.SINGLE_AUDIO,
            metadata = MediaConvertMetadata(title = "Song", artist = "Singer", album = "Album"), coverSupported = true
        ))
        var saves = 0
        compose.setContent { SubtitleEditComposeTheme {
            MediaTagEditorScreen(state.value, {}, { state.value = state.value.copy(metadata = it) }, {}, { saves++ }, {})
        } }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("convert-info-artist").performScrollTo().performClick()
        compose.onNodeWithTag("convert-metadata-artist").performTextReplacement("New singer")
        compose.onNodeWithTag("convert-metadata-confirm").performClick()
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals("New singer", state.value.metadata.artist)
            assertEquals("Song", state.value.metadata.title)
            assertEquals("Album", state.value.metadata.album)
        }
    }

    @Test fun mixedSelectionCannotEditOrSaveTags() {
        compose.setContent { SubtitleEditComposeTheme {
            MediaTagEditorScreen(MediaTagEditorState(
                fileNames = listOf("song.mp3", "film.mp4"), selection = MediaConvertMetadataSelection.MIXED
            ), {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("convert-info-title").assertIsNotEnabled()
        compose.onNodeWithTag("convert-info-cover").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-export").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-delete").assertIsNotEnabled()
    }

    @Test fun coverExportFollowsTheSelectedArtworkAndBusyState() {
        val state = mutableStateOf(MediaTagEditorState(
            fileNames = listOf("song.mp3"), selection = MediaConvertMetadataSelection.SINGLE_AUDIO,
            coverSupported = true, hasSourceCover = true
        ))
        var exports = 0
        var picks = 0
        compose.setContent { SubtitleEditComposeTheme {
            MediaTagEditorScreen(state.value, {}, { state.value = state.value.copy(metadata = it) },
                { picks++ }, {}, {}, onExportCover = { exports++ })
        } }
        compose.onNodeWithTag("convert-cover-export").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, exports)
            assertEquals(0, picks)
            state.value = state.value.copy(hasSourceCover = false)
        }
        compose.onNodeWithTag("convert-cover-export").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(metadata = MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM)) }
        compose.onNodeWithTag("convert-cover-export").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(isExportingCover = true) }
        compose.onNodeWithTag("convert-cover-export").assertIsNotEnabled()
        compose.onNodeWithTag("convert-cover-delete").assertIsNotEnabled()
        compose.onNodeWithTag("convert-info-cover").assertIsNotEnabled()
        compose.onNodeWithText("保存").assertDoesNotExist()
        compose.onNodeWithText("取消").assertIsEnabled()
    }
}
