package com.subtitleedit.feature.ui

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MainActivity
import com.subtitleedit.R
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only inclusive composition timings, kept separate from uninstrumented frame measurements. */
@OptIn(InternalComposeTracingApi::class)
@RunWith(AndroidJUnit4::class)
class MainNavigationCompositionProfileTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun profileFirstAndRepeatedNavigation() {
        val timings = linkedMapOf<String, LongArray>()
        val stack = ArrayDeque<Pair<String, Long>>()
        val tracer = object : CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                stack.addLast(info.substringBefore(" (") to System.nanoTime())
            }
            override fun traceEventEnd() {
                val (name, start) = stack.removeLast()
                if (!name.startsWith("com.subtitleedit.")) return
                val elapsed = System.nanoTime() - start
                val values = timings.getOrPut(name) { LongArray(3) }
                values[0]++
                values[1] += elapsed
                values[2] = maxOf(values[2], elapsed)
            }
        }
        val output = JSONArray()
        try {
            compose.waitForIdle()
            assumeTrue("This diagnostic requires the existing player-mode tabs",
                compose.onAllNodesWithText(compose.activity.getString(R.string.nav_library))
                    .fetchSemanticsNodes().isNotEmpty())
            repeat(2) { round ->
                for (label in listOf(R.string.nav_library, R.string.media_categories_navigation,
                    R.string.nav_stats, R.string.menu_main_title_02, R.string.nav_directory)) {
                    compose.runOnIdle { timings.clear(); stack.clear(); Composer.setTracer(tracer) }
                    val text = compose.activity.getString(label)
                    compose.onNodeWithText(text).performClick()
                    compose.waitForIdle()
                    compose.runOnIdle {
                        Composer.setTracer(null)
                        val rows = JSONArray()
                        timings.entries.sortedByDescending { it.value[1] }.forEach { (name, values) ->
                            rows.put(JSONObject().put("name", name).put("calls", values[0])
                                .put("total_ms", values[1] / 1e6).put("max_ms", values[2] / 1e6))
                        }
                        output.put(JSONObject().put("round", round).put("page", text).put("timings", rows))
                    }
                }
            }
        } finally {
            Composer.setTracer(null)
            File(compose.activity.cacheDir, "navigation-composition-profile.json").writeText(output.toString(2))
        }
    }
}
