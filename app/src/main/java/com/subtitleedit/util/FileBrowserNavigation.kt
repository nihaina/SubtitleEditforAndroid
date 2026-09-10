package com.subtitleedit.util

import java.io.File

/** 文件浏览器导航的纯路径算法。 */
internal object FileBrowserNavigation {
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
