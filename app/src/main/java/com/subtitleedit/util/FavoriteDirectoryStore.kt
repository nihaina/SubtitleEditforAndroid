package com.subtitleedit.util

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Locale

internal data class FavoriteDirectorySnapshot(
    val paths: Set<String>,
    val mediaLibraryPaths: Set<String>
) {
    val directories: List<File> = paths.map(::File).sortedWith(
        compareBy<File> { it.name.lowercase(Locale.getDefault()) }.thenBy { it.absolutePath }
    )
    val commonDirectories: List<File> = directories.filter { it.absolutePath !in mediaLibraryPaths }
    val mediaLibraryDirectories: List<File> = directories.filter { it.absolutePath in mediaLibraryPaths }
}

/** Keeps shared shortcuts together while recording directories explicitly added through the media library. */
internal class FavoriteDirectoryStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    fun load(): FavoriteDirectorySnapshot = synchronized(lock) { read() }

    fun addCommon(directory: File): Boolean = synchronized(lock) {
        val current = read()
        val path = directory.absolutePath
        if (path in current.paths) return@synchronized false
        write(current.paths + path, current.mediaLibraryPaths)
        true
    }

    fun addMediaLibrary(directory: File): Unit = synchronized(lock) {
        val current = read()
        val path = directory.absolutePath
        write(current.paths + path, current.mediaLibraryPaths + path)
    }

    fun remove(directory: File): Unit = synchronized(lock) {
        val current = read()
        val path = directory.absolutePath
        write(current.paths - path, current.mediaLibraryPaths - path)
    }

    fun replace(paths: Set<String>, mediaLibraryPaths: Set<String> = emptySet()): Unit = synchronized(lock) {
        write(paths, mediaLibraryPaths)
    }

    private fun read(): FavoriteDirectorySnapshot {
        val paths = preferences.getStringSet(PATHS_KEY, emptySet()).orEmpty().toSet()
        // Unmarked directories, including all directories saved by older versions, are common shortcuts.
        val mediaLibraryPaths = preferences.getStringSet(MEDIA_LIBRARY_PATHS_KEY, emptySet())
            .orEmpty().intersect(paths)
        return FavoriteDirectorySnapshot(paths, mediaLibraryPaths)
    }

    private fun write(paths: Set<String>, mediaLibraryPaths: Set<String>) {
        preferences.edit()
            .putStringSet(PATHS_KEY, paths.toSet())
            .putStringSet(MEDIA_LIBRARY_PATHS_KEY, mediaLibraryPaths.intersect(paths))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "favorite_directories"
        const val PATHS_KEY = "paths"
        const val MEDIA_LIBRARY_PATHS_KEY = "media_library_paths"
        val lock = Any()
    }
}
