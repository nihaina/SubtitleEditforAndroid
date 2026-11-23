package com.subtitleedit.translation

import com.subtitleedit.util.TxtTranslationSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class TextDocumentTranslationResult(
    val text: String,
    val missingModels: Set<PageTranslationModel> = emptySet(),
    val hasFailures: Boolean = false,
)

internal class TextDocumentPreviewer {
    suspend fun preview(
        text: String,
        plan: PageTranslationPlan,
        session: PageTranslationSession,
        wordStart: (String, Int) -> Int,
    ): TextDocumentTranslationResult {
        currentCoroutineContext().ensureActive()
        if (plan.options.source == plan.options.target) return TextDocumentTranslationResult(text)

        val segments = TxtTranslationSegmenter.split(text, wordStart = wordStart)
        val missingModels = linkedSetOf<PageTranslationModel>()
        val missingBySource = mutableMapOf<PageTranslationLanguage, Set<PageTranslationModel>>()
        val pendingOriginals = mutableListOf<TxtTranslationSegmenter.Segment>()
        val pendingTranslations = mutableListOf<String>()
        var output: StringBuilder? = null
        var sourceOffset = 0
        var hasFailures = false

        fun StringBuilder.appendTranslations() {
            if (pendingTranslations.isEmpty()) return
            append(TxtTranslationSegmenter.merge(pendingTranslations, pendingOriginals))
            pendingOriginals.clear()
            pendingTranslations.clear()
        }

        fun append(segment: TxtTranslationSegmenter.Segment, translated: String?) {
            if (translated != null) {
                if (output == null) output = StringBuilder(text.length).append(text, 0, sourceOffset)
                pendingOriginals += segment
                pendingTranslations += translated
            } else {
                // Merging an untranslated run could insert spaces into the original text.
                output?.apply {
                    appendTranslations()
                    append(segment.text)
                }
            }
            sourceOffset += segment.text.length
        }

        for (segment in segments) {
            currentCoroutineContext().ensureActive()
            val original = segment.text
            val language = try {
                val source = if (original.codePoints().anyMatch(Character::isLetter)) {
                    plan.options.source ?: session.identifyLanguage(original)
                } else null
                val route = source?.let(plan::route)
                if (source != null && !route.isNullOrEmpty()) {
                    val missing = missingBySource.getOrPut(source) { session.missingModels(route) }
                    if (missing.isEmpty()) {
                        source
                    } else {
                        missingModels += missing
                        null
                    }
                } else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                hasFailures = true
                null
            }
            if (language == null) {
                append(segment, null)
                continue
            }
            for (chunk in FoxletTextChunker.split(original, language.toLocale())) {
                currentCoroutineContext().ensureActive()
                val input = chunk.text.trim()
                val translated = if (input.codePoints().anyMatch(Character::isLetter)) {
                    try {
                        session.translateDocumentChunk(input, language).trim().also { check(it.isNotEmpty()) }
                            .takeUnless { it == input }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        hasFailures = true
                        null
                    }
                } else null
                append(chunk, translated)
            }
        }

        currentCoroutineContext().ensureActive()
        val translatedText = output?.apply { appendTranslations() }?.toString() ?: text
        return TextDocumentTranslationResult(translatedText, missingModels, hasFailures)
    }
}
