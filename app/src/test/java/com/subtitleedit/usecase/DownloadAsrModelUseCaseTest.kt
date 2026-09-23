package com.subtitleedit.usecase

import com.subtitleedit.repository.DefaultModelRepository
import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.util.ModelDownloader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadAsrModelUseCaseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun allSenseVoiceVariantsSelectPreparedFilesIncludingNpuBinary() = runBlocking {
        val repository = FakeRepository()
        val selected = mutableListOf<DownloadedAsrModel>()
        val useCase = useCase(repository, selected)

        (listOf(repository.senseVoiceCpuModel) + repository.senseVoiceNpuModels).forEach { option ->
            val modelFile = useCase(DownloadAsrModelUseCase.KIND_SENSEVOICE, option.id) { }
            val result = selected.last() as DownloadedAsrModel.SenseVoice
            assertEquals(option, result.option)
            assertEquals(result.files.model, modelFile)
            assertEquals(
                if (option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN) "model.bin" else "model.onnx",
                modelFile.name
            )
        }
        assertEquals(3, selected.size)
    }

    @Test
    fun whisperAndParakeetVariantsDownloadAndSelectCompleteModelBundles() = runBlocking {
        val repository = FakeRepository()
        val selected = mutableListOf<DownloadedAsrModel>()
        val progress = mutableListOf<ModelDownloader.Progress>()
        val useCase = useCase(repository, selected)

        repository.whisperModels.forEach { option ->
            val output = useCase(DownloadAsrModelUseCase.KIND_WHISPER, option.id, progress::add)
            assertTrue(selected.last() is DownloadedAsrModel.Whisper)
            assertEquals(repository.whisperFiles.encoder, output)
        }
        repository.parakeetModels.forEach { option ->
            val output = useCase(DownloadAsrModelUseCase.KIND_PARAKEET, option.modelType, progress::add)
            val result = selected.last() as DownloadedAsrModel.Parakeet
            assertEquals(option, result.option)
            assertEquals(result.modelFile, output)
            assertEquals(if (option.architecture == ModelDownloader.ParakeetArchitecture.TDT) 4 else 2,
                result.requiredFiles.size)
        }
        assertEquals(repository.whisperModels.map { it.id } + repository.parakeetModels.map { it.modelType },
            repository.requestedOptions)
        assertEquals(6, selected.size)
        assertTrue(progress.all { it == repository.progress })
        assertEquals(6, progress.size)
    }

    @Test
    fun qwen3AsrVariantsDownloadAndSelectCompleteModelBundles() = runBlocking {
        val repository = FakeRepository()
        val selected = mutableListOf<DownloadedAsrModel>()
        val progress = mutableListOf<ModelDownloader.Progress>()
        val useCase = useCase(repository, selected)

        repository.qwen3AsrModels.forEach { option ->
            val output = useCase(DownloadAsrModelUseCase.KIND_QWEN3_ASR, option.id, progress::add)
            val result = selected.last() as DownloadedAsrModel.Qwen3Asr
            assertEquals(option, result.option)
            assertEquals(result.files.encoder, output)
            assertEquals(9, result.requiredFiles.size)
        }

        assertEquals(repository.qwen3AsrModels.map { it.id }, repository.requestedOptions)
        assertEquals(2, selected.size)
        assertTrue(progress.all { it == repository.progress })
        assertEquals(2, progress.size)
    }

    @Test
    fun unknownKindsAndVariantsFailWithoutDownloadingOrChangingSelection() = runBlocking {
        val repository = FakeRepository()
        val selected = mutableListOf<DownloadedAsrModel>()
        val useCase = useCase(repository, selected)
        val requests = DownloadAsrModelUseCase.KINDS.map { it to "missing" } + ("unknown" to "tiny")
        requests.forEach { (kind, option) ->
            try {
                useCase(kind, option) { }
                fail("Invalid persisted request should fail")
            } catch (_: IllegalArgumentException) {
                // Expected for stale or malformed WorkManager input.
            }
        }
        assertTrue(repository.requestedOptions.isEmpty())
        assertTrue(selected.isEmpty())
    }

    @Test
    fun incompleteModelBundleDoesNotReplaceSelection() = runBlocking {
        val repository = FakeRepository()
        val selected = mutableListOf<DownloadedAsrModel>()
        repository.whisperFiles.decoder.delete()
        try {
            useCase(repository, selected)(DownloadAsrModelUseCase.KIND_WHISPER, "tiny") { }
            fail("Missing decoder should fail")
        } catch (_: IllegalStateException) {
            assertTrue(selected.isEmpty())
        }
    }

    @Test
    fun cancellationAfterDownloadDoesNotReplaceSelection() = runBlocking {
        val repository = FakeRepository()
        val selected = mutableListOf<DownloadedAsrModel>()
        repository.afterDownload = { currentCoroutineContext().cancel() }
        val job = launch {
            useCase(repository, selected)(DownloadAsrModelUseCase.KIND_WHISPER, "tiny") { }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(selected.isEmpty())
    }

    @Test
    fun failedDownloadPreservesErrorAndDoesNotReplaceSelection() = runBlocking {
        val repository = FakeRepository()
        val selected = mutableListOf<DownloadedAsrModel>()
        val failure = IOException("connection lost")
        repository.afterDownload = { throw failure }
        try {
            useCase(repository, selected)(DownloadAsrModelUseCase.KIND_PARAKEET, repository.parakeetTdtModel.modelType) { }
            fail("Download should fail")
        } catch (error: IOException) {
            assertEquals(failure, error)
            assertTrue(selected.isEmpty())
        }
    }

    private fun useCase(repository: FakeRepository, selected: MutableList<DownloadedAsrModel>) =
        DownloadAsrModelUseCase(
            repository,
            prepareSenseVoice = { option, _ ->
                val isNpu = option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN
                DownloadedAsrModel.SenseVoice(option, ModelDownloader.SenseVoiceFiles(
                    model = modelFile(if (isNpu) "model.bin" else "model.onnx"),
                    tokens = modelFile("tokens.txt")
                ))
            },
            selectModel = { selected.add(it) }
        )

    private fun modelFile(name: String): File = File(temporaryFolder.newFolder(), name).apply {
        writeText("model content")
    }

    private inner class FakeRepository : ModelRepository by DefaultModelRepository() {
        val requestedOptions = mutableListOf<String>()
        val progress = ModelDownloader.Progress("downloading", 25L, 100L)
        var afterDownload: suspend () -> Unit = { }
        val whisperFiles = ModelDownloader.WhisperFiles(
            modelFile("encoder.onnx"), modelFile("decoder.onnx"), modelFile("tokens.txt")
        )
        val qwen3AsrFiles = ModelDownloader.Qwen3AsrFiles(
            convFrontend = modelFile("conv_frontend.onnx"),
            encoder = modelFile("encoder.int8.onnx"),
            decoder = modelFile("decoder.int8.onnx"),
            tokenizer = temporaryFolder.newFolder().apply {
                listOf(
                    "config.json", "tokenizer_config.json", "vocab.json", "merges.txt",
                    "chat_template.json", "preprocessor_config.json"
                ).forEach { File(this, it).writeText("tokenizer") }
            }
        )

        override suspend fun downloadWhisper(
            option: ModelDownloader.WhisperModelOption,
            onProgress: (ModelDownloader.Progress) -> Unit
        ): ModelDownloader.WhisperFiles {
            requestedOptions.add(option.id)
            onProgress(progress)
            afterDownload()
            return whisperFiles
        }

        override suspend fun downloadParakeet(
            option: ModelDownloader.ParakeetModelOption,
            onProgress: (ModelDownloader.Progress) -> Unit
        ): ModelDownloader.ParakeetFiles {
            requestedOptions.add(option.modelType)
            onProgress(progress)
            afterDownload()
            return if (option.architecture == ModelDownloader.ParakeetArchitecture.TDT) {
                ModelDownloader.ParakeetFiles(
                    encoder = modelFile("encoder.onnx"), decoder = modelFile("decoder.onnx"),
                    joiner = modelFile("joiner.onnx"), tokens = modelFile("tokens.txt")
                )
            } else {
                ModelDownloader.ParakeetFiles(model = modelFile("model.onnx"), tokens = modelFile("tokens.txt"))
            }
        }

        override suspend fun downloadQwen3Asr(
            option: ModelDownloader.Qwen3AsrModelOption,
            onProgress: (ModelDownloader.Progress) -> Unit
        ): ModelDownloader.Qwen3AsrFiles {
            requestedOptions.add(option.id)
            onProgress(progress)
            afterDownload()
            return qwen3AsrFiles
        }
    }
}
