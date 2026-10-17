package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedTextMergePolicyTest {
    private data class Cue(val start: Long, val end: Long, val text: String)

    private fun merge(
        cues: List<Cue>,
        smart: Boolean = true,
        limit: Int? = null,
        splitPunctuation: Boolean = true,
        maxGapMs: Int = 500
    ): List<Cue> =
        TimedTextMergePolicy.merge(
            cues, maxGapMs = maxGapMs, smart = smart, maxCharacters = limit,
            start = { it.start }, end = { it.end }, text = { it.text },
            combine = { left, right -> Cue(left.start, maxOf(left.end, right.end), left.text + right.text) },
            split = { cue, index ->
                val boundary = cue.start + (cue.end - cue.start) * index / cue.text.length
                Cue(cue.start, boundary, cue.text.substring(0, index)) to
                    Cue(boundary, cue.end, cue.text.substring(index))
            },
            splitOnPunctuation = splitPunctuation
        )

    @Test
    fun countsLettersAndDigitsButIgnoresPunctuationAndWhitespace() {
        assertEquals(4, TimedTextMergePolicy.characterCount("你，好！  A 1\n"))
    }

    @Test
    fun smartMergeAcceptsGapsThroughFiveHundredMillisecondsAndRejectsLargerGap() {
        for (gap in listOf(0L, 1L, 150L, 200L, 230L, 250L, 300L, 350L, 400L, 499L, 500L)) {
            assertEquals(
                listOf("甲乙"),
                merge(
                    listOf(Cue(0, 10, "甲"), Cue(10 + gap, 20 + gap, "乙")),
                    maxGapMs = 500
                ).map { it.text }
            )
        }
        val cues = listOf(Cue(0, 10, "甲"), Cue(510, 520, "乙"), Cue(1021, 1031, "丙"))
        assertEquals(listOf("甲乙", "丙"), merge(cues, maxGapMs = 500).map { it.text })
    }

    @Test
    fun smartMergeUsesConfiguredMaximumGapInsteadOfFixedFiveHundredMilliseconds() {
        val cues = listOf(
            Cue(0, 10, "甲"),
            Cue(210, 220, "乙"), // 200 ms gap from the first cue
            Cue(421, 431, "丙") // 201 ms gap from the merged first pair
        )

        assertEquals(
            listOf("甲乙", "丙"),
            merge(cues, maxGapMs = 200).map { it.text }
        )
        assertEquals(
            listOf("甲乙丙"),
            merge(cues, maxGapMs = 201).map { it.text }
        )
        assertEquals(
            listOf("甲", "乙", "丙"),
            merge(cues, maxGapMs = 0).map { it.text }
        )
    }

    @Test
    fun smartMergePrefersTheShorterPairWhenEqualGapGroupCannotFitWithoutPunctuation() {
        val cues = listOf(
            Cue(0, 100, "12345678901234"),
            Cue(200, 300, "A"),
            Cue(400, 500, "B")
        )
        assertEquals(listOf("12345678901234", "AB"), merge(cues, limit = 15).map { it.text })
    }

    @Test
    fun smartMergeProcessesASmallerGapBeforeAnEarlierLargerGap() {
        val cues = listOf(
            Cue(0, 100, "12345678901234"),
            Cue(500, 600, "A"),
            Cue(650, 750, "B")
        )
        assertEquals(listOf("12345678901234", "AB"), merge(cues, limit = 15).map { it.text })
    }

    @Test
    fun lengthFilterRejectsOnlyTheTooLongPairAndKeepsLookingAhead() {
        val cues = listOf(
            Cue(0, 100, "1234567890123456789012345"),
            Cue(200, 300, "A"),
            Cue(500, 600, "B")
        )
        assertEquals(listOf("1234567890123456789012345", "AB"), merge(cues, limit = 25).map { it.text })
        assertEquals(listOf("1234567890123456789012345AB"), merge(cues, smart = false, limit = null).map { it.text })
    }

    @Test
    fun splitsAfterPunctuationAndCarriesTheRightSideIntoTheNextMerge() {
        val cues = listOf(
            Cue(0, 100, "12345678，甲乙"),
            Cue(200, 300, "丙丁戊己庚辛壬癸"),
            Cue(400, 500, "子丑寅卯")
        )
        val merged = merge(cues, limit = 15)
        assertEquals(listOf("12345678，", "甲乙丙丁戊己庚辛壬癸子丑寅卯"), merged.map { it.text })
        assertEquals(cues.joinToString("") { it.text }, merged.joinToString("") { it.text })
        assertEquals(0L, merged.first().start)
        assertEquals(500L, merged.last().end)
    }

    @Test
    fun splitsAtEachPunctuationAndKeepsClosingMarksOnTheLeft() {
        val cues = listOf(
            Cue(0, 100, "12345，67890。！？」甲乙丙"),
            Cue(200, 300, "丁戊己庚辛")
        )
        assertEquals(
            listOf("12345，", "67890。！？」", "甲乙丙丁戊己庚辛"),
            merge(cues, limit = 15).map { it.text }
        )
    }

    @Test
    fun rejectsTheWholePairWhenTheFirstPunctuationIsBeyondTheLimit() {
        val cues = listOf(
            Cue(0, 100, "1234567890123456，甲"),
            Cue(200, 300, "乙丙")
        )
        assertEquals(cues, merge(cues, limit = 15))
    }

    @Test
    fun splitsAnOversizedRemainderAtItsNextPunctuation() {
        val cues = listOf(
            Cue(0, 100, "1234567890，"),
            Cue(200, 300, "abcdefghij。ABCDEFGHIJ！甲乙丙丁戊己")
        )
        assertEquals(
            listOf("1234567890，", "abcdefghij。", "ABCDEFGHIJ！", "甲乙丙丁戊己"),
            merge(cues, limit = 15, splitPunctuation = false).map { it.text }
        )
    }

    @Test
    fun touchingAndOverlappingSegmentsWithoutPunctuationMergeDespiteTheLimit() {
        for (smart in listOf(true, false)) {
            for (nextStart in listOf(99L, 100L)) {
                val cues = listOf(
                    Cue(0, 100, "123456789012345"),
                    Cue(nextStart, 200, "甲乙"),
                    Cue(200, 300, "丙丁")
                )
                assertEquals(listOf("123456789012345甲乙丙丁"), merge(cues, smart, 15).map { it.text })
            }
        }
    }

    @Test
    fun touchingSegmentsForceSplitAtPunctuationEvenWithoutALengthLimit() {
        val cues = listOf(Cue(0, 100, "1234567890，甲乙"), Cue(100, 200, "丙丁戊己"))
        for (limit in listOf(null, 15, 50)) {
            val merged = merge(cues, limit = limit)
            assertEquals(listOf("1234567890，", "甲乙丙丁戊己"), merged.map { it.text })
            assertEquals(cues.joinToString("") { it.text }, merged.joinToString("") { it.text })
            assertEquals(0L, merged.first().start)
            assertEquals(200L, merged.last().end)
        }
    }

    @Test
    fun forcedPunctuationSplittingKeepsOversizedPiecesAndClosingMarksOnTheLeft() {
        val cues = listOf(Cue(0, 100, "12345，67890。！？」甲乙"), Cue(99, 200, "丙丁"))
        assertEquals(
            listOf("12345，", "67890。！？」", "甲乙丙丁"),
            merge(cues, limit = 3).map { it.text }
        )
    }

    @Test
    fun touchingSegmentsKeepInternalPunctuationWhenTheOptionIsOff() {
        val cues = listOf(Cue(0, 100, "12345，甲乙"), Cue(100, 200, "丙丁"))
        assertEquals(
            listOf("12345，甲乙丙丁"),
            merge(cues, limit = 3, splitPunctuation = false).map { it.text }
        )
    }

    @Test
    fun forcedSplitAlsoProcessesInternalPunctuationBeforeAnOriginalTerminalBoundary() {
        val cues = listOf(Cue(0, 100, "甲，乙。"), Cue(100, 200, "丙"))
        assertEquals(listOf("甲，", "乙。", "丙"), merge(cues).map { it.text })
    }

    @Test
    fun forcedSplitRemainderCanMergeRightInLaterRoundsWithoutRejoiningLeftPieces() {
        val cues = listOf(Cue(0, 100, "甲，乙"), Cue(100, 200, "丙"), Cue(250, 300, "丁"))
        assertEquals(listOf("甲，", "乙丙丁"), merge(cues, limit = 3).map { it.text })
    }

    @Test
    fun touchingPunctuationOnlySegmentsKeepTheirMarksOnTheLeft() {
        val cues = listOf(Cue(0, 100, "甲"), Cue(100, 110, "，。"), Cue(110, 200, "乙"))
        assertEquals(listOf("甲，。", "乙"), merge(cues).map { it.text })
    }

    @Test
    fun forcedSplittingDoesNotApplyToIsolatedCuesOrPositiveGapsBelowTheLimit() {
        val isolated = listOf(Cue(0, 100, "甲，乙"))
        assertEquals(isolated, merge(isolated))
        val separated = isolated + Cue(101, 200, "丙")
        assertEquals(listOf("甲，乙丙"), merge(separated, limit = 3).map { it.text })
    }

    @Test
    fun aPositiveGapWithoutPunctuationStillEnforcesTheLimit() {
        val cues = listOf(Cue(0, 100, "123456789012345"), Cue(101, 200, "甲乙"))
        assertEquals(cues, merge(cues, limit = 15))
    }

    @Test
    fun disablingTheLengthFilterKeepsPunctuationInsideTheMergedText() {
        val cues = listOf(Cue(0, 100, "123456789012345，甲"), Cue(200, 300, "乙丙"))
        assertEquals(listOf("123456789012345，甲乙丙"), merge(cues).map { it.text })
    }

    @Test
    fun manualMergingIgnoresTheSmartLengthAndPunctuationOptions() {
        val cues = listOf(Cue(0, 100, "1234567890，甲乙"), Cue(1000, 1100, "丙丁戊己"))
        assertEquals(
            listOf("1234567890，甲乙丙丁戊己"),
            merge(cues, smart = false, limit = 15, maxGapMs = 5000).map { it.text }
        )
        assertEquals(cues, merge(cues, smart = true, limit = 15, maxGapMs = 500))
    }

    @Test
    fun smartMergeTriesEachMillisecondBeforeTheNextGap() {
        val cues = listOf(
            Cue(0, 10, "12"),
            Cue(12, 20, "3"),
            Cue(21, 30, "4")
        )
        assertEquals(listOf("12", "34"), merge(cues, limit = 3).map { it.text })
    }

    @Test
    fun refusesTheWholePairIfAPunctuationSeparatedMiddlePieceStillExceedsTheLimit() {
        val cues = listOf(Cue(0, 10, "1"), Cue(20, 100, "12,3123,12"))
        assertEquals(cues, merge(cues, limit = 3))
    }

    @Test
    fun splitsAValidPairAndDoesNotImmediatelyForceJoinItsTouchingPiecesAgain() {
        val cues = listOf(Cue(0, 10, "1"), Cue(20, 100, "12,3"))
        assertEquals(listOf("112,", "3"), merge(cues, limit = 3).map { it.text })
    }

    @Test
    fun validPunctuationSplittingDoesNotDependOnTheDirectionalPunctuationOption() {
        val cues = listOf(Cue(0, 10, "1"), Cue(20, 100, "12,3"))
        assertEquals(
            listOf("112,", "3"),
            merge(cues, limit = 3, splitPunctuation = false).map { it.text }
        )
    }

    @Test
    fun acceptsSeveralPunctuationPiecesOnlyWhenEveryPieceFits() {
        val cues = listOf(Cue(0, 10, "1"), Cue(20, 100, "12,12,12"))
        assertEquals(listOf("112,", "12,", "12"), merge(cues, limit = 3).map { it.text })
    }

    @Test
    fun rightPieceFromAValidSplitCanStillMergeWithTheNextSourceSubtitle() {
        val cues = listOf(Cue(0, 10, "1"), Cue(20, 100, "12,3"), Cue(130, 140, "4"))
        assertEquals(listOf("112,", "34"), merge(cues, limit = 3).map { it.text })
    }

    @Test
    fun terminalPunctuationAllowsAMergeOnTheLeftAndBlocksTheRight() {
        val cues = listOf(Cue(0, 10, "甲"), Cue(20, 30, "乙。"), Cue(40, 50, "丙"))
        assertEquals(listOf("甲乙。", "丙"), merge(cues).map { it.text })
    }

    @Test
    fun terminalPunctuationAlsoBlocksTouchingRightSegments() {
        val cues = listOf(Cue(0, 10, "甲。"), Cue(10, 20, "乙"))
        assertEquals(listOf("甲。", "乙"), merge(cues, limit = 1).map { it.text })
    }

    @Test
    fun disablingDirectionalPunctuationAllowsTerminalPunctuationToMergeRight() {
        val cues = listOf(Cue(0, 10, "甲。"), Cue(20, 30, "乙"))
        assertEquals(listOf("甲。乙"), merge(cues, splitPunctuation = false).map { it.text })
    }

    @Test
    fun punctuationInTheMiddleAndPunctuationOnlySegmentsMergeNormally() {
        for (leftText in listOf("甲，乙", "，。")) {
            val cues = listOf(Cue(0, 10, leftText), Cue(20, 30, "丙"))
            assertEquals(listOf(leftText + "丙"), merge(cues).map { it.text })
        }
    }

    @Test
    fun punctuationAfterTrailingWhitespaceStillBlocksARightMerge() {
        val cues = listOf(Cue(0, 10, "甲。  \n"), Cue(20, 30, "乙"))
        assertEquals(cues, merge(cues))
    }

    @Test
    fun rejectsTheUsersEqualGapGroupIfItsMiddlePunctuationPieceStillExceedsTheLimit() {
        val cues = listOf(
            Cue(0, 100, "1"), Cue(200, 300, "12,3"),
            Cue(400, 500, "123,"), Cue(600, 700, "12"),
        )
        assertEquals(cues, merge(cues, limit = 3, splitPunctuation = false))
    }

    @Test
    fun acceptsTheWholeEqualGapGroupWhenEveryPunctuationPieceFits() {
        val cues = listOf(
            Cue(0, 100, "1"), Cue(200, 300, "12,3"),
            Cue(400, 500, "12,"), Cue(600, 700, "12"),
        )
        assertEquals(listOf("112,", "312,", "12"), merge(cues, limit = 3, splitPunctuation = false).map { it.text })
    }

    @Test
    fun aRejectedSmallGapDoesNotBlockTheNextLargerValidPair() {
        val cues = listOf(Cue(0, 100, "123"), Cue(101, 200, "4"), Cue(202, 300, "5"))
        assertEquals(listOf("123", "45"), merge(cues, limit = 3).map { it.text })
    }
}
