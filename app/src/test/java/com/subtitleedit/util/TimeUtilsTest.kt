package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeUtilsTest {

    // ==================== formatSRT ====================

    @Test
    fun formatSRT_zero() {
        assertEquals("00:00:00,000", TimeUtils.formatSRT(0))
    }

    @Test
    fun formatSRT_normal() {
        assertEquals("00:00:01,234", TimeUtils.formatSRT(1234))
        assertEquals("01:01:01,234", TimeUtils.formatSRT(3661234))
        assertEquals("09:59:59,999", TimeUtils.formatSRT(35999999))
    }

    @Test
    fun formatSRT_hoursOverTwoDigits() {
        // 超过 99 小时位宽自然扩展
        assertEquals("100:00:00,000", TimeUtils.formatSRT(360000000))
    }

    @Test
    fun negativeTimesUseSingleSignAndRemainEditable() {
        assertEquals("-00:00:01,250", TimeUtils.formatSRT(-1250))
        assertEquals("-01:01:01,234", TimeUtils.formatSRT(-3661234))
        assertEquals(-1250L, TimeUtils.parseFromInput(TimeUtils.formatForInput(-1250)))
        assertEquals(-3661234L, TimeUtils.parseFromInput(TimeUtils.formatForInput(-3661234)))
    }

    // ==================== formatLRC ====================

    @Test
    fun formatLRC_zero() {
        assertEquals("[00:00.00]", TimeUtils.formatLRC(0))
    }

    @Test
    fun formatLRC_normal() {
        assertEquals("[00:01.23]", TimeUtils.formatLRC(1234))
        assertEquals("[10:54.32]", TimeUtils.formatLRC(654320))
        assertEquals("[00:59.99]", TimeUtils.formatLRC(59994))
    }

    @Test
    fun formatLRC_roundsUpToNextMinute_notSixtySeconds() {
        // 回归测试：旧实现用浮点 %05.2f，59995ms 会输出非法的 [00:60.00]
        assertEquals("[01:00.00]", TimeUtils.formatLRC(59995))
        assertEquals("[01:00.00]", TimeUtils.formatLRC(59999))
        assertEquals("[60:00.00]", TimeUtils.formatLRC(3599999))
    }

    @Test
    fun formatLRC_minutesOverTwoDigits() {
        assertEquals("[100:00.00]", TimeUtils.formatLRC(6000000))
    }

    // ==================== parseSRT ====================

    @Test
    fun parseSRT_commaAndDotMillis() {
        assertEquals(1500L, TimeUtils.parseSRT("00:00:01,500"))
        assertEquals(1500L, TimeUtils.parseSRT("00:00:01.500"))
        assertEquals(3723456L, TimeUtils.parseSRT("01:02:03,456"))
    }

    @Test
    fun parseSRT_trimsWhitespace() {
        assertEquals(2000L, TimeUtils.parseSRT("  00:00:02,000  "))
    }

    @Test
    fun parseSRT_shortMillisTreatedAsFraction() {
        // 回归测试：".5" 表示 0.5 秒（500ms），旧实现解析成 5ms
        assertEquals(1500L, TimeUtils.parseSRT("00:00:01.5"))
        assertEquals(1450L, TimeUtils.parseSRT("00:00:01,45"))
    }

    @Test
    fun parseSRT_extraFractionDigitsTruncated() {
        assertEquals(1456L, TimeUtils.parseSRT("00:00:01.4567"))
    }

    @Test
    fun parseSRT_missingMillis() {
        assertEquals(1000L, TimeUtils.parseSRT("00:00:01"))
    }

    @Test
    fun parseSRT_singleDigitFields() {
        assertEquals(3723400L, TimeUtils.parseSRT("1:2:3,4"))
    }

    @Test
    fun parseSRT_invalidReturnsZero() {
        assertEquals(0L, TimeUtils.parseSRT("abc"))
        assertEquals(0L, TimeUtils.parseSRT("aa:bb:cc"))
        assertEquals(0L, TimeUtils.parseSRT(""))
    }

    // ==================== parseLRC ====================

    @Test
    fun parseLRC_normal() {
        assertEquals(1230L, TimeUtils.parseLRC("[00:01.23]"))
        assertEquals(654320L, TimeUtils.parseLRC("[10:54.32]"))
    }

    @Test
    fun parseLRC_roundsFloatCorrectly() {
        // 回归测试：0.29 的二进制浮点表示乘 1000 后 toLong 截断成 289
        assertEquals(290L, TimeUtils.parseLRC("[00:00.29]"))
    }

    @Test
    fun parseLRC_withoutBrackets() {
        assertEquals(1230L, TimeUtils.parseLRC("00:01.23"))
    }

    @Test
    fun parseLRC_variableFractionDigits() {
        assertEquals(1500L, TimeUtils.parseLRC("[00:01.5]"))
        assertEquals(187000L, TimeUtils.parseLRC("[03:07]"))
    }

    @Test
    fun parseLRC_invalidReturnsZero() {
        assertEquals(0L, TimeUtils.parseLRC("[bad]"))
        assertEquals(0L, TimeUtils.parseLRC(""))
    }

    @Test
    fun parseLRC_formatLRC_roundTrip() {
        // LRC 精度为厘秒，取 10ms 对齐的值验证互逆
        for (timeMs in listOf(0L, 1230L, 59990L, 654320L, 3600000L)) {
            assertEquals(timeMs, TimeUtils.parseLRC(TimeUtils.formatLRC(timeMs)))
        }
    }

    @Test
    fun parseSRT_formatSRT_roundTrip() {
        for (timeMs in listOf(0L, 1L, 999L, 1234L, 3661234L, 35999999L)) {
            assertEquals(timeMs, TimeUtils.parseSRT(TimeUtils.formatSRT(timeMs)))
        }
    }

    // ==================== applyOffset ====================

    @Test
    fun applyOffset_positiveAndNegative() {
        assertEquals(1500L, TimeUtils.applyOffset(1000, 500))
        assertEquals(500L, TimeUtils.applyOffset(1000, -500))
    }

    @Test
    fun applyOffset_clampsAtZero() {
        assertEquals(0L, TimeUtils.applyOffset(1000, -2000))
        assertEquals(0L, TimeUtils.applyOffset(0, -1))
    }

    // ==================== formatDuration ====================

    @Test
    fun formatDuration_combinations() {
        assertEquals("0秒", TimeUtils.formatDuration(0))
        assertEquals("1分", TimeUtils.formatDuration(60000))
        assertEquals("1小时", TimeUtils.formatDuration(3600000))
        assertEquals("1分1秒", TimeUtils.formatDuration(61000))
        assertEquals("1小时1分1秒1毫秒", TimeUtils.formatDuration(3661001))
    }

    @Test
    fun formatDuration_subSecondIncludesZeroSeconds() {
        // 记录现状：不足 1 秒时会先补 "0秒" 再接毫秒
        assertEquals("0秒999毫秒", TimeUtils.formatDuration(999))
    }

    // ==================== calculateDuration / isValidTimeAxis ====================

    @Test
    fun calculateDuration_normalAndClamped() {
        assertEquals(50L, TimeUtils.calculateDuration(50, 100))
        assertEquals(0L, TimeUtils.calculateDuration(100, 50))
    }

    @Test
    fun isValidTimeAxis_rules() {
        assertTrue(TimeUtils.isValidTimeAxis(0, 1))
        assertFalse(TimeUtils.isValidTimeAxis(5, 5))
        assertFalse(TimeUtils.isValidTimeAxis(-1, 3))
        assertFalse(TimeUtils.isValidTimeAxis(3, 2))
    }

    // ==================== 输入框格式 ====================

    @Test
    fun formatForInput_usesDotSeparator() {
        assertEquals("00:00:01.500", TimeUtils.formatForInput(1500))
        assertEquals("01:02:03.456", TimeUtils.formatForInput(3723456))
    }

    @Test
    fun parseFromInput_blankReturnsNull() {
        assertNull(TimeUtils.parseFromInput(""))
        assertNull(TimeUtils.parseFromInput("   "))
    }

    @Test
    fun parseFromInput_validInput() {
        assertEquals(1500L, TimeUtils.parseFromInput("00:00:01.500"))
    }

    @Test
    fun parseFromInput_garbageReturnsZero() {
        // 记录现状：无法解析的非空输入返回 0 而不是 null（parseSRT 从不抛异常）
        assertEquals(0L, TimeUtils.parseFromInput("abc"))
    }

    @Test
    fun formatForDisplay_sameAsFormatForInput() {
        assertEquals(TimeUtils.formatForInput(1500), TimeUtils.formatForDisplay(1500))
    }
}
