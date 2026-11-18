package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.library.ListeningDay
import com.subtitleedit.library.ListeningDayStat
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slow reads exercise the real page while navigation disposes and remounts its composition. */
@RunWith(AndroidJUnit4::class)
class ListeningStatsTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstLoadKeepsTheFullCalendarGeometryWhileRealValuesArrive() {
        val today = ListeningDay.currentDate()
        val state = ListeningStatsPageState(today)
        val gate = CompletableDeferred<Unit>()
        val loaded = content(today, today.take(4).toInt())
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                    ListeningStatsScreen(0, state) { _, _, _, _ -> gate.await(); loaded }
                }
            }
        }

        compose.onNodeWithTag("stats-heatmap").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.stats_no_records)).assertDoesNotExist()
        val before = compose.onNodeWithTag("stats-heatmap").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertNull(state.content) }

        gate.complete(Unit)
        compose.waitUntil(5_000L) { state.content != null }
        compose.onNodeWithText("2m").assertIsDisplayed()
        val after = compose.onNodeWithTag("stats-heatmap").fetchSemanticsNode().boundsInRoot
        assertEquals("The calendar must not grow during the page entrance", before.height, after.height, 0.5f)
        assertEquals("Loaded metrics must not push the calendar down", before.top, after.top, 0.5f)
    }

    @Test fun returningToStatisticsReusesItsSnapshotAndOnlyRefreshesWhenRequested() {
        val today = ListeningDay.currentDate()
        val year = today.take(4).toInt()
        val state = ListeningStatsPageState(today).apply { granularity = StatsGranularity.WEEK }
        val visible = mutableStateOf(true)
        val refreshVersion = mutableIntStateOf(0)
        val refreshGate = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val loaded = content(today, year)
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                    if (visible.value) {
                        ListeningStatsScreen(refreshVersion.intValue, state) { _, requestedYear, mode, date ->
                            assertEquals(year, requestedYear)
                            assertEquals(StatsGranularity.WEEK, mode)
                            assertEquals(today, date)
                            if (reads.incrementAndGet() > 1) refreshGate.await()
                            loaded
                        }
                    } else {
                        Text("Another page", Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitUntil(5_000L) { state.content != null }
        compose.onNodeWithText("2m").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Another page").assertIsDisplayed()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("2m").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Re-entering an unchanged query must reuse the retained data", 1, reads.get())
            refreshVersion.intValue++
        }
        compose.waitUntil(5_000L) { reads.get() >= 2 }

        // The second read is still blocked, so these can only be the retained real values.
        compose.onNodeWithText("2m").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.stats_summary_week)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(loaded.copy(topItemsRange = StatsCalculator.currentRange(today, StatsGranularity.WEEK)), state.content)
            assertEquals(StatsGranularity.WEEK, state.granularity)
            assertEquals(year, state.selectedYear)
            assertEquals(today, state.selectedDate)
        }
        refreshGate.complete(Unit)
    }

    @Test fun calendarOnlyComposesVisibleWeeksAndRetainsItsHorizontalPositionOnReturn() {
        val today = ListeningDay.currentDate()
        val year = today.take(4).toInt() - 1
        val visible = mutableStateOf(true)
        val state = ListeningStatsPageState(today).apply {
            selectedYear = year
            selectedDate = "$year-01-01"
        }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                    if (visible.value) ListeningStatsScreen(0, state) { _, _, _, _ -> content(today, year) }
                    else Text("Another page")
                }
            }
        }
        compose.waitUntil(5_000L) { state.content != null }
        val days = SemanticsMatcher("Heatmap day") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("stats-heatmap-day:") == true
        }
        val composedDays = compose.onAllNodes(days).fetchSemanticsNodes().size
        assertTrue("Only viewport weeks should create clickable cells: $composedDays", composedDays in 1..200)
        compose.onNodeWithTag("stats-heatmap-day:$year-12-31").assertIsDisplayed()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals("$year-12-31", state.selectedDate) }
        compose.onNodeWithTag("stats-heatmap-scroll").performTouchInput { swipeRight() }
        compose.waitForIdle()
        val offset = state.heatmapScrollState(year).value
        assertTrue("The horizontal gesture should reveal earlier weeks", offset < state.heatmapScrollState(year).maxValue)
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Another page").assertIsDisplayed()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("stats-heatmap").assertIsDisplayed()
        compose.runOnIdle { assertEquals(offset, state.heatmapScrollState(year).value) }
    }

    @Test fun calendarStartsAtTheLatestMonthsBeforeTheFirstReadCompletes() {
        val today = ListeningDay.currentDate()
        val year = today.take(4).toInt() - 1
        val state = ListeningStatsPageState(today).apply {
            selectedYear = year
            selectedDate = "$year-12-31"
        }
        val gate = CompletableDeferred<Unit>()
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                    ListeningStatsScreen(0, state) { _, _, _, _ ->
                        gate.await()
                        content(today, year)
                    }
                }
            }
        }

        compose.onNodeWithTag("stats-heatmap").assertIsDisplayed()
        compose.onNodeWithText("12月").assertIsDisplayed()
        compose.onNodeWithText("1月").assertIsNotDisplayed()
        compose.runOnIdle { assertNull(state.content) }
        gate.complete(Unit)
    }

    @Test fun calendarHandlesRepeatedReverseGesturesResizeAndYearReplacement() {
        val today = ListeningDay.currentDate()
        val year = today.take(4).toInt() - 1
        val state = ListeningStatsPageState(today).apply { selectedYear = year; selectedDate = "$year-12-31" }
        val width = mutableStateOf(300.dp)
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(width.value, 600.dp)) {
                    ListeningStatsScreen(0, state) { _, requestedYear, _, _ -> content(today, requestedYear) }
                }
            }
        }
        compose.waitUntil(5_000L) { state.content != null }
        val calendar = compose.onNodeWithTag("stats-heatmap-scroll")
        repeat(4) { index ->
            calendar.performTouchInput { swipeRight(durationMillis = 100L) }
            compose.runOnIdle { width.value = if (index % 2 == 0) 360.dp else 300.dp }
            calendar.performTouchInput { swipeLeft(durationMillis = 100L) }
        }
        compose.runOnIdle { state.selectedYear = year - 1; state.selectedDate = "${year - 1}-12-31" }
        compose.waitUntil(5_000L) { state.content?.heatmapDays?.firstOrNull()?.date == "${year - 1}-01-01" }
        compose.onNodeWithTag("stats-heatmap-day:${year - 1}-12-31").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals("${year - 1}-12-31", state.selectedDate) }
        compose.onNodeWithTag("stats-heatmap-day:$year-12-31").assertDoesNotExist()
    }

    private fun content(today: String, year: Int): ListeningStatsContent {
        val (start, end) = StatsCalculator.yearRange(year, today)
        return ListeningStatsContent(
            stats = ListeningStatsIndex(listOf(ListeningDayStat(today, durationMs = 120_000L, trackCount = 7, playCount = 9))),
            availableYears = listOf(today.take(4).toInt(), year).distinct(),
            heatmapDays = ListeningDay.datesBetween(start, end).map { date ->
                StatsHeatmapDay(date, if (date == today) 120_000L else 0L, if (date == today) 1 else 0)
            }
        )
    }
}
