package com.subtitleedit.util

import java.io.File

/** 文件浏览器导航的纯路径算法。 */
internal object FileBrowserNavigation {
    fun breadcrumbDirectories(storageRoot: File, directory: File): List<File> {
        val rootPath = FilePathPolicy.canonicalOrAbsolute(storageRoot)
        val current = File(FilePathPolicy.canonicalOrAbsolute(directory))
        return generateSequence(current) { it.parentFile }
            .takeWhile { it.absolutePath != rootPath }
            .toList()
            .asReversed()
    }

    fun historyForSearchResult(root: File, target: File): List<File> {
        val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        val reversedHistory = mutableListOf<File>()
        var directory = target.parentFile

        while (directory != null) {
            reversedHistory.add(directory)
            val directoryPath = runCatching { directory.canonicalPath }
                .getOrElse { directory.absolutePath }
            if (directoryPath == rootPath) return reversedHistory.asReversed()
            directory = directory.parentFile
        }

        return listOf(root)
    }
}
