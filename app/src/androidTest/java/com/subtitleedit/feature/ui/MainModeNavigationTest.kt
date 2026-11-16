package com.subtitleedit.feature.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainModeNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatingTheSelectedPlayerTabDoesNotDispatchNavigationOrBlockDirectory() {
        assertSelectedTabsIgnoreRepeatedTouches(
            playerModeEnabled = true,
            destinations = listOf(
                R.id.nav_library to R.string.nav_library,
                R.id.nav_directory to R.string.nav_directory,
                R.id.nav_tools to R.string.media_categories_navigation,
                R.id.nav_stats to R.string.nav_stats,
                R.id.nav_settings to R.string.menu_main_title_02
            )
        )
    }

    @Test fun repeatingTheSelectedEditorTabDoesNotDispatchNavigationOrBlockDirectory() {
        assertSelectedTabsIgnoreRepeatedTouches(
            playerModeEnabled = false,
            destinations = listOf(
                R.id.nav_favorites to R.string.nav_favorites,
                R.id.nav_directory to R.string.nav_directory,
                R.id.nav_drafts to R.string.drafts,
                R.id.nav_tools to R.string.menu_main_title_01,
                R.id.nav_settings to R.string.menu_main_title_02
            )
        )
    }

    @Test fun turningPlayerModeOffRestoresOriginalTabsAndTheirDestinations() {
        var enabled by mutableStateOf(true)
        var selected = -1
        compose.setContent {
            SubtitleEditComposeTheme {
                MainBottomNavigation(R.id.nav_directory, enabled) { selected = it }
            }
        }
        compose.onNodeWithText("媒体库").assertIsDisplayed().performClick()
        assertEquals(R.id.nav_library, selected)
        compose.onNodeWithText("数据统计").assertIsDisplayed().performClick()
        assertEquals(R.id.nav_stats, selected)
        compose.onNodeWithText("分类").assertIsDisplayed().performClick()
        assertEquals(R.id.nav_tools, selected)
        compose.onNodeWithText("工具").assertDoesNotExist()
        compose.onNodeWithText("常用目录").assertDoesNotExist()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("媒体库").assertDoesNotExist()
        compose.onNodeWithText("数据统计").assertDoesNotExist()
        compose.onNodeWithText("分类").assertDoesNotExist()
        compose.onNodeWithText("工具").assertIsDisplayed().performClick()
        assertEquals(R.id.nav_tools, selected)
        compose.onNodeWithText("常用目录").assertIsDisplayed().performClick()
        assertEquals(R.id.nav_favorites, selected)
        compose.onNodeWithText("草稿箱").assertIsDisplayed().performClick()
        assertEquals(R.id.nav_drafts, selected)
        compose.runOnIdle { enabled = true }
        compose.onNodeWithText("媒体库").assertIsDisplayed()
        compose.onNodeWithText("数据统计").assertIsDisplayed()
    }

    private fun assertSelectedTabsIgnoreRepeatedTouches(
        playerModeEnabled: Boolean,
        destinations: List<Pair<Int, Int>>
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var selected by mutableIntStateOf(R.id.nav_directory)
        val selections = mutableListOf<Int>()
        val expected = mutableListOf<Int>()
        compose.setContent {
            SubtitleEditComposeTheme {
                MainBottomNavigation(selected, playerModeEnabled) {
                    selections += it
                    selected = it
                }
            }
        }

        val directory = compose.onNodeWithText(context.getString(R.string.nav_directory))
        directory.assertIsSelected().assertIsNotEnabled()
        repeat(3) { directory.performTouchInput { click() } }
        compose.runOnIdle { assertEquals(emptyList<Int>(), selections) }

        destinations.forEach { (id, labelRes) ->
            val tab = compose.onNodeWithText(context.getString(labelRes))
            tab.assertIsEnabled().performClick()
            expected += id
            tab.assertIsSelected().assertIsNotEnabled()
            repeat(3) { tab.performTouchInput { click() } }
            compose.runOnIdle { assertEquals(expected, selections) }
        }

        // Even after every other destination was reselected, returning to Directory still works.
        directory.assertIsEnabled().performClick().assertIsSelected().assertIsNotEnabled()
        expected += R.id.nav_directory
        compose.runOnIdle { assertEquals(expected, selections) }
    }
}
