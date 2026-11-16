package com.subtitleedit.player

import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real pointer events must reach the directory even underneath an invisible AndroidView. */
@RunWith(AndroidJUnit4::class)
class PlayerMediaViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun collapsedAudioAndVideoCannotCoverNavigationAndKeepTheNativeViewportSize() {
        val collapsed = mutableStateOf(true)
        val video = mutableStateOf(false)
        var navigations = 0
        var nativePresses = 0
        lateinit var output: View
        compose.setContent {
            val density = LocalDensity.current.density
            val bounds = if (collapsed.value) Rect(12f, 590f, 84f, 638f) else Rect(0f, 0f, 360f, 700f)
            Box(Modifier.requiredSize(360.dp, 700.dp)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().height(60.dp)) {
                        repeat(5) { index ->
                            Box(Modifier.weight(1f).height(60.dp).testTag("navigation:$index")
                                .clickable { navigations++ })
                        }
                    }
                }
                Box(Modifier.fillMaxSize().playerMediaViewport(Rect(bounds.left * density,
                    bounds.top * density, bounds.right * density, bounds.bottom * density))) {
                    AndroidView(factory = { context -> View(context).also { view ->
                        output = view
                        view.setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) nativePresses++
                            true
                        }
                    } }, modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (video.value) 1f else 0f
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = if (collapsed.value) 0.2f else 1f
                        scaleY = scaleX
                        translationX = bounds.left * density
                        translationY = (bounds.center.y - 700f * scaleY / 2f) * density
                    })
                }
            }
        }
        compose.waitForIdle()
        val initialOutput = output
        val initialSize = output.width to output.height
        compose.onNodeWithTag("navigation:0").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, navigations); assertEquals(0, nativePresses) }
        compose.runOnIdle { video.value = true }
        compose.onNodeWithTag("navigation:0").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, navigations); assertEquals(0, nativePresses) }
        compose.runOnIdle { collapsed.value = false }
        compose.onNodeWithTag("navigation:0").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(2, navigations)
            assertEquals(1, nativePresses)
            assertSame(initialOutput, output)
            assertEquals(initialSize, output.width to output.height)
            collapsed.value = true
        }
        compose.onNodeWithTag("navigation:0").performTouchInput { click() }
        compose.runOnIdle { assertEquals(3, navigations); assertSame(initialOutput, output) }
    }
}
