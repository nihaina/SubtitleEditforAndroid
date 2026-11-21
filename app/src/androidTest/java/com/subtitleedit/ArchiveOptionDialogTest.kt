package com.subtitleedit

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.ArchiveActionUiPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveOptionDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun landscapeKeepsAllActionsReachableWithALongArchiveName() {
        val previous = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            val labels = ArchiveActionUiPolicy.actionLabels.toList()
            val selected = mutableListOf<Int>()
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                    SubtitleEditComposeTheme {
                        ArchiveActionsDialog(
                            archiveName = "Very long archive name ".repeat(12) + ".zip",
                            actionLabels = labels,
                            onDismiss = {},
                            onAction = { selected += it },
                        )
                    }
                }
            }
            val cancel = compose.activity.getString(R.string.cancel)
            val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            labels.forEach { label ->
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assert(button).performClick()
                compose.onNodeWithText(cancel).assertIsDisplayed()
            }
            compose.runOnIdle { assertEquals(labels.indices.toList(), selected) }
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
        }
    }

    @Test fun passwordBookEntryIsAButtonAndPassesTheOriginalPassword() {
        var selected: String? = null
        compose.setContent {
            SubtitleEditComposeTheme {
                ArchivePasswordBookDialog(
                    passwords = listOf("example-password"),
                    onSelectPassword = { selected = it },
                    onSavePassword = {},
                    onClearPasswordBook = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("密码 1（••••••••）")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.runOnIdle { assertEquals("example-password", selected) }
    }
}
