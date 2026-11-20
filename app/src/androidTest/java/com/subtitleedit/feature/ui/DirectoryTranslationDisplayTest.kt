package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.translation.DirectoryTranslationResult
import com.subtitleedit.translation.PageTranslationLanguage
import com.subtitleedit.translation.PageTranslationModel
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                    translationPreview = preview,
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

    @Test fun missingDetectedModelsShowATappableHintWithoutOpeningTheDialogAutomatically() {
        val file = File(compose.activity.cacheDir, "Rain.txt")
        val state = MainActivityScreenState(
            currentDirectory = file.parentFile,
            files = listOf(file),
            fileMetadata = mapOf(file.absolutePath to BrowserFileMetadata(file, false, 0, 0)),
        )
        val preview = DirectoryTranslationPreviewState().apply {
            setPreviewEnabled(true)
            val token = beginRequest()
            showName(token, file.absolutePath, "下雨.txt")
            finishRequest(token)
            result = DirectoryTranslationResult(
                missingDetectedModels = setOf(PageTranslationModel(PageTranslationLanguage("da"), PageTranslationLanguage.ENGLISH)),
            )
        }
        showDirectory(state, preview)

        val attribution = compose.onNodeWithText(text(R.string.page_translation_attribution)).assertIsDisplayed()
        val hint = compose.onNodeWithText(text(R.string.page_translation_missing_models_hint)).assertIsDisplayed()
        val title = compose.onNodeWithText(text(R.string.page_translation_missing_models))
        val attributionBounds = attribution.fetchSemanticsNode().boundsInRoot
        val hintBounds = hint.fetchSemanticsNode().boundsInRoot
        assertTrue(hintBounds.left > attributionBounds.left)
        assertEquals(attributionBounds.center.y, hintBounds.center.y, 1f)
        title.assertDoesNotExist()
        compose.onNodeWithText("下雨.txt").assertIsDisplayed()

        hint.performClick()
        title.assertIsDisplayed()
        compose.onNodeWithText(text(R.string.page_translation_download_missing)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        title.assertDoesNotExist()
        hint.assertIsDisplayed().performClick()
        title.assertIsDisplayed()
    }

    @Test fun missingRequiredModelsOfferSettingsEvenWhenNoNamesWereTranslated() {
        val preview = DirectoryTranslationPreviewState().apply {
            result = DirectoryTranslationResult(
                missingRequiredModels = setOf(PageTranslationModel(PageTranslationLanguage.ENGLISH, PageTranslationLanguage.CHINESE)),
            )
        }
        var settingsOpened = false
        showDirectory(MainActivityScreenState(currentDirectory = compose.activity.cacheDir), preview) {
            settingsOpened = true
        }

        val title = compose.onNodeWithText(text(R.string.page_translation_missing_models))
        val hint = compose.onNodeWithText(text(R.string.page_translation_missing_models_hint))
        title.assertDoesNotExist()
        hint.assertIsDisplayed().performClick()
        title.assertIsDisplayed()
        compose.onNodeWithText(text(R.string.page_translation_open_settings)).performClick()
        compose.runOnIdle { assertTrue(settingsOpened) }
        title.assertDoesNotExist()
        hint.assertDoesNotExist()
    }

    private fun showDirectory(
        state: MainActivityScreenState,
        preview: DirectoryTranslationPreviewState,
        onOpenSettings: () -> Unit = {},
    ) {
        compose.setContent {
            SubtitleEditComposeTheme {
                DirectoryBrowserContent(
                    state = state,
                    onScrollPosition = {},
                    onDirectoryBreadcrumbClick = { false },
                    onFileClick = {},
                    onFileLongClick = {},
                    translationPreview = preview,
                    onOpenTranslationSettings = onOpenSettings,
                )
            }
        }
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
