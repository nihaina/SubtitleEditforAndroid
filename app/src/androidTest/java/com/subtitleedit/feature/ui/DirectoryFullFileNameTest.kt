package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryFullFileNameTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longFileNameWrapsWithoutEllipsisInEachViewMode() {
        val directory = File(compose.activity.cacheDir, "full-file-name-test")
        val file = File(directory, "Learn Kotlin Programming – Full Course for Beginners 眠れない人へ 完整文件名显示.txt")
        val state = mutableStateOf(MainActivityScreenState(
            currentDirectory = directory,
            files = listOf(file),
            fileMetadata = mapOf(file.absolutePath to BrowserFileMetadata(file, false, 1024, 0)),
        ))
        compose.setContent {
            SubtitleEditComposeTheme {
                Box(Modifier.width(320.dp)) {
                    DirectoryBrowserContent(
                        state = state.value,
                        onScrollPosition = {},
                        onDirectoryBreadcrumbClick = { false },
                        onFileClick = {},
                        onFileLongClick = {},
                    )
                }
            }
        }

        for (mode in FileBrowserViewMode.entries) {
            compose.runOnIdle { state.value = state.value.copy(viewMode = mode, showFullFileNames = false) }
            val shortened = nameLayout(file.name)
            assertTrue(mode.name, shortened.isLineEllipsized(shortened.lineCount - 1))

            compose.runOnIdle { state.value = state.value.copy(showFullFileNames = true) }
            val full = nameLayout(file.name)
            assertTrue(mode.name, full.lineCount > shortened.lineCount)
            assertFalse(mode.name, full.hasVisualOverflow)
            assertEquals(mode.name, file.name.length, full.getLineEnd(full.lineCount - 1, visibleEnd = true))
        }
    }

    private fun nameLayout(name: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
