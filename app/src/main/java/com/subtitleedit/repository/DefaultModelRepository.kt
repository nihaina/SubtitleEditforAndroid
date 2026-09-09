package com.subtitleedit.repository

import com.subtitleedit.util.ModelDownloader
import java.io.File

internal class DefaultModelRepository : ModelRepository {
    override fun modelsDirectory(): File = ModelDownloader.modelsDirectory()

    override val senseVoiceCpuModel: ModelDownloader.SenseVoiceModelOption
        get() = ModelDownloader.SENSEVOICE_CPU_MODEL
    override val senseVoiceNpuModels: List<ModelDownloader.SenseVoiceModelOption>
        get() = ModelDownloader.SENSEVOICE_NPU_MODELS
    override val whisperModels: List<ModelDownloader.WhisperModelOption>
        get() = ModelDownloader.WHISPER_MODELS
    override val parakeetTdtModel: ModelDownloader.ParakeetModelOption
        get() = ModelDownloader.PARAKEET_TDT_MODEL
    override val parakeetCtcJaModel: ModelDownloader.ParakeetModelOption
        get() = ModelDownloader.PARAKEET_CTC_JA_MODEL
    override val parakeetModels: List<ModelDownloader.ParakeetModelOption>
        get() = ModelDownloader.PARAKEET_MODELS
    override val separationDirectoryName: String
        get() = ModelDownloader.SEPARATION_DIRECTORY_NAME

    override suspend fun downloadSenseVoice(
        option: ModelDownloader.SenseVoiceModelOption,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): ModelDownloader.SenseVoiceFiles = ModelDownloader.downloadSenseVoice(option, onProgress)

    override suspend fun downloadWhisper(
        option: ModelDownloader.WhisperModelOption,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): ModelDownloader.WhisperFiles = ModelDownloader.downloadWhisper(option, onProgress)

    override suspend fun downloadParakeet(
        option: ModelDownloader.ParakeetModelOption,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): ModelDownloader.ParakeetFiles = ModelDownloader.downloadParakeet(option, onProgress)

    override suspend fun downloadDemixGeneralModel(
        onProgress: (ModelDownloader.Progress) -> Unit
    ): File = ModelDownloader.downloadDemixGeneralModel(onProgress)
}
