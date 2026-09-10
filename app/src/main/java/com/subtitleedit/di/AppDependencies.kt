package com.subtitleedit.di

import android.app.Application
import android.net.Uri
import com.subtitleedit.nativebridge.DefaultNativeMediaEngine
import com.subtitleedit.nativebridge.NativeMediaEngine
import com.subtitleedit.demix.VocalSeparationEngine
import com.subtitleedit.demix.VocalSeparationRunner
import com.subtitleedit.repository.AiTranslationService
import com.subtitleedit.repository.ArchiveRepository
import com.subtitleedit.repository.DefaultAiTranslationService
import com.subtitleedit.repository.DefaultArchiveRepository
import com.subtitleedit.repository.DefaultMediaRepository
import com.subtitleedit.repository.DefaultModelRepository
import com.subtitleedit.repository.DefaultSpeechRecognitionService
import com.subtitleedit.repository.DefaultSubtitleRepository
import com.subtitleedit.repository.MediaRepository
import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.repository.SpeechRecognitionService
import com.subtitleedit.repository.SubtitleRepository
import com.subtitleedit.task.TaskStateStore
import com.subtitleedit.usecase.DownloadGeneralModelUseCase
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.work.TaskWorkScheduler
import java.io.File

internal class AppDependencies(application: Application) {
    private val appContext = application.applicationContext

    val nativeMediaEngine: NativeMediaEngine by lazy { DefaultNativeMediaEngine() }

    fun vocalSeparationRunner(
        modelPath: String,
        modelDisplayName: String,
        modelSize: Long?,
        graphOptimizationEnabled: Boolean,
        cpuArenaEnabled: Boolean,
        log: (String) -> Unit
    ): VocalSeparationRunner = VocalSeparationEngine(
        modelPath = modelPath,
        modelDisplayName = modelDisplayName,
        modelSize = modelSize,
        graphOptimizationEnabled = graphOptimizationEnabled,
        cpuArenaEnabled = cpuArenaEnabled,
        log = log
    )

    val subtitleRepository: SubtitleRepository by lazy { DefaultSubtitleRepository() }
    val modelRepository: ModelRepository by lazy { DefaultModelRepository() }
    val archiveRepository: ArchiveRepository by lazy { DefaultArchiveRepository() }
    val aiTranslationService: AiTranslationService by lazy { DefaultAiTranslationService() }
    val speechRecognitionService: SpeechRecognitionService by lazy {
        DefaultSpeechRecognitionService()
    }
    val taskStateStore: TaskStateStore by lazy { TaskStateStore() }
    val downloadGeneralModel: DownloadGeneralModelUseCase by lazy {
        DownloadGeneralModelUseCase(modelRepository) { file ->
            SettingsManager.getInstance(appContext)
                .selectDownloadedDemixModel(Uri.fromFile(file).toString())
        }
    }
    val taskWorkScheduler: TaskWorkScheduler by lazy {
        TaskWorkScheduler(appContext, taskStateStore)
    }

    fun mediaRepository(cacheDir: File): MediaRepository = DefaultMediaRepository(
        cacheDir = cacheDir,
        nativeMediaEngine = nativeMediaEngine
    )
}
