package com.subtitleedit.feature.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
