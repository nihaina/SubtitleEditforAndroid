package com.subtitleedit.util

import java.text.Normalizer

/**
 * Expands Arabic numbers to the Japanese pronunciation used by the speaker.
 *
 * The forced aligner scores Japanese pronunciation. Keeping the source ranges here lets the
 * caller feed kana to the model while retaining the ASR text (including the original digits) in
 * the generated subtitle.
 */
internal object JapaneseNumberNormalizer {
    internal data class MappedCharacter(
        val sourceStart: Int,
        val sourceEnd: Int,
    )

    class Result internal constructor(
        val originalText: String,
        val textForAlignment: String,
        private val mappedCharacters: List<MappedCharacter>,
        private val changed: Boolean,
    ) {
        /** Restores aligned kana units to source text and merges units from one source number. */
        fun restore(units: List<ForcedAlignmentUnit>): List<ForcedAlignmentUnit> {
            if (!changed || units.isEmpty()) return units

            val restored = mutableListOf<ForcedAlignmentUnit>()
            var cursor = 0
            var previousSourceStart = -1
            var previousSourceEnd = -1
            for (unit in units) {
                val meaningful = meaningfulCharacters(unit.text)
                if (meaningful.isEmpty()) continue

                val start = cursor.coerceAtMost(mappedCharacters.size)
                val end = (cursor + meaningful.size).coerceAtMost(mappedCharacters.size)
                cursor = end
                if (start >= end) continue

                val sourceStart = mappedCharacters
                    .subList(start, end)
                    .minOf { it.sourceStart }
                val sourceEnd = mappedCharacters
                    .subList(start, end)
                    .maxOf { it.sourceEnd }
                if (sourceStart >= sourceEnd) continue
                val sourceText = originalText.substring(sourceStart, sourceEnd)
                if (sourceText.isBlank()) continue

                val previous = restored.lastOrNull()
                if (previous != null &&
                    previous.text == sourceText &&
                    previous.startTimeMs <= unit.endTimeMs &&
                    sourceStart == previousSourceStart &&
                    sourceEnd == previousSourceEnd
                ) {
                    restored[restored.lastIndex] = previous.copy(
                        startTimeMs = minOf(previous.startTimeMs, unit.startTimeMs),
                        endTimeMs = maxOf(previous.endTimeMs, unit.endTimeMs),
                    )
                } else {
                    restored += ForcedAlignmentUnit(
                        text = sourceText,
                        startTimeMs = unit.startTimeMs,
                        endTimeMs = unit.endTimeMs,
                    )
                }
                previousSourceStart = sourceStart
                previousSourceEnd = sourceEnd
            }
            return restored
        }
    }

