package com.subtitleedit

/** Selection type is based on real media streams; embedded artwork does not make audio a video. */
internal enum class MediaConvertMetadataSelection {
    EMPTY, READING, UNAVAILABLE, SINGLE_VIDEO, SINGLE_AUDIO, BATCH_VIDEO, BATCH_AUDIO, MIXED;

    val editable: Boolean get() = this in setOf(SINGLE_VIDEO, SINGLE_AUDIO, BATCH_VIDEO, BATCH_AUDIO)
    val batch: Boolean get() = this == BATCH_VIDEO || this == BATCH_AUDIO
    val audio: Boolean get() = this == SINGLE_AUDIO || this == BATCH_AUDIO

    companion object {
        fun fromSources(sources: List<MediaConvertSource?>, pending: Boolean): MediaConvertMetadataSelection {
            if (sources.isEmpty()) return EMPTY
            if (pending) return READING
            if (sources.any { it == null || (!it.hasVideo && !it.hasAudio) }) return UNAVAILABLE
            val videoCount = sources.count { it?.hasVideo == true }
            if (videoCount != 0 && videoCount != sources.size) return MIXED
            return if (videoCount == sources.size) {
                if (sources.size == 1) SINGLE_VIDEO else BATCH_VIDEO
            } else {
                if (sources.size == 1) SINGLE_AUDIO else BATCH_AUDIO
            }
        }
    }
}
