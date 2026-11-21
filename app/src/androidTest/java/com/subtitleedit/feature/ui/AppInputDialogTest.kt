package com.subtitleedit.feature.ui

import android.view.WindowManager.LayoutParams.MATCH_PARENT
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.components.AppInputDialog
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInputDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortInputDialogKeepsAFullHeightWindowAndHonorsOutsideDismissPolicy() {
        val allowOutsideDismiss = mutableStateOf(false)
        var window: android.view.Window? = null
        var dismissals = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                AppInputDialog(
                    onDismissRequest = { dismissals++ },
                    title = { Text("Rename") },
                    text = {
                        val provider = LocalView.current.parent as DialogWindowProvider
                        SideEffect { window = provider.window }
                        Text("Example file")
                    },
                    confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                    dismissOnClickOutside = allowOutsideDismiss.value,
                )
            }
        }
        val root = compose.onNode(isDialog())
        val content = compose.onNodeWithText("Example file").assertIsDisplayed()
        val outside = root.fetchSemanticsNode().boundsInRoot.height / 6f
        assertTrue(outside < content.fetchSemanticsNode().boundsInRoot.top)
        compose.runOnIdle { assertEquals(MATCH_PARENT, checkNotNull(window).attributes.height) }

        root.performTouchInput { click(Offset(center.x, outside)) }
        compose.runOnIdle {
            assertEquals(0, dismissals)
            allowOutsideDismiss.value = true
        }
        content.performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, dismissals) }
        root.performTouchInput { click(Offset(center.x, outside)) }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