    fun normalize(text: String, language: String): Result {
        if (!isJapanese(language) || text.isEmpty()) {
            return Result(text, text, emptyList(), changed = false)
        }

        val alignmentText = StringBuilder(text.length)
        val mapped = mutableListOf<MappedCharacter>()
        var changed = false
        var index = 0
        while (index < text.length) {
            val kanjiDigit = KANJI_DIGITS[text[index]]
            if (kanjiDigit != null) {
                val counter = COUNTERS.firstOrNull { text.startsWith(it, index + 1) }
                if (counter != null) {
                    val sourceEnd = index + 1 + counter.length
                    val reading = counterReading(kanjiDigit.toString(), counter)
                    if (reading != null) {
                        appendMapped(alignmentText, mapped, reading, index, sourceEnd)
                        changed = true
                        index = sourceEnd
                        continue
                    }
                }
            }
            val kanjiRunEnd = kanjiNumericRunEnd(text, index)
            if (kanjiRunEnd > index) {
                val kanjiNumber = text.substring(index, kanjiRunEnd)
                val counter = COUNTERS.firstOrNull { text.startsWith(it, kanjiRunEnd) }
                val sourceEnd = kanjiRunEnd + (counter?.length ?: 0)
                val reading = kanjiNumberReading(kanjiNumber, counter)
                if (reading != null) {
                    appendMapped(alignmentText, mapped, reading, index, sourceEnd)
                    changed = true
                    index = sourceEnd
                    continue
                }
            }
            val digit = digitValue(text[index])
            if (digit < 0 || (index > 0 && isAsciiWordCharacter(text[index - 1]))) {
                appendMapped(alignmentText, mapped, text[index].toString(), index, index + 1)
                index++
                continue
            }

            var end = index
            while (end < text.length && digitValue(text[end]) >= 0) end++
            val digits = text.substring(index, end)
            val counter = COUNTERS.firstOrNull { text.startsWith(it, end) }
            val sourceEnd = end + (counter?.length ?: 0)
            val adjacentAsciiWord =
                (index > 0 && isAsciiWordCharacter(text[index - 1])) ||
                    (end < text.length && isAsciiWordCharacter(text[end]))
            val ambiguousNumericPunctuation =
                text.getOrNull(index - 1) in NUMERIC_SEPARATORS ||
                    text.getOrNull(end) in NUMERIC_SEPARATORS
            if (adjacentAsciiWord || ambiguousNumericPunctuation) {
                appendMapped(alignmentText, mapped, text.substring(index, end), index, end)
                index = end
                continue
            }
            val reading = if (counter != null) {
                counterReading(digits, counter)
            } else {
                cardinalReading(digits)
            }

            if (reading == null) {
                appendMapped(alignmentText, mapped, text.substring(index, sourceEnd), index, sourceEnd)
            } else {
                appendMapped(alignmentText, mapped, reading, index, sourceEnd)
                changed = true
            }
            index = sourceEnd
        }

        return Result(text, alignmentText.toString(), mapped.toList(), changed)
    }

    private fun appendMapped(
        output: StringBuilder,
        mapped: MutableList<MappedCharacter>,
        value: String,
        sourceStart: Int,
        sourceEnd: Int,
    ) {
        output.append(value)
        value.forEach { character ->
            if (isMeaningful(character)) {
                mapped += MappedCharacter(sourceStart, sourceEnd)
            }
        }
    }

    private fun isJapanese(language: String): Boolean = when (language.trim().lowercase()) {
        "japanese", "日语", "ja" -> true
        else -> false
    }

    private fun isAsciiWordCharacter(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9'

    private fun isMeaningful(character: Char): Boolean =
        character.isLetterOrDigit() || character == '\''

    private fun meaningfulCharacters(value: String): List<Char> =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .filter(::isMeaningful)
            .toList()

    private fun digitValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in '０'..'９' -> character - '０'
        else -> -1
    }

    private fun kanjiNumericRunEnd(text: String, start: Int): Int {
        if (start >= text.length || text[start] !in KANJI_NUMERIC_CHARS) return start
        var end = start
        while (end < text.length && text[end] in KANJI_NUMERIC_CHARS) end++
        return end
    }

    private fun kanjiNumberReading(value: String, counter: String?): String? {
        if (value.isEmpty()) return null
        val onlyDigits = value.all { KANJI_DIGITS.containsKey(it) }
        if (onlyDigits) {
            // A sequence such as 一二三 is normally spoken digit by digit, especially in
            // identifiers, counting, and enumerations. A single digit still uses counter rules.
            if (counter != null && value.length == 1) {
                return counterReading(KANJI_DIGITS.getValue(value.single()).toString(), counter)
            }
            return value.map { digitReading(KANJI_DIGITS.getValue(it)) }.joinToString("") +
                counterSuffix(counter)
        }

        val numericValue = parseKanjiNumber(value) ?: return null
        val cardinal = cardinalReading(numericValue.toString()) ?: return null
        return if (counter == null) cardinal else counterReading(numericValue.toString(), counter)
    }

