package com.subtitleedit.translation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextTranslationCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun newInstanceReusesSuccessfulTranslationForTheSameRouteAndText() {
        val directory = File(temp.root, "translations")
        val text = "A train\r\narrived at the station 🚆"
        val translation = "一列火车\r\n到站了 🚆"
        TextTranslationCache(directory).put("en-zh:pack-v1", text, translation)

        val reopened = TextTranslationCache(directory)
        assertEquals(translation, reopened.get("en-zh:pack-v1", text))
        assertNull(reopened.get("en-zh:pack-v2", text))
        assertNull(reopened.get("en-zh:pack-v1", text.replace("\r\n", "\n")))
        assertFalse(directory.listFiles()!!.single().readBytes().decodeToString().contains(text))
    }

    @Test fun compoundKeyDistinguishesSeparatorsFromText() {
        val cache = TextTranslationCache(temp.root)
        cache.put("route\u0000one", "text", "译文")
        assertNull(cache.get("route", "one\u0000text"))
        assertEquals("译文", cache.get("route\u0000one", "text"))
    }

    @Test fun blankAndCorruptedValuesCanBeRetried() {
        val directory = temp.newFolder()
        val cache = TextTranslationCache(directory)
        cache.put("route", "text", "\t\r\n")
        assertTrue(directory.listFiles()!!.isEmpty())
        cache.put("route", "text", "译文")
        val file = directory.listFiles()!!.single()
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = 'x'.code.toByte()
        file.writeBytes(bytes)

        assertNull(cache.get("route", "text"))
        cache.put("route", "text", "重试后的译文")
        assertEquals("重试后的译文", cache.get("route", "text"))
    }

    @Test fun aFullCacheEvictsTheOldestEntry() {
        val directory = temp.newFolder()
        val oldest = File(directory, "${"0".repeat(64)}.translation").apply {
            writeText("oldest")
            assertTrue(setLastModified(1_000L))
        }
        for (index in 1 until 1024) {
            File(directory, "${index.toString(16).padStart(64, '0')}.translation").writeText("old")
        }
        val cache = TextTranslationCache(directory)
        cache.put("route", "new", "最新译文")

        assertFalse(oldest.exists())
        assertEquals(1024, directory.listFiles()!!.size)
        assertEquals("最新译文", cache.get("route", "new"))
    }
}
