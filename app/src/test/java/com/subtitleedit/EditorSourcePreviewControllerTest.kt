package com.subtitleedit

import com.subtitleedit.editor.EditorSourcePreviewController
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorSourcePreviewControllerTest {
    @Test
    fun scheduleDebouncesAndPublishesParsedDocument() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val generation = AtomicLong(7L)
        val parsed = CopyOnWriteArrayList<String>()
        val controller = EditorSourcePreviewController(
            scope = scope,
            isSourceViewMode = { true },
            suppressSourceViewChanges = { false },
            editGeneration = generation::get,
            currentFormat = { SubtitleParser.SubtitleFormat.SRT },
            snapshotContent = { "source" },
            onParsed = { _, content, _ -> parsed += content },
            debounceMillis = 1L,
            parseDocument = { content, format ->
                SubtitleDocument(format, listOf(SubtitleEntry(text = content)))
            }
        )

        controller.schedule()
        withTimeout(1000L) {
            while (parsed.isEmpty()) delay(5L)
        }

        assertEquals(listOf("source"), parsed)
        controller.cancel()
        scope.cancel()
    }

    @Test
    fun parsedResultIsDroppedWhenGenerationChangesDuringParsing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val generation = AtomicLong(3L)
        val parsingStarted = CompletableDeferred<Unit>()
        val releaseParsing = CompletableDeferred<SubtitleDocument>()
        var callbackCount = 0
        val controller = EditorSourcePreviewController(
            scope = scope,
            isSourceViewMode = { true },
            suppressSourceViewChanges = { false },
            editGeneration = generation::get,
            currentFormat = { SubtitleParser.SubtitleFormat.SRT },
            snapshotContent = { "source" },
            onParsed = { _, _, _ -> callbackCount++ },
            debounceMillis = 1L,
            parseDocument = { _, format ->
                parsingStarted.complete(Unit)
                releaseParsing.await().copy(format = format)
            }
        )

        controller.schedule()
        withTimeout(1000L) { parsingStarted.await() }
        generation.set(4L)
        releaseParsing.complete(SubtitleDocument(SubtitleParser.SubtitleFormat.SRT, emptyList()))
        delay(50L)

        assertEquals(0, callbackCount)
        controller.cancel()
        scope.cancel()
    }
}