    private fun counterSuffix(counter: String?): String = when (counter) {
        "人" -> "にん"
        "本" -> "ほん"
        "個" -> "こ"
        "冊" -> "さつ"
        "匹" -> "ひき"
        "杯" -> "はい"
        "歳", "才" -> "さい"
        "階", "回" -> "かい"
        "分" -> "ふん"
        "時" -> "じ"
        "日" -> "にち"
        "月" -> "がつ"
        "年" -> "ねん"
        "台" -> "だい"
        "枚" -> "まい"
        "名" -> "めい"
        "頭" -> "とう"
        "羽" -> "わ"
        "番" -> "ばん"
        "号" -> "ごう"
        "件" -> "けん"
        "着" -> "ちゃく"
        "通" -> "つう"
        "円" -> "えん"
        "つ" -> "つ"
        "番目" -> "ばんめ"
        else -> ""
    }

    private fun parseKanjiNumber(value: String): Long? {
        var total = 0L
        var section = 0L
        var current = 0L
        for (character in value) {
            val digit = KANJI_DIGITS[character]
            if (digit != null) {
                current = digit.toLong()
                continue
            }
            val smallUnit = KANJI_SMALL_UNITS[character]
            if (smallUnit != null) {
                section += (if (current == 0L) 1L else current) * smallUnit
                current = 0L
                continue
            }
            val largeUnit = KANJI_LARGE_UNITS[character]
            if (largeUnit != null) {
                section += current
                if (section == 0L) section = 1L
                total += section * largeUnit
                section = 0L
                current = 0L
                continue
            }
            return null
        }
        return total + section + current
    }

    private fun cardinalReading(digits: String): String? {
        if (digits.isEmpty()) return null
        if (digits.length > 16 || (digits.length > 1 && digitValue(digits.first()) == 0)) {
            return digits.map { digitReading(digitValue(it)) }.joinToString("")
        }
        val asciiDigits = digits.map { digitValue(it) }.joinToString("")
        val value = asciiDigits.toLongOrNull() ?: return null
        if (value == 0L) return "ゼロ"

        val groups = asciiDigits.padStart(((asciiDigits.length + 3) / 4) * 4, '0')
            .chunked(4)
        val result = StringBuilder()
        groups.forEachIndexed { index, group ->
            val number = group.toInt()
            if (number == 0) return@forEachIndexed
            val groupReading = belowTenThousand(number)
            val unitIndex = groups.size - index - 1
            if (unitIndex >= LARGE_UNITS.size) return null
            result.append(groupReading)
            if (unitIndex > 0) result.append(LARGE_UNITS[unitIndex])
        }
        return result.toString()
    }

    private fun belowTenThousand(value: Int): String {
        var remaining = value
        val result = StringBuilder()
        val thousands = remaining / 1000
        if (thousands > 0) {
            result.append(
                when (thousands) {
                    1 -> "せん"
                    2 -> "にせん"
                    3 -> "さんぜん"
                    4 -> "よんせん"
                    5 -> "ごせん"
                    6 -> "ろくせん"
                    7 -> "ななせん"
                    8 -> "はっせん"
                    else -> "きゅうせん"
                }
            )
            remaining %= 1000
        }
        val hundreds = remaining / 100
        if (hundreds > 0) {
            result.append(
                when (hundreds) {
                    1 -> "ひゃく"
                    2 -> "にひゃく"
                    3 -> "さんびゃく"
                    4 -> "よんひゃく"
                    5 -> "ごひゃく"
                    6 -> "ろっぴゃく"
                    7 -> "ななひゃく"
                    8 -> "はっぴゃく"
                    else -> "きゅうひゃく"
                }
            )
            remaining %= 100
        }
        val tens = remaining / 10
        if (tens > 0) {
            result.append(if (tens == 1) "じゅう" else digitReading(tens) + "じゅう")
            remaining %= 10
        }
        if (remaining > 0) result.append(digitReading(remaining))
        return result.toString()
    }

