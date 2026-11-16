package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A paused frame checks page geometry and state while both navigation destinations exist. */
@RunWith(AndroidJUnit4::class)
class MainTopLevelTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pageAnimationDoesNotHashTheDirectoryContents() {
        val files = object : AbstractList<File>() {
            override val size = 2_000
            override fun get(index: Int) = File("/directory/track-$index.ogg")
            override fun hashCode(): Int = error("Navigation must not hash every directory row")
        }
        val state = showPage(page(R.id.nav_directory, "Directory").copy(files = files))
        changePage(state, page(R.id.nav_tools, "Categories").copy(files = files), 32L)
        changePage(state, page(R.id.nav_directory, "Directory returned").copy(files = files), 32L)
        compose.mainClock.advanceTimeBy(700L)
        compose.onNodeWithTag(bodyTag(R.id.nav_directory)).assertIsDisplayed()
    }

    @Test fun directoryKeepsItsHeaderAndBodyPositionWhileCategoryEnters() {
        val state = showPage(page(R.id.nav_directory, "Directory original", search = true))
        val oldBody = bounds(bodyTag(R.id.nav_directory))
        val bottom = bounds("test-bottom-navigation")

        changePage(state, page(R.id.nav_tools, "Category"))

        assertEquals(oldBody.top, bounds(bodyTag(R.id.nav_directory)).top, 0.5f)
        val host = bounds("test-page-host")
        assertEquals(host.top, bounds(bodyTag(R.id.nav_tools)).top, 0.5f)
        compose.onNodeWithTag(titleTag(R.id.nav_directory)).assertTextEquals("Directory original")
        compose.onNodeWithTag(actionTag(R.id.nav_directory)).assertTextEquals("Search active")
        assertSameBounds(bottom, bounds("test-bottom-navigation"))
    }

    @Test fun categoryIsNotPushedDownByTheIncomingStatisticsHeader() {
        val state = showPage(page(R.id.nav_tools, "Category"))
        val categoryTop = bounds(bodyTag(R.id.nav_tools)).top
        val bottom = bounds("test-bottom-navigation")

        changePage(state, page(R.id.nav_stats, "Statistics"))

        assertEquals(categoryTop, bounds(bodyTag(R.id.nav_tools)).top, 0.5f)
        assertTrue(bounds(bodyTag(R.id.nav_stats)).top > categoryTop)
        assertEquals(bounds(headerTag(R.id.nav_stats)).bottom, bounds(bodyTag(R.id.nav_stats)).top, 0.5f)
        assertSameBounds(bottom, bounds("test-bottom-navigation"))
    }

    @Test fun outgoingToolbarKeepsItsSnapshotAndMovesWithItsBody() {
        val state = showPage(page(R.id.nav_directory, "Directory original", search = true))

        changePage(state, page(R.id.nav_stats, "Statistics latest"))

        compose.onNodeWithTag(titleTag(R.id.nav_directory)).assertTextEquals("Directory original")
        compose.onNodeWithTag(actionTag(R.id.nav_directory)).assertTextEquals("Search active")
        compose.onNodeWithTag(titleTag(R.id.nav_stats)).assertTextEquals("Statistics latest")
        for (id in listOf(R.id.nav_directory, R.id.nav_stats)) {
            val header = bounds(headerTag(id))
            val body = bounds(bodyTag(id))
            assertEquals("The header and body must share the same horizontal transform", header.left, body.left, 0.5f)
            assertEquals(header.bottom, body.top, 0.5f)
        }
    }

    @Test fun activePageUpdatesDoNotStartAnotherNavigationTransition() {
        val state = showPage(page(R.id.nav_stats, "Statistics before"))
        val original = bounds(bodyTag(R.id.nav_stats))

        compose.runOnIdle { state.value = state.value.copy(toolbarTitle = "Statistics updated", toolbarHasBack = true) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48L)

        compose.onAllNodesWithTag(bodyTag(R.id.nav_stats)).assertCountEquals(1)
        compose.onNodeWithTag(titleTag(R.id.nav_stats)).assertTextEquals("Statistics updated")
        assertSameBounds(original, bounds(bodyTag(R.id.nav_stats)))
    }

    @Test fun rapidReverseNavigationSettlesOnOnlyTheLastRequestedPage() {
        val state = showPage(page(R.id.nav_directory, "Directory"))
        val bottom = bounds("test-bottom-navigation")
        changePage(state, page(R.id.nav_tools, "Category first"), 32L)
        changePage(state, page(R.id.nav_stats, "Statistics"), 32L)
        changePage(state, page(R.id.nav_tools, "Category final"), 32L)
        compose.mainClock.advanceTimeBy(700L)

        compose.onNodeWithTag(bodyTag(R.id.nav_tools)).assertIsDisplayed()
        compose.onAllNodesWithTag(bodyTag(R.id.nav_tools)).assertCountEquals(1)
        compose.onNodeWithTag(bodyTag(R.id.nav_directory)).assertDoesNotExist()
        compose.onNodeWithTag(bodyTag(R.id.nav_stats)).assertDoesNotExist()
        compose.onNodeWithTag(headerTag(R.id.nav_directory)).assertDoesNotExist()
        compose.onNodeWithTag(headerTag(R.id.nav_stats)).assertDoesNotExist()
        assertEquals(bounds("test-page-host").top, bounds(bodyTag(R.id.nav_tools)).top, 0.5f)
        assertSameBounds(bottom, bounds("test-bottom-navigation"))
    }

    @Test fun navigationDoesNotFlashTheEmptyHostBetweenPages() {
        val state = showPage(page(R.id.nav_directory, "Directory"))
        changePage(state, page(R.id.nav_tools, "Category"), 80L)

        val pixels = compose.onNodeWithTag("test-page-host").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        // The host is black and both pages are white. A fade-out / delayed fade-in gap is black.
        assertTrue("At least one page should remain painted during the handoff", center.red > 0.4f)
        assertTrue(center.green > 0.4f && center.blue > 0.4f)
    }

    private fun showPage(initial: MainActivityScreenState): MutableState<MainActivityScreenState> {
        val state = mutableStateOf(initial)
        compose.setContent {
            SubtitleEditComposeTheme {
                Column(Modifier.requiredSize(360.dp, 600.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).testTag("test-page-host")) {
                        MainTopLevelPageTransition(
                            state = state.value,
                            pageOrder = listOf(R.id.nav_directory, R.id.nav_stats, R.id.nav_tools),
                            modifier = Modifier.fillMaxSize()
                        ) { snapshot ->
                            val id = snapshot.selectedTopLevelItem
                            Column(Modifier.fillMaxSize()) {
                                if (id != R.id.nav_tools) {
                                    Row(Modifier.fillMaxWidth().height(64.dp).background(Color.White).testTag(headerTag(id))) {
                                        Text(snapshot.toolbarTitle, Modifier.testTag(titleTag(id)))
                                        Text(if (snapshot.isSearchExpanded) "Search active" else "Page actions",
                                            Modifier.testTag(actionTag(id)))
                                    }
                                }
                                Box(Modifier.fillMaxWidth().weight(1f).background(Color.White).testTag(bodyTag(id))) {
                                    Text("Page $id", Modifier.padding(8.dp))
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(56.dp).background(Color.Gray).testTag("test-bottom-navigation"))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        return state
    }

    private fun changePage(state: MutableState<MainActivityScreenState>, target: MainActivityScreenState, elapsed: Long = 64L) {
        compose.runOnIdle { state.value = target }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(elapsed)
        compose.waitForIdle()
    }

    private fun page(id: Int, title: String, search: Boolean = false) = MainActivityScreenState(
        selectedTopLevelItem = id, toolbarTitle = title, isSearchExpanded = search, playerModeEnabled = true
    )

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertSameBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.5f)
        assertEquals(expected.top, actual.top, 0.5f)
        assertEquals(expected.right, actual.right, 0.5f)
        assertEquals(expected.bottom, actual.bottom, 0.5f)
    }

    private fun bodyTag(id: Int) = "test-page-body:$id"
    private fun headerTag(id: Int) = "test-page-header:$id"
    private fun titleTag(id: Int) = "test-page-title:$id"
    private fun actionTag(id: Int) = "test-page-actions:$id"
}
