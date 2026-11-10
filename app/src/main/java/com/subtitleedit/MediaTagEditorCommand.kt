package com.subtitleedit

internal data class MediaTagStream(
    val index: Long,
    val type: String,
    val attachedPicture: Boolean = false,
    val coverAttachment: Boolean = false
)

/** Rewrites tags in the original container without dropping or encoding any media streams. */
internal object MediaTagEditorCommand {
    fun supportsCover(muxer: String): Boolean = muxer in
        setOf("mp3", "mp4", "ipod", "mov", "flac", "matroska", "adts")

    fun build(
        input: String,
        output: String,
        muxer: String,
        streams: List<MediaTagStream>,
        metadata: MediaConvertMetadata,
        isAudio: Boolean
    ): List<String> {
        require(streams.isNotEmpty()) { "The source has no streams" }
        val customCover = metadata.coverPath?.takeIf { metadata.cover == MediaConvertCoverMode.CUSTOM }
        if (metadata.cover == MediaConvertCoverMode.CUSTOM) {
            require(supportsCover(muxer)) { "The source container does not support cover images" }
            require(!customCover.isNullOrBlank()) { "The selected cover image must be prepared before saving" }
        }
        val removed = if (metadata.cover == MediaConvertCoverMode.DEFAULT) emptyList()
            else streams.filter { it.attachedPicture || it.coverAttachment }
        val retained = streams - removed.toSet()
        return buildList {
            addAll(listOf("-y", "-hide_banner", "-i", input))
            if (customCover != null && muxer != "matroska") addAll(listOf("-i", customCover))
            // Keep all audio tracks, subtitles, data streams, font attachments and dispositions.
            addAll(listOf("-map", "0", "-copy_unknown"))
            removed.forEach { addAll(listOf("-map", "-0:${it.index}")) }
            if (customCover != null && muxer != "matroska") addAll(listOf("-map", "1:v:0"))
            addAll(listOf("-c", "copy", "-map_metadata", "0", "-map_chapters", "0"))
            if (muxer == "adts") addAll(listOf("-write_id3v2", "1"))
            if (customCover != null) {
                if (muxer == "matroska") {
                    val attachmentIndex = retained.count { it.type == "attachment" }
                    addAll(listOf("-attach", customCover,
                        "-metadata:s:t:$attachmentIndex", "mimetype=image/jpeg",
                        "-metadata:s:t:$attachmentIndex", "filename=cover.jpg"))
                } else {
                    val videoIndex = retained.count { it.type == "video" }
                    addAll(listOf("-disposition:v:$videoIndex", "attached_pic"))
                }
            }
            val tagArguments = metadata.arguments(isAudio)
            addAll(tagArguments)
            // The editor reads tags from the container, then the primary media streams.
            // Ogg comments live on the audio stream, so global overrides alone are ignored.
            // Clearing a value must also remove these fallback tags, while other tracks keep
            // their own names and metadata.
            val streamTags = tagArguments.chunked(2).map { it[1] }.toMutableList()
            if (isAudio && MediaConvertMetadataField.ARTIST in metadata.editedFields && metadata.artist.isEmpty()) {
                addAll(listOf("-metadata", "author="))
                streamTags += "author="
            }
            val primaryAudio = retained.indexOfFirst { it.type == "audio" }
            if (muxer in setOf("ogg", "opus")) {
                if (primaryAudio >= 0) streamTags.forEach { tag ->
                    addAll(listOf("-metadata:s:$primaryAudio", tag))
                }
            } else {
                val primaryVideo = retained.indexOfFirst { it.type == "video" && !it.attachedPicture }
                val primaryStreams = listOf(primaryVideo, primaryAudio).filter { it >= 0 }
                streamTags.filter { it.endsWith('=') }.forEach { tag ->
                    primaryStreams.forEach { index -> addAll(listOf("-metadata:s:$index", tag)) }
                }
            }
            addAll(listOf("-f", muxer, output))
        }
    }
}
