package com.subtitleedit.ui

import com.subtitleedit.library.MediaLibraryTrack
import java.util.Locale

/** Retained worker results use source identity so returning to the same page needs no full scan. */
internal class PreparedMediaLibraryIndex(
    val tracks: List<MediaLibraryTrack>,
    val rootPaths: List<String>,
    val rootMediaCounts: Map<String, Int>,
    val durationByPath: Map<String, Long>
)

internal class PreparedMediaLibraryFolder(
    val tracks: List<MediaLibraryTrack>,
    val directoryPath: String,
    val entries: List<MediaFolderEntry>
)

internal fun prepareMediaLibraryIndex(
    tracks: List<MediaLibraryTrack>,
    rootPaths: List<String>,
    checkCancelled: () -> Unit = {}
): PreparedMediaLibraryIndex {
    val roots = rootPaths.associateWith { it.trimEnd('/') + "/" }
    val counts = rootPaths.associateWithTo(LinkedHashMap()) { 0 }
    val durations = HashMap<String, Long>(tracks.size)
    tracks.forEach { track ->
        checkCancelled()
        durations[track.path] = (durations[track.path] ?: 0L) + track.durationMs
        roots.forEach { (root, prefix) ->
            if (track.path.startsWith(prefix)) counts[root] = counts.getValue(root) + 1
        }
    }
    return PreparedMediaLibraryIndex(tracks, rootPaths, counts, durations)
}

internal fun prepareMediaLibraryFolder(
    tracks: List<MediaLibraryTrack>,
    directoryPath: String,
    checkCancelled: () -> Unit = {}
): PreparedMediaLibraryFolder {
    val base = directoryPath.trimEnd('/')
    val prefix = "$base/"
    val directories = LinkedHashMap<String, MediaFolderEntry>()
    val files = ArrayList<MediaFolderEntry>()
    tracks.forEach { track ->
        checkCancelled()
        val path = track.path
        if (!path.startsWith(prefix)) return@forEach
        val relative = path.substring(prefix.length)
        if (relative.isEmpty()) return@forEach
        val separator = relative.indexOf('/')
        if (separator < 0) {
            files += MediaFolderEntry(path, relative, false, 1, track.durationMs)
        } else {
            val name = relative.substring(0, separator)
            val childPath = "$base/$name"
            val previous = directories[childPath]
            directories[childPath] = MediaFolderEntry(childPath, name, true,
                (previous?.mediaCount ?: 0) + 1, (previous?.totalDurationMs ?: 0L) + track.durationMs)
        }
    }
    checkCancelled()
    val entries = directories.values.sortedBy { it.name.lowercase(Locale.ROOT) } +
        files.sortedBy { it.name.lowercase(Locale.ROOT) }
    return PreparedMediaLibraryFolder(tracks, directoryPath, entries)
}
