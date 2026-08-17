package com.subtitleedit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SenseVoiceNpuModelPathPolicyTest {

    @Test
    fun contextBinarySelectionRecognizesFileAndContentPaths() {
        assertTrue(
            SenseVoiceNpuModelPathPolicy.isContextBinarySelection(
                "file:///data/user/0/com.subtitleedit/files/model.bin"
            )
        )
        assertTrue(
            SenseVoiceNpuModelPathPolicy.isContextBinarySelection(
                "content://provider/models/MODEL.BIN?token=1"
            )
        )
        assertFalse(
            SenseVoiceNpuModelPathPolicy.isContextBinarySelection(
                "content://provider/models/libmodel.so"
            )
        )
    }

    @Test
    fun managedFileCheckRejectsSiblingDirectories() {
        val parent = Files.createTempDirectory("sensevoice-path-policy").toFile()
        try {
            val root = parent.resolve("models").apply { mkdirs() }
            val nested = root.resolve("npu/libmodel.so").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("model")
            }
            val sibling = parent.resolve("models-backup/libmodel.so").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("model")
            }

            assertTrue(SenseVoiceNpuModelPathPolicy.isInside(root, nested))
            assertFalse(SenseVoiceNpuModelPathPolicy.isInside(root, sibling))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun installedModelLookupRequiresMatchingDurationBinAndTokens() {
        val root = Files.createTempDirectory("sensevoice-installed-models").toFile()
        try {
            val incomplete = root.resolve("5s-old").apply { mkdirs() }
            incomplete.resolve("model.bin").writeText("bin")

            val fiveSecond = root.resolve("5s-current").apply { mkdirs() }
            val fiveSecondBin = fiveSecond.resolve("model.bin").apply { writeText("bin") }
            fiveSecond.resolve("tokens.txt").writeText("tokens")

            val tenSecond = root.resolve("10s-current").apply { mkdirs() }
            tenSecond.resolve("model.bin").writeText("bin")
            tenSecond.resolve("tokens.txt").writeText("tokens")

            val selected = SenseVoiceNpuModelPathPolicy.findLatestCompleteModel(root, 5)

            assertTrue(selected?.first?.canonicalFile == fiveSecondBin.canonicalFile)
            assertTrue(SenseVoiceNpuModelPathPolicy.findLatestCompleteModel(root, 10) != null)
            assertTrue(SenseVoiceNpuModelPathPolicy.findLatestCompleteModel(root, 8) == null)
        } finally {
            root.deleteRecursively()
        }
    }
}