    private fun counterReading(digits: String, counter: String): String? {
        val value = digits.map { digitValue(it) }.joinToString("").toLongOrNull() ?: return null
        val cardinal = cardinalReading(digits) ?: return null
        return when (counter) {
            "つ" -> when (value) {
                1L -> "ひとつ"
                2L -> "ふたつ"
                3L -> "みっつ"
                4L -> "よっつ"
                5L -> "いつつ"
                6L -> "むっつ"
                7L -> "ななつ"
                8L -> "やっつ"
                9L -> "ここのつ"
                10L -> "とお"
                else -> null
            }
            "人" -> when (value) {
                1L -> "ひとり"
                2L -> "ふたり"
                4L -> "よにん"
                else -> cardinal + "にん"
            }
            "本" -> counterSoundChange(cardinal, value, "いち", "いっぽん", "さん", "さんぼん", "ろく", "ろっぽん", "はち", "はっぽん", "ほん")
            "個" -> counterSoundChange(cardinal, value, "いち", "いっこ", "ろく", "ろっこ", "はち", "はっこ", "こ")
            "冊" -> counterSoundChange(cardinal, value, "いち", "いっさつ", "はち", "はっさつ", "じゅう", "じゅっさつ", "さつ")
            "匹" -> counterSoundChange(cardinal, value, "いち", "いっぴき", "さん", "さんびき", "ろく", "ろっぴき", "はち", "はっぴき", "ひき")
            "杯" -> counterSoundChange(cardinal, value, "いち", "いっぱい", "さん", "さんばい", "ろく", "ろっぱい", "はち", "はっぱい", "はい")
            "歳", "才" -> counterSoundChange(cardinal, value, "いち", "いっさい", "はち", "はっさい", "じゅう", "じゅっさい", "さい")
            "階" -> counterSoundChange(cardinal, value, "いち", "いっかい", "さん", "さんがい", "ろく", "ろっかい", "はち", "はっかい", "かい")
            "回" -> counterSoundChange(cardinal, value, "いち", "いっかい", "さん", "さんかい", "ろく", "ろっかい", "はち", "はっかい", "かい")
            "分" -> minuteReading(cardinal, value)
            "時" -> hourReading(value, cardinal)
            "日" -> dayReading(value, cardinal)
            "月" -> monthReading(value, cardinal)
            "年" -> yearReading(value, cardinal)
            "台" -> cardinal + "だい"
            "枚" -> cardinal + "まい"
            "名" -> cardinal + "めい"
            "頭" -> cardinal + "とう"
            "羽" -> cardinal + "わ"
            "番" -> cardinal + "ばん"
            "番目" -> cardinal + "ばんめ"
            "号" -> cardinal + "ごう"
            "件" -> cardinal + "けん"
            "着" -> cardinal + "ちゃく"
            "通" -> cardinal + "つう"
            "円" -> cardinal + "えん"
            else -> null
        }
    }

    private fun counterSoundChange(
        cardinal: String,
        value: Long,
        vararg forms: String,
    ): String {
        if (forms.isEmpty()) return cardinal
        val plainSuffix = forms.last()
        val variants = forms.dropLast(1).chunked(2).associate { it[0] to it[1] }
        val lastDigit = (value % 10).toInt()
        val stem = when (lastDigit) {
            1 -> "いち"
            3 -> "さん"
            6 -> "ろく"
            8 -> "はち"
            else -> null
        }
        return when {
            stem != null && variants.containsKey(stem) ->
                cardinal.removeSuffix(stem) + variants.getValue(stem)
            value % 100 == 10L -> cardinal.removeSuffix("じゅう") + "じゅっ" + tenSuffix(plainSuffix)
            else -> cardinal + plainSuffix
        }
    }

    private fun tenSuffix(plainSuffix: String): String = when (plainSuffix) {
        "ほん" -> "ぽん"
        "ひき" -> "ぴき"
        "はい" -> "ぱい"
        else -> plainSuffix
    }

