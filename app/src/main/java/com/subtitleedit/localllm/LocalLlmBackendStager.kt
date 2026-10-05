package com.subtitleedit.localllm

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Makes the dynamically loaded ggml CPU variants visible as regular files.
 *
 * With uncompressed native libraries Android can load the main JNI library
 * directly from the APK, but some releases do not expose those entries while
 * enumerating nativeLibraryDir. Only the CPU backend variants are copied to a
 * cache directory in that case; the rest of the native runtime is untouched.
 */
internal object LocalLlmBackendStager {
    private const val CPU_LIBRARY_PREFIX = "libggml-cpu-"
    private const val CPU_LIBRARY_SUFFIX = ".so"
    private const val CACHE_DIRECTORY = "llama-backends"

    fun resolve(context: Context): String {
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        if (hasCpuBackends(nativeDirectory)) return nativeDirectory.absolutePath

        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi.isBlank()) return nativeDirectory.absolutePath

        val stagedDirectory = File(context.codeCacheDir, "$CACHE_DIRECTORY/$abi")
        val staged = stageFromApk(context, abi, stagedDirectory)
        return if (staged && hasCpuBackends(stagedDirectory)) {
            stagedDirectory.absolutePath
        } else {
            nativeDirectory.absolutePath
        }
    }

    private fun hasCpuBackends(directory: File): Boolean =
        directory.isDirectory && directory.listFiles()?.any { file ->
            file.isFile && file.name.startsWith(CPU_LIBRARY_PREFIX) && file.name.endsWith(CPU_LIBRARY_SUFFIX)
        } == true

    private fun stageFromApk(context: Context, abi: String, targetDirectory: File): Boolean {
        val apkPaths = buildList {
            add(context.applicationInfo.sourceDir)
            context.applicationInfo.splitSourceDirs?.let(::addAll)
        }.distinct()
        val entryPrefix = "lib/$abi/"
        var copied = false

        runCatching {
            targetDirectory.mkdirs()
            for (apkPath in apkPaths) {
                ZipFile(apkPath).use { apk ->
                    apk.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith(entryPrefix) &&
                                entry.name.removePrefix(entryPrefix).startsWith(CPU_LIBRARY_PREFIX) &&
                                entry.name.endsWith(CPU_LIBRARY_SUFFIX)
                        }
                        .forEach { entry ->
                            val fileName = entry.name.removePrefix(entryPrefix)
                            val destination = File(targetDirectory, fileName)
                            if (destination.isFile && destination.length() == entry.size) {
                                copied = true
                                return@forEach
                            }

                            val temporary = File(targetDirectory, "$fileName.part")
                            apk.getInputStream(entry).use { input ->
                                temporary.outputStream().use { output -> input.copyTo(output) }
                            }
                            check(temporary.renameTo(destination)) { "无法写入本地 LLM backend" }
                            copied = true
                        }
                }
            }
        }
        return copied
    }
}
