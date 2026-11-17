package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.library.ListeningDay
import com.subtitleedit.library.ListeningSessionRecord
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.PlaybackHistoryEntry
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Long sections must expose every row without composing the whole section together. */
@RunWith(AndroidJUnit4::class)
class ListeningStatsLazyListTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun insightRowsStayLazyWhenSwitchingTabsAndShowingMore() {
        val loaded = showStatistics()
        val page = compose.onNodeWithTag("listening-stats-page")
        val recentFirst = "stats-insight-row:${loaded.recentHistory.first().path}"
        page.performScrollToNode(hasTestTag(recentFirst))
        compose.onNodeWithTag(recentFirst).assertIsDisplayed()
        assertComposedRowCount("stats-insight-row:", maximum = 19)

        val neverPlayedLabel = compose.activity.getString(R.string.stats_section_never_played)
        page.performScrollToNode(hasText(neverPlayedLabel))
        compose.onNodeWithText(neverPlayedLabel).performClick()
        val unplayedFirst = "stats-insight-row:${loaded.neverPlayed.first().path}"
        page.performScrollToNode(hasTestTag(unplayedFirst))
        compose.onNodeWithTag(unplayedFirst).assertIsDisplayed()
        // Even the first page has twenty rows: it must not be one eager Column.
        assertComposedRowCount("stats-insight-row:", maximum = 19)

        val showMoreLabel = compose.activity.getString(R.string.stats_show_more)
        page.performScrollToNode(hasText(showMoreLabel))
        compose.onNodeWithText(showMoreLabel).performClick()
        val nextPageRow = "stats-insight-row:${loaded.neverPlayed[20].path}"
        page.performScrollToNode(hasTestTag(nextPageRow))
        compose.onNodeWithTag(nextPageRow).assertIsDisplayed()
        assertComposedRowCount("stats-insight-row:", maximum = 19)
    }

    @Test fun longTimelineComposesOnlyNearbyRowsAndCanReachItsLastSession() {
        val loaded = showStatistics()
        val page = compose.onNodeWithTag("listening-stats-page")
        val first = "stats-timeline-row:${loaded.sessions.first().id}"
        val last = "stats-timeline-row:${loaded.sessions.last().id}"
        page.performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).assertIsDisplayed()
        assertComposedRowCount("stats-timeline-row:", maximum = 30)

        page.performScrollToNode(hasTestTag(last))
        compose.onNodeWithTag(last).assertIsDisplayed()
        assertComposedRowCount("stats-timeline-row:", maximum = 30)
    }

    @Test fun timestampsFollowTheSystemTimeZoneAfterRefresh() {
        val originalTimeZone = TimeZone.getDefault()
        val refreshVersion = mutableIntStateOf(0)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val loaded = showStatistics(refreshVersion)
            val page = compose.onNodeWithTag("listening-stats-page")
            val session = loaded.sessions.first()
            val sessionTag = "stats-timeline-row:${session.id}"
            page.performScrollToNode(hasTestTag(sessionTag))
            compose.onNodeWithTag(sessionTag).assert(hasAnyDescendant(hasText(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.startAtMs))
            )))

            compose.runOnIdle {
                TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
                refreshVersion.intValue++
            }
            compose.onNodeWithTag(sessionTag).assert(hasAnyDescendant(hasText(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.startAtMs))
            )))
            val history = loaded.recentHistory.first()
            val historyTag = "stats-insight-row:${history.path}"
            page.performScrollToNode(hasTestTag(historyTag))
            compose.onNodeWithTag(historyTag, useUnmergedTree = true).assert(hasAnyDescendant(hasText(
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(history.playedAtMs))
            )))
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    private fun assertComposedRowCount(prefix: String, maximum: Int) {
        val rows = SemanticsMatcher("Row tag starts with $prefix") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }
        val count = compose.onAllNodes(rows).fetchSemanticsNodes().size
        assertTrue("Only nearby rows should be composed, found $count", count in 1..maximum)
    }

    private fun showStatistics(refreshVersion: MutableIntState = mutableIntStateOf(0)): ListeningStatsContent {
        val today = ListeningDay.currentDate()
        val state = ListeningStatsPageState(today)
        val fixturePrefix = "${compose.activity.cacheDir.path}/stats-lazy-fixture"
        val now = System.currentTimeMillis()
        val loaded = ListeningStatsContent(
            availableYears = listOf(today.take(4).toInt()),
            recentHistory = (1..20).map { index ->
                PlaybackHistoryEntry(
                    id = index.toLong(),
                    path = "$fixturePrefix/recent-$index.wav",
                    title = "Recent $index",
                    playedAtMs = now - index * 60_000L,
                )
            },
            neverPlayed = (1..100).map { index ->
                MediaLibraryTrack(
                    path = "$fixturePrefix/unplayed-$index.wav",
                    title = "Unplayed $index",
                )
            },
            sessions = (1..200).map { index ->
                val start = now - index * 60_000L
                ListeningSessionRecord(
                    id = index.toLong(),
                    mediaPath = "$fixturePrefix/session-$index.wav",
                    title = "Session $index",
                    artist = "",
                    album = "",
                    isVideo = false,
                    listeningDate = today,
                    startAtMs = start,
                    lastActiveAtMs = start + 30_000L,
                    durationMs = 30_000L,
                    trackCount = 1,
                )
            },
        )
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 480.dp)) {
                    ListeningStatsScreen(refreshVersion.intValue, state) { _, _, _, _ -> loaded }
                }
            }
        }
        compose.waitUntil(5_000L) { state.content != null }
        return loaded
    }
}
