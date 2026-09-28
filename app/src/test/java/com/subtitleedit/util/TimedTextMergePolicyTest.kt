package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedTextMergePolicyTest {
    private data class Cue(val start: Long, val end: Long, val text: String)

    private fun merge(cues: List<Cue>, smart: Boolean = true, limit: Int? = null): List<Cue> =
        TimedTextMergePolicy.merge(
            cues, maxGapMs = 5000, smart = smart, maxCharacters = limit,
            start = { it.start }, end = { it.end }, text = { it.text },
            combine = { left, right -> Cue(left.start, right.end, left.text + right.text) }
        )

    @Test
    fun countsLettersAndDigitsButIgnoresPunctuationAndWhitespace() {
        assertEquals(4, TimedTextMergePolicy.characterCount("你，好！  A 1\n"))
    }

    @Test
    fun smartMergeUsesAllSevenThresholdsAndRejectsLargerGap() {
        assertEquals(listOf(150, 200, 230, 250, 300, 350, 400), TimedTextMergePolicy.SMART_GAPS_MS)
        val cues = listOf(Cue(0, 10, "甲"), Cue(410, 420, "乙"), Cue(821, 831, "丙"))
        assertEquals(listOf("甲乙", "丙"), merge(cues).map { it.text })
    }

    @Test
    fun smartMergePrefersShorterCompetingCandidate() {
        val cues = listOf(
            Cue(0, 100, "12345678901234"),
            Cue(200, 300, "A"),
            Cue(400, 500, "B")
        )
        assertEquals(listOf("12345678901234", "AB"), merge(cues, limit = 15).map { it.text })
    }

    @Test
    fun lengthFilterRejectsOnlyTheTooLongPairAndKeepsLookingAhead() {
        val cues = listOf(
            Cue(0, 100, "1234567890123456789012345"),
            Cue(200, 300, "A"),
            Cue(400, 500, "B")
        )
        assertEquals(listOf("1234567890123456789012345", "AB"), merge(cues, limit = 25).map { it.text })
        assertEquals(listOf("1234567890123456789012345AB"), merge(cues, smart = false, limit = null).map { it.text })
    }
}
