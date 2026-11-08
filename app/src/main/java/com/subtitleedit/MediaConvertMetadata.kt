package com.subtitleedit

internal enum class MediaConvertCoverMode { DEFAULT, NONE }

internal data class MediaConvertMetadata(
    val cover: MediaConvertCoverMode = MediaConvertCoverMode.DEFAULT,
    val title: String = "",
    val author: String = "",
    val comment: String = ""
) {
    fun arguments(): List<String> = listOf(
        "-metadata" to "title=$title",
        "-metadata" to "author=$author",
        "-metadata" to "comment=$comment"
    ).flatMap { (option, value) -> listOf(option, value) }

    companion object {
        fun fromTags(value: (String) -> String?): MediaConvertMetadata = MediaConvertMetadata(
            cover = MediaConvertCoverMode.DEFAULT,
            title = value("title").orEmpty(),
            author = value("author") ?: value("artist") ?: "",
            comment = value("comment") ?: value("description") ?: ""
        )
    }
}
