package com.subtitleedit.util

import com.subtitleedit.model.FileBrowserOrder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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
            validateName(name)
            val targetExtension = if (isDirectory) "" else extension ?: originalExtension
            val targetName = if (targetExtension.isEmpty()) name else "$name.$targetExtension"
            validateName(targetName)
            Entry(source, File(parent, targetName))
        }
        requireDistinct(entries.map { it.source }, "重复选择了同一文件或目录")
        requireDistinct(entries.map { it.target }, "多个项目重命名后名称相同")
        return entries
    }

    fun execute(entries: List<Entry>): Int = execute(entries) { source, target ->
        Files.move(source.toPath(), target.toPath())
    }

    /** The move seam lets filesystem tests exercise failures after a real partial transaction. */
    internal fun execute(entries: List<Entry>, move: (File, File) -> Unit): Int {
        requireDistinct(entries.map { it.source }, "重复选择了同一文件或目录")
        requireDistinct(entries.map { it.target }, "多个项目重命名后名称相同")
        entries.forEach { entry ->
            require(exists(entry.source)) { "未找到源文件或目录：${entry.source.name}" }
            validateName(entry.target.name)
            require(entry.source.absoluteFile.parentFile?.let(::pathKey) ==
                entry.target.absoluteFile.parentFile?.let(::pathKey)
            ) { "批量重命名不能更改所在目录" }
        }
        val sources = entries.map { pathKey(it.source) }.toSet()
        entries.forEach { entry ->
            val sameFileCaseChange = entry.source.name.equals(entry.target.name, ignoreCase = true) &&
                runCatching { Files.isSameFile(entry.source.toPath(), entry.target.toPath()) }.getOrDefault(false)
            require(!exists(entry.target) || pathKey(entry.target) in sources || sameFileCaseChange) {
                "目标已存在：${entry.target.name}"
            }
        }
        val sourcePaths = entries.map { pathKey(it.source) }
        sourcePaths.forEachIndexed { index, sourcePath ->
            require(sourcePaths.withIndex().none { (otherIndex, otherPath) ->
                otherIndex != index && otherPath.startsWith(sourcePath + File.separator)
            }) { "不能同时重命名目录及其内部项目" }
        }

        val changed = entries.filter { it.source.absoluteFile.path != it.target.absoluteFile.path }
        val reservedPaths = (sourcePaths + entries.map { pathKey(it.target) }).toMutableSet()
        val staged = changed.map { entry ->
            val parent = requireNotNull(entry.source.absoluteFile.parentFile)
            var temporary: File
            do {
                temporary = File(parent, ".subtitleedit-rename-${UUID.randomUUID()}")
            } while (exists(temporary) || !reservedPaths.add(pathKey(temporary)))
            StagedEntry(entry, temporary)
        }
        try {
            staged.forEach { item ->
                move(item.entry.source, item.temporary)
                item.phase = Phase.TEMPORARY
            }
            staged.forEach { item ->
                move(item.temporary, item.entry.target)
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
            staged.asReversed().filter { it.phase == Phase.TEMPORARY }.forEach { item ->
                try {
                    move(item.temporary, item.entry.source)
                    item.phase = Phase.SOURCE
                } catch (rollbackError: Exception) {
                    rollbackErrors += rollbackError
                }
            }
            val remaining = staged.filter { it.phase != Phase.SOURCE }
            val recovery = if (remaining.isEmpty()) "，已恢复原名称" else {
                "；部分项目未能恢复，当前位置：" + remaining.joinToString { it.location.absolutePath }
            }
            throw IOException("批量重命名失败：${error.message ?: "未知错误"}$recovery", error).apply {
                rollbackErrors.forEach(::addSuppressed)
            }
        }
        return changed.size
    }

    fun sourceExtension(source: File): String =
        source.name.lastIndexOf('.').takeIf { !source.isDirectory && it > 0 }
            ?.let { source.name.substring(it + 1) }.orEmpty()

    private enum class Phase { SOURCE, TEMPORARY, TARGET }

    private data class StagedEntry(val entry: Entry, val temporary: File, var phase: Phase = Phase.SOURCE) {
        val location: File get() = when (phase) {
            Phase.SOURCE -> entry.source
            Phase.TEMPORARY -> temporary
            Phase.TARGET -> entry.target
        }
    }

    private fun validateName(name: String) {
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
