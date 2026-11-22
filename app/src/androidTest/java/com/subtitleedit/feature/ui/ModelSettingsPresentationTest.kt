package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.VocalSeparationSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelSettingsPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun llmContextChangeStillRescalesAndPersistsTheThinkingBudget() {
        val settings = SettingsManager.getInstance(compose.activity)
        val originalContext = settings.getLlmContextSize()
        val originalBudget = settings.getLlmThinkingBudget()
        try {
            settings.setLlmContextSize(2048)
            settings.setLlmThinkingBudget(512)
            compose.setContent { SubtitleEditComposeTheme { LlmSettingsScreen(settings, onNavigateBack = {}) } }

            compose.onNode(hasSetTextAction() and hasText(label(R.string.llm_settings_context_size)))
                .performScrollTo().performTextReplacement("4096")
            compose.runOnIdle {
                assertEquals(4096, settings.getLlmContextSize())
                assertEquals(1024, settings.getLlmThinkingBudget())
            }
            compose.onNode(hasSetTextAction() and hasText(label(R.string.llm_settings_thinking_budget)))
                .performScrollTo().performTextReplacement("1")
            compose.runOnIdle { assertEquals(64, settings.getLlmThinkingBudget()) }
        } finally {
            settings.setLlmContextSize(originalContext)
            settings.setLlmThinkingBudget(originalBudget)
        }
    }

    @Test fun vocalSeparationThreadAndParallelControlsUseTheOriginalCallbacks() {
        val threads = mutableStateOf(4)
        val parallel = mutableStateOf(false)
        val committedThreads = mutableListOf<Int>()
        compose.setContent {
            SubtitleEditComposeTheme {
                VocalSeparationSettingsScreen(
                    intraOpThreads = threads.value,
                    onIntraOpThreadsChanged = {
                        committedThreads += it
                        threads.value = it
                    },
                    chunkParallelEnabled = parallel.value,
                    onChunkParallelChanged = { parallel.value = it },
                    graphOptimizationEnabled = true,
                    cpuArenaEnabled = true,
                    onGraphOptimizationChanged = {},
                    onCpuArenaChanged = {},
                    onBack = {},
                )
            }
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(7f)) }
        compose.runOnIdle { assertEquals(listOf(7), committedThreads) }
        val count = compose.activity.getString(R.string.activity_vocal_separation_settings_text_22, 7)
        compose.onNodeWithText(count).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.activity_vocal_separation_settings_text_23))
            .performScrollTo().assertIsOff().performClick().assertIsOn()
    }

    private fun label(id: Int): String = compose.activity.getString(id)
}
