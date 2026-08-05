package com.subtitleedit.editor

internal enum class EditorMediaType {
    SUBTITLE_ONLY,
    AUDIO,
    VIDEO;

    val hasPlayableMedia: Boolean
        get() = this != SUBTITLE_ONLY

    companion object {
        fun fromIntentValue(value: String?): EditorMediaType =
            entries.firstOrNull { it.name == value } ?: SUBTITLE_ONLY
    }
}
