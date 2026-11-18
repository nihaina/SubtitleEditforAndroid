package com.subtitleedit.library

internal class MediaFolderCatalog(
    val categoryRoots: List<String>,
    private val tracksByDirectory: Map<String, List<MediaLibraryTrack>>,
    private val countsByDirectory: Map<String, Int>
) {
    fun tracksIn(directory: String): List<MediaLibraryTrack> = tracksByDirectory[directory].orEmpty()

    fun mediaCount(directory: String): Int = countsByDirectory[directory] ?: 0
}

internal fun buildMediaFolderCatalog(
    tracks: List<MediaAlbumTrack>,
    registeredDirectories: List<String>,
    checkCancelled: () -> Unit = {}
): MediaFolderCatalog {
    val roots = registeredDirectories.map { it.trimEnd('/').ifEmpty { "/" } }.distinct()
    val prefixes = roots.sortedByDescending { it.length }.associateWith { it.trimEnd('/') + "/" }.entries.toList()
    val categoryRoots = linkedSetOf<String>()
    val tracksByDirectory = hashMapOf<String, MutableList<MediaLibraryTrack>>()
    val counts = hashMapOf<String, Int>()
    val seen = hashSetOf<String>()
    for (entry in tracks) {
        checkCancelled()
        val track = entry.track
        val path = track.path
        val root = prefixes.firstOrNull { path.startsWith(it.value) } ?: continue
        if (!seen.add(path)) continue
        val outermost = prefixes.last { path.startsWith(it.value) }.key
        val parent = folderParent(path)
        tracksByDirectory.getOrPut(parent, ::mutableListOf).add(track)
        var directory = parent
        while (true) {
            counts[directory] = (counts[directory] ?: 0) + 1
            if (directory == outermost || directory == "/") break
            directory = folderParent(directory)
        }
        if (track.album.isBlank() || track.album.trim().equals("<unknown>", ignoreCase = true)) {
            val firstChild = path.substring(root.value.length).substringBefore('/', missingDelimiterValue = "")
            categoryRoots += if (root.key != outermost || firstChild.isEmpty()) root.key else root.value + firstChild
        }
    }
    return MediaFolderCatalog(categoryRoots.toList(), tracksByDirectory, counts)
}

private fun folderParent(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }
