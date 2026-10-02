package com.subtitleedit.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class EditorSourceWordSelectionTest {
    @Test
    fun longPressSelectsPunctuationSeparately() {
        assertEquals(5 to 6, sourceWordSelectionRange("hello,world", 5, Locale.ENGLISH))
    }

    @Test
    fun longPressSelectsAUnicodeCharacterCluster() {
        assertEquals(1 to 3, sourceWordSelectionRange("a😊b", 1, Locale.ENGLISH))
    }

    @Test
    fun longPressSelectsAWordInsteadOfTheWholePhysicalLine() {
        assertEquals(6 to 11, sourceWordSelectionRange("hello world", 7, Locale.ENGLISH))
    }
}