    private fun minuteReading(cardinal: String, value: Long): String {
        return when ((value % 10).toInt()) {
            1 -> cardinal.removeSuffix("いち") + "いっぷん"
            3 -> cardinal.removeSuffix("さん") + "さんぷん"
            4 -> cardinal.removeSuffix("よん") + "よんぷん"
            6 -> cardinal.removeSuffix("ろく") + "ろっぷん"
            8 -> cardinal.removeSuffix("はち") + "はっぷん"
            0 -> if (value % 100L == 0L) cardinal + "ふん"
            else cardinal.removeSuffix("じゅう") + "じゅっぷん"
            else -> cardinal + "ふん"
        }
    }

    private fun hourReading(value: Long, cardinal: String): String = when (value) {
        4L -> "よじ"
        7L -> "しちじ"
        9L -> "くじ"
        else -> cardinal + "じ"
    }

    private fun dayReading(value: Long, cardinal: String): String = when (value) {
        1L -> "ついたち"
        2L -> "ふつか"
        3L -> "みっか"
        4L -> "よっか"
        5L -> "いつか"
        6L -> "むいか"
        7L -> "なのか"
        8L -> "ようか"
        9L -> "ここのか"
        10L -> "とおか"
        14L -> "じゅうよっか"
        20L -> "はつか"
        24L -> "にじゅうよっか"
        else -> cardinal + "にち"
    }

    private fun monthReading(value: Long, cardinal: String): String = when (value) {
        4L -> "しがつ"
        7L -> "しちがつ"
        9L -> "くがつ"
        else -> cardinal + "がつ"
    }

    private fun yearReading(value: Long, cardinal: String): String = when (value % 10) {
        4L -> cardinal.removeSuffix("よん") + "よねん"
        7L -> cardinal.removeSuffix("なな") + "ななねん"
        9L -> cardinal.removeSuffix("きゅう") + "きゅうねん"
        else -> cardinal + "ねん"
    }

    private fun digitReading(value: Int): String = when (value) {
        0 -> "ゼロ"
        1 -> "いち"
        2 -> "に"
        3 -> "さん"
        4 -> "よん"
        5 -> "ご"
        6 -> "ろく"
        7 -> "なな"
        8 -> "はち"
        9 -> "きゅう"
        else -> error("无效数字：$value")
    }

    private val LARGE_UNITS = listOf("", "まん", "おく", "ちょう", "けい")
    private val NUMERIC_SEPARATORS = setOf('.', ',', '/', ':', '-')

    private val KANJI_DIGITS = mapOf(
        '零' to 0,
        '〇' to 0,
        '一' to 1,
        '二' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
        '壹' to 1,
        '贰' to 2,
        '貳' to 2,
        '叁' to 3,
        '參' to 3,
        '肆' to 4,
        '伍' to 5,
        '陆' to 6,
        '陸' to 6,
        '柒' to 7,
        '捌' to 8,
        '玖' to 9,
    )

    private val KANJI_SMALL_UNITS = mapOf(
        '十' to 10L,
        '拾' to 10L,
        '百' to 100L,
        '佰' to 100L,
        '千' to 1_000L,
        '仟' to 1_000L,
    )

    private val KANJI_LARGE_UNITS = mapOf(
        '万' to 10_000L,
        '萬' to 10_000L,
        '亿' to 100_000_000L,
        '億' to 100_000_000L,
        '兆' to 1_000_000_000_000L,
    )

    private val KANJI_NUMERIC_CHARS =
        KANJI_DIGITS.keys + KANJI_SMALL_UNITS.keys + KANJI_LARGE_UNITS.keys

    private val COUNTERS = listOf(
        "番目", "歳", "才", "本", "個", "冊", "匹", "杯", "階", "回", "分", "時",
        "日", "月", "年", "台", "枚", "名", "頭", "羽", "番", "号", "件", "着", "通", "円", "つ", "人",
    ).sortedByDescending { it.length }
}
