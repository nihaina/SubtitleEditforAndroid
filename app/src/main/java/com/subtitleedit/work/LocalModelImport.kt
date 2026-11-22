package com.subtitleedit.work

import com.subtitleedit.usecase.DownloadAsrModelUseCase
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.Qwen3ForcedAlignerModelFiles
import java.io.File

internal fun canImportModelLocally(
    root: File,
    kind: String,
    optionId: String?,
    hasInstalledSenseVoiceNpuModel: (Int) -> Boolean = { false },
): Boolean = when (kind) {
    DownloadAsrModelUseCase.KIND_SENSEVOICE ->
        (listOf(ModelDownloader.SENSEVOICE_CPU_MODEL) + ModelDownloader.SENSEVOICE_NPU_MODELS)
            .firstOrNull { it.id == optionId }?.let { option ->
                option.durationSeconds?.let(hasInstalledSenseVoiceNpuModel) == true ||
                    ModelDownloader.canImportSenseVoiceLocally(root, option)
            } ?: false
    DownloadAsrModelUseCase.KIND_WHISPER ->
        ModelDownloader.WHISPER_MODELS.firstOrNull { it.id == optionId }
            ?.let { ModelDownloader.canImportWhisperLocally(root, it) } ?: false
    DownloadAsrModelUseCase.KIND_PARAKEET ->
        ModelDownloader.PARAKEET_MODELS.firstOrNull { it.modelType == optionId }
            ?.let { ModelDownloader.canImportParakeetLocally(root, it) } ?: false
    DownloadAsrModelUseCase.KIND_QWEN3_ASR ->
        ModelDownloader.QWEN3_ASR_MODELS.firstOrNull { it.id == optionId }
            ?.let { ModelDownloader.canImportQwen3AsrLocally(root, it) } ?: false
    ModelDownloadWorker.KIND_LLM -> optionId != null && ModelDownloader.canImportLlmLocally(root, optionId)
    ModelDownloadWorker.KIND_DEMIX_GENERAL -> ModelDownloader.canImportDemixLocally(root)
    ModelDownloadWorker.KIND_QWEN3_FORCED_ALIGNER ->
        Qwen3ForcedAlignerModelFiles.findCompleteGraph(File(root, Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME)) != null
    else -> false
}
