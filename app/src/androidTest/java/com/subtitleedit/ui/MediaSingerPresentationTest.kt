package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.MediaSinger
import com.subtitleedit.library.SingerSortField
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSingerPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun automaticMetadataFollowsSortAndNoneHidesItWithoutChangingTheSinger() {
        val options = mutableStateOf(MediaSingerViewOptions())
        val singer = MediaSinger("Singer A", listOf(MediaAlbumTrack(MediaLibraryTrack("/missing/a.mp3", "A", artist = "Singer A", durationMs = 65_000))))
        var opened: MediaSinger? = null
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaSingerBrowseContent(listOf(singer), options.value, "", {}, {}, {}, { opened = it })
                }
            }
        }
        compose.onNodeWithTag("singer-info:singer a").assertDoesNotExist()
        compose.runOnIdle { options.value = options.value.copy(sortField = SingerSortField.DURATION) }
        compose.onNodeWithTag("singer-info:singer a", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("singer-item:singer a").performClick()
        compose.runOnIdle {
            assertEquals(singer, opened)
            options.value = options.value.copy(moreInfo = SingerMoreInfo.NONE)
        }
        compose.onNodeWithTag("singer-info:singer a", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("singer-item:singer a").assertIsDisplayed()
    }

    @Test fun singerSortRequiresConfirmationAndCancelDiscardsDirectionChanges() {
        val options = mutableStateOf(MediaSingerViewOptions())
        val open = mutableStateOf(true)
        compose.setContent {
            SubtitleEditComposeTheme {
                if (open.value) MediaSingerSortDialog(
                    options.value.sortField,
                    options.value.ascending,
                    onChange = { field, ascending -> options.value = options.value.copy(sortField = field, ascending = ascending) },
                    onDismiss = { open.value = false }
                )
            }
        }
        compose.onNodeWithTag("singer-sort:DURATION").performClick()
        compose.onNodeWithTag("singer-sort:DURATION").performClick()
        compose.onNodeWithTag("singer-sort:DURATION").assertIsSelected()
        compose.runOnIdle { assertEquals(MediaSingerViewOptions(), options.value) }
        compose.onNodeWithTag("singer-sort-cancel").performClick()
        compose.runOnIdle {
            assertEquals(MediaSingerViewOptions(), options.value)
            open.value = true
        }
        compose.onNodeWithTag("singer-sort:NAME").assertIsSelected()
        compose.onNodeWithTag("singer-sort:TRACK_COUNT").performClick()
        compose.onNodeWithTag("singer-sort:TRACK_COUNT").performClick()
        compose.onNodeWithTag("singer-sort-confirm").performClick()
        compose.runOnIdle {
            assertEquals(SingerSortField.TRACK_COUNT, options.value.sortField)
            assertFalse(options.value.ascending)
            assertFalse(open.value)
        }
    }

    @Test fun singerSettingsApplyInformationAndSwitchesWhileAlbumSortStaysADraft() {
        val options = mutableStateOf(MediaSingerViewOptions())
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaSingerSettings(options.value, { options.value = it }, {})
                }
            }
        }
        settingsRow("singer-settings-more-info").performClick()
        compose.onNodeWithTag("singer-more-info-list").performScrollToNode(hasTestTag("singer-more-info:YEAR"))
        compose.onNodeWithTag("singer-more-info:YEAR").performClick()
        settingsRow("singer-settings-group-tracks").performClick()
        settingsRow("singer-settings-quick-search").performClick()
        compose.runOnIdle {
            assertEquals(SingerMoreInfo.YEAR, options.value.moreInfo)
            assertFalse(options.value.groupTracks)
            assertFalse(options.value.quickSearch)
        }
        settingsRow("singer-settings-album-sort").performClick()
        compose.onNodeWithTag("album-sort:NAME").performClick()
        compose.runOnIdle { assertTrue(options.value.albumAscending) }
        compose.onNodeWithTag("album-sort-cancel").performClick()
        compose.runOnIdle { assertTrue(options.value.albumAscending) }
        settingsRow("singer-settings-album-sort").performClick()
        compose.onNodeWithTag("album-sort:NAME").performClick()
        compose.onNodeWithTag("album-sort-confirm").performClick()
        compose.runOnIdle { assertFalse(options.value.albumAscending) }
    }

    private fun settingsRow(tag: String) = compose.onNodeWithTag("singer-settings-list")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }
}
