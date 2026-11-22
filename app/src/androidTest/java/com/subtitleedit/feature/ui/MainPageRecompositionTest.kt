package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.translation.FoxletPageTranslation
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Observe real compiler trace events: data updates must not execute unrelated composable bodies. */
@OptIn(InternalComposeTracingApi::class)
@RunWith(AndroidJUnit4::class)
class MainPageRecompositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private val tracer = object : CompositionTracer {
        override fun isTraceInProgress() = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            calls.getOrPut(info.substringBefore(" (")) { AtomicInteger() }.incrementAndGet()
        }
        override fun traceEventEnd() = Unit
    }

    @After fun releaseTracer() { Composer.setTracer(null) }

    @Test fun toolbarUpdatesDoNotRecomposeDirectoryRowsOrBottomNavigation() {
        val state = showPage()
        calls.clear()
        compose.runOnIdle { state.value = state.value.copy(toolbarTitle = "Updated toolbar") }
        compose.onNodeWithText("Updated toolbar").assertIsDisplayed()

        assertTrue(count("MainPageToolbar") > 0)
        assertEquals(0, count("DirectoryBrowserPage"))
        assertEquals(0, count("BrowserFileLayout"))
        assertEquals(0, count("MainBottomBar"))
    }

    @Test fun directoryDataUpdatesDoNotRecomposeToolbarOrBottomNavigation() {
        val state = showPage()
        calls.clear()
        compose.runOnIdle {
            val file = File("/scope/Added.srt")
            state.value = state.value.copy(
                files = listOf(file) + state.value.files,
                fileMetadata = state.value.fileMetadata + (file.path to BrowserFileMetadata(file, false, 1L, 0L)),
            )
        }
        compose.onNodeWithText("Added.srt").assertIsDisplayed()

        assertTrue(count("DirectoryBrowserPage") > 0)
        assertEquals(0, count("MainPageToolbar"))
        assertEquals(0, count("MainBottomBar"))
    }

    @Test fun translateIconKeepsItsAppearanceWhileTheNextDirectoryLoads() {
        val state = showPage(withTranslation = true)
        val button = translationButton()
        if (FoxletPageTranslation.isSupported) button.assertIsEnabled()
        val before = button.captureToImage().asAndroidBitmap()
        compose.runOnIdle {
            state.value = state.value.copy(isDirectoryLoading = true, isDirectoryNavigationPending = true)
        }
        button.assertIsDisplayed().assertIsNotEnabled()
        assertTrue(before.sameAs(button.captureToImage().asAndroidBitmap()))

        compose.runOnIdle {
            state.value = state.value.copy(
                currentDirectory = File("/next"), files = emptyList(), fileMetadata = emptyMap(),
                isDirectoryLoading = false, isDirectoryNavigationPending = false,
            )
        }
        button.assertIsDisplayed()
        if (FoxletPageTranslation.isSupported) button.assertIsEnabled()
    }

    @Test fun translateButtonSlidesWithTheDirectoryToolbarDuringADrag() {
        showPage(withTranslation = true)
        val button = translationButton().assertIsDisplayed()
        val restingPosition = button.fetchSemanticsNode().boundsInRoot
        val pager = compose.onNodeWithTag("main-page-pager")
        pager.performTouchInput {
            down(Offset(width * .8f, height * .6f))
            moveBy(Offset(-width * .09f, 0f), 250L)
        }
        button.assertIsDisplayed().assertIsNotEnabled()
        assertTrue(button.fetchSemanticsNode().boundsInRoot.left < restingPosition.left)

        pager.performTouchInput {
            moveBy(Offset(width * .09f, 0f), 250L)
            up()
        }
        button.assertIsDisplayed()
        assertEquals(restingPosition, button.fetchSemanticsNode().boundsInRoot)
        if (FoxletPageTranslation.isSupported) button.assertIsEnabled()
    }

    private fun translationButton() = compose.onNodeWithContentDescription(compose.activity.getString(
        if (FoxletPageTranslation.isSupported) R.string.page_translation_show_preview else R.string.page_translation_unsupported
    ))

    private fun count(function: String) = calls.entries.filter { it.key.endsWith(".$function") }.sumOf { it.value.get() }

    private fun showPage(withTranslation: Boolean = false): MutableState<MainActivityScreenState> {
        val files = (0 until 30).map { File("/scope/Track $it.srt") }
        val state = mutableStateOf(MainActivityScreenState(
            currentDirectory = File("/scope"), files = files,
            fileMetadata = files.associate { it.path to BrowserFileMetadata(it, false, 1L, 0L) },
        ))
        Composer.setTracer(tracer)
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                    MainActivityScreen(
                        state = state.value, topLevelPageRefreshVersion = 0, topLevelPageSelectionVersion = 0,
                        onDirectoryScrollPosition = {}, onTopLevelPageSelected = {}, onOpenDirectoryFromFavorites = {},
                        onUpdateTopLevelToolbar = { _, _, _ -> }, onSearchRequested = {}, onSearchChanged = {},
                        onCreateItem = { _, _, _ -> null }, onSortFieldChanged = {}, onSortDirectionChanged = {},
                        onViewModeChanged = {}, onTogglePlayerMode = {}, onOnlineClicked = {},
                        onOpenFileManagementSettings = {}, onSelectAll = {}, onSelectRange = {}, onToolbarBack = {},
                        onDirectoryBreadcrumbClick = { false }, onFileClick = {}, onFileLongClick = {},
                        onCopy = {}, onMove = {}, onRename = {}, onDelete = {}, onConvertSelection = {},
                        onCompressSelection = {}, onEditTagsSelection = {}, onClearCacheSelection = {},
                        onShowSelectionProperties = {}, onConfirmDestination = {}, onCancelDestination = {},
                        subtitleConversion = null, subtitleConversionResult = null, archiveCreation = null,
                        onDismissSubtitleConversion = {}, onConvertSubtitle = { _, _, _ -> false },
                        onDismissConversionResult = {}, onDismissArchiveCreation = {}, onCreateArchive = { null },
                        onLoadArchivePasswords = { emptyList() }, onSaveArchivePassword = {}, onClearArchivePasswordBook = {},
                        mainDialog = null, onDismissMainDialog = {}, onPermissionDialogConfirm = {}, onRenameConfirm = {},
                        batchRename = null, onDismissBatchRename = {}, onBatchRenameConfirm = {}, onBatchRenameOrderChanged = {},
                        onDeleteBatchRenameHistory = {}, onDeleteConfirm = {}, onCacheItemSelected = {}, onCacheClearConfirm = {},
                        onOpenPageTranslation = if (withTranslation) ({}) else null,
                    )
                }
            }
        }
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("directory-files").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("directory-files").assertIsDisplayed()
        compose.waitForIdle()
        return state
    }
}
