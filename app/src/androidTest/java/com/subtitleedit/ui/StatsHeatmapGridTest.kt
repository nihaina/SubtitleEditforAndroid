package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.ListeningDay
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(InternalComposeTracingApi::class)
@RunWith(AndroidJUnit4::class)
class StatsHeatmapGridTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun releaseTracer() { Composer.setTracer(null) }

    @Test fun daysMatchOriginalPixelsAtFractionalDensitiesAndInBothDirections() {
        val density = mutableStateOf(1f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val dark = mutableStateOf(false)
        val selected = mutableStateOf("2024-02-04")
        val columns = StatsCalculator.buildHeatmapColumns(days("2024-02-01", "2024-03-10"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density.value), LocalLayoutDirection provides direction.value) {
                SubtitleEditComposeTheme(darkTheme = dark.value) {
                    Column {
                        Box(Modifier.testTag("current")) { StatsHeatmapGrid(columns, columns.indices, selected.value) {} }
                        Box(Modifier.testTag("original")) { OriginalHeatmapGrid(columns, columns.indices, selected.value) }
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.25f, 1.5f, 2.75f, 3f)) {
            for (layoutDirection in LayoutDirection.entries) {
                compose.runOnIdle { density.value = scale; direction.value = layoutDirection }
                assertMatchingPixels()
            }
        }
        // Replacing the selected border must preserve both its old and new cell rendering.
        compose.runOnIdle { selected.value = "2024-03-09" }
        assertMatchingPixels()
        // A retained grid must pick up the new palette without resetting the selection.
        compose.runOnIdle { dark.value = true }
        assertMatchingPixels()
    }

    @Test fun selectingADayOnlyRecomposesTheChangedCellsAndRetainsItsClickNode() {
        val selected = mutableStateOf("2024-02-02")
        val columns = StatsCalculator.buildHeatmapColumns(days("2024-02-01", "2024-03-10"))
        val cellCalls = AtomicInteger()
        val tracer = object : CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.substringBefore(" (").endsWith(".StatsHeatmapCell")) cellCalls.incrementAndGet()
            }
            override fun traceEventEnd() = Unit
        }
        compose.setContent {
            SubtitleEditComposeTheme { StatsHeatmapGrid(columns, columns.indices, selected.value) { selected.value = it } }
        }
        compose.waitForIdle()
        compose.runOnIdle { Composer.setTracer(tracer) }
        compose.onNodeWithTag("stats-heatmap-day:2024-02-15").performClick()
        compose.runOnIdle {
            assertEquals("2024-02-15", selected.value)
            assertEquals("Only the previous and new selection should execute", 2, cellCalls.get())
        }
    }

    @Test fun viewportReplacementAndUpdatedValuesKeepEveryDayInTheCorrectPosition() {
        val days = mutableStateOf(days("2024-01-01", "2024-12-31"))
        val visible = mutableStateOf(0..5)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                SubtitleEditComposeTheme {
                    val columns = StatsCalculator.buildHeatmapColumns(days.value)
                    Column {
                        Box(Modifier.testTag("current")) { StatsHeatmapGrid(columns, visible.value, "2024-06-14") {} }
                        Box(Modifier.testTag("original")) { OriginalHeatmapGrid(columns, visible.value, "2024-06-14") }
                    }
                }
            }
        }
        // Stable date keys must handle disjoint visible ranges, rapid reversal, and new data.
        for (range in listOf(22..27, 0..5, 46..52, 10..15)) {
            compose.runOnIdle { visible.value = range }
            assertMatchingPixels()
        }
        compose.runOnIdle { days.value = days.value.map { it.copy(level = (it.level + 2) % 5) } }
        assertMatchingPixels()
    }

    /** Interleaved debug-device timings are diagnostic data, not a timing-sensitive assertion. */
    @Test fun profileInterleavedGridCompositionAndMeasurement() {
        val columns = StatsCalculator.buildHeatmapColumns(days("2024-01-01", "2024-12-31"))
        val iteration = mutableStateOf(-1)
        val optimized = mutableStateOf(true)
        val samples = JSONArray()
        var compositionNs = 0L
        var measureNs = 0L
        compose.setContent {
            SubtitleEditComposeTheme {
                key(iteration.value) {
                    Box(Modifier.width(332.dp).horizontalScroll(ScrollState(Int.MAX_VALUE))) {
                        Box(Modifier.layout { measurable, constraints ->
                            val start = System.nanoTime()
                            val placeable = measurable.measure(constraints)
                            measureNs += System.nanoTime() - start
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }) {
                            val start = System.nanoTime()
                            if (optimized.value) StatsHeatmapGrid(columns, 31..52, "2024-12-31") {}
                            else OriginalHeatmapGrid(columns, 31..52, "2024-12-31")
                            compositionNs += System.nanoTime() - start
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        try {
            repeat(40) { pair ->
                val order = if (pair % 2 == 0) listOf(false, true) else listOf(true, false)
                for (current in order) {
                    compose.runOnIdle {
                        compositionNs = 0L; measureNs = 0L
                        optimized.value = current
                        iteration.value++
                    }
                    compose.waitForIdle()
                    compose.runOnIdle {
                        samples.put(JSONObject().put("pair", pair).put("optimized", current)
                            .put("composition_ms", compositionNs / 1e6).put("measure_ms", measureNs / 1e6))
                    }
                }
            }
        } finally {
            Composer.setTracer(null)
            File(compose.activity.cacheDir, "heatmap-grid-profile.json").writeText(samples.toString(2))
        }
    }

    private fun days(start: String, end: String) = ListeningDay.datesBetween(start, end).mapIndexed { index, date ->
        StatsHeatmapDay(date, index * 60_000L, index % 5)
    }

    private fun assertMatchingPixels() {
        val current = compose.onNodeWithTag("current").captureToImage().toPixelMap()
        val original = compose.onNodeWithTag("original").captureToImage().toPixelMap()
        assertEquals(original.width, current.width)
        assertEquals(original.height, current.height)
        var differences = 0
        for (y in 0 until current.height) for (x in 0 until current.width) {
            val a = current[x, y]; val b = original[x, y]
            if (kotlin.math.abs(a.red - b.red) > 0.02f || kotlin.math.abs(a.green - b.green) > 0.02f ||
                kotlin.math.abs(a.blue - b.blue) > 0.02f || kotlin.math.abs(a.alpha - b.alpha) > 0.02f) differences++
        }
        assertTrue("Different pixels: $differences/${current.width * current.height}",
            differences.toDouble() / (current.width * current.height) < 0.003)
    }
}

/** Frozen Row / week Box / Column implementation used before the flat layout. */
@Composable
private fun OriginalHeatmapGrid(columns: List<List<StatsHeatmapDay?>>, visible: IntRange, selectedDate: String) {
    val cellSize = 13.dp
    val cellGap = 3.dp
    Row(horizontalArrangement = Arrangement.spacedBy(cellGap)) {
        columns.forEachIndexed { index, week ->
            Box(Modifier.width(cellSize).height(cellSize * 7 + cellGap * 6)) {
                if (index in visible) Column(verticalArrangement = Arrangement.spacedBy(cellGap)) {
                    week.forEach { day ->
                        if (day == null) Spacer(Modifier.size(cellSize)) else {
                            val shape = RoundedCornerShape(3.dp)
                            Box(Modifier.size(cellSize).testTag("stats-heatmap-day:${day.date}")
                                .clip(shape).background(heatmapColor(day.level)).then(
                                if (day.date == selectedDate) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                                else if (day.level == 0) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
                                else Modifier
                            ).clickable {})
                        }
                    }
                }
            }
        }
    }
}
