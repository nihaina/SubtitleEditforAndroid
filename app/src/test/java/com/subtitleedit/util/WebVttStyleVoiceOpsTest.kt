package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttStyleOps
import com.subtitleedit.util.subtitle.WebVttVoiceOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVttStyleVoiceOpsTest {
    private fun document(texts: List<String> = listOf("<v Alice>Hello</v>", "World")): SubtitleDocument =
        SubtitleDocument(
            format = SubtitleParser.SubtitleFormat.VTT,
            entries = texts.mapIndexed { index, text -> SubtitleEntry(index = index + 1, text = text) },
            header = "WEBVTT\n\nSTYLE\n" +
                "::cue(.emphasis) { font-weight: bold; font-style: italic; }\n" +
                "::cue(.red) { color:rgb(255,0,0); }"
        )

    @Test
    fun listsStylesAndCueUsage() {
        val styles = WebVttStyleOps.list(document(listOf("<c.emphasis>One</c>", "<c.red>Two</c>")))
        assertEquals(listOf("emphasis", "red"), styles.map { it.name })
        assertEquals(1, styles.first().usageCount)
        assertEquals("bold", styles.first().property("font-weight"))
    }

    @Test
    fun appliesStyleInsideVoiceWithoutBreakingVoiceWrapper() {
        val updated = WebVttStyleOps.apply(document(), setOf(0), "red")
        assertEquals("<v Alice><c.red>Hello</c></v>", updated.entries[0].text)
    }

    @Test
    fun renameAndDeleteCanPreserveOrRemoveCueReferences() {
        val renamed = WebVttStyleOps.rename(document(listOf("<c.emphasis>A</c>")), "emphasis", "strong")
        assertTrue(renamed.header.contains("::cue(.strong)"))
        assertEquals("<c.strong>A</c>", renamed.entries.single().text)
        val removed = WebVttStyleOps.delete(renamed, "strong", removeCueReferences = true)
        assertTrue("::cue(.strong)" !in removed.header)
        assertEquals("A", removed.entries.single().text)
    }

    @Test
    fun removingOuterNestedClassKeepsInnerScopeBalanced() {
        val source = document(listOf("<c.emphasis><c.red>A</c></c>"))
        val result = WebVttStyleOps.remove(source, setOf(0), "emphasis")
        assertEquals("<c.red>A</c>", result.entries.single().text)
    }

    @Test
    fun voiceListAndSetRemoveNestedVoiceTags() {
        val source = document(listOf("<v Alice><i>Hello</i></v>", "<v Bob>Bye</v>"))
        assertEquals(listOf("Alice", "Bob"), WebVttVoiceOps.list(source))
        assertEquals("Alice", WebVttVoiceOps.get(source.entries[0]))
        val changed = WebVttVoiceOps.set(source, setOf(0), "Carol")
        assertEquals("<v Carol><i>Hello</i></v>", changed.entries[0].text)
        val removed = WebVttVoiceOps.remove(changed, setOf(0))
        assertEquals("<i>Hello</i>", removed.entries[0].text)
    }

    @Test
    fun upsertAddsNewRuleInsideExistingStyleBlock() {
        val source = document().copy(header = "WEBVTT\n\nSTYLE\n::cue(.old) { color:red; }\n\nNOTE keep me")
        val result = WebVttStyleOps.upsert(source, "new", mapOf("font-style" to "italic"))
        assertTrue(result.header.indexOf("::cue(.new)") < result.header.indexOf("NOTE keep me"))
        assertTrue(result.header.contains("::cue(.new) { font-style:italic; }"))
    }
}
