package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Diagnostic timings are printed, never used as machine-dependent pass/fail limits. */
class EditorEditPerformanceTest {
    @Test fun reportSmallAndLargeListEditCosts() {
        repeat(15) { measureEdits(50, report = false) }
        listOf(50, 5_000, 20_000).forEach { size -> repeat(3) { measureEdits(size, report = true) } }
    }

    @Test fun reportSmallAndLargeHistoryCosts() {
        repeat(10) { measureHistory(50, report = false) }
        listOf(50, 5_000, 20_000).forEach { size -> repeat(3) { measureHistory(size, report = true) } }
    }

    private fun measureHistory(size: Int, report: Boolean) {
        val source = buildString {
            repeat(size) { index ->
                append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n")
            }
        }
        val model = EditorViewModel().apply {
            loadSubtitleContent(source, "bench.srt")
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
        }
        val middle = size / 2
        model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries[middle].stableId, "edited"), emptySet())!!
        measure(size, "undo-text", report) { moveHistory(model, undo = true) }
        measure(size, "redo-text", report) { moveHistory(model, undo = false) }
        model.commitListEdit(EditorCommand.Insert(middle,
            listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "inserted"))), emptySet())!!
        measure(size, "undo-insert", report) { moveHistory(model, undo = true) }
        measure(size, "redo-insert", report) { moveHistory(model, undo = false) }
        assertEquals(size + 1, model.subtitleEntries.size)
    }

    private fun moveHistory(model: EditorViewModel, undo: Boolean) {
        val apply: (EditorEditHistory.Operation, Boolean) -> Unit = { command, reversed ->
            model.executeHistoryCommand(command, reversed)
        }
        check(if (undo) model.undo(false, apply) else model.redo(false, apply))
    }

    private fun measureEdits(size: Int, report: Boolean) {
        val source = buildString {
            repeat(size) { index ->
                append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n")
            }
        }
        val model = EditorViewModel().apply {
            loadSubtitleContent(source, "bench.srt")
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
        }
        val middle = size / 2
        measure(size, "text", report) {
            model.commitListEdit(EditorCommand.UpdateTexts(listOf(middle to "edited"), false), emptySet())
        }
        measure(size, "insert", report) {
            model.commitListEdit(EditorCommand.Insert(middle, listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "inserted"))), emptySet())
        }
        measure(size, "delete", report) {
            model.commitListEdit(EditorCommand.Delete(setOf(middle)), emptySet())
        }
        assertEquals(size, model.subtitleEntries.size)
    }

    private fun measure(size: Int, operation: String, report: Boolean, action: () -> Unit) {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val beforeBytes = bean?.getThreadAllocatedBytes(thread) ?: 0L
        val started = System.nanoTime()
        action()
        val elapsed = System.nanoTime() - started
        val bytes = (bean?.getThreadAllocatedBytes(thread) ?: 0L) - beforeBytes
        if (report) println("EDIT_COST rows=$size op=$operation elapsedNs=$elapsed allocatedBytes=$bytes")
    }
}
