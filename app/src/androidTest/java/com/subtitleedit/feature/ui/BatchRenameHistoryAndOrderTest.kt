package com.subtitleedit.feature.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.BatchRename
import com.subtitleedit.util.BatchRenameHistory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BatchRenameHistoryAndOrderTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    private fun confirm() = compose.onNodeWithText(
        ApplicationProvider.getApplicationContext<Context>().getString(android.R.string.ok)
    ).performClick()

    @Test
    fun applyingHistoryRestoresTheWholeRuleIncludingTokenAndRegexSettings() {
        val sources = listOf(files.newFile("alpha.txt"), files.newFile("beta.srt"))
        val saved = BatchRename.Options(
            pattern = "历史模板${BatchRename.NUMBER_TOKEN}",
            extension = "vtt",
            numberWidth = 3,
            startNumber = 12,
            find = "模板([0-9]+)",
            replacement = "字幕\$1",
            regexEnabled = true
        )
        var submitted: BatchRename.Options? = null
        compose.setContent {
            SubtitleEditComposeTheme {
                BatchRenameDialog(
                    state = BatchRenameDialogUi(sources, history = listOf(BatchRenameHistory.Entry("saved", saved))),
                    onDismiss = {},
                    onConfirm = { submitted = it }
                )
            }
        }

        compose.onNodeWithContentDescription("历史记录").performClick()
        compose.onNodeWithText("历史模板序号").performClick()
        compose.onNodeWithText("历史字幕012.vtt").assertExists()
        confirm()
        compose.runOnIdle { assertEquals(saved, submitted) }
    }

    @Test
    fun historyLongPressOnlyDeletesAfterConfirmation() {
        val entry = BatchRenameHistory.Entry("to-delete", BatchRename.Options(pattern = "历史规则"))
        val deleted = mutableListOf<String>()
        compose.setContent {
            SubtitleEditComposeTheme {
                BatchRenameHistoryDialog(listOf(entry), onDismiss = {}, onSelect = {}, onDelete = { deleted += it })
            }
        }

        compose.onNodeWithText("历史规则").performTouchInput { longClick() }
        compose.onNodeWithText("确定删除这条重命名历史记录吗？").assertExists()
        compose.runOnIdle { assertEquals(emptyList<String>(), deleted) }
        confirm()
        compose.runOnIdle { assertEquals(listOf(entry.id), deleted) }
    }

    @Test
    fun sortingThenConfirmingUsesThatOrderForNumberingAndExecution() {
        val z = files.newFile("z.txt")
        val a = files.newFile("a.txt")
        var state by mutableStateOf(BatchRenameDialogUi(listOf(z, a)))
        var submitted = emptyList<BatchRename.Entry>()
        compose.setContent {
            SubtitleEditComposeTheme {
                BatchRenameDialog(
                    state = state,
                    onDismiss = {},
                    onConfirm = { submitted = BatchRename.preview(state.sources, it) },
                    onOrderChanged = { state = state.copy(sources = it) }
                )
            }
        }

        compose.onNodeWithContentDescription("排序").performClick()
        compose.onNodeWithContentDescription("排序方式").performClick()
        compose.onNodeWithText("按名称").performClick()
        confirm()
        compose.onNodeWithText("a(1).txt").assertExists()
        compose.onNodeWithText("z(2).txt").assertExists()
        confirm()
        compose.runOnIdle {
            assertEquals(listOf(a, z), submitted.map { it.source })
            assertEquals(listOf("a(1).txt", "z(2).txt"), submitted.map { it.target.name })
        }
    }

    @Test
    fun draggingAHandleMovesItsFileToTheNewNumberingPosition() {
        val sources = listOf("a.txt", "b.txt", "c.txt").map(files::newFile)
        var submitted = emptyList<File>()
        compose.setContent {
            SubtitleEditComposeTheme {
                BatchRenameOrderDialog(sources, onDismiss = {}, onConfirm = { submitted = it })
            }
        }
        val first = compose.onNodeWithContentDescription("拖动调整顺序：a.txt")
        val last = compose.onNodeWithContentDescription("拖动调整顺序：c.txt")
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val lastBounds = last.fetchSemanticsNode().boundsInRoot
        val distance = lastBounds.center.y - firstBounds.center.y + lastBounds.height / 2f
        first.performTouchInput {
            swipe(start = center, end = center + Offset(0f, distance), durationMillis = 600)
        }
        confirm()
        compose.runOnIdle { assertEquals(listOf(sources[1], sources[2], sources[0]), submitted) }
    }
}
