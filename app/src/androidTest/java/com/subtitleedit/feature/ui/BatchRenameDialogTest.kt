package com.subtitleedit.feature.ui

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.BatchRename
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchRenameDialogTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    private val defaultPattern = BatchRename.FILE_NAME_TOKEN + "(" + BatchRename.NUMBER_TOKEN + ")"
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun tokenButton(label: String) = compose.onNode(hasClickAction() and hasText(label) and !hasSetTextAction())
    private fun confirm() = compose.onNodeWithText(
        ApplicationProvider.getApplicationContext<Context>().getString(android.R.string.ok)
    ).performClick()

    private fun showDialog(onConfirm: (BatchRename.Options) -> Unit = {}) {
        val sources = listOf(files.newFile("alpha.txt"), files.newFile("beta.srt"))
        compose.setContent {
            SubtitleEditComposeTheme {
                BatchRenameDialog(BatchRenameDialogUi(sources), onDismiss = {}, onConfirm = onConfirm)
            }
        }
    }

    @Test
    fun defaultPreviewKeepsMixedExtensionsAndUnfocusedTokenInsertionAppends() {
        var submitted: BatchRename.Options? = null
        showDialog { submitted = it }
        compose.onNodeWithText("alpha(1).txt").assertExists()
        compose.onNodeWithText("beta(2).srt").assertExists()

        tokenButton("文件名").performClick()
        compose.onNodeWithText("alpha(1)alpha.txt").assertExists()
        confirm()

        compose.runOnIdle {
            assertEquals(defaultPattern + BatchRename.FILE_NAME_TOKEN, submitted?.pattern)
            assertEquals(null, submitted?.extension)
        }
    }

    @Test
    fun focusedInsertionUsesCaretAndBackspaceRemovesTheEntireFileNameToken() {
        var submitted: BatchRename.Options? = null
        showDialog { submitted = it }
        field("文件名").performClick().performTextInputSelection(TextRange(1))
        tokenButton("序号").performClick()
        compose.onNodeWithText("alpha1(1).txt").assertExists()

        field("文件名").performClick().performTextInputSelection(TextRange(1))
        field("文件名").performKeyInput { pressKey(Key.Backspace) }
        compose.onNodeWithText("1(1).txt").assertExists()
        confirm()

        compose.runOnIdle {
            assertEquals(BatchRename.NUMBER_TOKEN + "(" + BatchRename.NUMBER_TOKEN + ")", submitted?.pattern)
        }
    }

    @Test
    fun regexToggleChangesFindReplacementWithoutChangingThePattern() {
        var submitted: BatchRename.Options? = null
        showDialog { submitted = it }
        field("查找").performTextReplacement("[0-9]+")
        field("替换").performTextReplacement("X")
        compose.onNodeWithText("alpha(1).txt").assertExists()
        tokenButton("正则").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未启用正则表达式"))
        tokenButton("正则").performClick()
        tokenButton("正则").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已启用正则表达式"))
        compose.onNodeWithText("alpha(X).txt").assertExists()
        confirm()

        compose.runOnIdle {
            assertEquals(true, submitted?.regexEnabled)
            assertEquals(defaultPattern, submitted?.pattern)
        }
    }

    @Test
    fun invalidRegexBlocksConfirmationAndRemainsEditable() {
        showDialog()
        field("查找").performTextReplacement("[")
        tokenButton("正则").performClick()
        compose.onNodeWithText(
            ApplicationProvider.getApplicationContext<Context>().getString(android.R.string.ok)
        ).assertIsNotEnabled()
        field("查找").performTextReplacement("alpha")
        field("替换").performTextReplacement("renamed")
        compose.onNodeWithText("renamed(1).txt").assertExists()
    }
}
