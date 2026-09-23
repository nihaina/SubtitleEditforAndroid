package com.subtitleedit.repository

import com.subtitleedit.util.ModelDownloader
import java.io.File

internal interface ModelRepository {
    fun modelsDirectory(): File

    val senseVoiceCpuModel: ModelDownloader.SenseVoiceModelOption
    val senseVoiceNpuModels: List<ModelDownloader.SenseVoiceModelOption>
    val whisperModels: List<ModelDownloader.WhisperModelOption>
    val parakeetTdtModel: ModelDownloader.ParakeetModelOption
    val parakeetCtcJaModel: ModelDownloader.ParakeetModelOption
    val parakeetModels: List<ModelDownloader.ParakeetModelOption>
    val qwen3AsrModels: List<ModelDownloader.Qwen3AsrModelOption>
    val separationDirectoryName: String

    suspend fun downloadSenseVoice(
        option: ModelDownloader.SenseVoiceModelOption,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): ModelDownloader.SenseVoiceFiles

    suspend fun downloadWhisper(
        option: ModelDownloader.WhisperModelOption,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): ModelDownloader.WhisperFiles

    suspend fun downloadParakeet(
        option: ModelDownloader.ParakeetModelOption,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): ModelDownloader.ParakeetFiles

    suspend fun downloadQwen3Asr(
        option: ModelDownloader.Qwen3AsrModelOption,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): ModelDownloader.Qwen3AsrFiles

    suspend fun downloadDemixGeneralModel(
        onProgress: (ModelDownloader.Progress) -> Unit
    ): File
}
