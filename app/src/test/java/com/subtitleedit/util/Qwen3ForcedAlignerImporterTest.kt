package com.subtitleedit.util

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Qwen3ForcedAlignerImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val graphName = "forced_aligner.onnx"

    private fun source(name: String, content: String, size: Long? = content.toByteArray().size.toLong()) =
        Qwen3ForcedAlignerImporter.Source(name, size) { ByteArrayInputStream(content.toByteArray()) }

    private fun sources() = listOf(source("$graphName.data", "new weights"), source(graphName, "new graph"))

    private fun oldModel(): File = temporary.newFolder("forced-aligner").apply {
        resolve(graphName).writeText("old graph")
        resolve("$graphName.data").writeText("old weights")
    }

    private fun assertOldModel(directory: File) {
        assertEquals("old graph", directory.resolve(graphName).readText())
        assertEquals("old weights", directory.resolve("$graphName.data").readText())
        assertEquals(listOf("forced-aligner"), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun selectsBothFilesInEitherOrderAndPreservesNames() {
        assertEquals(graphName, Qwen3ForcedAlignerModelFiles.graphName(listOf("$graphName.data", graphName)))
        assertEquals("custom.onnx", Qwen3ForcedAlignerModelFiles.graphName(listOf("custom.onnx", "custom.onnx.data")))
    }

    @Test fun rejectsMissingDuplicateUnrelatedAndUnsafeFilenames() {
        for (names in listOf(
            listOf(graphName), listOf(graphName, graphName),
            listOf(graphName, "other.onnx.data"), listOf(graphName, "$graphName.data", "extra"),
            listOf("../a.onnx", "../a.onnx.data"), listOf("a\\b.onnx", "a\\b.onnx.data"),
        )) assertThrows(IllegalArgumentException::class.java) { Qwen3ForcedAlignerModelFiles.graphName(names) }
    }

    @Test fun validatesBothFilesInSameDirectoryBeforePublishing() = runBlocking {
        val directory = oldModel()
        var published: File? = null
        val progress = mutableListOf<Qwen3ForcedAlignerImporter.Progress>()
        val installed = Qwen3ForcedAlignerImporter(directory).install(sources(), validate = { graph ->
            assertEquals(graphName, graph.name)
            assertEquals("new graph", graph.readText())
            assertEquals("new weights", Qwen3ForcedAlignerModelFiles.dataFile(graph).readText())
            assertOldModelContents(directory)
            assertNull(published)
        }, publish = { published = it }, onProgress = { progress += it })
        assertEquals(directory.resolve(graphName), installed)
        assertEquals(installed, published)
        assertEquals("new graph", installed.readText())
        assertEquals("new weights", Qwen3ForcedAlignerModelFiles.dataFile(installed).readText())
        assertTrue(Qwen3ForcedAlignerModelFiles.isComplete(installed))
        assertEquals(20L, progress.last().copied)
        assertEquals(20L, progress.last().total)
        assertEquals(listOf("forced-aligner"), temporary.root.listFiles()!!.map { it.name })
    }

    private fun assertOldModelContents(directory: File) {
        assertEquals("old graph", directory.resolve(graphName).readText())
        assertEquals("old weights", directory.resolve("$graphName.data").readText())
    }

    @Test fun validationFailurePreservesInstalledPair() = runBlocking {
        val directory = oldModel()
        try {
            Qwen3ForcedAlignerImporter(directory).install(sources(), validate = {
                throw IllegalArgumentException("external weight reference mismatch")
            }, publish = { fail("Invalid model must not be published") })
            fail("Import must fail")
        } catch (_: IllegalArgumentException) {
            assertOldModel(directory)
        }
    }

    @Test fun truncatedOrEmptyWeightsPreserveInstalledPair() = runBlocking {
        val directory = oldModel()
        for (weights in listOf(source("$graphName.data", "short", 50), source("$graphName.data", ""))) {
            try {
                Qwen3ForcedAlignerImporter(directory).install(listOf(source(graphName, "new"), weights),
                    validate = { fail("Do not validate incomplete files") }, publish = { fail("Do not publish") })
                fail("Import must fail")
            } catch (_: IllegalStateException) {
                assertOldModel(directory)
            }
        }
    }

    @Test fun copyFailurePreservesInstalledPair() = runBlocking {
        val directory = oldModel()
        val unreadable = Qwen3ForcedAlignerImporter.Source("$graphName.data", null) { throw IOException("read failed") }
        try {
            Qwen3ForcedAlignerImporter(directory).install(listOf(source(graphName, "new"), unreadable),
                validate = { fail("Do not validate") }, publish = { fail("Do not publish") })
            fail("Import must fail")
        } catch (_: IOException) {
            assertOldModel(directory)
        }
    }

    @Test fun cancellationDuringCopyPreservesInstalledPairAndCleansStaging() = runBlocking {
        val directory = oldModel()
        val pending = async {
            Qwen3ForcedAlignerImporter(directory).install(sources(),
                validate = { fail("Cancelled import must not validate") }, publish = { fail("Do not publish") },
                onProgress = { if (it.copied > 0) currentCoroutineContext().cancel() })
        }
        try {
            pending.await()
            fail("Import must be cancelled")
        } catch (_: CancellationException) {
            assertOldModel(directory)
        }
    }

    @Test fun preferenceWriteFailureRestoresInstalledPair() = runBlocking {
        val directory = oldModel()
        try {
            Qwen3ForcedAlignerImporter(directory).install(sources(), validate = {},
                publish = { throw IOException("cannot save preference") })
            fail("Import must fail")
        } catch (_: IOException) {
            assertOldModel(directory)
        }
    }

    @Test fun preparedDownloadInstallsWithoutCopyingAndPreservesPublishedPath() = runBlocking {
        val directory = oldModel()
        val staging = temporary.newFolder("release-extract")
        staging.resolve(graphName).writeText("release graph")
        staging.resolve("$graphName.data").writeText("release weights")
        val installed = Qwen3ForcedAlignerImporter(directory).installPrepared(
            staging,
            validate = { graph ->
                assertEquals("release weights", Qwen3ForcedAlignerModelFiles.dataFile(graph).readText())
                assertOldModelContents(directory)
            },
            publish = { assertEquals(directory.resolve(graphName), it) },
        )
        assertEquals("release graph", installed.readText())
        assertEquals("release weights", Qwen3ForcedAlignerModelFiles.dataFile(installed).readText())
        assertFalse(staging.exists())
    }

    @Test fun preparedDownloadValidationFailureRetainsOldModel() = runBlocking {
        val directory = oldModel()
        val staging = temporary.newFolder("release-extract")
        staging.resolve(graphName).writeText("invalid graph")
        staging.resolve("$graphName.data").writeText("invalid weights")
        try {
            Qwen3ForcedAlignerImporter(directory).installPrepared(
                staging,
                validate = { throw IllegalArgumentException("bad release") },
                publish = { fail("Do not publish invalid release") },
            )
            fail("Invalid release must not be installed")
        } catch (_: IllegalArgumentException) {
            // The previous pair must remain selected after validation fails.
        }
        assertOldModel(directory)
        assertFalse(staging.exists())
    }

    @Test fun preparedDownloadPreferenceFailureRestoresOldModel() = runBlocking {
        val directory = oldModel()
        val staging = temporary.newFolder("release-extract")
        staging.resolve(graphName).writeText("release graph")
        staging.resolve("$graphName.data").writeText("release weights")
        try {
            Qwen3ForcedAlignerImporter(directory).installPrepared(
                staging,
                validate = {},
                publish = { throw IOException("cannot save preference") },
            )
            fail("Import must fail")
        } catch (_: IOException) {
            assertOldModel(directory)
            assertFalse(staging.exists())
        }
    }

    @Test fun recoversInterruptedSwapBeforeNextImport() = runBlocking {
        val directory = oldModel()
        val backup = File(temporary.root, ".forced-aligner_backup")
        assertTrue(directory.renameTo(backup))
        try {
            Qwen3ForcedAlignerImporter(directory).install(sources(), validate = {
                throw IllegalArgumentException("invalid new pair")
            }, publish = { fail("Do not publish") })
            fail("Import must fail")
        } catch (_: IllegalArgumentException) {
            assertOldModel(directory)
        }
    }

    @Test fun missingOrEmptyDataDisablesConfiguredStatus() {
        val directory = oldModel()
        val graph = directory.resolve(graphName)
        assertTrue(Qwen3ForcedAlignerModelFiles.isComplete(graph))
        Qwen3ForcedAlignerModelFiles.dataFile(graph).writeText("")
        assertFalse(Qwen3ForcedAlignerModelFiles.isComplete(graph))
        Qwen3ForcedAlignerModelFiles.dataFile(graph).delete()
        assertFalse(Qwen3ForcedAlignerModelFiles.isComplete(graph))
        assertFalse(Qwen3ForcedAlignerModelFiles.isComplete(null))
    }

    @Test fun externalModelRequiresSelectionAndResetDoesNotRestoreIt() {
        val models = temporary.newFolder("downloaded-models")
        val privateFiles = temporary.newFolder("private-files")
        val directory = File(models, Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME)
        assertTrue(directory.mkdir())
        val graph = directory.resolve(graphName)
        graph.writeText("graph")
        Qwen3ForcedAlignerModelFiles.dataFile(graph).writeText("weights")

        assertEquals(graph, Qwen3ForcedAlignerModelFiles.findCompleteGraph(directory))
        assertTrue(Qwen3ForcedAlignerModelFiles.isConfigured(graph, privateFiles))
        assertEquals(null, Qwen3ForcedAlignerModelFiles.configuredGraph(null, privateFiles))
        assertEquals(graph, Qwen3ForcedAlignerModelFiles.configuredGraph(graph, privateFiles))
        val otherDirectory = temporary.newFolder("other-local-folder")
        val otherGraph = otherDirectory.resolve(graphName)
        otherGraph.writeText("graph")
        Qwen3ForcedAlignerModelFiles.dataFile(otherGraph).writeText("weights")
        assertTrue(Qwen3ForcedAlignerModelFiles.isConfigured(otherGraph, privateFiles))
        val legacyDirectory = File(privateFiles, "models/qwen3-asr/forced-aligner")
        assertTrue(legacyDirectory.mkdirs())
        val legacy = legacyDirectory.resolve(graphName)
        legacy.writeText("graph")
        Qwen3ForcedAlignerModelFiles.dataFile(legacy).writeText("weights")
        assertFalse(Qwen3ForcedAlignerModelFiles.isConfigured(legacy, privateFiles))
        assertEquals(null, Qwen3ForcedAlignerModelFiles.configuredGraph(legacy, privateFiles))
        Qwen3ForcedAlignerModelFiles.dataFile(graph).delete()
        assertEquals(null, Qwen3ForcedAlignerModelFiles.findCompleteGraph(directory))
        assertEquals(null, Qwen3ForcedAlignerModelFiles.configuredGraph(graph, privateFiles))
    }
}
