package com.subtitleedit.util

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Qwen3ForcedAlignmentInputTest {
    private fun encoded() = QwenHuggingFaceTokenizer.EncodedText(
        inputIds = longArrayOf(151669, 151676, 151670, 9707, 151705, 151705, 14957, 151705, 151705),
        timestampPositions = intArrayOf(4, 5, 7, 8),
        units = listOf("Hello", "world"),
    )

    @Test fun followsOfficialChunkBoundaries() {
        val frames = intArrayOf(1, 7, 8, 9, 51, 99, 100, 101, 199, 200, 337, 420, 3000)
        val tokens = intArrayOf(1, 1, 1, 2, 7, 13, 13, 14, 26, 26, 44, 55, 390)
        frames.indices.forEach { assertEquals("frames=${frames[it]}", tokens[it], Qwen3ForcedAlignmentInput.audioTokenCount(frames[it])) }
    }

    @Test fun expandsAudioOnlyAndMovesAllTimestampPositions() {
        val raw = encoded()
        val expanded = Qwen3ForcedAlignmentInput.expand(raw, 200)
        assertEquals(34, expanded.inputIds.size)
        assertEquals(26, expanded.inputIds.count { it == 151676L })
        assertArrayEquals(intArrayOf(29, 30, 32, 33), expanded.timestampPositions)
        assertEquals(raw.units, expanded.units)
        assertArrayEquals(raw.inputIds.copyOfRange(2, raw.inputIds.size), expanded.inputIds.copyOfRange(27, expanded.inputIds.size))
        assertTrue(expanded.timestampPositions.all { expanded.inputIds[it] == 151705L })
        assertArrayEquals(intArrayOf(4, 5, 7, 8), raw.timestampPositions)
        assertEquals(9, raw.inputIds.size)
    }

    @Test fun minimumAudioLengthKeepsTimestampPositions() {
        val raw = encoded()
        val expanded = Qwen3ForcedAlignmentInput.expand(raw, 1)
        assertArrayEquals(raw.inputIds, expanded.inputIds)
        assertArrayEquals(raw.timestampPositions, expanded.timestampPositions)
    }

    @Test fun rejectsBadFramesWrapperOrTimestampPositions() {
        assertThrows(IllegalArgumentException::class.java) { Qwen3ForcedAlignmentInput.expand(encoded(), 0) }
        assertThrows(IllegalArgumentException::class.java) { Qwen3ForcedAlignmentInput.expand(encoded(), -1) }
        val noPad = encoded().let { it.copy(inputIds = it.inputIds.map { id -> if (id == 151676L) 0L else id }.toLongArray()) }
        assertThrows(IllegalArgumentException::class.java) { Qwen3ForcedAlignmentInput.expand(noPad, 200) }
        assertThrows(IllegalArgumentException::class.java) {
            Qwen3ForcedAlignmentInput.expand(encoded().copy(timestampPositions = intArrayOf(3, 4, 7, 8)), 200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Qwen3ForcedAlignmentInput.expand(Qwen3ForcedAlignmentInput.expand(encoded(), 200), 200)
        }
    }

    @Test fun matchesOfficialProcessorFixturesForAllIdsAndPositions() {
        val text = requireNotNull(javaClass.getResourceAsStream("/qwen3_forced_alignment_inputs.json"))
            .bufferedReader().use { it.readText() }
        val cases = JSONObject(text).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            fun longs(name: String) = case.getJSONArray(name).let { array -> LongArray(array.length()) { array.getLong(it) } }
            fun ints(name: String) = case.getJSONArray(name).let { array -> IntArray(array.length()) { array.getInt(it) } }
            val raw = QwenHuggingFaceTokenizer.EncodedText(
                longs("raw_ids"), ints("raw_timestamp_positions"),
                case.getJSONArray("units").let { units -> List(units.length()) { units.getString(it) } },
            )
            val expanded = Qwen3ForcedAlignmentInput.expand(raw, case.getInt("frames"))
            assertArrayEquals(case.getString("text"), longs("expanded_ids"), expanded.inputIds)
            assertArrayEquals(ints("expanded_timestamp_positions"), expanded.timestampPositions)
        }
    }
}
