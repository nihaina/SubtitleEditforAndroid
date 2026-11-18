package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(InternalComposeTracingApi::class)
@RunWith(AndroidJUnit4::class)
class MainBottomNavigationSwipeIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val navigationCalls = AtomicInteger()
    private val tracer = object : CompositionTracer {
        override fun isTraceInProgress() = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.substringBefore(" (").substringAfterLast('.').startsWith("MainBottomNavigation")) navigationCalls.incrementAndGet()
        }
        override fun traceEventEnd() = Unit
    }
    private val colors = lightColorScheme(surfaceContainer = Color.White, secondaryContainer = Color.Green,
        onSecondaryContainer = Color.Black, onSurfaceVariant = Color.Black)

    @After fun releaseTracer() { Composer.setTracer(null) }

    @Test fun theCapsuleStretchesContinuouslyMirrorsInRtlAndOnlyInvalidatesDrawing() {
        val page = mutableFloatStateOf(0f)
        val frame = { MainTabIndicatorFrame.adjacent(page.floatValue) }
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Column(Modifier.requiredWidth(360.dp)) {
                    for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) {
                            Box(Modifier.testTag(direction.name)) {
                                MainBottomNavigation(R.id.nav_directory, true, indicatorFrame = frame) {}
                            }
                        }
                    }
                }
            }
        }
        val start = pill("Ltr")
        compose.runOnIdle { Composer.setTracer(tracer) }
        val frames = listOf(0f, .25f, .5f, .75f, 1f).map { fraction ->
            compose.runOnIdle { page.floatValue = fraction }
            val ltr = pill("Ltr", "elastic-bottom-${(fraction * 100).toInt()}.png")
            val rtl = pill("Rtl", "elastic-bottom-rtl-${(fraction * 100).toInt()}.png")
            val width = compose.onNodeWithTag("Ltr").captureToImage().width.toFloat()
            assertEquals(ltr.left, width - rtl.right, 1.1f)
            assertEquals(ltr.right, width - rtl.left, 1.1f)
            ltr
        }
        val end = frames.last()
        val distance = end.left - start.left
        assertEquals(start.width, end.width, 1.1f)
        assertTrue("The middle stays one connected stretched capsule", frames[2].width > start.width * 1.4f)
        assertTrue("The rear edge initially lags the finger", frames[1].left < start.left + distance * .25f - 1f)
        assertTrue("The front edge initially leads the finger", frames[1].right > start.right + distance * .25f + 1f)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.left > a.left && b.right > a.right })
        assertEquals("Pager progress must not recompose navigation", 0, navigationCalls.get())
    }

    @Test fun editorDraggingAndReversingKeepsTheIndicatorUnderTheFinger() = checkPager(false)
    @Test fun playerDraggingAndReversingKeepsTheIndicatorUnderTheFinger() = checkPager(true)

    @Test fun interruptingAClickAnimationWithADragKeepsSelectionSynchronized() {
        val items = bottomNavigationItems(true)
        val state = mutableStateOf(MainActivityScreenState(playerModeEnabled = true))
        lateinit var pager: PagerState
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                val motion = rememberMainTopLevelMotion(items.map { it.first }, state.value.selectedTopLevelItem)
                pager = motion.pager
                MainTopLevelPager(state.value, items.map { it.first }, onSelected = {
                    state.value = state.value.copy(selectedTopLevelItem = it)
                }, modifier = Modifier.requiredSize(360.dp, 600.dp), motion = motion) { page, _ ->
                    Text("Page ${page.selectedTopLevelItem}")
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = items[3].first) }
        compose.mainClock.advanceTimeBy(800L)
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = items[0].first) }
        compose.mainClock.advanceTimeBy(112L)
        compose.onNodeWithTag("main-page-pager").performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveBy(Offset(-width * .6f, 0f), 300L)
            up()
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.runOnIdle { assertEquals(items[pager.settledPage].first, state.value.selectedTopLevelItem) }
    }

    @Test fun playerLongJumpStretchesOnceAndNeverEmphasizesPassedTabs() = checkLongJump(true)
    @Test fun editorLongJumpStretchesOnceAndNeverEmphasizesPassedTabs() = checkLongJump(false)

    private fun checkLongJump(playerMode: Boolean) {
        val state = mutableStateOf(MainActivityScreenState(playerModeEnabled = playerMode))
        val bar = "main-bottom-navigation"
        val items = bottomNavigationItems(playerMode)
        showPager(state, colors.copy(onSecondaryContainer = Color.Red))
        val resting = pill(bar)
        compose.mainClock.autoAdvance = false
        val settings = compose.onNodeWithText(compose.activity.getString(R.string.menu_main_title_02))
        settings.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(32L)
        settings.assertIsSelected()
        val frames = (0..18).map {
            compose.mainClock.advanceTimeByFrame()
            val pixels = compose.onNodeWithTag(bar).captureToImage().toPixelMap()
            val start = pixels.width / items.size
            val end = pixels.width * items.lastIndex / items.size
            for (y in 0 until pixels.height) for (x in start until end) {
                val color = pixels[x, y]
                if (color.red - color.green > .15f) {
                    throw AssertionError("A passed tab's icon or label used the selected tint")
                }
            }
            pill(pixels)
        }
        val max = frames.maxBy { it.width }
        assertTrue("One capsule stretches across the entire jump", max.width > resting.width * 2f)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.left >= a.left && b.right >= a.right })
        val peak = frames.indexOf(max)
        assertTrue(frames.take(peak + 1).zipWithNext().all { (a, b) -> b.width >= a.width - 2f })
        assertTrue(frames.drop(peak).zipWithNext().all { (a, b) -> b.width <= a.width + 2f })
        assertEquals(resting.width, frames.last().width, 1.1f)
        compose.runOnIdle { assertEquals(items.last().first, state.value.selectedTopLevelItem) }
    }

    private fun showPager(state: MutableState<MainActivityScreenState>, colorScheme: ColorScheme = colors) {
        val playerMode = state.value.playerModeEnabled
        val order = bottomNavigationItems(playerMode).map { it.first }
        val select: (Int) -> Unit = { state.value = state.value.copy(selectedTopLevelItem = it) }
        compose.setContent {
            MaterialTheme(colorScheme = colorScheme) {
                val motion = rememberMainTopLevelMotion(order, state.value.selectedTopLevelItem)
                Column(Modifier.requiredSize(360.dp, 600.dp)) {
                    MainTopLevelPager(state.value, order, select, modifier = Modifier.weight(1f), motion = motion) { page, _ ->
                        Box(Modifier.fillMaxSize().background(Color.White)) { Text("Page ${page.selectedTopLevelItem}") }
                    }
                    MainBottomNavigation(state.value.selectedTopLevelItem, playerMode, motion.indicatorFrame, select)
                }
            }
        }
    }

    private fun checkPager(playerMode: Boolean) {
        val items = bottomNavigationItems(playerMode)
        val state = mutableStateOf(MainActivityScreenState(playerModeEnabled = playerMode))
        showPager(state)
        val resting = pill("main-bottom-navigation")
        compose.onNodeWithTag("main-page-pager").performTouchInput {
            down(Offset(width * .8f, height * .6f))
            moveBy(Offset(-width * .3f, 0f), 300L)
        }
        val dragging = pill("main-bottom-navigation")
        assertTrue(dragging.left > resting.left)
        assertTrue(dragging.width > resting.width * 1.2f)
        assertEquals(R.id.nav_directory, state.value.selectedTopLevelItem)
        compose.mainClock.advanceTimeBy(500L)
        assertEquals("A held finger must not leave a spring chasing the indicator", dragging, pill("main-bottom-navigation"))
        compose.onNodeWithTag("main-page-pager").performTouchInput { moveBy(Offset(width * .15f, 0f), 300L) }
        val reversed = pill("main-bottom-navigation")
        assertTrue(reversed.left < dragging.left && reversed.right < dragging.right)
        compose.onNodeWithTag("main-page-pager").performTouchInput {
            moveBy(Offset(-width * .65f, 0f), 300L)
            up()
        }
        compose.waitForIdle()
        assertEquals(items[1].first, state.value.selectedTopLevelItem)
        assertEquals(resting.width, pill("main-bottom-navigation").width, 1.1f)
    }

    private fun pill(tag: String, save: String? = null): Rect {
        val image = compose.onNodeWithTag(tag).captureToImage()
        if (save != null) File(compose.activity.cacheDir, save).outputStream().use {
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        return pill(image.toPixelMap())
    }

    private fun pill(pixels: PixelMap): Rect {
        var left = pixels.width
        var right = -1
        var top = pixels.height
        var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.green > .95f && color.red < .05f && color.blue < .05f) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("Expected a visible capsule", right >= left && bottom >= top)
        return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }
}
