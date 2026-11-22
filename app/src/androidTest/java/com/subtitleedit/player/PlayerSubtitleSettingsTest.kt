package com.subtitleedit.player

import android.graphics.Color
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSubtitleSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerSubtitleSettingsActivity>()

    @Test fun turningOffTheFloatingWindowLocksItsAdjustmentsWithoutLockingNotificationLyrics() {
        val preferences = PlayerPreferences.getInstance(compose.activity)
        val saved = preferences.exportSettingsSnapshot()
        try {
            compose.runOnIdle {
                preferences.floatingSubtitleEnabled = false
                preferences.floatingSubtitleLocked = false
                preferences.notificationsShowLyrics = false
            }
            compose.onNodeWithText(text(R.string.player_floating_subtitle_title)).assertIsOff()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_adjustments)).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_lock)).assertIsOff().assertIsNotEnabled()
            val notificationToggle = compose.onNodeWithText(text(R.string.player_notification_lyrics_title))
            notificationToggle.performClick().assertIsOn()
            compose.runOnIdle { assertTrue(preferences.notificationsShowLyrics) }
        } finally {
            preferences.restoreSettingsSnapshot(saved)
        }
    }

    @Test fun aSavedEnabledSettingCannotUnlockAdjustmentsWithoutAndroidPermission() {
        assumeFalse(Settings.canDrawOverlays(compose.activity))
        val preferences = PlayerPreferences.getInstance(compose.activity)
        val saved = preferences.exportSettingsSnapshot()
        try {
            compose.runOnIdle { preferences.floatingSubtitleEnabled = true }
            compose.onNodeWithText(text(R.string.player_floating_subtitle_title)).assertIsOff()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_adjustments)).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_lock)).assertIsNotEnabled()
        } finally {
            preferences.restoreSettingsSnapshot(saved)
        }
    }

    @Test fun enablingTheAuthorizedFloatingWindowOpensAdjustmentsAndPersistsChanges() {
        assumeTrue("The test app must have Android's overlay permission", Settings.canDrawOverlays(compose.activity))
        val preferences = PlayerPreferences.getInstance(compose.activity)
        val saved = preferences.exportSettingsSnapshot()
        try {
            compose.runOnIdle {
                preferences.floatingSubtitleEnabled = false
                preferences.floatingSubtitleLocked = false
                preferences.floatingSubtitleStyle = PlayerFloatingSubtitleStyle()
            }
            compose.onNodeWithText(text(R.string.player_floating_subtitle_title)).performClick().assertIsOn()
            val lockToggle = compose.onNodeWithText(text(R.string.player_floating_subtitle_lock))
            lockToggle.performScrollTo().performClick().assertIsOn()
            compose.runOnIdle { assertTrue(preferences.floatingSubtitleLocked) }
            lockToggle.performClick().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_adjustments)).performClick()
            compose.onNodeWithTag("player-floating-subtitle").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_left_margin)).performScrollTo().assertIsDisplayed()
            setSlider("floating-subtitle-size", 28f)
            setSlider("floating-subtitle-left-margin", 73f)
            compose.onNodeWithText(text(R.string.player_floating_subtitle_font_weight)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.player_floating_subtitle_weight_bold)).performClick()

            compose.onNodeWithText(text(R.string.player_floating_subtitle_font_color)).performScrollTo().performClick()
            onView(withContentDescription("#FF00FFFF")).perform(scrollTo(), click())
            onView(withId(android.R.id.button1)).perform(click())
            compose.onNodeWithText(text(R.string.player_floating_subtitle_background_color)).performScrollTo().performClick()
            onView(withContentDescription("HEX")).perform(scrollTo(), replaceText("#FF112233"))
            onView(withId(android.R.id.button1)).perform(click())
            setSlider("floating-subtitle-background-opacity", 40f)
            compose.runOnIdle {
                assertEquals(
                    PlayerFloatingSubtitleStyle(fontSize = 28, leftMarginPercent = 73,
                        weight = PlayerFloatingSubtitleWeight.BOLD, colorArgb = Color.CYAN,
                        backgroundColorArgb = Color.argb(102, 0x11, 0x22, 0x33)),
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
