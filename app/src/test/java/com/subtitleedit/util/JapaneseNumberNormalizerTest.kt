package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JapaneseNumberNormalizerTest {
    @Test
    fun expandsCardinalNumbersAndKeepsSourceWhenRestoring() {
        val normalized = JapaneseNumberNormalizer.normalize("123", "Japanese")
        assertEquals("ひゃくにじゅうさん", normalized.textForAlignment)

        val restored = normalized.restore(
            listOf(
                ForcedAlignmentUnit("ひゃく", 0, 160),
                ForcedAlignmentUnit("に", 160, 240),
                ForcedAlignmentUnit("じゅう", 240, 400),
                ForcedAlignmentUnit("さん", 400, 560),
            )
        )
        assertEquals(1, restored.size)
        assertEquals("123", restored.single().text)
        assertEquals(0L, restored.single().startTimeMs)
        assertEquals(560L, restored.single().endTimeMs)
    }

    @Test
    fun keepsSeparateDigitsSeparateButUsesCounterReadings() {
        val separated = JapaneseNumberNormalizer.normalize("4 5 6 頭 の 中", "Japanese")
        assertEquals("よん ご ろく 頭 の 中", separated.textForAlignment)
        assertEquals(
            listOf("4", "5", "6", "頭", "の", "中"),
            separated.restore(
                listOf(
                    ForcedAlignmentUnit("よん", 0, 100),
                    ForcedAlignmentUnit("ご", 100, 200),
                    ForcedAlignmentUnit("ろく", 200, 300),
                    ForcedAlignmentUnit("頭", 300, 400),
                    ForcedAlignmentUnit("の", 400, 500),
                    ForcedAlignmentUnit("中", 500, 600),
                )
            ).map { it.text }
        )
        assertEquals("ひとつ", JapaneseNumberNormalizer.normalize("1つ", "Japanese").textForAlignment)
        assertEquals("いっぽん", JapaneseNumberNormalizer.normalize("1本", "Japanese").textForAlignment)
        assertEquals("ひとり", JapaneseNumberNormalizer.normalize("1人", "Japanese").textForAlignment)
        assertEquals("ひとつ", JapaneseNumberNormalizer.normalize("一つ", "Japanese").textForAlignment)
        assertEquals("いっぽん", JapaneseNumberNormalizer.normalize("一本", "Japanese").textForAlignment)
        assertEquals("ひとり", JapaneseNumberNormalizer.normalize("一人", "Japanese").textForAlignment)
        assertEquals("いちにさん", JapaneseNumberNormalizer.normalize("一二三", "Japanese").textForAlignment)
        assertEquals("ひゃくにじゅうさん", JapaneseNumberNormalizer.normalize("一百二十三", "Japanese").textForAlignment)
        assertEquals("せんにひゃくさんじゅうよん", JapaneseNumberNormalizer.normalize("壹仟貳佰參拾肆", "Japanese").textForAlignment)
    }

    @Test
    fun supportsFullWidthDigitsAndLeavesOtherLanguagesUntouched() {
        assertEquals("にせんにじゅうよん", JapaneseNumberNormalizer.normalize("２０２４", "Japanese").textForAlignment)
        assertEquals("123", JapaneseNumberNormalizer.normalize("123", "Chinese").textForAlignment)
        assertEquals("ver2.0", JapaneseNumberNormalizer.normalize("ver2.0", "Japanese").textForAlignment)
        assertEquals("3.14", JapaneseNumberNormalizer.normalize("3.14", "Japanese").textForAlignment)
    }
}
