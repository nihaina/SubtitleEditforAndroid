package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class SubtitleFontChoice(val name: String, val file: File? = null, val collectionIndex: Int = 0)

/** System and imported font discovery used by the editor font picker and mpv. */
object SubtitleFontManager {
    /** Fonts are kept beside models and other user-owned application data. */
    const val FONT_DIR_NAME = "fonts"
    private const val LEGACY_FONT_DIR_NAME = "subtitle-fonts"
    private val systemCache = ConcurrentHashMap<String, SubtitleSystemFontCatalog.ResolvedCatalog>()
    private val extensions = setOf("ttf", "otf", "ttc", "otc")

    /** Returns the shared application font directory, creating it when needed. */
    fun ensureFontDirectory(context: Context): File {
        val directory = File(ModelDirectoryManager.softwareDirectory(), FONT_DIR_NAME)
            .also { it.mkdirs() }
        migrateLegacyPrivateFonts(context, directory)
        return directory
    }

    /** Backwards-compatible alias used by the editor font picker. */
    fun fontDirectory(context: Context): File = ensureFontDirectory(context)

    fun listFonts(context: Context): List<SubtitleFontChoice> {
        val installed = systemFontChoices(context)
        val imported = ensureFontDirectory(context).walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.US) in extensions }
            .flatMap { file ->
                SubtitleFontMetadata.families(file).ifEmpty {
                    listOf(SubtitleFontMetadata.Family(file.nameWithoutExtension.replace('_', ' '), 0))
                }.map { SubtitleFontChoice(it.name, file, it.collectionIndex) }
            }
            .toList()
        return (installed + imported).distinctBy { it.name.lowercase(Locale.US) }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    /**
     * Copies one font selected through SAF into the current shared application directory.
     *
     * The returned file is the actual file used by the picker and mpv.  Files are never
     * staged in [Context.filesDir]; a collision receives a deterministic numeric suffix.
     */
    fun importFile(context: Context, uri: Uri): File? {
        val displayName = documentDisplayName(context, uri) ?: return null
        if (!isSupportedFontFile(displayName)) return null

        val target = uniqueTarget(ensureFontDirectory(context), displayName)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return null
            target
        }.getOrElse {
            target.delete()
            null
        }
    }

    /**
     * Moves imported fonts when the user changes the application root directory.
     * Existing files in the destination are preserved and renamed with a numeric suffix.
     */
    fun synchronizeSoftwareDirectory(oldRoot: File, newRoot: File): Int {
        val sourceRoot = File(oldRoot, FONT_DIR_NAME)
        val destinationRoot = File(newRoot, FONT_DIR_NAME).also { it.mkdirs() }
        if (sourceRoot.canonicalFile == destinationRoot.canonicalFile) return 0
        if (!sourceRoot.isDirectory) return 0

        val files = sourceRoot.walkTopDown()
            .filter { it.isFile && isSupportedFontFile(it.name) }
            .toList()
        var moved = 0
        files.forEach { source ->
            val target = uniqueTarget(destinationRoot, source.name)
            runCatching {
                source.copyTo(target, overwrite = false)
                check(source.delete()) { "无法删除旧字体文件：${source.absolutePath}" }
            }.onSuccess { moved++ }
                .onFailure { target.delete() }
        }
        cleanupEmptyDirectories(sourceRoot)
        return moved
    }

    fun writeFontConfig(context: Context): File {
        val config = File(context.filesDir, "fonts.conf")
        val fontDir = fontDirectory(context)
        fun xml(value: String) = value
            .replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
        val content = """
            <fontconfig>
              ${SYSTEM_FONT_DIRECTORIES.joinToString("\n") { "<dir>${xml(it)}</dir>" }}
              <dir>${xml(fontDir.absolutePath)}</dir>
              <cachedir>${xml(context.cacheDir.absolutePath)}</cachedir>
              <alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>
              <alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>
              <alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>
            </fontconfig>
        """.trimIndent()
        if (!config.isFile || config.readText() != content) config.writeText(content)
        // config=no disables mpv's fonts.conf lookup too. Fontconfig still reads
        // its own environment variable when libass creates a font provider.
        Os.setenv("FONTCONFIG_FILE", config.absolutePath, true)
        return fontDir
    }

    private fun documentDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun migrateLegacyPrivateFonts(context: Context, destination: File) {
        val legacy = File(context.filesDir, LEGACY_FONT_DIR_NAME)
        if (!legacy.isDirectory || legacy.canonicalFile == destination.canonicalFile) return
        val files = legacy.walkTopDown()
            .filter { it.isFile && isSupportedFontFile(it.name) }
            .toList()
        files.forEach { source ->
            val target = uniqueTarget(destination, source.name)
            runCatching {
                source.copyTo(target, overwrite = false)
                if (!source.delete()) target.delete()
            }
        }
        cleanupEmptyDirectories(legacy)
    }

    private fun cleanupEmptyDirectories(root: File) {
        root.walkBottomUp()
            .filter { it.isDirectory && it != root }
            .forEach { directory ->
                runCatching {
                    if (directory.listFiles()?.isEmpty() == true) directory.delete()
                }
            }
        runCatching {
            if (root.isDirectory && root.listFiles()?.isEmpty() == true) root.delete()
        }
    }

    private fun isSupportedFontFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.US) in extensions

    private fun uniqueTarget(directory: File, name: String): File {
        val candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        var index = 2
        while (true) {
            val next = File(directory, "${candidate.nameWithoutExtension}-$index.${candidate.extension}")
            if (!next.exists()) return next
            index++
        }
    }

    fun systemFontNames(context: Context): List<String> = systemFontChoices(context).map { it.name }

    /** Only actual installed families are listed, with their regular face for preview. */
    fun systemFontChoices(context: Context): List<SubtitleFontChoice> =
        systemCatalog(context).choices.map { it.toChoice() }

    /** Resolves existing Android generic/compatibility aliases to the real renderer name. */
    fun resolveSystemFont(context: Context, name: String): SubtitleFontChoice? =
        systemCatalog(context).names[name.trim().lowercase(Locale.US)]?.toChoice()

    private fun SubtitleSystemFontCatalog.InstalledFace.toChoice() =
        SubtitleFontChoice(name, File(path), collectionIndex)

    private fun systemCatalog(context: Context): SubtitleSystemFontCatalog.ResolvedCatalog =
        systemCache.getOrPut(context.packageName) {
        val installedFiles = SYSTEM_FONT_DIRECTORIES
            .flatMap { directory -> File(directory).listFiles().orEmpty().asSequence() }
            .filter { it.isFile && it.canRead() && it.extension.lowercase(Locale.US) in extensions }
            .toList()
        val faces = installedFiles.flatMap { file ->
            SubtitleFontMetadata.families(file).map { face ->
                SubtitleSystemFontCatalog.InstalledFace(
                    face.name, file.absolutePath, face.collectionIndex, face.weight, face.italic
                )
            }
        }
        val configs = SYSTEM_FONT_CONFIGS.mapNotNull { file ->
            runCatching { File(file).takeIf(File::isFile)?.readText() }.getOrNull()
        }
        SubtitleSystemFontCatalog.resolve(configs, faces)
    }

    private val SYSTEM_FONT_DIRECTORIES = listOf(
        "/system/fonts", "/system_ext/fonts", "/product/fonts", "/vendor/fonts", "/odm/fonts"
    )
    private val SYSTEM_FONT_CONFIGS = listOf(
        "/system/etc/fonts.xml", "/system/etc/fonts_customization.xml",
        "/system_ext/etc/fonts_customization.xml", "/product/etc/fonts_customization.xml"
    )
}
