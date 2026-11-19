package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.PageTranslationSettingsState
import com.subtitleedit.R
import com.subtitleedit.translation.PageTranslationCatalog
import com.subtitleedit.translation.PageTranslationLanguage.Companion.CHINESE
import com.subtitleedit.translation.PageTranslationLanguage.Companion.ENGLISH
import com.subtitleedit.translation.PageTranslationLanguage.Companion.JAPANESE
import com.subtitleedit.translation.PageTranslationModel
import com.subtitleedit.translation.PageTranslationOptions
import com.subtitleedit.ui.settings.PageTranslationSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTranslationSettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun showsOnlyMissingDirectionsAndHidesShortcutAfterDownload() {
        val japaneseToEnglish = PageTranslationModel(JAPANESE, ENGLISH)
        val englishToChinese = PageTranslationModel(ENGLISH, CHINESE)
        val catalog = PageTranslationCatalog(setOf(japaneseToEnglish, englishToChinese))
        val state = mutableStateOf(PageTranslationSettingsState(
            options = PageTranslationOptions(),
            supported = true,
            catalog = catalog,
            downloadedModels = setOf(englishToChinese),
            checkingModels = false,
        ))
        compose.setContent {
            SubtitleEditComposeTheme {
                PageTranslationSettingsScreen(
                    state = state.value,
                    systemLocale = Locale.JAPAN,
                    onSourceChanged = {},
                    onTargetChanged = {},
                    onDownload = {},
                    onDownloadMissing = { state.value = state.value.copy(enqueuingModels = setOf(japaneseToEnglish)) },
                    onCancelDownload = {},
                    onDelete = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        val context = compose.activity
        val direction = context.getString(
            R.string.page_translation_model_direction,
            context.getString(R.string.page_translation_japanese),
            context.getString(R.string.page_translation_english),
        )
        val missing = context.getString(R.string.page_translation_missing_models_inline, direction)
        val download = context.getString(R.string.page_translation_download_missing)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(download))
        compose.onNodeWithText(missing).assertExists()
        compose.onNodeWithText(download).assertIsDisplayed().performClick()
        compose.onNodeWithText(download).assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(downloadedModels = catalog.models, enqueuingModels = emptySet())
        }
        compose.onNodeWithText(missing).assertDoesNotExist()
        compose.onNodeWithText(download).assertDoesNotExist()
    }
}
