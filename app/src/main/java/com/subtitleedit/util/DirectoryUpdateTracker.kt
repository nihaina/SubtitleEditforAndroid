package com.subtitleedit.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Conflates wake-ups while retaining the latest version of every observed directory. */
internal class DirectoryUpdateTracker {
    private val versions = ConcurrentHashMap<String, Long>()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    fun versionOf(path: String): Long = versions[path] ?: 0L

    @Synchronized
    fun publish(paths: Set<String>) {
        if (paths.isEmpty()) return
        val next = changes.value + 1L
        paths.forEach { versions[it] = next }
        changes.value = next
    }

    @Synchronized
    fun retain(paths: Set<String>) {
        versions.keys.removeAll { it !in paths }
    }
}
