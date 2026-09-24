package com.subtitleedit.util

import java.io.File

/** Official-assets-only text side of Qwen3 ForcedAligner input preparation. */
internal class Qwen3ForcedAlignmentTextEncoder(
    tokenizerDirectory: File,
) : AutoCloseable {
    private val tokenizer = QwenHuggingFaceTokenizer.open(tokenizerDirectory)

    fun encode(text: String, language: String, validFrames: Int): QwenHuggingFaceTokenizer.EncodedText =
        Qwen3ForcedAlignmentInput.expand(tokenizer.encodeForForcedAlignment(text, language), validFrames)

    override fun close() = tokenizer.close()
}
