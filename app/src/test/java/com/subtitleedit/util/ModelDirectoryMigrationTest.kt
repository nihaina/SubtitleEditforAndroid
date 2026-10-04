package com.subtitleedit.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDirectoryMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun migratesOnlyRecognizedModelDirectoriesAndKeepsArchivesAndTemporaryFiles() {
        val root = temporaryFolder.newFolder("migration")
        val legacy = File(root, "SubtitleEdit/models").apply { mkdirs() }
        val destination = File(root, "TsumugiSub/models")

        val senseVoice = File(legacy, ModelDownloader.SENSEVOICE_DIRECTORY_NAME).apply { mkdirs() }
        File(senseVoice, "model.int8.onnx").writeBytes(ByteArray(1024 * 1024))
        File(senseVoice, "tokens.txt").writeText("tokens")
        val archive = File(legacy, "${ModelDownloader.SENSEVOICE_DIRECTORY_NAME}.tar.bz2")
            .apply { writeText("archive") }
        val partial = File(legacy, ".sensevoice_cpu_extracting").apply { mkdirs() }
        File(partial, "model.int8.onnx").writeText("partial")
        val unrelated = File(legacy, "notes.txt").apply { writeText("keep") }
        val unknownDirectory = File(legacy, "custom-model").apply { mkdirs() }
        File(unknownDirectory, "model.onnx").writeBytes(ByteArray(1024 * 1024))

        val result = ModelDirectoryMigration.migrate(legacy, destination)

        assertEquals(listOf("${ModelDownloader.SENSEVOICE_DIRECTORY_NAME}"), result.migrated.map { it.name })
        assertTrue(File(destination, "${ModelDownloader.SENSEVOICE_DIRECTORY_NAME}/model.int8.onnx").isFile)
        assertFalse(senseVoice.exists())
        assertTrue(archive.isFile)
        assertTrue(partial.isDirectory)
        assertTrue(unrelated.isFile)
        assertTrue(unknownDirectory.isDirectory)
    }

    @Test
    fun configuredDirectoryIsUsedByEveryModelPathConsumer() {
        val selected = temporaryFolder.newFolder("selected-models")
        try {
            ModelDirectoryManager.setModelsDirectory(selected)
            assertEquals(File(selected, "models").absolutePath, ModelDownloader.modelsDirectory().absolutePath)
        } finally {
            ModelDirectoryManager.setModelsDirectory(null)
        }
    }

    @Test
    fun separationMigrationLeavesUnrelatedFilesInTheOldDirectory() {
        val root = temporaryFolder.newFolder("separation")
        val legacy = File(root, "SubtitleEdit/models").apply { mkdirs() }
        val separation = File(legacy, ModelDownloader.SEPARATION_DIRECTORY_NAME).apply { mkdirs() }
        File(separation, "htdemucs_fp16weights.onnx").writeBytes(ByteArray(1024 * 1024))
        val unrelated = File(separation, "readme.txt").apply { writeText("keep") }
        val destination = File(root, "TsumugiSub/models")

        ModelDirectoryMigration.migrate(legacy, destination)

        assertTrue(File(destination, "separation/htdemucs_fp16weights.onnx").isFile)
        assertFalse(File(separation, "htdemucs_fp16weights.onnx").exists())
        assertTrue(unrelated.isFile)
    }

}
