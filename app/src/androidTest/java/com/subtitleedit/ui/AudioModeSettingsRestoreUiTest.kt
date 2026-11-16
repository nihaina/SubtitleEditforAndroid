package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaCreditRole
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsBackupManager
import java.io.File
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioModeSettingsRestoreUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun restoringSettingsRefreshesMountedAlbumAndEveryCreditCategory() {
        val app = compose.activity.applicationContext
        val original = JSONObject(SettingsBackupManager.export(app)).getJSONObject("audioModeSettings")
        original.remove("player")
        try {
            MediaAlbumPreferences(app).save(MediaAlbumViewOptions())
            MediaCreditRole.entries.forEach { MediaSingerPreferences(app, it).save(MediaSingerViewOptions()) }
            val state = MediaAlbumPageState()
            compose.setContent {
                SubtitleEditComposeTheme {
                    Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                        MediaAlbumScreen(0, listOf(File("/backup-ui-fixture")), true, state,
                            catalogLoader = { listOf(MediaAlbumTrack(MediaLibraryTrack(
                                "/backup-ui-fixture/song.wav", "Song", artist = "Singer", album = "Album"
                            ))) })
                    }
                }
            }
            compose.waitUntil(5_000L) { state.prepared != null }
            compose.onNodeWithTag("album-grid").assertIsDisplayed()
            compose.onNodeWithTag("album-search").assertIsDisplayed()

            restore(JSONObject()
                .put("albums", JSONObject()
                    .put("view_mode", typed("string", "LIST_NO_ART"))
                    .put("quick_search", typed("boolean", false)))
                .apply {
                    listOf("singers", "artists", "composers", "genres").forEach {
                        put(it, JSONObject().put("quick_search", typed("boolean", false)))
                    }
                })

            compose.waitForIdle()
            compose.onNodeWithTag("album-grid").assertDoesNotExist()
            compose.onNodeWithTag("album-list").assertIsDisplayed()
            compose.onNodeWithTag("album-search").assertDoesNotExist()
            listOf(MediaCategory.SINGERS to "singer", MediaCategory.ARTISTS to "artist",
                MediaCategory.COMPOSERS to "composer", MediaCategory.GENRES to "genre").forEach { (category, prefix) ->
                compose.onNodeWithTag("media-category-tabs").performScrollToNode(hasTestTag("media-category:${category.name}"))
                compose.onNodeWithTag("media-category:${category.name}").performClick()
                compose.onNodeWithTag("$prefix-settings").assertIsDisplayed()
                compose.onNodeWithTag("$prefix-search").assertDoesNotExist()
            }
        } finally {
            restore(original)
        }
    }

    private fun restore(settings: JSONObject) = SettingsBackupManager.restore(compose.activity,
        JSONObject().put("schemaVersion", 1).put("audioModeSettings", settings).toString())
    private fun typed(type: String, value: Any) = JSONObject().put("type", type).put("value", value)
}
