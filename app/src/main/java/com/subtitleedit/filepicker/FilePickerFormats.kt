package com.subtitleedit.filepicker

import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils

/** Filename aliases for media formats already recognized by the application's media tools. */
internal object FilePickerFormats {
    val audioExtensions = FileUtils.AUDIO_EXTENSIONS + setOf(
        "m4b", "oga", "opus", "ac3", "amr", "aif", "aiff", "mka", "alac",
        "tta", "wv", "mid", "midi", "3ga"
    )
    val videoExtensions = FileTypePolicy.videoExtensions
}
