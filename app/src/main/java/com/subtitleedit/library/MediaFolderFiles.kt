package com.subtitleedit.library

import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SharedFileCache
import java.io.File
import java.nio.file.Files

internal data class MediaFolderOrder(val field: FileSortField, val direction: FileSortDirection) {
    fun sort(entries: List<BrowserFileMetadata>): List<BrowserFileMetadata> =
        FileBrowserOrder.sortMetadata(entries, field, direction)
}

internal data class MediaFolderListing(
    val entries: List<BrowserFileMetadata>,
    val tracks: List<MediaLibraryTrack>
)

internal fun loadMediaFolderListing(
    folderStack: List<String>,
    catalog: MediaFolderCatalog,
    order: MediaFolderOrder,
    children: (File) -> List<BrowserFileMetadata> = ::visibleFolderChildren
): MediaFolderListing {
    val directory = File(folderStack.last())
    val tracks = catalog.tracksIn(directory.absolutePath).associateBy { it.path }
    val hasMediaAlongPath = folderStack.any { catalog.tracksIn(it).isNotEmpty() }
    val entries = order.sort(children(directory).filter { entry ->
        if (entry.isDirectory) {
            hasMediaAlongPath || catalog.mediaCount(entry.path) > 0
        } else {
            entry.path in tracks || FileTypePolicy.isImage(entry.file) || FileUtils.isSubtitleFile(entry.file)
        }
    })
    return MediaFolderListing(entries, entries.mapNotNull { entry -> tracks[entry.path].takeIf { entry.isRegularFile } })
}

internal fun mediaFolderArtworkCandidates(
    directory: File,
    order: MediaFolderOrder,
    children: (File) -> List<BrowserFileMetadata> = ::visibleFolderChildren,
    checkCancelled: () -> Unit = {}
): Sequence<BrowserFileMetadata> = sequence {
    val pending = ArrayDeque<File>()
    pending.addLast(directory)
    while (pending.isNotEmpty()) {
        checkCancelled()
        for (entry in order.sort(children(pending.removeFirst()))) {
            checkCancelled()
            when {
                entry.isDirectory -> pending.addLast(entry.file)
                entry.isRegularFile && (FileTypePolicy.isImage(entry.file) ||
                    FileTypePolicy.isVideo(entry.file) || FileUtils.isAudioFile(entry.file)) -> yield(entry)
            }
        }
    }
}

internal suspend fun <Artwork : Any> findMediaFolderArtwork(
    candidates: Sequence<BrowserFileMetadata>,
    readImage: suspend (BrowserFileMetadata) -> Artwork?,
    readMediaArtwork: suspend (BrowserFileMetadata) -> Artwork?
): Artwork? {
    val media = mutableListOf<BrowserFileMetadata>()
    for (entry in candidates) {
        if (FileTypePolicy.isImage(entry.file)) readImage(entry)?.let { return it }
        else media += entry
    }
    for (entry in media) readMediaArtwork(entry)?.let { return it }
    return null
}

private fun visibleFolderChildren(directory: File): List<BrowserFileMetadata> =
    SharedFileCache.directories.load(directory).entries.filter { entry ->
        !entry.restricted && !entry.file.name.startsWith('.') && (entry.isDirectory || entry.isRegularFile) &&
            !Files.isSymbolicLink(entry.file.toPath())
    }
