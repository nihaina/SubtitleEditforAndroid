package com.subtitleedit.util

import java.io.File

/** Locates and removes caches that are keyed by a media file's content hash. */
object MediaCacheCleaner {
    enum class Kind(val key: String) {
        WAVEFORM("waveform"),
        SPECTROGRAM("spectrogram"),
        QUICK_TRANSCRIBE("quick_transcribe");

        companion object {
            fun fromKey(key: String): Kind? = values().firstOrNull { it.key == key }
        }
    }

    data class Stats(
        val fileCount: Int = 0,
        val sizeBytes: Long = 0L
    )

    class CacheSnapshot internal constructor(internal val files: List<File>)

    private data class MediaEntry(val file: File, val cacheKey: String)

    fun statsForMedia(mediaFiles: Iterable<File>, appCacheDir: File): Map<Kind, Stats> {
        val entries = mediaEntries(mediaFiles)
        return Kind.values().associateWith { kind ->
            matchingFilesForMedia(entries, kind, appCacheDir).fold(Stats()) { stats, file ->
                stats.copy(fileCount = stats.fileCount + 1, sizeBytes = stats.sizeBytes + file.length())
            }
        }
    }

    fun clearForMedia(
        mediaFiles: Iterable<File>,
        kinds: Iterable<Kind>,
        appCacheDir: File
    ): Stats = clear(snapshotForMedia(mediaFiles, kinds, appCacheDir))

    fun snapshotForMedia(
        mediaFiles: Iterable<File>,
        kinds: Iterable<Kind>,
        appCacheDir: File
    ): CacheSnapshot {
        val entries = mediaEntries(mediaFiles)
        val files = kinds.flatMap { kind ->
            matchingFilesForMedia(entries, kind, appCacheDir)
        }.distinctBy(::canonicalPath)
        return CacheSnapshot(files)
    }

    fun clear(snapshot: CacheSnapshot): Stats {
        var deletedCount = 0
        var deletedBytes = 0L
        snapshot.files.forEach { file ->
            val size = file.length()
            if (file.delete()) {
                deletedCount++
                deletedBytes += size
                pruneEmptyCacheDirectory(file.parentFile)
            }
        }
        return Stats(deletedCount, deletedBytes)
    }

    private fun mediaEntries(mediaFiles: Iterable<File>): List<MediaEntry> = mediaFiles
        .filter(File::isFile)
        .distinctBy(::canonicalPath)
        .mapNotNull { file -> runCatching { MediaEntry(file, FileHashUtils.md5(file)) }.getOrNull() }

    private fun matchingFilesForMedia(
        mediaEntries: Iterable<MediaEntry>,
        kind: Kind,
        appCacheDir: File
    ): List<File> {
        val files = mutableListOf<File>()
        mediaEntries.forEach { (mediaFile, cacheKey) ->
            cacheRoots(mediaFile, kind, appCacheDir)
                .map { File(it, cacheKey) }
                .filter(File::isDirectory)
                .forEach { cacheDirectory ->
                    cacheDirectory.walkTopDown()
                        .filter { it.isFile && matches(it, kind, mediaFile) }
                        .forEach(files::add)
                }
        }
        return files.distinctBy(::canonicalPath)
    }

    private fun cacheRoots(
        mediaFile: File,
        kind: Kind,
        appCacheDir: File
    ): List<File> = when (kind) {
        Kind.QUICK_TRANSCRIBE -> listOf(File(appCacheDir, "quick_transcribe"))
        Kind.WAVEFORM, Kind.SPECTROGRAM -> buildList {
            // Cache location can change, so clear both current and previously used roots.
            add(File(appCacheDir, "waveform"))
            mediaFile.parentFile?.let(::add)
        }.distinctBy(::canonicalPath)
    }

    private fun matches(file: File, kind: Kind, mediaFile: File): Boolean = when (kind) {
        Kind.WAVEFORM -> file.name.startsWith(mediaFile.nameWithoutExtension) &&
            (file.extension.equals("wave", ignoreCase = true) ||
                (file.name.contains(".wave.") && file.name.endsWith(".part", ignoreCase = true)))
        Kind.SPECTROGRAM -> (file.name.startsWith(mediaFile.nameWithoutExtension) &&
            file.extension.equals("png", ignoreCase = true) && file.name.contains(".spec_")) ||
            (file.name.startsWith(".spectrogram_part_") && file.extension.equals("png", ignoreCase = true))
        Kind.QUICK_TRANSCRIBE ->
            (file.name.startsWith("quick_transcribe_") && file.name.endsWith("_16k.wav", ignoreCase = true)) ||
                (file.name.startsWith(".quick_transcribe_") && file.name.endsWith(".wav.part", ignoreCase = true))
    }

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }
        .getOrDefault(file.absolutePath)

    private fun pruneEmptyCacheDirectory(directory: File?) {
        if (directory?.list()?.isEmpty() == true) directory.delete()
    }
}
