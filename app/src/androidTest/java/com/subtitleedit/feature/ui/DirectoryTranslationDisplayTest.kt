package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryTranslationDisplayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allDirectoryViewsDisplayPreviewAndOpenTheOriginalEntries() {
        val directory = File(compose.activity.cacheDir, "directory-translation-preview-test")
        val folder = File(directory, "Rain")
        val file = File(directory, "Notes.txt")
        val state = mutableStateOf(MainActivityScreenState(
            currentDirectory = directory,
            files = listOf(folder, file),
            fileMetadata = mapOf(
                folder.absolutePath to BrowserFileMetadata(folder, true, 0, 0),
                file.absolutePath to BrowserFileMetadata(file, false, 0, 0),
            ),
        ))
        val preview = DirectoryTranslationPreviewState().apply {
            setPreviewEnabled(true)
            val token = beginRequest()
            showName(token, folder.absolutePath, "下雨")
            showName(token, file.absolutePath, "记事.txt")
            finishRequest(token)
        }
        val opened = mutableListOf<File>()
        compose.setContent {
            SubtitleEditComposeTheme {
                DirectoryBrowserContent(
                    state = state.value,
                    onScrollPosition = {},
                    onDirectoryBreadcrumbClick = { false },
                    onFileClick = { opened += it },
                    onFileLongClick = {},
                    translatedNames = preview.translatedNames,
                )
            }
        }

        for (mode in FileBrowserViewMode.entries) {
            compose.runOnIdle { state.value = state.value.copy(viewMode = mode) }
            compose.onNodeWithText("下雨").assertIsDisplayed().performClick()
            compose.onNodeWithText("记事.txt").assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals(FileBrowserViewMode.entries.flatMap { listOf(folder, file) }, opened)
            preview.setPreviewEnabled(false)
        }
        compose.onNodeWithText(folder.name).assertIsDisplayed()
        compose.onNodeWithText(file.name).assertIsDisplayed()
    }
}
