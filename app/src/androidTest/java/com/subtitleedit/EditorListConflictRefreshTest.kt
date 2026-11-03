package com.subtitleedit

import android.os.SystemClock
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.adapter.SubtitleAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the Activity delivers local time-color payloads after ordinary deletions. */
@RunWith(AndroidJUnit4::class)
class EditorListConflictRefreshTest {
    @Test fun clearingTheMiddleCueRefreshesBothNeighborsEvenWhenNeighborBindingIsDisabled() {
        val source = "1\n00:00:00,000 --> 00:00:01,000\nA\n\n" +
            "2\n00:00:01,500 --> 00:00:03,000\nB\n\n" +
            "3\n00:00:02,500 --> 00:00:04,000\nC\n"

        assertDeletionRefresh(source, 3, setOf(1), explicitDelete = false, checkSuccessorColor = true)
    }

    @Test fun separatedBlankCueRemovalsRefreshEveryBoundaryAtItsFinalPosition() {
        assertDeletionRefresh(source(12), 12, setOf(2, 3, 4, 8), explicitDelete = false)
    }

    @Test fun explicitDeletionUsesTheSameLocalTimeColorPayloads() {
        assertDeletionRefresh(source(12), 12, setOf(2, 3, 4, 8), explicitDelete = true)
    }

    private fun assertDeletionRefresh(
        source: String,
        originalCount: Int,
        deleted: Set<Int>,
        explicitDelete: Boolean,
        checkSuccessorColor: Boolean = false
    ) {
        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                invoke(activity, "parseContent", arrayOf(String::class.java, String::class.java), source, "test.srt")
            }
            await(scenario) { adapter(it).itemCount == originalCount }

            val conflicts = mutableSetOf<Long>()
            var fullRefreshes = 0
            var fullRowChanges = 0
            var remainingIds = emptyList<Long>()
            var expectedBoundaryIds = emptySet<Long>()
            lateinit var oldRows: List<com.subtitleedit.model.SubtitleEntry>
            var heldSuccessor: SubtitleAdapter.SubtitleViewHolder? = null

            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                val adapter = adapter(activity)
                val recycler = activity.findViewById<RecyclerView>(R.id.rvSubtitles)
                oldRows = model.subtitleEntries.toList()
                remainingIds = oldRows.filterIndexed { index, _ -> index !in deleted }.map { it.stableId }
                expectedBoundaryIds = deleted.flatMap { listOf(it - 1, it + 1) }
                    .filter { it in oldRows.indices && it !in deleted }
                    .mapTo(mutableSetOf()) { oldRows[it].stableId }

                if (checkSuccessorColor) {
                    // A detached holder exercises the actual payload binder without depending on scroll position.
                    heldSuccessor = adapter.onCreateViewHolder(recycler, 0).also { holder ->
                        adapter.onBindViewHolder(holder, 2)
                        assertEquals(ContextCompat.getColor(activity, R.color.error),
                            holder.itemView.findViewById<TextView>(R.id.tvStartTime).currentTextColor)
                    }
                }
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { fullRefreshes++ }

                    override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                        if (payload == null) fullRowChanges += itemCount
                        if (payload != SubtitleAdapter.PAYLOAD_TIME_CONFLICT) return
                        for (position in positionStart until positionStart + itemCount) {
                            val id = adapter.currentList[position].stableId
                            conflicts += id
                            if (heldSuccessor != null && id == oldRows[2].stableId) {
                                adapter.onBindViewHolder(heldSuccessor!!, position,
                                    mutableListOf<Any>(SubtitleAdapter.PAYLOAD_TIME_CONFLICT))
                            }
                        }
                    }
                })
                val command = if (explicitDelete) EditorCommand.Delete(deleted)
                    else EditorCommand.UpdateTexts(deleted.map { it to "" })
                val change = model.commitListEdit(command, emptySet())!!
                if (explicitDelete) {
                    invoke(activity, "syncAfterDelete", arrayOf(Set::class.java, EditorListChange::class.java), deleted, change)
                } else {
                    invoke(activity, "renderCommittedListEdit",
                        arrayOf(EditorListChange::class.java, Boolean::class.javaPrimitiveType!!), change, false)
                }
            }

            await(scenario) { activity ->
                adapter(activity).currentList.map { it.stableId } == remainingIds &&
                    conflicts.containsAll(expectedBoundaryIds)
            }
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                assertEquals(expectedBoundaryIds, conflicts)
                assertEquals(0, fullRefreshes)
                assertEquals(0, fullRowChanges)
                oldRows.filterIndexed { index, _ -> index !in deleted }.forEachIndexed { index, row ->
                    assertSame(row, model.subtitleEntries[index])
                }
                assertEquals(model.originalFileContent, model.sourceViewContent)
                assertEquals(model.subtitleEntries, model.document.value.entries)
                if (checkSuccessorColor) {
                    assertEquals(ContextCompat.getColor(activity, R.color.primary),
                        heldSuccessor!!.itemView.findViewById<TextView>(R.id.tvStartTime).currentTextColor)
                }
            }
        }
    }

    private fun adapter(activity: EditorActivity) =
        activity.findViewById<RecyclerView>(R.id.rvSubtitles).adapter as SubtitleAdapter

    private fun await(scenario: ActivityScenario<EditorActivity>, condition: (EditorActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var complete = false
            scenario.onActivity { complete = condition(it) }
            if (complete) return
            Thread.sleep(20L)
        }
        fail("Expected list submission and time-color payloads were not delivered")
    }

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg arguments: Any?) =
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *arguments)

    private fun source(size: Int) = buildString {
        repeat(size) { index ->
            append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n")
        }
    }
}
