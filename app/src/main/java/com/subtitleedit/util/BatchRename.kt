package com.subtitleedit.util

import com.subtitleedit.model.FileBrowserOrder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID

/** Filename generation and collision-safe execution for one ordered browser selection. */
internal object BatchRename {
    const val FILE_NAME_TOKEN = "\uE000"
    const val NUMBER_TOKEN = "\uE001"

    data class Options(
        val pattern: String = FILE_NAME_TOKEN + "(" + NUMBER_TOKEN + ")",
        /** null preserves each file's extension; an empty string removes it. */
        val extension: String? = null,
        val numberWidth: Int = 1,
        val startNumber: Long = 1,
        val find: String = "",
        val replacement: String = "",
        val regexEnabled: Boolean = false
    )

    data class Entry(val source: File, val target: File)

    fun preview(files: List<File>, options: Options): List<Entry> {
        require(options.numberWidth in 1..18) { "序号位数应为 1 至 18" }
        require(options.startNumber >= 0) { "起始序号不能小于 0" }
        val extension = options.extension?.trim()
        extension?.let { FileBrowserOrder.validateExtension(it)?.let { message -> throw IllegalArgumentException(message) } }
        val regex = if (options.regexEnabled && options.find.isNotEmpty()) {
            try {
                Regex(options.find)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("正则表达式无效：${error.message}", error)
            }
        } else null
        val entries = files.mapIndexed { index, source ->
            val parent = source.absoluteFile.parentFile
            require(parent != null) { "不能重命名根目录" }
            val isDirectory = source.isDirectory
            val dot = source.name.lastIndexOf('.').takeIf { !isDirectory && it > 0 }
            val originalName = dot?.let { source.name.substring(0, it) } ?: source.name
            val originalExtension = sourceExtension(source)
            val number = try {
                Math.addExact(options.startNumber, index.toLong()).toString().padStart(options.numberWidth, '0')
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("序号超出支持范围", error)
            }
            // Expand the template only once: original names are literal text, not more tokens.
            var name = buildString {
                options.pattern.forEach { character ->
                    when (character.toString()) {
                        FILE_NAME_TOKEN -> append(originalName)
                        NUMBER_TOKEN -> append(number)
                        else -> append(character)
                    }
                }
            }
            if (options.find.isNotEmpty()) {
                name = try {
                    regex?.replace(name, options.replacement)
                        ?: name.replace(options.find, options.replacement)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("正则替换内容无效：${error.message}", error)
                } catch (error: IndexOutOfBoundsException) {
                    throw IllegalArgumentException("正则替换内容无效：${error.message}", error)
                }
            }
            val targetExtension = if (isDirectory) "" else extension ?: originalExtension
            val targetName = if (targetExtension.isEmpty()) name else "$name.$targetExtension"
            // An empty base name is valid when the extension produces a hidden name such as .txt.
            validateName(targetName)
            Entry(source, File(parent, targetName))
        }
        requireDistinct(entries.map { it.source }, "重复选择了同一文件或目录")
        return entries
    }

    fun execute(entries: List<Entry>): Int = execute(entries) { source, target ->
        Files.move(source.toPath(), target.toPath())
    }

    /** The move seam lets filesystem tests exercise failures after a real partial transaction. */
    internal fun execute(entries: List<Entry>, move: (File, File) -> Unit): Int =
        execute(BatchRenameConflictResolver.Plan(entries), move)

    fun execute(plan: BatchRenameConflictResolver.Plan): Int = execute(plan) { source, target ->
        Files.move(source.toPath(), target.toPath())
    }

