package com.subtitleedit.util

import com.subtitleedit.util.ArchiveManager.ConflictPolicy
import com.subtitleedit.util.ArchiveManager.ConflictResolution
import com.subtitleedit.util.ArchiveManager.DestinationConflict
import com.subtitleedit.util.ArchiveManager.DestinationConflictException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Collects all rename decisions before the executor changes any source or destination. */
internal object BatchRenameConflictResolver {
    data class Plan(
        val entries: List<BatchRename.Entry>,
        /** Existing, unselected destinations that the user explicitly agreed to replace. */
        val overwritten: List<File> = emptyList(),
        /** Earlier results replaced by a later item; keep their sources until the commit succeeds. */
        val discardedSources: List<File> = emptyList(),
        val strictNames: Boolean = true
    )

    suspend fun resolve(
        entries: List<BatchRename.Entry>,
        strictNames: Boolean = true,
        onConflict: suspend (DestinationConflict) -> ConflictResolution
    ): Plan {
        validateEntries(entries, strictNames)
        val selected = entries.associateBy { identity(it.source) }
        val unchanged = entries.filter(::isUnchanged).mapTo(mutableSetOf()) { identity(it.source) }
        val skipped = mutableSetOf<String>()
        val decisions = mutableMapOf<ConflictIdentity, ConflictPolicy>()
        var allConflicts: ConflictPolicy? = null
        // A skipped source remains in place, so reevaluate earlier dependent targets before committing.
        while (true) {
            val preserved = unchanged + skipped
            var addedSkip = false
            val planned = mutableListOf<BatchRename.Entry>()
            val overwritten = linkedMapOf<String, File>()
            val discarded = linkedMapOf<String, File>()
            val reserved = entries.flatMap { listOf(identity(it.source), identity(it.target)) }.toMutableSet()

            for (entry in entries) {
                coroutineContext.ensureActive()
                if (identity(entry.source) in skipped) continue
                if (isUnchanged(entry)) {
                    planned += entry
                    continue
                }
                val duplicate = planned.firstOrNull { identity(it.target) == identity(entry.target) }
                val targetSource = selectedSourceAt(entry.target, selected)
                val retainedSource = targetSource?.takeIf { identity(it.source) in preserved }
                val external = exists(entry.target) && targetSource == null && !sameCaseOnlyFile(entry)
                if (duplicate == null && retainedSource == null && !external) {
                    planned += entry
                    continue
                }

                val existing = duplicate?.source ?: retainedSource?.source ?: entry.target
                val conflict = conflict(entry, existing)
                val decisionKey = ConflictIdentity(identity(entry.source), identity(entry.target), identity(existing), retainedSource != null)
                val policy = allConflicts ?: decisions[decisionKey] ?: onConflict(conflict).also { resolution ->
                    if (resolution.applyToAll) allConflicts = resolution.policy
                    decisions[decisionKey] = resolution.policy
                }.policy
                coroutineContext.ensureActive()
                when (policy) {
                    ConflictPolicy.FAIL -> throw DestinationConflictException(conflict)
                    ConflictPolicy.SKIP -> addedSkip = skipped.add(identity(entry.source)) || addedSkip
                    ConflictPolicy.RENAME -> {
                        val target = uniqueTarget(entry.target, entry.source.isDirectory, reserved)
                        planned += entry.copy(target = target)
                        reserved += identity(target)
                    }
                    ConflictPolicy.OVERWRITE -> {
                        require(retainedSource == null && duplicate?.let(::isUnchanged) != true) {
                            "不能覆盖本次重命名中保留的源文件或目录：${entry.target.name}"
                        }
                        if (external) {
                            require(selected.keys.none { overlap(it, identity(entry.target)) }) {
                                "不能覆盖源文件或源文件所在目录：${entry.target.name}"
                            }
                            overwritten[identity(entry.target)] = entry.target
                        }
                        if (duplicate != null) {
                            planned.remove(duplicate)
                            discarded[identity(duplicate.source)] = duplicate.source
                        }
                        planned += entry
                    }
                }
            }
            if (!addedSkip) {
                return Plan(planned, overwritten.values.toList(), discarded.values.toList(), strictNames)
            }
        }
    }

    private data class ConflictIdentity(val source: String, val target: String, val existing: String, val retained: Boolean)

    private fun validateEntries(entries: List<BatchRename.Entry>, strictNames: Boolean) {
        val sourcePaths = entries.map { identity(it.source) }
        require(sourcePaths.size == sourcePaths.toSet().size) { "重复选择了同一文件或目录" }
        entries.forEach { entry ->
            require(exists(entry.source)) { "未找到源文件或目录：${entry.source.name}" }
            BatchRename.validateTargetName(entry.target.name, strictNames)
            val parent = entry.source.absoluteFile.parentFile
            require(parent != null && identity(parent) == entry.target.absoluteFile.parentFile?.let(::identity)) {
                "批量重命名不能更改所在目录"
            }
        }
        sourcePaths.forEachIndexed { index, path ->
            require(sourcePaths.withIndex().none { (otherIndex, other) ->
                index != otherIndex && other.startsWith(path + File.separator)
            }) { "不能同时重命名目录及其内部项目" }
        }
    }

    private fun conflict(entry: BatchRename.Entry, existing: File) = DestinationConflict(
        entryName = entry.target.name,
        sourceSize = if (entry.source.isFile) entry.source.length() else -1L,
        sourceModifiedTimeMillis = entry.source.lastModified(),
        sourceIsDirectory = entry.source.isDirectory,
        existingSize = if (existing.isFile) existing.length() else -1L,
        existingModifiedTimeMillis = existing.lastModified(),
        existingIsDirectory = existing.isDirectory
    )

    private fun uniqueTarget(target: File, directory: Boolean, reserved: Set<String>): File {
        val dot = target.name.lastIndexOf('.').takeIf { !directory && it > 0 }
        val base = dot?.let { target.name.substring(0, it) } ?: target.name
        val extension = dot?.let { target.name.substring(it) }.orEmpty()
        var index = 1L
    while (true) {
            val candidate = File(target.parentFile, "$base ($index)$extension")
            if (identity(candidate) !in reserved && !exists(candidate)) return candidate
            index++
        }
    }

    private fun selectedSourceAt(
        target: File,
        selected: Map<String, BatchRename.Entry>
    ): BatchRename.Entry? = selected[identity(target)] ?: selected.values.firstOrNull { entry ->
        entry.source.name.equals(target.name, ignoreCase = true) &&
            entry.source.absoluteFile.parentFile?.let(::identity) == target.absoluteFile.parentFile?.let(::identity) &&
            runCatching { Files.isSameFile(entry.source.toPath(), target.toPath()) }.getOrDefault(false)
    }

    private fun isUnchanged(entry: BatchRename.Entry): Boolean =
        entry.source.absoluteFile.normalize().path == entry.target.absoluteFile.normalize().path

    private fun sameCaseOnlyFile(entry: BatchRename.Entry): Boolean =
        entry.source.name.equals(entry.target.name, ignoreCase = true) &&
            runCatching { Files.isSameFile(entry.source.toPath(), entry.target.toPath()) }.getOrDefault(false)

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun overlap(first: String, second: String): Boolean =
        first == second || first.startsWith(second + File.separator) || second.startsWith(first + File.separator)

    private fun identity(file: File): String {
        val path = file.absoluteFile.normalize().let { absolute ->
            absolute.parentFile?.let { File(FilePathPolicy.canonicalOrAbsolute(it), absolute.name).path }
                ?: absolute.path
        }
        return if (File.separatorChar == '\\') path.lowercase(Locale.ROOT) else path
    }
}
