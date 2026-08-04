package com.subtitleedit.model

import java.io.File
import java.util.ArrayDeque

object FileBrowserSearch {
    fun search(
        root: File,
        query: String,
        includeHidden: Boolean,
        includeFile: (File) -> Boolean,
        canEnterDirectory: (File) -> Boolean = { true },
        onEntryVisited: () -> Unit = {},
        onDirectoryScanned: (List<File>) -> Unit = {}
    ): List<File> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()

        val matches = mutableListOf<File>()
        val pendingDirectories = ArrayDeque<File>().apply { add(root) }
        val visitedDirectories = mutableSetOf<String>()

        while (pendingDirectories.isNotEmpty()) {
            onEntryVisited()
            val directory = pendingDirectories.removeLast()
            val directoryPath = runCatching { directory.canonicalPath }
                .getOrElse { directory.absolutePath }
            if (!visitedDirectories.add(directoryPath)) continue

            val children = runCatching { directory.listFiles()?.toList().orEmpty() }
                .getOrDefault(emptyList())
            children.forEach { file ->
                onEntryVisited()
                if (!includeHidden && file.name.startsWith(".")) return@forEach

                when {
                    file.isDirectory -> {
                        if (file.name.contains(normalizedQuery, ignoreCase = true)) {
                            matches.add(file)
                        }
                        if (canEnterDirectory(file)) pendingDirectories.add(file)
                    }
                    file.isFile && includeFile(file) &&
                        file.name.contains(normalizedQuery, ignoreCase = true) -> {
                        matches.add(file)
                    }
                }
            }
            onDirectoryScanned(matches)
        }

        return matches.distinctBy { it.absolutePath }
    }
}
