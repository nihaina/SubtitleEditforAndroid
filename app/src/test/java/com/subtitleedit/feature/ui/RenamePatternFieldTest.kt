package com.subtitleedit.feature.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.subtitleedit.util.BatchRename
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenamePatternFieldTest {
    private val fileName = BatchRename.FILE_NAME_TOKEN
    private val number = BatchRename.NUMBER_TOKEN
    private val transformation = RenamePatternTransformation(Color.Blue)

    @Test
    fun placeholdersRenderAsBlueLabelsWithoutChangingPlainText() {
        val rendered = transformation.filter(AnnotatedString("前缀${fileName}(${number})文件名"))

        assertEquals("前缀文件名(序号)文件名", rendered.text.text)
        assertEquals(2, rendered.text.spanStyles.size)
        assertEquals(listOf("文件名", "序号"), rendered.text.spanStyles.map {
            assertEquals(Color.Blue, it.item.color)
            rendered.text.text.substring(it.start, it.end)
        })
    }

    @Test
    fun cursorBoundariesRoundTripAndTapsInsideLabelsSnapToAnEdge() {
        val rendered = transformation.filter(AnnotatedString("a${fileName}(${number})z"))
        val mapping = rendered.offsetMapping

        assertEquals(listOf(0, 1, 4, 5, 7, 8, 9), (0..6).map(mapping::originalToTransformed))
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 6), (0..9).map(mapping::transformedToOriginal))
        (0..6).forEach { original ->
            assertEquals(original, mapping.transformedToOriginal(mapping.originalToTransformed(original)))
        }
    }

    @Test
    fun backspaceAndForwardDeleteRemoveWholeVisiblePlaceholders() {
        listOf(fileName to "文件名", number to "序号").forEach { (token, label) ->
            val original = "a${token}z"
            val mapping = transformation.filter(AnnotatedString(original)).offsetMapping
            val before = mapping.transformedToOriginal(1)
            val after = mapping.transformedToOriginal(1 + label.length)

            val afterBackspace = original.removeRange(after - 1, after)
            val afterForwardDelete = original.removeRange(before, before + 1)

            assertEquals("az", transformation.filter(AnnotatedString(afterBackspace)).text.text)
            assertEquals("az", transformation.filter(AnnotatedString(afterForwardDelete)).text.text)
        }
    }

    @Test
    fun insertionUsesTheVisibleCursorPositionBetweenPlaceholders() {
        val original = "prefix${fileName}suffix"
        val mapping = transformation.filter(AnnotatedString(original)).offsetMapping
        val cursor = mapping.transformedToOriginal("prefix文件名".length)
        val result = insertRenameToken(TextFieldValue(original, TextRange(cursor)), number, useSelection = true)

        assertEquals("prefix文件名序号suffix", transformation.filter(AnnotatedString(result.text)).text.text)
        assertEquals(TextRange("prefix".length + 2), result.selection)
    }

    @Test
    fun insertionReplacesTheEntireSelectionIncludingReversedSelections() {
        val original = "a${fileName}replace${number}z"
        val start = 1
        val end = original.length - 1
        val forward = insertRenameToken(TextFieldValue(original, TextRange(start, end)), number, true)
        val reversed = insertRenameToken(TextFieldValue(original, TextRange(end, start)), number, true)

        assertEquals("a${number}z", forward.text)
        assertEquals(forward, reversed)
        assertEquals(TextRange(2), forward.selection)
    }

    @Test
    fun unfocusedInsertionAppendsAndDropsAnyStaleSelectionOrImeComposition() {
        val result = insertRenameToken(
            TextFieldValue("abc", selection = TextRange(0, 2), composition = TextRange(0, 2)),
            fileName,
            useSelection = false
        )

        assertEquals("abc$fileName", result.text)
        assertEquals(TextRange(4), result.selection)
        assertEquals(null, result.composition)
    }

    @Test
    fun emptyAndAdjacentTokensKeepAllOffsetsValidAndMonotonic() {
        listOf("", fileName, number, "$fileName$number$fileName", "普通文件名").forEach { original ->
            val rendered = transformation.filter(AnnotatedString(original))
            val offsets = (0..rendered.text.length).map(rendered.offsetMapping::transformedToOriginal)

            assertTrue(offsets.all { it in 0..original.length })
            assertTrue(offsets.zipWithNext().all { (left, right) -> left <= right })
            assertEquals(original.length, offsets.last())
        }
    }
}
