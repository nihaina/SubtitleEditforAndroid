package com.subtitleedit.usecase

import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.util.ModelDownloader
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed class DownloadedAsrModel {
    abstract val modelFile: File
    abstract val requiredFiles: List<File>

    data class SenseVoice(
        val option: ModelDownloader.SenseVoiceModelOption,
        val files: ModelDownloader.SenseVoiceFiles,
        val downloadedSource: File? = null
    ) : DownloadedAsrModel() {
        override val modelFile get() = files.model
        override val requiredFiles get() = listOf(files.model, files.tokens)
    }

    data class Whisper(
        val files: ModelDownloader.WhisperFiles
    ) : DownloadedAsrModel() {
        override val modelFile get() = files.encoder
        override val requiredFiles get() = listOf(files.encoder, files.decoder, files.tokens)
    }

    data class Parakeet(
        val option: ModelDownloader.ParakeetModelOption,
        val files: ModelDownloader.ParakeetFiles
    ) : DownloadedAsrModel() {
        override val modelFile get() = when (option.architecture) {
            ModelDownloader.ParakeetArchitecture.TDT -> requireNotNull(files.encoder)
            ModelDownloader.ParakeetArchitecture.CTC -> requireNotNull(files.model)
        }
        override val requiredFiles get() = when (option.architecture) {
            ModelDownloader.ParakeetArchitecture.TDT -> listOf(
                requireNotNull(files.encoder), requireNotNull(files.decoder),
                requireNotNull(files.joiner), files.tokens
            )
            ModelDownloader.ParakeetArchitecture.CTC -> listOf(requireNotNull(files.model), files.tokens)
        }
    }

    data class Qwen3Asr(
        val option: ModelDownloader.Qwen3AsrModelOption,
        val files: ModelDownloader.Qwen3AsrFiles
    ) : DownloadedAsrModel() {
        override val modelFile get() = files.encoder
        override val requiredFiles get() = listOf(
            files.convFrontend, files.encoder, files.decoder,
            File(files.tokenizer, "config.json"),
            File(files.tokenizer, "tokenizer_config.json"),
            File(files.tokenizer, "vocab.json"),
            File(files.tokenizer, "merges.txt"),
            File(files.tokenizer, "chat_template.json"),
            File(files.tokenizer, "preprocessor_config.json")
        )
    }
}

internal class DownloadAsrModelUseCase(
    private val repository: ModelRepository,
    private val prepareSenseVoice: suspend (
        ModelDownloader.SenseVoiceModelOption, (ModelDownloader.Progress) -> Unit
    ) -> DownloadedAsrModel.SenseVoice,
    private val selectModel: (DownloadedAsrModel) -> Unit
) {
    suspend operator fun invoke(
        kind: String,
        optionId: String,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): File {
        val downloaded = when (kind) {
            KIND_SENSEVOICE -> {
                val option = (listOf(repository.senseVoiceCpuModel) + repository.senseVoiceNpuModels)
                    .firstOrNull { it.id == optionId }
                requireNotNull(option) { "不支持的 SenseVoice 模型：$optionId" }
                prepareSenseVoice(option, onProgress)
            }
            KIND_WHISPER -> {
                val option = repository.whisperModels.firstOrNull { it.id == optionId }
                requireNotNull(option) { "不支持的 Whisper 模型：$optionId" }
                DownloadedAsrModel.Whisper(repository.downloadWhisper(option, onProgress))
            }
            KIND_PARAKEET -> {
                val option = repository.parakeetModels.firstOrNull { it.modelType == optionId }
                requireNotNull(option) { "不支持的 Parakeet 模型：$optionId" }
                DownloadedAsrModel.Parakeet(option, repository.downloadParakeet(option, onProgress))
            }
            KIND_QWEN3_ASR -> {
                val option = repository.qwen3AsrModels.firstOrNull { it.id == optionId }
                requireNotNull(option) { "不支持的 Qwen3-ASR 模型：$optionId" }
                DownloadedAsrModel.Qwen3Asr(option, repository.downloadQwen3Asr(option, onProgress))
            }
            else -> throw IllegalArgumentException("不支持的模型任务：$kind")
        }
        currentCoroutineContext().ensureActive()
        check(downloaded.requiredFiles.all { it.isFile && it.length() > 0L }) {
            "下载完成但模型文件不完整"
        }
        selectModel(downloaded)
        return downloaded.modelFile
    }

    companion object {
        const val KIND_SENSEVOICE = "sensevoice"
        const val KIND_WHISPER = "whisper"
        const val KIND_PARAKEET = "parakeet"
        const val KIND_QWEN3_ASR = "qwen3_asr"
        val KINDS = setOf(KIND_SENSEVOICE, KIND_WHISPER, KIND_PARAKEET, KIND_QWEN3_ASR)
    }
}
