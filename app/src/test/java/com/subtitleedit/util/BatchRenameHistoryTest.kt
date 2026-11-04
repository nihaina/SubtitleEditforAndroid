package com.subtitleedit.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatchRenameHistoryTest {
    @Test
    fun rulesWhoseReplacementSuppliesTheNameRemainReusableAfterSuccessfulRename() {
        val sources = listOf(File("first.txt"), File("second.srt"))
        val rules = listOf(
            BatchRename.Options(pattern = " ", find = " ", replacement = "renamed"),
            BatchRename.Options(pattern = "", find = "^", replacement = "renamed", regexEnabled = true)
        )
        rules.forEach { options ->
            assertEquals(listOf("renamed.txt", "renamed.srt"), BatchRename.preview(sources, options).map { it.target.name })
            val recorded = BatchRenameHistory.record(emptyList(), options)
            assertEquals(recorded, BatchRenameHistory.decode(BatchRenameHistory.encode(recorded)))
        }
    }

    @Test
    fun fullRuleRoundTripPreservesTokensUnicodeCapturesAndLargeStartNumber() {
        val options = BatchRename.Options(
            pattern = "字幕_${BatchRename.FILE_NAME_TOKEN}-${BatchRename.NUMBER_TOKEN}",
            extension = "字幕.srt",
            numberWidth = 12,
            startNumber = 9_007_199_254_740_993L,
            find = "(第)([0-9]+)(集)",
            replacement = "\$2-\$1\$3",
            regexEnabled = true
        )
        val entries = listOf(BatchRenameHistory.Entry("original-id", options))

        assertEquals(entries, BatchRenameHistory.decode(BatchRenameHistory.encode(entries)))
    }

    @Test
    fun keepingAnExtensionAndRemovingItRemainDifferentRules() {
        val preserved = BatchRename.Options(extension = null)
        val removed = preserved.copy(extension = "")
        val changed = preserved.copy(extension = "srt")
        var entries = BatchRenameHistory.record(emptyList(), preserved)
        entries = BatchRenameHistory.record(entries, removed)
        entries = BatchRenameHistory.record(entries, changed)

        val restored = BatchRenameHistory.decode(BatchRenameHistory.encode(entries))

        assertEquals(listOf(changed, removed, preserved), restored.map { it.options })
        assertEquals(listOf("srt", "", null), restored.map { it.options.extension })
    }

    @Test
    fun repeatedSuccessfulRulesMoveToTheFrontWithoutChangingTheirIdentity() {
        val first = BatchRename.Options(pattern = "first${BatchRename.NUMBER_TOKEN}")
        val second = first.copy(pattern = "second${BatchRename.NUMBER_TOKEN}")
        val initial = BatchRenameHistory.record(emptyList(), first)
        val firstId = initial.single().id
        val both = BatchRenameHistory.record(initial, second)
        val revisited = BatchRenameHistory.record(both, first)

        assertEquals(listOf(first, second), revisited.map { it.options })
        assertEquals(firstId, revisited.first().id)
        assertEquals(both.first().id, revisited.last().id)
    }

    @Test
    fun recordingKeepsTheFiftyMostRecentRulesAndRemovalUsesStableIdentity() {
        var entries = emptyList<BatchRenameHistory.Entry>()
        repeat(55) { index ->
            entries = BatchRenameHistory.record(entries, BatchRename.Options(pattern = "rule-$index"))
        }
        assertEquals(50, entries.size)
        assertEquals("rule-54", entries.first().options.pattern)
        assertEquals("rule-5", entries.last().options.pattern)
        val removed = entries[20]
        val retained = BatchRenameHistory.remove(entries, removed.id)

        assertEquals(49, retained.size)
        assertFalse(retained.any { it.id == removed.id })
        assertEquals(entries.filterNot { it.id == removed.id }, retained)
        assertEquals(retained, BatchRenameHistory.remove(retained, "missing"))
    }

    @Test
    fun malformedBackupEntriesAreSkippedWithoutLosingValidRules() {
        val valid = JSONArray(BatchRenameHistory.encode(listOf(
            BatchRenameHistory.Entry("valid", BatchRename.Options())
        ))).getJSONObject(0)
        fun changed(id: String, name: String, value: Any): JSONObject = JSONObject(valid.toString()).apply {
            put("id", id)
            getJSONObject("options").put(name, value)
        }
        val array = JSONArray()
            .put(JSONObject().put("id", "missing-options"))
            .put(changed("bad-width", "numberWidth", 0))
            .put(changed("bad-start", "startNumber", -1))
            .put(changed("fraction", "startNumber", 1.5))
            .put(changed("overflow", "startNumber", "9223372036854775808"))
            .put(changed("bad-extension", "extension", "bad/name"))
            .put(changed("bad-pattern", "pattern", JSONObject()))
            .put(changed("bad-find", "find", JSONObject()))
            .put(changed("bad-flag", "regexEnabled", "yes"))
            .put(JSONObject(valid.toString()).put("id", ""))
            .put(changed("bad-regex", "find", "[").apply {
                getJSONObject("options").put("regexEnabled", true)
            })
            .put(42)
            .put(valid)
            .put(JSONObject(valid.toString()).put("id", "duplicate-rule"))
            .put(changed("valid", "pattern", "duplicate-id"))
            .put(changed("last", "pattern", "last"))

        val restored = BatchRenameHistory.decode(array.toString())

        assertEquals(listOf("valid", "last"), restored.map { it.id })
        assertEquals(BatchRename.Options(), restored.first().options)
    }

    @Test
    fun missingOptionalFieldsUseDefaultsAndDoNotTurnNullExtensionIntoText() {
        val array = JSONArray().put(JSONObject()
            .put("id", "old")
            .put("options", JSONObject().put("pattern", BatchRename.FILE_NAME_TOKEN)))

        assertEquals(
            listOf(BatchRenameHistory.Entry("old", BatchRename.Options(pattern = BatchRename.FILE_NAME_TOKEN))),
            BatchRenameHistory.decode(array.toString())
        )
    }

    @Test
    fun malformedTopLevelHistoryProducesAnEmptyList() {
        listOf("", "null", "{}", "broken", "[null,1,\"text\"]").forEach {
            assertTrue(BatchRenameHistory.decode(it).isEmpty())
        }
    }
}
