package com.subtitleedit.work

import com.subtitleedit.usecase.DownloadAsrModelUseCase
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.Qwen3ForcedAlignerModelFiles
import com.subtitleedit.util.Qwen3ForcedAlignerReleaseDownloader
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelImportTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root

    @Test fun completeSenseVoiceBundleOrReadableArchiveCanBeImportedOffline() {
        val option = ModelDownloader.SENSEVOICE_CPU_MODEL
        val kind = DownloadAsrModelUseCase.KIND_SENSEVOICE
        sizedFile("${option.directoryName}/model.int8.onnx")
        assertFalse(canImportModelLocally(root, kind, option.id))
        File(root, "${option.directoryName}/tokens.txt").writeText("word 0")
        assertTrue(canImportModelLocally(root, kind, option.id))
        assertFalse(canImportModelLocally(root, kind, "unknown"))
        val whisper = ModelDownloader.WHISPER_MODELS.first()
        val archive = File(root, "${whisper.directoryName}.tar.bz2")
        archive.createNewFile()
        assertFalse(canImportModelLocally(root, DownloadAsrModelUseCase.KIND_WHISPER, whisper.id))
        archive.writeText("cached archive")
        assertTrue(canImportModelLocally(root, DownloadAsrModelUseCase.KIND_WHISPER, whisper.id))
    }

    @Test fun privateNpuModelUsesTheRequestedDuration() {
        val option = ModelDownloader.SENSEVOICE_NPU_MODELS.first()
        val duration = checkNotNull(option.durationSeconds)
        assertFalse(canImportModelLocally(root, DownloadAsrModelUseCase.KIND_SENSEVOICE, option.id))
        assertTrue(canImportModelLocally(root, DownloadAsrModelUseCase.KIND_SENSEVOICE, option.id) {
            assertEquals(duration, it)
            true
        })
    }

    @Test fun qwenStagingRequiresTheFullBundleForTheRequestedVariant() {
        val option = ModelDownloader.QWEN3_ASR_MODELS.first()
        val staging = ".qwen3_${option.id}_modelscope_downloading"
        for (name in listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx")) {
            sizedFile("$staging/${option.remoteModelDirectory}/$name")
        }
        val kind = DownloadAsrModelUseCase.KIND_QWEN3_ASR
        assertFalse(canImportModelLocally(root, kind, option.id))
        val tokenizer = File(root, "$staging/tokenizer").apply { mkdir() }
        listOf("config.json", "tokenizer_config.json", "vocab.json", "merges.txt", "chat_template.json", "preprocessor_config.json")
            .forEach { File(tokenizer, it).writeText("tokenizer data") }
        assertTrue(canImportModelLocally(root, kind, option.id))
        assertFalse(canImportModelLocally(root, kind, ModelDownloader.QWEN3_ASR_MODELS.last().id))
    }

    @Test fun legacyDemixModelIsReusedButLlmPartialFilesAreNot() {
        val demix = ModelDownloadWorker.KIND_DEMIX_GENERAL
        assertFalse(canImportModelLocally(root, demix, null))
        sizedFile("htdemucs_fp16weights.onnx")
        assertTrue(canImportModelLocally(root, demix, null))
        val option = ModelDownloader.LLM_MODELS.first()
        val relativePath = "llm/${option.fileName}"
        sizedFile("$relativePath.part")
        assertFalse(canImportModelLocally(root, ModelDownloadWorker.KIND_LLM, option.id))
        sizedFile(relativePath, option.minimumSizeBytes)
        assertTrue(canImportModelLocally(root, ModelDownloadWorker.KIND_LLM, option.id))
    }

    @Test fun cachedForcedAlignerIsValidatedAndPublishedWithoutCreatingDownloadCache() = runBlocking {
        val directory = File(root, Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME).apply { mkdir() }
        val graph = File(directory, "forced_aligner.onnx").apply { writeText("local graph") }
        val kind = ModelDownloadWorker.KIND_QWEN3_FORCED_ALIGNER
        assertFalse(canImportModelLocally(root, kind, null))
        Qwen3ForcedAlignerModelFiles.dataFile(graph).writeText("local tensors")
        assertTrue(canImportModelLocally(root, kind, null))
        val actions = mutableListOf<String>()
        val result = Qwen3ForcedAlignerReleaseDownloader.downloadAndInstall(
            directory,
            validate = { assertEquals(graph, it); actions += "validate" },
            publish = { assertEquals(graph, it); actions += "publish" },
            onProgress = {},
        )
        assertEquals(graph, result)
        assertEquals(listOf("validate", "publish"), actions)
        assertEquals(listOf(directory.name), root.listFiles()?.map(File::getName))
    }

    private fun sizedFile(path: String, size: Long = 1024L * 1024): File = File(root, path).also { file ->
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }
}
