package com.subtitleedit.util

/** Batch-one equivalent of Qwen3ASRProcessor.replace_multimodal_special_tokens(). */
internal object Qwen3ForcedAlignmentInput {
    // Official Qwen3-ForcedAligner-0.6B tokenizer/config IDs.
    const val AUDIO_START_ID = 151669L
    const val AUDIO_PAD_ID = 151676L
    const val AUDIO_END_ID = 151670L
    const val TIMESTAMP_ID = 151705L

    fun audioTokenCount(validFrames: Int): Int {
        require(validFrames > 0) { "Qwen 对齐音频特征帧数必须大于 0" }
        // ceil(remainder / 8); unlike a transcription of Python's // with -1,
        // this also works at exact 100-frame boundaries with Kotlin division.
        return validFrames / 100 * 13 + (validFrames % 100 + 7) / 8
    }

    fun expand(
        encoded: QwenHuggingFaceTokenizer.EncodedText,
        validFrames: Int,
    ): QwenHuggingFaceTokenizer.EncodedText {
        val count = audioTokenCount(validFrames)
        val ids = encoded.inputIds
        val padIndex = ids.indexOf(AUDIO_PAD_ID)
        require(padIndex > 0 && padIndex < ids.lastIndex &&
            ids.count { it == AUDIO_PAD_ID } == 1 &&
            ids[padIndex - 1] == AUDIO_START_ID && ids[padIndex + 1] == AUDIO_END_ID
        ) { "Qwen tokenizer 必须生成一组未展开的 audio_start/audio_pad/audio_end" }
        val timestamps = ids.indices.filter { ids[it] == TIMESTAMP_ID }.toIntArray()
        require(encoded.timestampPositions.contentEquals(timestamps) &&
            timestamps.size == encoded.units.size * 2 &&
            timestamps.isNotEmpty() && timestamps.all { it > padIndex + 1 }
        ) { "Qwen tokenizer 时间戳位置与文本单元不匹配" }
        val extra = count - 1
        require(ids.size.toLong() + extra <= Int.MAX_VALUE) { "Qwen 对齐输入过长" }
        val expandedIds = LongArray(ids.size + extra)
        ids.copyInto(expandedIds, endIndex = padIndex)
        expandedIds.fill(AUDIO_PAD_ID, padIndex, padIndex + count)
        ids.copyInto(expandedIds, destinationOffset = padIndex + count, startIndex = padIndex + 1)
        return encoded.copy(
            inputIds = expandedIds,
            timestampPositions = IntArray(timestamps.size) { timestamps[it] + extra },
        )
    }
}
