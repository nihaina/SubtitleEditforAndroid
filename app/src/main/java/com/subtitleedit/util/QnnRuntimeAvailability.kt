package com.subtitleedit.util

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

object QnnRuntimeAvailability {

    const val QNN_EDITION_RELEASES_URL =
        "https://github.com/nihaina/SubtitleEditforAndroid/releases"

    private val requiredLibraries = buildList {
        add("libQnnHtp.so")
        add("libQnnSystem.so")
        add("libQnnHtpPrepare.so")
        listOf(68, 69, 73, 75, 79, 81).forEach { architecture ->
            add("libQnnHtpV${architecture}Stub.so")
            add("libQnnHtpV${architecture}Skel.so")
        }
    }

    fun isAvailable(context: Context): Boolean {
        if (hasCompleteRuntime(File(context.applicationInfo.nativeLibraryDir))) return true
        val abi = context.supportedAbis().firstOrNull() ?: return false
        return hasPackagedRuntime(File(context.applicationInfo.sourceDir), abi)
    }

    internal fun hasCompleteRuntime(nativeLibraryDir: File): Boolean =
        nativeLibraryDir.isDirectory && requiredLibraries.all { libraryName ->
            File(nativeLibraryDir, libraryName).isFile
        }

    internal fun hasPackagedRuntime(apkFile: File, abi: String): Boolean {
        if (!apkFile.isFile) return false
        return runCatching {
            ZipFile(apkFile).use { zipFile ->
                requiredLibraries.all { libraryName ->
                    zipFile.getEntry("lib/$abi/$libraryName") != null
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Returns a directory suitable for ADSP_LIBRARY_PATH. Modern APK packaging keeps
     * native libraries inside the APK, while the Qualcomm DSP loader needs filesystem
     * paths for HTP Skel libraries.
     */
    fun prepareAdspLibraryDir(context: Context): File {
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        if (hasCompleteRuntime(nativeLibraryDir)) return nativeLibraryDir

        val abi = context.supportedAbis().firstOrNull()
            ?: throw IllegalStateException("SenseVoice NPU 仅支持 arm64-v8a 骁龙设备")
        val apkFile = File(context.applicationInfo.sourceDir)
        if (!hasPackagedRuntime(apkFile, abi)) {
            throw IllegalStateException("当前安装包不包含完整 QNN 运行库")
        }

        val targetDir = File(context.codeCacheDir, ADSP_DIRECTORY)
        extractSkelLibraries(apkFile, abi, targetDir)
        return targetDir
    }

    internal fun extractSkelLibraries(apkFile: File, abi: String, targetDir: File) {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            throw IllegalStateException("无法创建 QNN ADSP 临时目录")
        }
        ZipFile(apkFile).use { zipFile ->
            skelLibraryNames().forEach { libraryName ->
                val target = File(targetDir, libraryName)
                val entry = zipFile.getEntry("lib/$abi/$libraryName")
                    ?: throw IllegalStateException("QNN 运行库缺少 $libraryName")
                if (!target.isFile || target.length() != entry.size) {
                    val temporary = File(targetDir, "$libraryName.part")
                    temporary.delete()
                    try {
                        zipFile.getInputStream(entry).use { input ->
                            temporary.outputStream().buffered().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (temporary.length() != entry.size) {
                            throw IllegalStateException("复制 QNN 运行库不完整：$libraryName")
                        }
                        if (target.exists() && !target.delete()) {
                            throw IllegalStateException("无法替换 QNN 运行库：$libraryName")
                        }
                        if (!temporary.renameTo(target)) {
                            throw IllegalStateException("复制 QNN 运行库失败：$libraryName")
                        }
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
    }

    fun deletePreparedAdspLibraryDir(context: Context) {
        File(context.codeCacheDir, ADSP_DIRECTORY).deleteRecursively()
    }

    private fun skelLibraryNames(): List<String> = requiredLibraries.filter { it.contains("Skel") }

    private fun Context.supportedAbis(): List<String> =
        android.os.Build.SUPPORTED_ABIS.filter { it == "arm64-v8a" }

    internal fun requiredLibraryNames(): List<String> = requiredLibraries.toList()

    private const val ADSP_DIRECTORY = "qnn-adsp"
}
