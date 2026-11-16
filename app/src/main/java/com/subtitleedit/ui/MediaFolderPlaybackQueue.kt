package com.subtitleedit.ui

import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import java.io.File

/** The visible folder's playable files, in the same order as its rows. */
internal fun mediaFolderPlaybackQueue(entries: List<MediaFolderEntry>): List<MediaLibraryTrack> =
    entries.asSequence().filterNot { it.isDirectory }.mapNotNull { entry ->
        val file = File(entry.path)
        val video = FileTypePolicy.isVideo(file)
        if (!video && !FileUtils.isAudioFile(file)) null
        else MediaLibraryTrack(entry.path, entry.name, isVideo = video, durationMs = entry.totalDurationMs)
    }.distinctBy { it.path }.toList()
