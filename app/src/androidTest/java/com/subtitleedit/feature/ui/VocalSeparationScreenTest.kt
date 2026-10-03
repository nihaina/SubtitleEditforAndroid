package com.subtitleedit.feature.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.demix.VocalSeparationEngine.Stem
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VocalSeparationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private val calls = mutableListOf<String>()

    private fun show(state: VocalSeparationUiState) {
        compose.setContent {
            SubtitleEditComposeTheme {
                VocalSeparationScreen(
                    state = state,
                    onBack = { calls += "back" },
                    onConfirmBack = { calls += "confirmBack" },
                    onSettings = { calls += "settings" },
                    onSelectFiles = { calls += "selectFiles" },
                    onSelectOutputDirectory = { calls += "selectOutput" },
                    onStemChange = { stem, checked -> calls += "stem:$stem:$checked" },
                    onStart = { calls += "start" },
                    onCancel = { calls += "cancel" },
                    onOverwrite = { calls += "overwrite" },
                    onAutoRename = { calls += "rename" },
                    onDismissDialog = { calls += "dismiss" }
                )
            }
        }
    }

    @Test
    fun startIsDisabledUntilFilesAreSelected() {
        show(VocalSeparationUiState())

        compose.onNodeWithText(text(R.string.activity_vocal_separation_text_08)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.vocal_separation_no_files)).assertIsDisplayed()
    }

    @Test
    fun startIsEnabledWithFilesAndStems() {
        show(VocalSeparationUiState(selectedFilesText = "a.mp3", hasSelectedFiles = true))

        compose.onNodeWithText(text(R.string.activity_vocal_separation_text_08))
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf("start"), calls)
    }

    @Test
    fun clickingAnEnabledStemTogglesIt() {
        show(VocalSeparationUiState(enabledStems = setOf(Stem.VOCALS, Stem.DRUMS)))

        compose.onNodeWithText(Stem.DRUMS.displayName).performClick()
        compose.onNodeWithText(Stem.VOCALS.displayName).performClick()

        assertEquals(listOf("stem:DRUMS:true", "stem:VOCALS:false"), calls)
    }

    @Test
    fun outputConflictDialogRoutesEachChoice() {
        show(VocalSeparationUiState(dialog = VocalSeparationDialog.OUTPUT_CONFLICT))

        compose.onNodeWithText(text(R.string.vocal_separation_output_conflict)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.overwrite)).performClick()
        compose.onNodeWithText(text(R.string.auto_rename)).performClick()

        assertEquals(listOf("overwrite", "rename"), calls)
    }

    @Test
    fun runningTaskShowsStatusAndCancel() {
        show(
            VocalSeparationUiState(
                isRunning = true,
                progressVisible = true,
                progressStatus = "[1/2] working",
                progress = 40
            )
        )

        compose.onNodeWithText("[1/2] working").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()

        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun backDialogConfirmsCancellation() {
        show(VocalSeparationUiState(isRunning = true, dialog = VocalSeparationDialog.BACK))

        compose.onNodeWithText(text(R.string.vocal_separation_back_confirm)).performClick()

        assertEquals(listOf("confirmBack"), calls)
    }
}
