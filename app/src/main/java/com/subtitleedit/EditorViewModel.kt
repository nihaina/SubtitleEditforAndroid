package com.subtitleedit

import androidx.lifecycle.ViewModel
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal class EditorViewModel : ViewModel() {
    var initialized = false
    var documentLoaded = false
    var filePath = ""
    var currentFile: File? = null
    var subtitleFilePath = ""
    var subtitleFile: File? = null
    var documentUri: String? = null
    var documentTitle = "未命名"
    var subtitleEntries = mutableListOf<SubtitleEntry>()
    var lastIndexedEntryCount = -1
    var currentCharset: Charset = StandardCharsets.UTF_8
    var currentFormat: SubtitleParser.SubtitleFormat = SubtitleParser.SubtitleFormat.UNKNOWN
    var isSourceViewMode = false
    var originalFileContent = ""
    var sourceViewContent = ""
    var savedScrollPosition = 0
    var savedFirstVisibleItemPosition = 0
    var selectedIndices: Set<Int> = emptySet()
    var playbackPositionMs = 0L
    var playbackSpeed = 1.0f
    var isVideoCollapsed = false
    var selectedAudioStreamIndex: Int? = null
    var hasUnsavedChanges = false
    var isNewFile = true
    var currentFormatInfo = ""
    var clipboardTexts: List<String> = emptyList()
    var mediaType = EditorMediaType.SUBTITLE_ONLY
    val saveCoordinator = EditorSaveCoordinator()
}
