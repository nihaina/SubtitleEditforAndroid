package com.subtitleedit.filepicker

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilePickerScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var state: MutableState<FilePickerUiState>
    private var selectAllCalls = 0
    private var selectRangeCalls = 0

    @Test fun fileRowsShowDirectoryBrowserDetailsWithoutSelectionCheckboxes() {
        val file = temporary.newFile("subtitle.txt").apply { writeBytes(ByteArray(1_024)) }
        show(listOf(file))

        compose.onNodeWithText(context.getString(R.string.file_picker_open_file)).assertExists()
        compose.onNodeWithText(context.getString(R.string.file_picker_open_files)).assertDoesNotExist()
        compose.onNodeWithText(file.name).assertExists()
        compose.onNodeWithText("TXT").assertExists()
        compose.onNodeWithText(FileUtils.formatFileSize(file.length())).assertExists()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(file.lastModified()))
        compose.onNodeWithText(date).assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.file_picker_clear_selection)).assertDoesNotExist()
    }

    @Test fun selectingFilesReplacesToolbarWithTemporaryCountAndSelectionActions() {
        val file = temporary.newFile("selected.srt")
        show(listOf(file))
        compose.onNodeWithText(file.name).performClick()

        compose.onNodeWithText("已选择 1 项").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.menu_search)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.file_picker_locations)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.file_picker_clear_selection)).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assertCountEquals(0)
        compose.onNodeWithContentDescription(context.getString(R.string.source_selection_select_all)).performClick()
        compose.onNodeWithContentDescription("局部全选").performClick()
        compose.runOnIdle {
            assertEquals(1, selectAllCalls)
            assertEquals(1, selectRangeCalls)
        }

        compose.onNodeWithContentDescription(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.file_picker_open_file)).assertExists()
        compose.onNodeWithText("已选择 1 项").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.file_picker_more)).assertExists()
        compose.runOnIdle { assertTrue(state.value.selectedPaths.isEmpty()) }
    }

    @Test fun searchReplacesTheToolbarTitleAndClearsBeforeClosing() {
        val file = temporary.newFile("searchable.srt")
        show(listOf(file))
        openSearch()
        val search = compose.onNode(hasSetTextAction())
        search.assertIsFocused()
        compose.onNodeWithContentDescription(context.getString(R.string.menu_search)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.file_picker_open_file)).assertDoesNotExist()
        val searchBounds = search.fetchSemanticsNode().boundsInRoot
        val breadcrumbBounds = compose.onNodeWithText(temporary.root.name).fetchSemanticsNode().boundsInRoot
        assertTrue("Search belongs inside the toolbar above the directory path", searchBounds.center.y < breadcrumbBounds.center.y)

        search.performTextReplacement("searchable")
        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.runOnIdle { assertEquals("", state.value.query) }
        compose.onNode(hasSetTextAction()).assertExists()
        // The empty search's clear button closes it; the first cancel icon is navigation.
        compose.onAllNodesWithContentDescription(context.getString(R.string.cancel))[1].performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.file_picker_open_file)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.file_picker_more)).assertExists()
    }

    @Test fun selectionTemporarilyHidesSearchAndRestoresItsQueryWhenCleared() {
        val file = temporary.newFile("matching.srt")
        show(listOf(file))
        openSearch()
        compose.onNode(hasSetTextAction()).performTextReplacement("matching")
        compose.onNodeWithText(file.name).performClick()

        compose.onNodeWithText("已选择 1 项").assertExists()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.cancel)).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals("matching", state.value.query) }
    }

    @Test fun unsupportedFilesRemainVisibleButCannotBeSelectedByTapOrLongPress() {
        val supported = temporary.newFile("supported.srt")
        val unsupported = temporary.newFile("unsupported.zip")
        show(listOf(supported, unsupported), setOf(unsupported.path))

        compose.onNodeWithText(unsupported.name).assertExists().assertIsNotEnabled()
            .performTouchInput { click() }
        compose.onNodeWithText(unsupported.name).performTouchInput { longClick() }
        compose.runOnIdle { assertTrue(state.value.selectedPaths.isEmpty()) }

        compose.onNodeWithText(supported.name).performClick()
        compose.onNodeWithText("已选择 1 项").assertExists()
        compose.onNodeWithText(unsupported.name).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(setOf(supported.path), state.value.selectedPaths) }
    }

    @Test fun unsupportedFilterDefaultsOffAndFollowsTheHiddenFilesOptionInMoreMenu() {
        show(listOf(temporary.newFile("subtitle.srt")))
        compose.runOnIdle { assertFalse(state.value.filterUnsupported) }
        compose.onNodeWithContentDescription(context.getString(R.string.file_picker_more)).performClick()
        val hidden = compose.onNodeWithText(context.getString(R.string.file_picker_show_hidden))
        val filter = compose.onNodeWithText(context.getString(R.string.file_picker_filter_unsupported))
        assertTrue(filter.fetchSemanticsNode().boundsInRoot.top > hidden.fetchSemanticsNode().boundsInRoot.top)
        filter.performClick()
        compose.runOnIdle { assertTrue(state.value.filterUnsupported) }

        compose.onNodeWithContentDescription(context.getString(R.string.file_picker_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.file_picker_filter_unsupported)).performClick()
        compose.runOnIdle { assertFalse(state.value.filterUnsupported) }
    }

    private fun openSearch() {
        compose.onNodeWithContentDescription(context.getString(R.string.menu_search)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.file_picker_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.menu_search)).performClick()
    }

    @Test fun longPressingBreadcrumbOpensPathEditor() {
        show(listOf(temporary.newFile("subtitle.srt")))
        compose.onNodeWithText(temporary.root.name).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.directory_jump_title)).assertExists()
    }

    private fun show(files: List<File>, unsupportedPaths: Set<String> = emptySet()) {
        state = mutableStateOf(FilePickerUiState(
            mode = FilePickerMode.OPEN_FILES,
            directory = temporary.root,
            files = files,
            unsupportedPaths = unsupportedPaths,
            hasStoragePermission = true
        ))
        compose.setContent {
            SubtitleEditComposeTheme {
                FilePickerScreen(
                    state = state.value,
                    onBack = {},
                    onCancel = {},
                    onPermission = {},
                    onNavigate = { true },
                    onFileClick = { file ->
                        val selected = state.value.selectedPaths.toMutableSet()
                        if (!selected.add(file.path)) selected.remove(file.path)
                        state.value = state.value.copy(selectedPaths = selected)
                    },
                    onSearch = { state.value = state.value.copy(query = it) },
                    onSort = { _, _ -> },
                    onShowHidden = {},
                    onFilterUnsupported = { state.value = state.value.copy(filterUnsupported = it) },
                    onNameChanged = {},
                    onConfirm = {},
                    onClearSelection = { state.value = state.value.copy(selectedPaths = emptySet()) },
                    onSelectAll = { selectAllCalls++ },
                    onSelectRange = { selectRangeCalls++ },
                    onRefresh = {},
                    onCreateDirectory = { _, _ -> },
                    onOverwrite = {},
                    onRename = {},
                    onDismissOverwrite = {}
                )
            }
        }
    }
}
