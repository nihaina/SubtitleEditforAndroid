package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.collectRetainedPageState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainTopLevelPagerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mounts = mutableMapOf<Int, Int>()
    private val disposals = mutableMapOf<Int, Int>()
    private val lifecycles = mutableMapOf<Int, Lifecycle>()
    private val backCalls = mutableListOf<Int>()
    private val selections = mutableListOf<Int>()
    private val updates = MutableStateFlow(0)
    private val displayedValues = mutableMapOf<Int, Int>()
    private val horizontalStates = mutableMapOf<Int, ScrollState>()

    @Test fun tapsMoveOpaquePagesHorizontallyAndKeepVisitedUiMounted() {
        val state = showPager()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = 2, toolbarTitle = "Page two") }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80L)
        val pixels = compose.onNodeWithTag("main-page-pager").captureToImage().toPixelMap()
        assertEquals("No fade to the host background", Color.White, pixels[pixels.width - 3, pixels.height / 2])
        val moving = compose.onNodeWithTag("body:2").fetchSemanticsNode().boundsInRoot
        assertTrue("Destination must move across the viewport", moving.left > 0f)
        compose.mainClock.advanceTimeBy(700L)
        compose.onNodeWithTag("body:2").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = 1, toolbarTitle = "Directory returned") }
        compose.mainClock.advanceTimeBy(700L)
        compose.onNodeWithTag("body:1").assertIsDisplayed()
        assertEquals(1, mounts[1])
        assertEquals(1, mounts[2])
        assertEquals(null, disposals[1])
        assertEquals(null, disposals[2])
    }

    @Test fun aNonAdjacentTapSlidesOnlyTheOldAndNewPage() {
        val state = showPager()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = 5, toolbarTitle = "Page five") }
        compose.mainClock.advanceTimeBy(128L)
        val viewport = compose.onNodeWithTag("main-page-pager").fetchSemanticsNode().boundsInRoot
        val old = compose.onNodeWithTag("body:1").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("body:5").fetchSemanticsNode().boundsInRoot
        assertTrue(old.width > 0f && old.width < viewport.width)
        assertTrue(target.left > viewport.left && target.left < viewport.right)
        val pixels = compose.onNodeWithTag("main-page-pager").captureToImage().toPixelMap()
        for (x in listOf(3, pixels.width / 2, pixels.width - 3)) {
            assertEquals("The two opaque pages should fill the viewport", Color.White, pixels[x, pixels.height - 3])
        }
        assertEquals(mapOf(1 to 1, 5 to 1), mounts)
        compose.mainClock.advanceTimeBy(800L)
        compose.onNodeWithTag("body:5").assertIsDisplayed()
        compose.onNodeWithTag("body:1").assertIsNotDisplayed()
        assertEquals(mapOf(1 to 1, 5 to 1), mounts)
    }

    @Test fun swipesSelectTheMatchingDestinationInBothDirections() {
        val state = showPager()
        compose.onNodeWithTag("main-page-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("body:2").assertIsDisplayed()
        assertEquals(2, state.value.selectedTopLevelItem)
        compose.onNodeWithTag("main-page-pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("body:1").assertIsDisplayed()
        assertEquals(listOf(2, 1), selections)
    }

    @Test fun rapidNonAdjacentTapsSettleOnTheLastRequest() {
        val state = showPager()
        compose.mainClock.autoAdvance = false
        for (id in listOf(5, 2, 4, 1, 3)) {
            compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = id, toolbarTitle = "Page $id") }
            compose.mainClock.advanceTimeBy(48L)
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onNodeWithTag("body:3").assertIsDisplayed()
        compose.onNodeWithTag("body:1").assertIsNotDisplayed()
        assertEquals(3, state.value.selectedTopLevelItem)
        mounts.values.forEach { assertEquals(1, it) }
    }

    @Test fun hiddenPagesKeepLocalStateAndReleaseBackHandlers() {
        val state = showPager()
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = 2) }
        compose.waitForIdle()
        pressBack()
        compose.runOnIdle {
            assertEquals(listOf(2), backCalls)
            assertEquals(Lifecycle.State.CREATED, lifecycles[1]?.currentState)
            assertTrue(requireNotNull(lifecycles[2]).currentState.isAtLeast(Lifecycle.State.STARTED))
            state.value = state.value.copy(selectedTopLevelItem = 1)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("token:1").assertTextEquals("mounted:1")
        pressBack()
        compose.runOnIdle { assertEquals(listOf(2, 1), backCalls) }
    }

    @Test fun dataUpdatesDoNotMoveOrRecreateTheSelectedPageOrHashDirectoryRows() {
        val files = object : AbstractList<File>() {
            override val size = 2_000
            override fun get(index: Int) = File("/directory/$index.ogg")
            override fun hashCode(): Int = error("Do not hash directory rows")
        }
        val state = showPager(files)
        val before = compose.onNodeWithTag("body:1").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(toolbarTitle = "Updated") }
        compose.onNodeWithTag("title:1").assertTextEquals("Updated")
        assertEquals(before, compose.onNodeWithTag("body:1").fetchSemanticsNode().boundsInRoot)
        assertEquals(1, mounts[1])
    }

    @Test fun disabledPagingDoesNotStealHorizontalInput() {
        val state = showPager(enabled = false)
        compose.onNodeWithTag("main-page-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, state.value.selectedTopLevelItem)
        assertTrue(selections.isEmpty())
    }

    @Test fun returningToAVisitedPageKeepsItsVerticalViewport() {
        val state = showPager()
        compose.onNodeWithTag("body:1").performScrollToIndex(40)
        val before = compose.onNodeWithTag("row:1:40").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = 2) }
        compose.waitForIdle()
        compose.onNodeWithTag("body:2").performScrollToIndex(60)
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = 1) }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("row:1:40").fetchSemanticsNode().boundsInRoot)
        assertEquals(1, mounts[1])
    }

    @Test fun retainedHiddenPagesFreezeSubscriptionsAndCatchUpOnReturn() {
        val state = showPager()
        compose.runOnIdle { state.value = state.value.copy(selectedTopLevelItem = 2) }
        compose.waitForIdle()
        compose.runOnIdle { updates.value = 7 }
        compose.waitUntil(5_000L) { displayedValues[2] == 7 }
        compose.runOnIdle {
            assertEquals(0, displayedValues[1])
            assertEquals(7, displayedValues[2])
            assertEquals(1, updates.subscriptionCount.value)
            state.value = state.value.copy(selectedTopLevelItem = 1)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(7, displayedValues[1])
            assertEquals(1, updates.subscriptionCount.value)
        }
    }

    @Test fun nestedHorizontalContentConsumesItsOwnDragBeforeThePager() {
        val state = showPager(nested = true)
        compose.onNodeWithTag("horizontal:1").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(requireNotNull(horizontalStates[1]).value > 0)
            assertEquals(1, state.value.selectedTopLevelItem)
            assertTrue(selections.isEmpty())
        }
        compose.onNodeWithTag("main-page-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(2, state.value.selectedTopLevelItem)
    }

    private fun showPager(files: List<File> = emptyList(), enabled: Boolean = true,
        nested: Boolean = false): MutableState<MainActivityScreenState> {
        val state = mutableStateOf(MainActivityScreenState(selectedTopLevelItem = 1, toolbarTitle = "Directory", files = files))
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 600.dp).background(Color.Magenta)) {
                MainTopLevelPager(state.value, listOf(1, 2, 3, 4, 5), onSelected = {
                    selections += it
                    state.value = state.value.copy(selectedTopLevelItem = it, toolbarTitle = "Page $it")
                }, userScrollEnabled = enabled) { snapshot, active ->
                    val id = snapshot.selectedTopLevelItem
                    val token = rememberSaveable { "mounted:${(mounts[id] ?: 0) + 1}" }
                    val value = updates.collectRetainedPageState(active).value
                    SideEffect { displayedValues[id] = value }
                    val horizontal = rememberScrollState()
                    SideEffect { horizontalStates[id] = horizontal }
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    DisposableEffect(id) {
                        mounts[id] = (mounts[id] ?: 0) + 1
                        lifecycles[id] = lifecycle
                        onDispose { disposals[id] = (disposals[id] ?: 0) + 1 }
                    }
                    BackHandler { backCalls += id }
                    LazyColumn(Modifier.fillMaxSize().background(Color.White).testTag("body:$id")) {
                        item { Text(snapshot.toolbarTitle, Modifier.testTag("title:$id")) }
                        item { Text(token, Modifier.testTag("token:$id")) }
                        if (nested) item {
                            Box(Modifier.fillMaxWidth().horizontalScroll(horizontal).testTag("horizontal:$id")) {
                                Box(Modifier.width(1200.dp).height(80.dp)) { Text("Horizontal content") }
                            }
                        }
                        items(100) { row ->
                            Text("Row $row", Modifier.fillMaxWidth().height(48.dp).testTag("row:$id:$row"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return state
    }
}
