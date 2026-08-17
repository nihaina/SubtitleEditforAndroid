package com.subtitleedit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QnnRuntimeAvailabilityTest {

    @Test
    fun completeRuntimeRequiresEveryPackagedLibrary() {
        val nativeLibraryDir = Files.createTempDirectory("qnn-runtime").toFile()
        try {
            QnnRuntimeAvailability.requiredLibraryNames().forEach { libraryName ->
                nativeLibraryDir.resolve(libraryName).writeText("library")
            }

            assertTrue(QnnRuntimeAvailability.hasCompleteRuntime(nativeLibraryDir))

            nativeLibraryDir.resolve("libQnnHtpV75Skel.so").delete()
            assertFalse(QnnRuntimeAvailability.hasCompleteRuntime(nativeLibraryDir))
        } finally {
            nativeLibraryDir.deleteRecursively()
        }
    }

    @Test
    fun missingRuntimeDirectoryIsUnavailable() {
        val parent = Files.createTempDirectory("qnn-runtime-parent").toFile()
        try {
            assertFalse(
                QnnRuntimeAvailability.hasCompleteRuntime(parent.resolve("missing"))
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun packagedRuntimeRequiresEveryLibraryForRequestedAbi() {
        val apkFile = Files.createTempFile("qnn-runtime", ".apk").toFile()
        try {
            writeRuntimeApk(apkFile, "arm64-v8a")

            assertTrue(QnnRuntimeAvailability.hasPackagedRuntime(apkFile, "arm64-v8a"))
            assertFalse(QnnRuntimeAvailability.hasPackagedRuntime(apkFile, "x86_64"))
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun extractsOnlySkelLibrariesForAdsp() {
        val apkFile = Files.createTempFile("qnn-runtime", ".apk").toFile()
        val targetDir = Files.createTempDirectory("qnn-adsp").toFile()
        try {
            writeRuntimeApk(apkFile, "arm64-v8a")

            QnnRuntimeAvailability.extractSkelLibraries(
                apkFile,
                "arm64-v8a",
                targetDir
            )

            val requiredLibraries = QnnRuntimeAvailability.requiredLibraryNames()
            requiredLibraries.filter { it.contains("Skel") }.forEach { libraryName ->
                assertTrue(targetDir.resolve(libraryName).isFile)
            }
            requiredLibraries.filterNot { it.contains("Skel") }.forEach { libraryName ->
                assertFalse(targetDir.resolve(libraryName).exists())
            }
        } finally {
            apkFile.delete()
            targetDir.deleteRecursively()
        }
    }

    private fun writeRuntimeApk(apkFile: File, abi: String) {
        ZipOutputStream(apkFile.outputStream().buffered()).use { output ->
            QnnRuntimeAvailability.requiredLibraryNames().forEach { libraryName ->
                output.putNextEntry(ZipEntry("lib/$abi/$libraryName"))
                output.write(byteArrayOf(1))
                output.closeEntry()
            }
        }
    }
}