    internal fun execute(plan: BatchRenameConflictResolver.Plan, move: (File, File) -> Unit): Int {
        val entries = plan.entries
        val allSources = entries.map { it.source } + plan.discardedSources
        requireDistinct(allSources, "重复选择了同一文件或目录")
        requireDistinct(entries.map { it.target }, "多个项目重命名后名称相同")
        allSources.forEach { source ->
            require(exists(source)) { "未找到源文件或目录：${source.name}" }
        }
        entries.forEach { entry ->
            validateTargetName(entry.target.name, plan.strictNames)
            require(entry.source.absoluteFile.parentFile?.let(::pathKey) ==
                entry.target.absoluteFile.parentFile?.let(::pathKey)
            ) { "批量重命名不能更改所在目录" }
        }
        val sources = allSources.map(::pathKey).toSet()
        val overwritten = plan.overwritten.distinctBy(::pathKey)
        val overwritePaths = overwritten.map(::pathKey).toSet()
        overwritten.forEach { target ->
            val targetPath = pathKey(target)
            require(sources.none { sourcePath ->
                sourcePath == targetPath || sourcePath.startsWith(targetPath + File.separator) ||
                    targetPath.startsWith(sourcePath + File.separator)
            }) { "不能覆盖源文件或源文件所在目录" }
            require(entries.any { pathKey(it.target) == targetPath }) { "覆盖目标不在重命名计划中" }
        }
        entries.forEach { entry ->
            val sameFileCaseChange = entry.source.name.equals(entry.target.name, ignoreCase = true) &&
                runCatching { Files.isSameFile(entry.source.toPath(), entry.target.toPath()) }.getOrDefault(false)
            require(!exists(entry.target) || pathKey(entry.target) in sources || sameFileCaseChange ||
                pathKey(entry.target) in overwritePaths
            ) {
                "目标已存在：${entry.target.name}"
            }
        }
        val sourcePaths = allSources.map(::pathKey)
        sourcePaths.forEachIndexed { index, sourcePath ->
            require(sourcePaths.withIndex().none { (otherIndex, otherPath) ->
                otherIndex != index && otherPath.startsWith(sourcePath + File.separator)
            }) { "不能同时重命名目录及其内部项目" }
        }

        val changed = entries.filter { it.source.absoluteFile.path != it.target.absoluteFile.path }
        val reservedPaths = (sourcePaths + entries.map { pathKey(it.target) } + overwritePaths).toMutableSet()
        fun temporaryFor(source: File): File {
            val parent = requireNotNull(source.absoluteFile.parentFile)
            var temporary: File
            do {
                temporary = File(parent, ".subtitleedit-rename-${UUID.randomUUID()}")
            } while (exists(temporary) || !reservedPaths.add(pathKey(temporary)))
            return temporary
        }
        val staged = changed.map { entry -> StagedEntry(entry.source, entry.target, temporaryFor(entry.source)) } +
            plan.discardedSources.map { source -> StagedEntry(source, null, temporaryFor(source)) }
        val backups = overwritten.filter(::exists).map { target ->
            StagedEntry(target, null, temporaryFor(target))
        }
        try {
            staged.forEach { item ->
                move(item.source, item.temporary)
                item.phase = Phase.TEMPORARY
            }
            backups.forEach { backup ->
                move(backup.source, backup.temporary)
                backup.phase = Phase.TEMPORARY
            }
            staged.filter { it.target != null }.forEach { item ->
                move(item.temporary, requireNotNull(item.target))
                item.phase = Phase.TARGET
            }
        } catch (error: Exception) {
            val rollbackErrors = mutableListOf<Exception>()
            // Free all original paths again before restoring them, including swapped names.
            staged.asReversed().filter { it.phase == Phase.TARGET }.forEach { item ->
                try {
                    move(item.location, item.temporary)
                    item.phase = Phase.TEMPORARY
                } catch (rollbackError: Exception) {
                    rollbackErrors += rollbackError
                }
            }
            (staged + backups).asReversed().filter { it.phase == Phase.TEMPORARY }.forEach { item ->
                try {
                    move(item.temporary, item.source)
                    item.phase = Phase.SOURCE
                } catch (rollbackError: Exception) {
                    rollbackErrors += rollbackError
                }
            }
            val remaining = (staged + backups).filter { it.phase != Phase.SOURCE }
            val recovery = if (remaining.isEmpty()) "，已恢复原名称" else {
                "；部分项目未能恢复，当前位置：" + remaining.joinToString { it.location.absolutePath }
            }
            throw IOException("批量重命名失败：${error.message ?: "未知错误"}$recovery", error).apply {
                rollbackErrors.forEach(::addSuppressed)
            }
        }
        // Keep overwritten targets and displaced batch entries recoverable until every name is committed.
        val cleanupErrors = (backups + staged.filter { it.target == null }).mapNotNull { item ->
            runCatching { deleteBackup(item.temporary) }.exceptionOrNull()?.let { item.temporary to it }
        }
        if (cleanupErrors.isNotEmpty()) {
            throw IOException("重命名已完成，但无法清理覆盖备份：" + cleanupErrors.joinToString { it.first.absolutePath }, cleanupErrors.first().second)
        }
        return changed.size + plan.discardedSources.size
    }

    fun sourceExtension(source: File): String =
        source.name.lastIndexOf('.').takeIf { !source.isDirectory && it > 0 }
            ?.let { source.name.substring(it + 1) }.orEmpty()

    private enum class Phase { SOURCE, TEMPORARY, TARGET }

    private data class StagedEntry(val source: File, val target: File?, val temporary: File, var phase: Phase = Phase.SOURCE) {
        val location: File get() = when (phase) {
            Phase.SOURCE -> source
            Phase.TEMPORARY -> temporary
            Phase.TARGET -> requireNotNull(target)
        }
    }

    /** Delete staged data without following directory symlinks outside the renamed item. */
    private fun deleteBackup(file: File) {
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun validateName(name: String) {
        validateTargetName(name)
    }

    internal fun validateTargetName(name: String, strictNames: Boolean = true) {
        if (!strictNames) {
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
                "文件名无效"
            }
            return
        }
        FileBrowserOrder.validateName(name)?.let { throw IllegalArgumentException(it) }
        require(name.none { it.isISOControl() }) { "名称不能包含控制字符" }
    }

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun requireDistinct(files: List<File>, message: String) {
        val paths = files.map(::pathKey)
        require(paths.size == paths.toSet().size) { message }
    }

    private fun pathKey(file: File): String {
        val path = file.absoluteFile.normalize().let { absolute ->
            absolute.parentFile?.let { File(FilePathPolicy.canonicalOrAbsolute(it), absolute.name).path }
                ?: absolute.path
        }
        return if (File.separatorChar == '\\') path.lowercase(Locale.ROOT) else path
    }
}
