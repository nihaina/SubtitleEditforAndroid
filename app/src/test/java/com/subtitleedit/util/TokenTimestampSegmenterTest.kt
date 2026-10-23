package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenTimestampSegmenterTest {

    @Test
    fun mergeSegments_mergesAtThresholdAndPreservesTextOrder() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0L, 1000L, "第一"),
            TokenTimestampSegmenter.Segment(1200L, 1600L, "第二"),
            TokenTimestampSegmenter.Segment(1851L, 2200L, "第三")
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, maxGapMs = 200)

        assertEquals(2, merged.size)
        assertEquals(0L, merged[0].startTimeMs)
        assertEquals(1600L, merged[0].endTimeMs)
        assertEquals("第一第二", merged[0].text)
        assertEquals("第三", merged[1].text)
    }

    @Test
    fun mergeSegments_zeroOnlyMergesTouchingOrOverlappingSegments() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0L, 1000L, "A"),
            TokenTimestampSegmenter.Segment(1000L, 1500L, "B"),
            TokenTimestampSegmenter.Segment(1501L, 2000L, "C")
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, maxGapMs = 0)

        assertEquals(2, merged.size)
        assertEquals("A B", merged[0].text)
        assertEquals(1500L, merged[0].endTimeMs)
        assertEquals("C", merged[1].text)
    }

    @Test
    fun fromTokens_usesFormerZeroSplitTimeBoundaries() {
        val tokens = TokenTimestampSegmenter.fromTokens(
            tokens = arrayOf("你", "好", "世界"),
            timestamps = floatArrayOf(0f, 0.1f, 0.46f),
            durations = floatArrayOf(0.1f, 0.1f, 0.1f),
            audioStartTimeMs = 1000L,
            audioEndTimeMs = 2000L
        )

        assertEquals(3, tokens.size)
        assertEquals(1000L, tokens[0].startTimeMs)
        assertEquals(1100L, tokens[0].endTimeMs)
        assertEquals(1100L, tokens[1].startTimeMs)
        assertEquals(1300L, tokens[1].endTimeMs)
        assertEquals(1360L, tokens[2].startTimeMs)
        assertEquals(1660L, tokens[2].endTimeMs)
        assertEquals(2, TokenTimestampSegmenter.mergeSegments(tokens, maxGapMs = 0).size)
        val merged = TokenTimestampSegmenter.mergeSegments(tokens, maxGapMs = 60)
        assertEquals(1, merged.size)
        assertEquals("你好世界", merged.single().text)
    }

    @Test
    fun fromTokens_withoutDurationAddsFormerZeroSplitTimeContext() {
        val tokens = TokenTimestampSegmenter.fromTokens(
            tokens = arrayOf("你"),
            timestamps = floatArrayOf(0.5f),
            durations = floatArrayOf(),
            audioStartTimeMs = 100L,
            audioEndTimeMs = 1100L
        )

        assertEquals(500L, tokens.single().startTimeMs)
        assertEquals(701L, tokens.single().endTimeMs)
    }

    @Test
    fun fromTokens_expandsSixtyMillisecondCtcRunsIntoAvailableContext() {
        val segments = TokenTimestampSegmenter.fromTokens(
            tokens = arrayOf("你", "好"),
            timestamps = floatArrayOf(0.5f, 0.8f),
            durations = floatArrayOf(0.06f, 0.06f),
            audioStartTimeMs = 0L,
            audioEndTimeMs = 1_000L
        )

        assertEquals(2, segments.size)
        assertEquals(400L, segments[0].startTimeMs)
        assertEquals(660L, segments[0].endTimeMs)
        assertEquals(700L, segments[1].startTimeMs)
        assertEquals(960L, segments[1].endTimeMs)
    }

    @Test
    fun smartMerge_splitsAtOriginalPunctuationBoundaryAndMergesTheRemainder() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0, 1_000, "1234567890，"),
            TokenTimestampSegmenter.Segment(1_100, 2_000, "甲乙丙丁戊己庚辛"),
            TokenTimestampSegmenter.Segment(2_050, 2_300, "尾"),
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, 50, smart = true, maxCharacters = 15, splitOnPunctuation = false)

        assertEquals(listOf("1234567890，", "甲乙丙丁戊己庚辛尾"), merged.map { it.text })
        assertEquals(0L, merged[0].startTimeMs)
        assertEquals(1_000L, merged[0].endTimeMs)
        assertEquals(1_100L, merged[1].startTimeMs)
        assertEquals(2_300L, merged[1].endTimeMs)
    }

    @Test
    fun smartMerge_keepsGeneratedTokenTimesWhenSplittingAtPunctuation() {
        val segments = TokenTimestampSegmenter.fromTokens(
            tokens = arrayOf("12345678", "，", "甲乙丙丁戊己庚辛"),
            timestamps = floatArrayOf(0f, 0.45f, 0.85f),
            durations = floatArrayOf(0.2f, 0.1f, 0.5f),
            audioStartTimeMs = 1_000,
            audioEndTimeMs = 3_000,
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, 50, smart = true, maxCharacters = 15, splitOnPunctuation = false)

        assertEquals(listOf("12345678，", "甲乙丙丁戊己庚辛"), merged.map { it.text })
        assertEquals(1_000L, merged[0].startTimeMs)
        assertEquals(1_650L, merged[0].endTimeMs)
        assertEquals(1_750L, merged[1].startTimeMs)
        assertEquals(2_450L, merged[1].endTimeMs)
    }

    @Test
    fun smartMerge_estimatesOnlyInsideTheSourceUnitUsingUnicodeCodePoints() {
        val firstText = "𠀀".repeat(8) + "，" + "甲".repeat(8)
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0, 1_700, firstText),
            TokenTimestampSegmenter.Segment(1_701, 1_800, "尾"),
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, 1, smart = true, maxCharacters = 15)

        assertEquals(listOf("𠀀".repeat(8) + "，", "甲".repeat(8) + "尾"), merged.map { it.text })
        assertEquals(firstText + "尾", merged.joinToString("") { it.text })
        assertEquals(900L, merged[0].endTimeMs)
        assertEquals(900L, merged[1].startTimeMs)
        assertEquals(1_800L, merged[1].endTimeMs)
    }

    @Test
    fun smartMerge_splitsTouchingUnitsAtPunctuationAndKeepsPositiveTimesForTinySources() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0, 1, "12345678，甲乙丙丁戊己庚辛"),
            TokenTimestampSegmenter.Segment(1, 2, "尾"),
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, 0, smart = true, maxCharacters = 15)

        assertEquals(listOf("12345678，", "甲乙丙丁戊己庚辛尾"), merged.map { it.text })
        assertTrue(merged.all { it.endTimeMs > it.startTimeMs })
    }

    @Test
    fun smartMerge_zeroGapRoundSplitsEveryPunctuationBoundaryRegardlessOfTheLengthFilter() {
        val parts = listOf(
            TimedTextPart("甲乙", 100, 200),
            TimedTextPart("，", 200, 220),
            TimedTextPart("丙丁", 220, 400),
            TimedTextPart("。", 400, 430),
            TimedTextPart("戊己", 430, 700),
        )
        val segments = listOf(
            TokenTimestampSegmenter.Segment(100, 400, "甲乙，丙丁", parts = parts.take(3)),
            TokenTimestampSegmenter.Segment(400, 700, "。戊己", parts = parts.drop(3)),
        )

        for (limit in listOf(null, 1, 50)) {
            val merged = TokenTimestampSegmenter.mergeSegments(
                segments, 0, smart = true, maxCharacters = limit,
            )

            assertEquals(listOf("甲乙，", "丙丁。", "戊己"), merged.map { it.text })
            assertEquals(listOf(100L, 220L, 430L), merged.map { it.startTimeMs })
            assertEquals(listOf(220L, 430L, 700L), merged.map { it.endTimeMs })
            assertEquals(parts, merged.flatMap { it.parts })
        }

        val disabled = TokenTimestampSegmenter.mergeSegments(
            segments, 0, smart = true, maxCharacters = 1, splitOnPunctuation = false,
        )
        assertEquals("甲乙，丙丁。戊己", disabled.single().text)
        assertEquals(100L, disabled.single().startTimeMs)
        assertEquals(700L, disabled.single().endTimeMs)
        assertEquals(parts, disabled.single().parts)
    }

    @Test
    fun smartMerge_zeroGapRoundSplitsOverlappingSourcesAndPreservesTheirOriginalParts() {
        val parts = listOf(
            TimedTextPart("甲", 0, 100),
            TimedTextPart("，", 100, 110),
            TimedTextPart("乙", 110, 300),
            TimedTextPart("丙", 250, 350),
            TimedTextPart("。", 350, 360),
            TimedTextPart("丁", 360, 500),
        )
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0, 300, "甲，乙", parts = parts.take(3)),
            TokenTimestampSegmenter.Segment(250, 500, "丙。丁", parts = parts.drop(3)),
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, 0, smart = true)

        assertEquals(listOf("甲，", "乙丙。", "丁"), merged.map { it.text })
        assertEquals(listOf(0L, 110L, 360L), merged.map { it.startTimeMs })
        assertEquals(listOf(110L, 360L, 500L), merged.map { it.endTimeMs })
        assertEquals(parts, merged.flatMap { it.parts })
    }

    @Test
    fun smartMerge_doesNotUndoVadClippingOrExtensionWhenSplittingSourceParts() {
        for ((originalStart, originalEnd) in listOf(0L to 1_000L, 200L to 800L)) {
            val segments = listOf(
                TokenTimestampSegmenter.Segment(
                    100, 900, "12345678，甲乙丙丁戊己庚辛",
                    parts = listOf(
                        TimedTextPart("12345678，", originalStart, 400),
                        TimedTextPart("甲乙丙丁戊己庚辛", 500, originalEnd),
                    ),
                ),
                TokenTimestampSegmenter.Segment(901, 1_100, "尾"),
            )

            val merged = TokenTimestampSegmenter.mergeSegments(segments, 1, smart = true, maxCharacters = 9)

            assertEquals(listOf("12345678，", "甲乙丙丁戊己庚辛尾"), merged.map { it.text })
            assertEquals(100L, merged.first().startTimeMs)
            assertEquals(400L, merged.first().endTimeMs)
            assertEquals(500L, merged.last().startTimeMs)
            assertEquals(1_100L, merged.last().endTimeMs)
        }
    }

    @Test
    fun manualMerge_ignoresLengthFilterAndPunctuationDirection() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0, 100, "1234567890。"),
            TokenTimestampSegmenter.Segment(200, 300, "甲乙丙丁戊己庚辛"),
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, 100, smart = false, maxCharacters = 15)

        assertEquals(listOf("1234567890。甲乙丙丁戊己庚辛"), merged.map { it.text })
    }

    @Test
    fun smartMerge_punctuationEndingCanMergeLeftButCannotMergeRight() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0, 100, "甲"),
            TokenTimestampSegmenter.Segment(101, 200, "乙。"),
            TokenTimestampSegmenter.Segment(200, 300, "丙"),
        )

        val enabled = TokenTimestampSegmenter.mergeSegments(segments, 1, smart = true)
        val disabled = TokenTimestampSegmenter.mergeSegments(segments, 1, smart = true, splitOnPunctuation = false)

        assertEquals(listOf("甲乙。", "丙"), enabled.map { it.text })
        assertEquals(listOf("甲乙。丙"), disabled.map { it.text })
    }

    @Test
    fun smartMerge_invalidPunctuationSplitPreservesOriginalTimesAndParts() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0, 100, "1"),
            TokenTimestampSegmenter.Segment(200, 300, "12,3"),
            TokenTimestampSegmenter.Segment(400, 500, "123,"),
            TokenTimestampSegmenter.Segment(600, 700, "12"),
        )

        val merged = TokenTimestampSegmenter.mergeSegments(
            segments, 0, smart = true, maxCharacters = 3, splitOnPunctuation = false,
        )

        assertEquals(segments, merged)
    }
}
