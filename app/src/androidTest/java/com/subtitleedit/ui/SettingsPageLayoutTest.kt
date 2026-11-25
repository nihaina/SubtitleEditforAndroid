package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.player.PlayerPreferences
import com.subtitleedit.player.PlayerUsbBitDepth
import com.subtitleedit.player.PlayerUsbDiagnostics
import com.subtitleedit.player.PlayerUsbVolumeMode
import com.subtitleedit.ui.settings.PlayerUsbSettingsPage
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

    @Test fun audioModeShowsAppearanceSubtitleAndPlaybackSettings() {
        var subtitleSettingsOpened = 0
        var usbSettingsOpened = 0
        showSettingsPage(
            state = mutableStateOf(SettingsPageState()),
            material = true,
            restoration = StateRestorationTester(compose),
            actions = mutableListOf(),
            onOpenPlayerSubtitleSettings = { subtitleSettingsOpened++ },
            onOpenPlayerUsbSettings = { usbSettingsOpened++ },
        )

        compose.onNodeWithText(compose.activity.getString(R.string.activity_settings_text_01)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.activity_settings_text_02)).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(6)
        compose.onNodeWithText(compose.activity.getString(R.string.player_subtitle_settings_title))
            .assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.activity_settings_text_09)).assertIsDisplayed()
        checkHardwareDecodingSwitch()
        val concurrentPlayback = compose.onNodeWithText(compose.activity.getString(R.string.player_allow_concurrent_playback))
        concurrentPlayback.assertIsDisplayed().assertIsOff().performClick()
        concurrentPlayback.assertIsOn().performClick()
        concurrentPlayback.assertIsOff()
        val usbExclusive = compose.onNodeWithText(compose.activity.getString(R.string.player_usb_exclusive_title))
        val usbSwitch = compose.onNodeWithText(compose.activity.getString(R.string.player_usb_enable))
        usbSwitch.assertDoesNotExist()
        usbExclusive.assertIsDisplayed().performClick()
        usbSwitch.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, subtitleSettingsOpened)
            assertEquals(1, usbSettingsOpened)
        }
    }

    @Test fun usbSettingsPageShowsAndPersistsHardwareVolumeAndBitDepth() {
        val preferences = PlayerPreferences.getInstance(compose.activity)
        val originalEnabled = preferences.usbExclusiveEnabled
        val originalVolume = preferences.usbVolumeMode
        val originalBits = preferences.usbBitDepth
        val originalUsbActive = PlayerUsbDiagnostics.active.value
        val originalHardwareSupported = PlayerUsbDiagnostics.hardwareVolumeSupported.value
        try {
            PlayerUsbDiagnostics.setActive(false)
            preferences.usbExclusiveEnabled = false
            preferences.usbVolumeMode = PlayerUsbVolumeMode.SOFTWARE
            preferences.usbBitDepth = PlayerUsbBitDepth.AUTO
            compose.setContent {
                SubtitleEditComposeTheme { PlayerUsbSettingsPage(onBack = {}, onOpenLogs = {}) }
            }

            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_settings_title)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.file_picker_refresh)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_enable)).assertIsOff().performClick()
            val hardwareVolume = compose.onNodeWithText(compose.activity.getString(R.string.player_usb_volume_hardware))
            hardwareVolume.performScrollTo().performClick().assertIsSelected()
            compose.runOnIdle {
                PlayerUsbDiagnostics.setActive(true)
                PlayerUsbDiagnostics.setHardwareVolumeSupported(false)
            }
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_volume_software))
                .performScrollTo().assertIsSelected()
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_volume_hardware_fallback))
                .performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(PlayerUsbVolumeMode.HARDWARE, preferences.usbVolumeMode)
                PlayerUsbDiagnostics.setHardwareVolumeSupported(true)
            }
            hardwareVolume.performScrollTo().assertIsSelected()
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_bit_depth_auto))
                .performScrollTo().assertIsSelected()
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_bit_depth_32))
                .performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_bit_depth_16))
                .performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_open_logs)).performScrollTo().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_log_title)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.player_usb_settings_close)).performClick()
            compose.runOnIdle {
                assertEquals(PlayerUsbVolumeMode.HARDWARE, preferences.usbVolumeMode)
                assertEquals(PlayerUsbBitDepth.BIT_16, preferences.usbBitDepth)
            }
        } finally {
            preferences.usbExclusiveEnabled = originalEnabled
            preferences.usbVolumeMode = originalVolume
            preferences.usbBitDepth = originalBits
            PlayerUsbDiagnostics.setActive(originalUsbActive)
            PlayerUsbDiagnostics.setHardwareVolumeSupported(originalHardwareSupported)
        }
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
        val hardwareDecoding = compose.activity.getString(R.string.settings_hardware_decoding)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(hardwareDecoding))
        checkHardwareDecodingSwitch()
        compose.onNodeWithText(compose.activity.getString(R.string.player_allow_concurrent_playback)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.player_usb_exclusive_title)).assertDoesNotExist()
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

    private fun checkHardwareDecodingSwitch() {
        val node = compose.onNodeWithText(compose.activity.getString(R.string.settings_hardware_decoding))
        node.assertIsDisplayed().assertIsOn().performClick()
        node.assertIsOff().performClick()
        node.assertIsOn()
    }

    private fun showSettingsPage(
        state: MutableState<SettingsPageState>,
        material: Boolean,
        restoration: StateRestorationTester,
        actions: MutableList<String>,
        onOpenPlayerSubtitleSettings: (() -> Unit)? = null,
        onOpenPlayerUsbSettings: () -> Unit = {},
    ) {
        restoration.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, if (onOpenPlayerSubtitleSettings == null) 500.dp else 560.dp)) {
                    SettingsScreen(
                        state = state.value, encodings = FileUtils.SUPPORTED_ENCODINGS,
                        showTopBar = false, useMaterial3Visuals = material,
                        onBack = {}, onEncodingSelected = {}, onThemeSelected = {},
                        onCheckUpdatesChanged = {}, onPreserveDirectoriesChanged = {},
                        onLoopSelectedChanged = {}, onSelectPlayingChanged = {},
                        onHardwareDecodingChanged = { state.value = state.value.copy(hardwareDecodingEnabled = it) },
                        onAllowConcurrentPlaybackChanged = { state.value = state.value.copy(allowConcurrentPlayback = it) },
                        onOpenPlayerUsbSettings = onOpenPlayerUsbSettings,
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
