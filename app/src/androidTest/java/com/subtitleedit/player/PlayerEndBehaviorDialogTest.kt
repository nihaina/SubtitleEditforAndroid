package com.subtitleedit.player

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerEndBehaviorDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun simpleModesAreADraftUntilConfirmed() {
        val result = showDialog()
        compose.onNodeWithTag("player-end-advanced").assertIsSelected()
        compose.onNodeWithTag("player-end-track:STOP").assertIsDisplayed()
        compose.onNodeWithTag("player-end-track:FOLLOW_MODE").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("player-end-queue:STOP").performScrollTo().assertIsSelected()
        compose.runOnIdle { assertNull(result.confirmed) }
        compose.onNodeWithTag("player-end-simple").performClick()
        compose.onNodeWithTag("player-end-mode:REPEAT_ALL").assertIsSelected()
        compose.onNodeWithTag("player-end-mode:REPEAT_ONE").assertDoesNotExist()
        compose.onNodeWithTag("player-end-mode:SEQUENCE").assertDoesNotExist()
        compose.onNodeWithTag("player-end-mode:SHUFFLE").performClick().assertIsSelected()
        compose.onNodeWithTag("player-end-mode:REPEAT_ALL").assertIsNotSelected()
        compose.runOnIdle { assertNull(result.confirmed) }
        compose.onNodeWithTag("player-end-confirm").performClick()
        compose.runOnIdle {
            assertEquals(PlayerPlayMode.SHUFFLE, result.confirmed?.first)
            assertFalse(requireNotNull(result.confirmed).second.enabled)
            assertEquals(PlayerEndBehavior(), result.confirmed?.second)
            assertEquals(1, result.dismissals)
        }
    }

    @Test fun advancedChoicesAndCheckboxesApplyTogetherOnConfirm() {
        val result = showDialog(behavior = PlayerEndBehavior(enabled = true))
        compose.onNodeWithTag("player-end-advanced").assertIsSelected()
        compose.onNodeWithTag("player-end-track:LOAD_NEXT_PAUSED").performScrollTo().performClick()
        compose.onNodeWithTag("player-end-reset-first").assertDoesNotExist()
        compose.onNodeWithTag("player-end-track:PLAY_NEXT").assertDoesNotExist()
        val nextPaused = compose.onNodeWithTag("player-end-track:LOAD_NEXT_PAUSED").fetchSemanticsNode().boundsInRoot
        val repeatTrack = compose.onNodeWithTag("player-end-track:REPEAT_TRACK").fetchSemanticsNode().boundsInRoot
        assertTrue("Single-track repeat follows load-next-paused", nextPaused.bottom <= repeatTrack.top)
        compose.onNodeWithText("单曲循环").assertIsDisplayed()
        compose.onNodeWithTag("player-end-track:FOLLOW_MODE").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("player-end-reshuffle").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("player-end-wrap-queues").assertDoesNotExist()
        compose.onNodeWithTag("player-end-queue:NEXT_QUEUE").performScrollTo().performClick()
        compose.onNodeWithTag("player-end-wrap-queues").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertNull(result.confirmed) }
        compose.onNodeWithTag("player-end-confirm").performClick()
        compose.runOnIdle {
            assertEquals(PlayerEndBehavior(enabled = true, trackAction = PlayerTrackEndAction.LOAD_NEXT_PAUSED,
                queueAction = PlayerQueueEndAction.NEXT_QUEUE, wrapQueues = true, reshuffle = false),
                result.confirmed?.second)
        }
    }

    @Test fun backDiscardsTheDraftAndResetRestoresDefaultChoices() {
        val result = showDialog(behavior = PlayerEndBehavior(enabled = true,
            trackAction = PlayerTrackEndAction.STOP, queueAction = PlayerQueueEndAction.REPEAT_QUEUE,
            wrapQueues = true, reshuffle = false))
        compose.onNodeWithTag("player-end-reset").performClick()
        compose.onNodeWithTag("player-end-track:STOP").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("player-end-track:LOAD_NEXT_PAUSED").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("player-end-track:FOLLOW_MODE").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("player-end-reshuffle").performScrollTo().assertIsOn()
        compose.onNodeWithTag("player-end-wrap-queues").assertDoesNotExist()
        compose.onNodeWithTag("player-end-queue:STOP").performScrollTo().assertIsSelected()
        pressBack()
        compose.onNodeWithTag("player-end-behavior-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(result.confirmed)
            assertEquals(1, result.dismissals)
        }
    }

    @Test fun shortWindowsKeepTheFooterAccessibleAndCanOmitNextQueue() {
        showDialog(behavior = PlayerEndBehavior(enabled = true), shortWindow = true, includeNextQueue = false)
        compose.onNodeWithTag("player-end-queue:NEXT_QUEUE").assertDoesNotExist()
        compose.onNodeWithTag("player-end-queue:REPEAT_QUEUE").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("player-end-confirm").assertIsDisplayed()
        compose.onNodeWithTag("player-end-simple").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-end-mode:SHUFFLE").performScrollTo().performClick()
        compose.onNodeWithTag("player-end-confirm").assertIsDisplayed()
    }

    private fun showDialog(
        mode: PlayerPlayMode = PlayerPlayMode.REPEAT_ALL,
        behavior: PlayerEndBehavior = PlayerEndBehavior(),
        shortWindow: Boolean = false,
        includeNextQueue: Boolean = true
    ): Result {
        val result = Result()
        val showing = mutableStateOf(true)
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            if (shortWindow) { screenHeightDp = 300; screenWidthDp = 640; orientation = Configuration.ORIENTATION_LANDSCAPE }
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                SubtitleEditComposeTheme {
                    if (showing.value) PlayerEndBehaviorDialog(mode, behavior,
                        onDismiss = { result.dismissals++; showing.value = false },
                        onConfirm = { selectedMode, selectedBehavior -> result.confirmed = selectedMode to selectedBehavior },
                        includeNextQueue = includeNextQueue)
                }
            }
        }
        return result
    }

    @Test fun queueWrapOptionIsOnlyExpandedForNextQueue() {
        showDialog(behavior = PlayerEndBehavior(enabled = true))
        compose.onNodeWithTag("player-end-wrap-queues").assertDoesNotExist()
        compose.onNodeWithTag("player-end-queue:NEXT_QUEUE").performScrollTo().performClick()
        compose.onNodeWithTag("player-end-wrap-queues").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("player-end-queue:REPEAT_QUEUE").performScrollTo().performClick()
        compose.onNodeWithTag("player-end-wrap-queues").assertDoesNotExist()
    }

    @Test fun selectingPlayNextRestoresTheDefaultAndConfirmsIt() {
        val result = showDialog(behavior = PlayerEndBehavior(enabled = true))
        compose.onNodeWithText("播放下一首").assertIsDisplayed()
        compose.onNodeWithText("继续播放").assertDoesNotExist()
        compose.onNodeWithTag("player-end-track:STOP").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("player-end-track:STOP").performClick().assertIsSelected()
        compose.onNodeWithTag("player-end-track:FOLLOW_MODE").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("player-end-track:STOP").assertIsNotSelected()
        compose.onNodeWithTag("player-end-confirm").performClick()
        compose.runOnIdle {
            assertEquals(PlayerTrackEndAction.FOLLOW_MODE, result.confirmed?.second?.trackAction)
            assertTrue(requireNotNull(result.confirmed).second.enabled)
        }
    }

    @Test fun simpleTraversalAndAdvancedEndRulesRemainEnabledAndConfirmTogether() {
        val result = showDialog(behavior = PlayerEndBehavior(enabled = true))
        compose.onNodeWithTag("player-end-track:REPEAT_TRACK").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("player-end-queue:REPEAT_QUEUE").performScrollTo().performClick()
        compose.onNodeWithTag("player-end-simple").performClick()
        compose.onNodeWithTag("player-end-mode:SHUFFLE").performClick()
        compose.onNodeWithTag("player-end-advanced").performClick()
        compose.onNodeWithTag("player-end-track:REPEAT_TRACK").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("player-end-simple").performClick()
        compose.onNodeWithTag("player-end-mode:SHUFFLE").assertIsSelected()
        compose.onNodeWithTag("player-end-confirm").performClick()
        compose.runOnIdle {
            assertEquals(PlayerPlayMode.SHUFFLE, result.confirmed?.first)
            assertEquals(PlayerEndBehavior(enabled = true, trackAction = PlayerTrackEndAction.REPEAT_TRACK,
                queueAction = PlayerQueueEndAction.REPEAT_QUEUE), result.confirmed?.second)
        }
    }

    @Test fun openingAdvancedTabDoesNotEnableDisabledRulesOrReplaceTheirSavedChoices() {
        val saved = PlayerEndBehavior(trackAction = PlayerTrackEndAction.REPEAT_TRACK,
            queueAction = PlayerQueueEndAction.NEXT_QUEUE, wrapQueues = true, reshuffle = false)
        val result = showDialog(behavior = saved)
        compose.onNodeWithTag("player-end-track:REPEAT_TRACK").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("player-end-confirm").performClick()
        compose.runOnIdle { assertEquals(saved, result.confirmed?.second) }
    }

    private class Result {
        var confirmed: Pair<PlayerPlayMode, PlayerEndBehavior>? = null
        var dismissals = 0
    }
}
