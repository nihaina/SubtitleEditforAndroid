package com.subtitleedit.util

/** Paths are lexical absolute paths, matching the directory snapshot being watched. */
data class DirectoryChanges(
    val directoryPath: String,
    val changedNames: Set<String> = emptySet(),
    val fullRefresh: Boolean = false,
    val countChangedDirectories: Set<String> = emptySet()
)

/** A batch is drained at a fixed deadline, rather than postponing it after every event. */
internal class DirectoryChangeAccumulator(private val directoryPath: String) {
    private val changedNames = linkedSetOf<String>()
    private val countChangedDirectories = linkedSetOf<String>()
    private var fullRefresh = false

    fun add(name: String?, countDirectory: String? = null, requireFullRefresh: Boolean = false) {
        if (name != null) changedNames += name
        if (countDirectory != null) countChangedDirectories += countDirectory
        fullRefresh = fullRefresh || requireFullRefresh
    }

    fun drain(): DirectoryChanges? {
        if (!fullRefresh && changedNames.isEmpty() && countChangedDirectories.isEmpty()) return null
        val result = DirectoryChanges(
            directoryPath = directoryPath,
            changedNames = changedNames.toSet(),
            fullRefresh = fullRefresh,
            countChangedDirectories = countChangedDirectories.toSet()
        )
        changedNames.clear()
        countChangedDirectories.clear()
        fullRefresh = false
        return result
    }
}
