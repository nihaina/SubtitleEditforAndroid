package com.subtitleedit.feature.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.util.SharedFileCache
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserFileMetadataRefreshTest {
    @get:Rule val compose = createComposeRule()

    private class CachedOnlyFile : File("/cached-only/subtitle.srt") {
        override fun length(): Long = error("Rendering must use the cached size")
        override fun lastModified(): Long = error("Rendering must use the cached modification time")
        override fun isDirectory(): Boolean = error("Rendering must use the cached type")
        override fun exists(): Boolean = error("Rendering must not probe cached files")
        override fun canRead(): Boolean = error("Rendering must not probe cached files")
    }

    @Test
    fun cachedDirectoryRowsRenderAndRecomposeWithoutReadingFileAttributes() {
        val file = CachedOnlyFile()
        val metadata = mutableStateOf(BrowserFileMetadata(file, false, 4L, 1000L, revision = 1L))
        val selected = mutableStateOf(false)
        compose.setContent {
            SubtitleEditComposeTheme {
                BrowserFileRow(
                    file = file, metadata = metadata.value, relativePathRoot = null,
                    isSelected = selected.value, isSelectionMode = selected.value,
                    onClick = {}, onLongClick = {}
                )
            }
        }
        compose.onNodeWithText("4 B").assertIsDisplayed()
        compose.runOnIdle {
            selected.value = true
            metadata.value = metadata.value.copy(size = 8L, revision = 2L)
        }
        compose.onNodeWithText("8 B").assertIsDisplayed()
    }

    @Test
    fun navigatingBetweenCachedPathsNeverPublishesAnEmptyOrPreviousFileSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "metadata-navigation-${System.nanoTime()}").apply { check(mkdirs()) }
        val first = File(directory, "first.srt").apply { writeText("first") }
        val second = File(directory, "second.srt").apply { writeText("second") }
        try {
            val cached = listOf(first, second).associateWith { requireNotNull(SharedFileCache.attributes.get(it)) }
            val selected = mutableStateOf(first)
            val observations = mutableListOf<Pair<File, BrowserFileMetadata?>>()
            compose.setContent {
                val file = selected.value
                val metadata = rememberBrowserFileMetadata(file)
                SideEffect { observations += file to metadata }
                Text(metadata?.file?.name.orEmpty())
            }
            compose.waitForIdle()
            compose.runOnIdle { selected.value = second }
            compose.waitForIdle()
            compose.runOnIdle { selected.value = first }
            compose.waitForIdle()
            compose.runOnIdle {
                observations.forEach { (file, snapshot) ->
                    assertSame("Every composition starts from its own cached snapshot", cached[file], snapshot)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sharedFileInvalidationUpdatesAStablePathRowWithoutHostRefresh() {
        withSourceFile { file ->
            val refresh = mutableStateOf(0L)
            compose.setContent {
                SubtitleEditComposeTheme {
                    BrowserFileRow(
                        file = file, relativePathRoot = null,
                        isSelected = false, isSelectionMode = false,
                        onClick = {}, onLongClick = {}, metadataRefreshKey = refresh.value
                    )
                }
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("4 B").fetchSemanticsNodes().isNotEmpty()
            }
            file.writeText("changed-data")
            SharedFileCache.attributes.invalidate(file.absolutePath)
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("12 B").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("12 B").assertIsDisplayed()
        }
    }

    @Test
    fun explicitRefreshValidatesAnUnobservedStablePath() {
        withSourceFile { file ->
            val refresh = mutableStateOf(0L)
            compose.setContent {
                SubtitleEditComposeTheme {
                    BrowserFileRow(file = file, relativePathRoot = null,
                        isSelected = false, isSelectionMode = false, onClick = {}, onLongClick = {},
                        metadataRefreshKey = refresh.value)
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("4 B").fetchSemanticsNodes().isNotEmpty() }
            file.writeText("changed-data")
            compose.runOnIdle { refresh.value++ }
            compose.waitUntil(5000) { compose.onAllNodesWithText("12 B").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun refreshGetsANewPreviewRevisionEvenWhenSizeAndTimeMatch() {
        withSourceFile { file ->
            val refresh = mutableStateOf(0L)
            val observed = AtomicReference<BrowserFileMetadata?>()
            compose.setContent {
                val metadata = rememberBrowserFileMetadata(file, metadataRefreshKey = refresh.value)
                SideEffect { observed.set(metadata) }
                Text(metadata?.cacheKey.orEmpty(), Modifier.testTag("metadata-key"))
            }
            compose.waitUntil(5000) { observed.get() != null }
            val initial = compose.runOnIdle { observed.get()!! }
            file.writeText("same")
            check(file.setLastModified(initial.modifiedTimeMillis))
            SharedFileCache.attributes.invalidate(file.absolutePath)
            compose.waitUntil(5000) { observed.get()?.cacheKey != initial.cacheKey }
            compose.runOnIdle {
                assertEquals(initial.size, observed.get()!!.size)
                assertEquals(initial.modifiedTimeMillis, observed.get()!!.modifiedTimeMillis)
                assertNotEquals(initial.cacheKey, observed.get()!!.cacheKey)
            }
        }
    }

    private fun withSourceFile(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "metadata-refresh-${System.nanoTime()}.srt").apply { writeText("text") }
        try { block(file) } finally { file.delete() }
    }
}
