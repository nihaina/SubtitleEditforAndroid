package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.ui.settings.SettingsPageState
import com.subtitleedit.ui.settings.SettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Both existing presentations keep bottom actions and their viewport through a page reload. */
@RunWith(AndroidJUnit4::class)
class SettingsPageLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun materialSettingsKeepBottomActionsAndScrollAfterStateRestoration() = checkLayout(true)

    @Test fun legacySettingsKeepBottomActionsAndScrollAfterStateRestoration() = checkLayout(false)

    @Test fun audioModeShowsAppearanceAndSubtitleSettings() {
        var subtitleSettingsOpened = 0
        showSettingsPage(
            state = mutableStateOf(SettingsPageState()),
            material = true,
            restoration = StateRestorationTester(compose),
            actions = mutableListOf(),
            onOpenPlayerSubtitleSettings = { subtitleSettingsOpened++ },
        )

        compose.onNodeWithText(compose.activity.getString(R.string.activity_settings_text_01)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.activity_settings_text_02)).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNodeWithText(compose.activity.getString(R.string.player_subtitle_settings_title))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, subtitleSettingsOpened) }
    }

    private fun checkLayout(material: Boolean) {
        val state = mutableStateOf(SettingsPageState())
        val actions = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        showSettingsPage(state, material, restoration, actions)
        val backup = compose.activity.getString(R.string.settings_backup)
        val restore = compose.activity.getString(R.string.settings_restore)
        // The general section is longer than this viewport. Its lower rows must be deferred,
        // while still becoming reachable through normal list scrolling.
        compose.onNodeWithText(compose.activity.getString(R.string.activity_settings_software_directory)).assertDoesNotExist()
        compose.onNodeWithText(backup).assertDoesNotExist()
        val translation = compose.activity.getString(R.string.page_translation)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(translation))
        compose.onNodeWithText(translation).assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(backup))
        val row = compose.onNodeWithText(backup)
        val before = row.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(cacheSize = "1 MB") }
        restoration.emulateSavedInstanceStateRestore()
        row.assertIsDisplayed()
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
        row.performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_backup_continue)).performClick()
        compose.onNodeWithText(restore).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("translation", "backup", "restore"), actions) }
    }

    private fun showSettingsPage(
        state: MutableState<SettingsPageState>,
        material: Boolean,
        restoration: StateRestorationTester,
        actions: MutableList<String>,
        onOpenPlayerSubtitleSettings: (() -> Unit)? = null,
    ) {
        restoration.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 500.dp)) {
                    SettingsScreen(
                        state = state.value, encodings = FileUtils.SUPPORTED_ENCODINGS,
                        showTopBar = false, useMaterial3Visuals = material,
                        onBack = {}, onEncodingSelected = {}, onThemeSelected = {},
                        onCheckUpdatesChanged = {}, onPreserveDirectoriesChanged = {},
                        onLoopSelectedChanged = {}, onSelectPlayingChanged = {},
                        onOpenAiSettings = {}, onOpenModelManagement = {}, onOpenTtsSettings = {},
                        onOpenPageTranslation = { actions += "translation" },
                        onOpenLogs = {}, onOpenAbout = {}, onSelectSoftwareDirectory = {},
                        onConfirmSoftwareDirectory = {}, onCancelSoftwareDirectory = {},
                        onCacheClear = {}, onEmptyCacheClear = {},
                        onBackup = { actions += "backup" }, onRestore = { actions += "restore" },
                        onOpenPlayerSubtitleSettings = onOpenPlayerSubtitleSettings,
                    )
                }
            }
        }
    }
}
