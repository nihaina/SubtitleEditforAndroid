package com.subtitleedit.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.usecase.DownloadedAsrModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Prepares and selects downloaded models without retaining an Activity. Called on the worker's IO context. */
internal class AsrModelDownloadInstaller(
    private val context: Context,
    private val repository: ModelRepository
) {
    private val settings get() = SettingsManager.getInstance(context)
    private val npuImporter get() = SenseVoiceNpuModelImporter(context, context.contentResolver)

    suspend fun prepareSenseVoice(
        option: ModelDownloader.SenseVoiceModelOption,
        onProgress: (ModelDownloader.Progress) -> Unit
    ): DownloadedAsrModel.SenseVoice {
        if (option.architecture != ModelDownloader.SenseVoiceArchitecture.QNN) {
            return DownloadedAsrModel.SenseVoice(option, repository.downloadSenseVoice(option, onProgress))
        }
        check("arm64-v8a" in Build.SUPPORTED_ABIS) { "SenseVoice NPU 仅支持 arm64-v8a 骁龙设备" }
        check(QnnRuntimeAvailability.isAvailable(context)) { "当前安装包不包含 QNN 运行库，请安装 arm64 QNN 版" }
        val duration = requireNotNull(option.durationSeconds) { "SenseVoice NPU 模型缺少时长信息" }
        val importer = npuImporter
        onProgress(ModelDownloader.Progress("正在检查已生成的 SenseVoice NPU BIN 模型"))
        importer.findInstalledModel(duration)?.let { installed ->
            return DownloadedAsrModel.SenseVoice(
                option, ModelDownloader.SenseVoiceFiles(installed.contextBinary, installed.tokens)
            )
        }
        val files = repository.downloadSenseVoice(option, onProgress)
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val imported = importer.importFromFiles(files.model, files.tokens, duration) { message ->
            coroutineContext.ensureActive()
            onProgress(ModelDownloader.Progress(message))
        }
        coroutineContext.ensureActive()
        return DownloadedAsrModel.SenseVoice(
            option,
            ModelDownloader.SenseVoiceFiles(imported.contextBinary, imported.tokens),
            downloadedSource = files.model
        )
    }

    fun selectModel(downloaded: DownloadedAsrModel) {
        when (downloaded) {
            is DownloadedAsrModel.SenseVoice -> selectSenseVoice(downloaded)
            is DownloadedAsrModel.Whisper -> {
                settings.setWhisperEncoderPath(Uri.fromFile(downloaded.files.encoder).toString())
                settings.setWhisperDecoderPath(Uri.fromFile(downloaded.files.decoder).toString())
                settings.setWhisperTokensPath(Uri.fromFile(downloaded.files.tokens).toString())
                settings.setAsrModelType(SettingsManager.ASR_MODEL_WHISPER)
            }
            is DownloadedAsrModel.Parakeet -> {
                val files = downloaded.files
                when (downloaded.option.architecture) {
                    ModelDownloader.ParakeetArchitecture.TDT -> {
                        settings.setParakeetTdtEncoderPath(Uri.fromFile(requireNotNull(files.encoder)).toString())
                        settings.setParakeetTdtDecoderPath(Uri.fromFile(requireNotNull(files.decoder)).toString())
                        settings.setParakeetTdtJoinerPath(Uri.fromFile(requireNotNull(files.joiner)).toString())
                        settings.setParakeetTdtTokensPath(Uri.fromFile(files.tokens).toString())
                    }
                    ModelDownloader.ParakeetArchitecture.CTC -> {
                        settings.setParakeetCtcModelPath(Uri.fromFile(requireNotNull(files.model)).toString())
                        settings.setParakeetCtcTokensPath(Uri.fromFile(files.tokens).toString())
                    }
                }
                settings.setAsrModelType(downloaded.option.modelType)
            }
            is DownloadedAsrModel.Qwen3Asr -> {
                val variant = downloaded.option.id
                settings.setQwen3AsrModelVariant(variant)
                settings.setQwen3AsrConvFrontendPath(Uri.fromFile(downloaded.files.convFrontend).toString(), variant)
                settings.setQwen3AsrEncoderPath(Uri.fromFile(downloaded.files.encoder).toString(), variant)
                settings.setQwen3AsrDecoderPath(Uri.fromFile(downloaded.files.decoder).toString(), variant)
                settings.setQwen3AsrTokenizerPath(Uri.fromFile(downloaded.files.tokenizer).toString(), variant)
                settings.setAsrModelType(SettingsManager.ASR_MODEL_QWEN3_ASR)
            }
        }
    }

    private fun selectSenseVoice(downloaded: DownloadedAsrModel.SenseVoice) {
        val isNpu = downloaded.option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN
        val previousModel = settings.getSenseVoiceModelPath(SettingsManager.SENSEVOICE_PROVIDER_NPU)
        val previousTokens = settings.getSenseVoiceTokensPath(SettingsManager.SENSEVOICE_PROVIDER_NPU)
        settings.setSenseVoiceProvider(
            if (isNpu) SettingsManager.SENSEVOICE_PROVIDER_NPU else SettingsManager.SENSEVOICE_PROVIDER_CPU
        )
        downloaded.option.durationSeconds?.let(settings::setSenseVoiceNpuDurationSeconds)
        settings.setSenseVoiceModelPath(Uri.fromFile(downloaded.files.model).toString())
        settings.setSenseVoiceTokensPath(Uri.fromFile(downloaded.files.tokens).toString())
        settings.setAsrModelType(SettingsManager.ASR_MODEL_SENSEVOICE)
        if (isNpu) {
            npuImporter.deleteManagedContextBinary(previousModel, except = downloaded.files.model)
            downloaded.downloadedSource?.delete()
            releasePersistedReadPermission(previousModel)
            releasePersistedReadPermission(previousTokens)
        }
    }

    private fun releasePersistedReadPermission(path: String) {
        if (path.isBlank()) return
        val uri = Uri.parse(path)
        if (uri.scheme != "content") return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
