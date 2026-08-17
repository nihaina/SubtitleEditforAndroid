package com.subtitleedit.util

import java.io.File

internal object SenseVoiceNpuModelPathPolicy {

    fun isContextBinarySelection(path: String): Boolean {
        val normalized = path.substringBefore('?').substringBefore('#').replace('\\', '/')
        return normalized.substringAfterLast('/').endsWith(".bin", ignoreCase = true)
    }

    fun isInside(root: File, candidate: File): Boolean = runCatching {
        candidate.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    }.getOrDefault(false)

    fun findLatestCompleteModel(root: File, durationSeconds: Int): Pair<File, File>? {
        if (durationSeconds != 5 && durationSeconds != 10) return null
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith("${durationSeconds}s-") }
            ?.mapNotNull { directory ->
                val contextBinary = File(directory, "model.bin")
                val tokens = File(directory, "tokens.txt")
                if (
                    contextBinary.isFile && contextBinary.length() > 0L &&
                    tokens.isFile && tokens.length() > 0L
                ) {
                    contextBinary to tokens
                } else {
                    null
                }
            }
            ?.maxByOrNull { (contextBinary, tokens) ->
                maxOf(contextBinary.lastModified(), tokens.lastModified())
            }
    }
}
