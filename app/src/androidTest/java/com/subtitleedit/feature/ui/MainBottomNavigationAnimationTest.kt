package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Keep settled geometry/pixels while the spatial indicator animates without recomposition. */
@OptIn(InternalComposeTracingApi::class)
@RunWith(AndroidJUnit4::class)
class MainBottomNavigationAnimationTest {
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

    @Test fun playerSpatialAnimationKeepsSettledPixelsWithoutRecomposingNavigation() = compareAnimation(true)
    @Test fun editorSpatialAnimationKeepsSettledPixelsWithoutRecomposingNavigation() = compareAnimation(false)

    private fun compareAnimation(playerMode: Boolean) {
        compose.mainClock.autoAdvance = false
        val selection = mutableIntStateOf(R.id.nav_directory)
        compose.setContent {
            SubtitleEditComposeTheme {
                Column(Modifier.requiredWidth(360.dp)) {
                    Box(Modifier.testTag("current")) { MainBottomNavigation(selection.intValue, playerMode) {} }
                    Box(Modifier.testTag("previous")) { PreviousNavigation(selection.intValue, playerMode) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        assertMatchingPixels()
        compose.runOnIdle {
            Composer.setTracer(tracer)
            selection.intValue = if (playerMode) R.id.nav_library else R.id.nav_tools
        }
        // Apply selection and start both versions' animations at the same frame time.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(count("MainBottomNavigation") > 0)
        assertTrue(count("MainBottomNavigationItem") > 0)
        calls.clear()
        for (elapsed in listOf(16L, 48L, 96L, 256L)) {
            compose.mainClock.advanceTimeBy(elapsed)
            compose.onNodeWithTag("current").captureToImage()
        }
        assertMatchingPixels()
        assertEquals("Animation must not recompose the navigation row", 0, count("MainBottomNavigation"))
        assertEquals("Animation must not recompose individual tabs", 0, count("MainBottomNavigationItem"))
        assertTrue("Reference proves animation frames were actually sampled", count("PreviousNavigation") > 2)
    }

    private fun count(function: String) = calls.entries.filter { it.key.endsWith(".$function") }.sumOf { it.value.get() }

    private fun assertMatchingPixels() {
        val currentImage = compose.onNodeWithTag("current").captureToImage()
        val previousImage = compose.onNodeWithTag("previous").captureToImage()
        val current = currentImage.toPixelMap()
        val previous = previousImage.toPixelMap()
        assertEquals(previous.width, current.width)
        assertEquals(previous.height, current.height)
        var differences = 0
        for (y in 0 until current.height) for (x in 0 until current.width) {
            val a = current[x, y]
            val b = previous[x, y]
            if (kotlin.math.abs(a.red - b.red) > 0.02f || kotlin.math.abs(a.green - b.green) > 0.02f ||
                kotlin.math.abs(a.blue - b.blue) > 0.02f || kotlin.math.abs(a.alpha - b.alpha) > 0.02f) differences++
        }
        if (differences.toDouble() / (current.width * current.height) >= 0.003) {
            for ((name, image) in listOf("current" to currentImage, "previous" to previousImage)) {
                File(compose.activity.cacheDir, "navigation-$name.png").outputStream().use {
                    image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        assertTrue("Different pixels: $differences/${current.width * current.height}",
            differences.toDouble() / (current.width * current.height) < 0.003)
    }
}

/** Frozen rendering oracle copied from the navigation before the drawing-phase optimization. */
@Composable
private fun PreviousNavigation(selectedItemId: Int, playerModeEnabled: Boolean) {
    Row(Modifier.fillMaxWidth().height(60.dp).background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically) {
        bottomNavigationItems(playerModeEnabled).forEach { (id, icon, title) ->
            val label = stringResource(title)
            val selected = selectedItemId == id
            val alpha by animateFloatAsState(if (selected) 1f else 0f, AppMotion.enter(), label = "reference-alpha")
            val scale by animateFloatAsState(if (selected) 1f else 0.6f, AppMotion.enter(), label = "reference-scale")
            val tint by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                AppMotion.fast(), label = "reference-tint")
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp).selectable(
                    selected = selected, enabled = !selected, role = Role.Tab,
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(56.dp, 28.dp).clip(MaterialTheme.shapes.extraLarge)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = alpha)),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), label, Modifier.size(24.dp), tint = tint)
                }
                Text(label, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
