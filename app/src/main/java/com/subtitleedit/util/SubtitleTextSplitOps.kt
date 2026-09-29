package com.subtitleedit.util

import android.content.Context
import android.icu.text.BreakIterator
import android.os.Build
import android.os.LocaleList
import android.view.textclassifier.TextClassificationContext
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextSelection
import java.util.Locale
import kotlin.math.ceil

/** Text boundary selection shared by quick split and the source editor's long-press behavior. */
object SubtitleTextSplitOps {
    data class Result(val left: String, val right: String)

    fun split(context: Context, text: String, ratio: Float): Result {
        if (text.length <= 1) return Result(text, "新字幕")
        val candidate = ceil(text.length * ratio.coerceIn(0f, 1f))
            .toInt()
            .coerceIn(1, text.length - 1)
        val punctuation = punctuationAfter(text, candidate)
        val splitOffset = punctuation ?: wordBoundary(context, text, candidate)
        val safeOffset = splitOffset.coerceIn(1, text.length - 1)
        return Result(text.substring(0, safeOffset), text.substring(safeOffset))
    }

    private fun punctuationAfter(text: String, candidate: Int): Int? {
        // Keep punctuation attached to the left cue when it is at the proposed boundary.
        val index = candidate.coerceAtMost(text.lastIndex)
        if (isPunctuation(text[index])) return index + 1
        if (index > 0 && isPunctuation(text[index - 1])) return index
        return null
    }

    private fun wordBoundary(context: Context, text: String, candidate: Int): Int {
        // At the first character the requested position is already the most precise boundary.
        // This also keeps a short cue such as “你好世界” from jumping over its first character.
        if (candidate == 1) return 1
        val iterator = BreakIterator.getWordInstance(Locale.getDefault()).apply {
            setText(text)
        }
        if (iterator.isBoundary(candidate)) {
            // A boundary normally belongs to the left cue. When two adjacent single-character
            // CJK words meet, leave the second one for the right cue, matching source selection.
            val previous = wordSelectionRange(text, candidate - 1)
            if (previous.second == candidate && previous.second - previous.first == 1 &&
                previous.first > 0
            ) {
                val beforePrevious = wordSelectionRange(text, previous.first - 1)
                if (beforePrevious.second == previous.first &&
                    beforePrevious.second - beforePrevious.first == 1
                ) return previous.first
            }
            return candidate
        }
        val localized = wordSelectionRange(text, candidate)
        val suggested = suggestTextClassifierSelection(context, text, localized.first, localized.second)
        val range = if (suggested != null && suggested.first <= localized.first &&
            suggested.second >= localized.second
        ) suggested else localized

        // The candidate is inside a word, so consume the complete matching word.
        return range.second.coerceIn(1, text.length - 1)
    }

    private fun wordSelectionRange(text: CharSequence, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val pivot = offset.coerceIn(0, text.length - 1)
        val iterator = BreakIterator.getWordInstance(Locale.getDefault())
        iterator.setText(text.toString())
        if (!isLetterOrDigitAt(text, pivot)) return characterClusterRange(text, pivot)
        if (pivot > 0 && pivot < text.length && isCjk(text[pivot - 1]) &&
            isCjk(text[pivot]) && iterator.isBoundary(pivot)
        ) {
            val rightOffset = (pivot + 1).coerceAtMost(text.length)
            val start = wordBeginning(iterator, text, rightOffset)
            val end = wordEnd(iterator, text, rightOffset)
            if (start >= 0 && end > start) return start to end
        }
        val start = wordBeginning(iterator, text, pivot)
        val end = wordEnd(iterator, text, pivot)
        if (start >= 0 && end > start) return start to end
        return fallbackWordRange(text, pivot)
    }

    private fun wordBeginning(iterator: BreakIterator, text: CharSequence, offset: Int): Int {
        if (isLetterOrDigitAt(text, offset)) {
            if (iterator.isBoundary(offset) && !isLetterOrDigitBefore(text, offset)) return offset
            return iterator.preceding(offset)
        }
        if (isLetterOrDigitBefore(text, offset)) return iterator.preceding(offset)
        return BreakIterator.DONE
    }

    private fun wordEnd(iterator: BreakIterator, text: CharSequence, offset: Int): Int {
        if (isLetterOrDigitBefore(text, offset)) {
            if (iterator.isBoundary(offset) && !isLetterOrDigitAt(text, offset)) return offset
            return iterator.following(offset)
        }
        if (isLetterOrDigitAt(text, offset)) return iterator.following(offset)
        return BreakIterator.DONE
    }

    private fun fallbackWordRange(text: CharSequence, pivot: Int): Pair<Int, Int> {
        if (isCjk(text[pivot])) return characterClusterRange(text, pivot)
        if (isLetterOrDigitAt(text, pivot)) {
            var start = pivot
            var end = pivot + 1
            while (start > 0 && isLetterOrDigitAt(text, start - 1)) start--
            while (end < text.length && isLetterOrDigitAt(text, end)) end++
            return start to end
        }
        return characterClusterRange(text, pivot)
    }

    private fun characterClusterRange(text: CharSequence, pivot: Int): Pair<Int, Int> {
        val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
        iterator.setText(text.toString())
        val start = iterator.preceding((pivot + 1).coerceAtMost(text.length))
        val end = iterator.following(pivot)
        return (if (start == BreakIterator.DONE) 0 else start) to
            (if (end == BreakIterator.DONE) text.length else end)
    }

    private fun suggestTextClassifierSelection(
        context: Context,
        text: String,
        start: Int,
        end: Int
    ): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || start >= end) return null
        val manager = context.getSystemService(TextClassificationManager::class.java) ?: return null
        val classifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.createTextClassificationSession(
                TextClassificationContext.Builder(
                    context.packageName,
                    TextClassifier.WIDGET_TYPE_EDITTEXT
                ).build()
            )
        } else manager.textClassifier
        return try {
            val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales.takeUnless { it.isEmpty }
                    ?: LocaleList(Locale.getDefault())
            } else LocaleList(Locale.getDefault())
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val request = TextSelection.Request.Builder(text, start, end)
                    .setDefaultLocales(locales)
                    .build()
                classifier.suggestSelection(request)
            } else {
                @Suppress("DEPRECATION")
                classifier.suggestSelection(text, start, end, locales)
            }
            val suggestedStart = selection.selectionStartIndex.coerceIn(0, text.length)
            val suggestedEnd = selection.selectionEndIndex.coerceIn(suggestedStart, text.length)
            suggestedStart to suggestedEnd
        } catch (_: Throwable) {
            null
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) classifier.destroy()
        }
    }

    private fun isLetterOrDigitAt(text: CharSequence, offset: Int): Boolean =
        offset in text.indices && Character.isLetterOrDigit(Character.codePointAt(text, offset))

    private fun isLetterOrDigitBefore(text: CharSequence, offset: Int): Boolean =
        offset > 0 && offset <= text.length && Character.isLetterOrDigit(Character.codePointBefore(text, offset))

    private fun isCjk(character: Char): Boolean = when (Character.UnicodeScript.of(character.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    private fun isPunctuation(character: Char): Boolean =
        Character.getType(character) in setOf<Int>(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt()
        )
}
