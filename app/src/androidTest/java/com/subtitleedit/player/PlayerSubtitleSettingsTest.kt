package com.subtitleedit.player

import android.graphics.Color
import android.provider.Settings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.util.SettingsBackupManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSubtitleSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerSubtitleSettingsActivity>()

    @Test fun floatingSubtitleSwitchesLiveInAdjustmentsAndNotificationLyricsStayOnTheParentPage() {
        val preferences = PlayerPreferences.getInstance(compose.activity)
        val saved = preferences.exportSettingsSnapshot()
        try {
            compose.runOnIdle {
                preferences.floatingSubtitleEnabled = false
                preferences.floatingSubtitleLocked = false
                preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.copy(scrollEnabled = false)
                preferences.notificationsShowLyrics = false
            }
            compose.onNode(
                hasText(text(R.string.player_floating_subtitle_title)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
            ).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_lock)).assertDoesNotExist()
            val notificationToggle = compose.onNodeWithText(text(R.string.player_notification_lyrics_title))
            notificationToggle.performClick().assertIsOn()
            compose.runOnIdle { assertTrue(preferences.notificationsShowLyrics) }

            compose.onNodeWithText(text(R.string.player_floating_subtitle_title)).performClick()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_adjustments)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_title)).assertIsOff()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_lock)).assertIsOff().assertIsNotEnabled()
            compose.onNodeWithTag("player-floating-subtitle").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_scroll)).performScrollTo().performClick().assertIsOn()
            compose.runOnIdle {
                assertFalse(preferences.floatingSubtitleEnabled)
                assertTrue(preferences.floatingSubtitleStyle.scrollEnabled)
            }
        } finally {
            preferences.restoreSettingsSnapshot(saved)
        }
    }

    @Test fun openingEachSettingsPageWithoutOverlayPermissionTurnsItsRestoredSwitchOff() {
        val activity = compose.activity
        assumeFalse(Settings.canDrawOverlays(activity))
        val preferences = PlayerPreferences.getInstance(compose.activity)
        val saved = preferences.exportSettingsSnapshot()
        try {
            compose.runOnIdle {
                preferences.floatingSubtitleEnabled = false
                preferences.statusBarLyrics = PlayerStatusBarLyricsSettings()
            }
            fun enabledValue() = JSONObject().put("type", "boolean").put("value", true)
            SettingsBackupManager.restore(activity, JSONObject().put("schemaVersion", 1)
                .put("audioModeSettings", JSONObject().put("player", JSONObject()
                    .put("floating_subtitle_enabled", enabledValue())
                    .put("status_bar_lyrics_enabled", enabledValue())))
                .toString())
            assertTrue(preferences.floatingSubtitleEnabled)
            assertTrue(preferences.statusBarLyrics.enabled)
            compose.onNodeWithText(text(R.string.player_floating_subtitle_title)).performClick()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_title)).assertIsOff()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_lock)).assertIsNotEnabled()
            compose.onNodeWithTag("player-floating-subtitle").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(preferences.floatingSubtitleEnabled)
                assertTrue(preferences.statusBarLyrics.enabled)
            }

            pressBack()
            compose.onNodeWithText(text(R.string.player_status_bar_lyrics_title)).performClick()
            compose.onNodeWithText(text(R.string.player_status_bar_lyrics_enabled)).assertIsOff()
            compose.runOnIdle { assertFalse(preferences.statusBarLyrics.enabled) }
            val playerBackup = JSONObject(SettingsBackupManager.export(activity))
                .getJSONObject("audioModeSettings").getJSONObject("player")
            assertFalse(playerBackup.getJSONObject("floating_subtitle_enabled").getBoolean("value"))
            assertFalse(playerBackup.getJSONObject("status_bar_lyrics_enabled").getBoolean("value"))
        } finally {
            preferences.restoreSettingsSnapshot(saved)
        }
    }

    @Test fun statusBarSubtitlesHaveSeparateAppearanceWidthAndVerticalPosition() {
        val preferences = PlayerPreferences.getInstance(compose.activity)
        val saved = preferences.exportSettingsSnapshot()
        try {
            compose.runOnIdle {
                preferences.floatingSubtitleStyle = PlayerFloatingSubtitleStyle()
                preferences.statusBarLyrics = PlayerStatusBarLyricsSettings()
                preferences.notificationsShowLyrics = false
            }
            val statusBarEntry = compose.onNodeWithText(text(R.string.player_status_bar_lyrics_title))
            val notificationToggle = compose.onNodeWithText(text(R.string.player_notification_lyrics_title))
            notificationToggle.performScrollTo().assertIsOff()
            assertTrue(statusBarEntry.fetchSemanticsNode().boundsInRoot.top < notificationToggle.fetchSemanticsNode().boundsInRoot.top)
            statusBarEntry.performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.player_status_bar_lyrics_enabled)).assertIsOff()
            compose.onNodeWithTag("player-floating-subtitle").assertIsDisplayed()
            setSlider("status-bar-lyrics-vertical-position", 61f)
            setSlider("status-bar-lyrics-width", 68f)
            setSlider("floating-subtitle-left-margin", 32f)
            compose.onNodeWithText(text(R.string.player_floating_subtitle_scroll)).performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_outline_color)).performScrollTo().performClick()
            onView(withContentDescription("HEX")).perform(scrollTo(), replaceText("#FFCC2211"))
            onView(withId(android.R.id.button1)).perform(click())
            compose.onNodeWithText(text(R.string.player_floating_subtitle_background_color)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_background_opacity)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_lock)).assertDoesNotExist()
            compose.runOnIdle {
                val status = preferences.statusBarLyrics
                assertEquals(61, status.verticalPositionPercent)
                assertEquals(68, status.widthPercent)
                assertEquals(32, status.style.leftMarginPercent)
                assertEquals(0xFFCC2211.toInt(), status.style.outlineColorArgb)
                assertEquals(Color.TRANSPARENT, status.style.backgroundColorArgb)
                assertTrue(status.style.scrollEnabled)
                assertFalse(status.enabled)
                assertFalse(preferences.notificationsShowLyrics)
                assertEquals(PlayerFloatingSubtitleStyle(), preferences.floatingSubtitleStyle)
            }
        } finally {
            preferences.restoreSettingsSnapshot(saved)
        }
    }

    @Test fun adjustmentsPersistChangesAndStayOpenWhenTheAuthorizedFloatingWindowIsTurnedOff() {
        assumeTrue("The test app must have Android's overlay permission", Settings.canDrawOverlays(compose.activity))
        val preferences = PlayerPreferences.getInstance(compose.activity)
        val saved = preferences.exportSettingsSnapshot()
        try {
            compose.runOnIdle {
                preferences.floatingSubtitleEnabled = false
                preferences.floatingSubtitleLocked = false
                preferences.floatingSubtitleStyle = PlayerFloatingSubtitleStyle()
            }
            compose.onNodeWithText(text(R.string.player_floating_subtitle_title)).performClick()
            val floatingToggle = compose.onNodeWithText(text(R.string.player_floating_subtitle_title))
            floatingToggle.performClick().assertIsOn()
            val lockToggle = compose.onNodeWithText(text(R.string.player_floating_subtitle_lock))
            lockToggle.performScrollTo().performClick().assertIsOn()
            compose.runOnIdle { assertTrue(preferences.floatingSubtitleLocked) }
            lockToggle.performClick().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithTag("player-floating-subtitle").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_left_margin)).performScrollTo().assertIsDisplayed()
            setSlider("floating-subtitle-size", 28f)
            setSlider("floating-subtitle-left-margin", 73f)
            compose.onNodeWithText(text(R.string.player_floating_subtitle_font_weight)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_weight_bold)).performClick()

            compose.onNodeWithText(text(R.string.player_floating_subtitle_font_color)).performScrollTo().performClick()
            onView(withContentDescription("#FF00FFFF")).perform(scrollTo(), click())
            onView(withId(android.R.id.button1)).perform(click())
            compose.onNodeWithText(text(R.string.player_floating_subtitle_outline_color)).performScrollTo().performClick()
            onView(withContentDescription("HEX")).perform(scrollTo(), replaceText("#FFEE4433"))
            onView(withId(android.R.id.button1)).perform(click())
            compose.onNodeWithText(text(R.string.player_floating_subtitle_scroll)).performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_background_color)).performScrollTo().performClick()
            onView(withContentDescription("HEX")).perform(scrollTo(), replaceText("#FF112233"))
            onView(withId(android.R.id.button1)).perform(click())
            setSlider("floating-subtitle-background-opacity", 40f)
            floatingToggle.performScrollTo().performClick().assertIsOff()
            lockToggle.assertIsOn().assertIsNotEnabled()
            compose.onNodeWithTag("player-floating-subtitle").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(preferences.floatingSubtitleEnabled)
                assertTrue(preferences.floatingSubtitleLocked)
                assertEquals(
                    PlayerFloatingSubtitleStyle(fontSize = 28, leftMarginPercent = 73,
                        weight = PlayerFloatingSubtitleWeight.BOLD, colorArgb = Color.CYAN,
                        backgroundColorArgb = Color.argb(102, 0x11, 0x22, 0x33),
                        outlineColorArgb = 0xFFEE4433.toInt(), scrollEnabled = true),
                    preferences.floatingSubtitleStyle,
                )
            }
        } finally {
            preferences.restoreSettingsSnapshot(saved)
        }
    }

    private fun setSlider(tag: String, value: Float) {
        val slider = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
                (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
        )
        slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
