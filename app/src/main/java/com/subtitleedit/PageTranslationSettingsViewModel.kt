package com.subtitleedit

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.subtitleedit.translation.FoxletPageTranslation
import com.subtitleedit.translation.PageTranslationCatalog
import com.subtitleedit.translation.PageTranslationLanguage
import com.subtitleedit.translation.PageTranslationModel
import com.subtitleedit.translation.PageTranslationOptions
import com.subtitleedit.translation.PageTranslationPlan
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.work.PageTranslationDownloadScheduler
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PageTranslationSettingsState(
    val options: PageTranslationOptions,
    val supported: Boolean,
    val catalog: PageTranslationCatalog? = null,
    val downloadedModels: Set<PageTranslationModel>? = null,
    val downloads: Map<PageTranslationModel, WorkInfo> = emptyMap(),
    val enqueuingModels: Set<PageTranslationModel> = emptySet(),
    val checkingModels: Boolean = true,
    val deletingModel: PageTranslationModel? = null,
    @get:StringRes val error: Int? = null,
) {
    fun missingModels(plan: PageTranslationPlan): Set<PageTranslationModel> {
        val installed = downloadedModels ?: return emptySet()
        return plan.requiredModels - installed
    }

    fun isDownloadPending(model: PageTranslationModel): Boolean =
        model in enqueuingModels || downloads[model]?.state?.isFinished == false

    fun canDownload(model: PageTranslationModel): Boolean = supported && !checkingModels && downloadedModels != null &&
        model in catalog?.models.orEmpty() && model !in downloadedModels && model != deletingModel && !isDownloadPending(model)
}

internal class PageTranslationSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsManager.getInstance(application)
    private val downloadScheduler by lazy { PageTranslationDownloadScheduler(application) }
    private val storageLock = Mutex()
    private var observingDownloads = false
    private val mutableState = MutableStateFlow(PageTranslationSettingsState(
        settings.getPageTranslationOptions(), FoxletPageTranslation.isSupported,
    ))
    val state = mutableState.asStateFlow()

    init {
        refreshModels()
    }

    fun selectSource(language: PageTranslationLanguage?) = saveOptions(state.value.options.copy(source = language))

    fun selectTarget(language: PageTranslationLanguage) = saveOptions(state.value.options.copy(target = language))

    private fun saveOptions(options: PageTranslationOptions) {
        val selection = state.value.catalog?.normalize(options) ?: return
        settings.setPageTranslationOptions(selection)
        mutableState.update { it.copy(options = selection) }
    }

    fun refreshModels() = refreshModels(null)

    private fun refreshModels(changedModels: Set<PageTranslationModel>?) {
        if (!state.value.supported) return
        viewModelScope.launch {
            storageLock.withLock {
                mutableState.update { it.copy(checkingModels = true) }
                try {
                    val application = getApplication<Application>()
                    val catalog = FoxletPageTranslation.catalog(application)
                    if (state.value.catalog == null) {
                        mutableState.update { it.copy(catalog = catalog, options = catalog.normalize(it.options)) }
                    }
                    val previous = state.value.downloadedModels
                    val candidates = if (previous != null && changedModels != null) changedModels else catalog.models
                    val verified = FoxletPageTranslation.downloaded(application, candidates)
                    val models = (previous.orEmpty() - candidates) + verified
                    mutableState.update {
                        it.copy(
                            downloadedModels = models,
                            error = it.error.takeUnless { error -> error == R.string.page_translation_check_failed },
                        )
                    }
                    observeDownloads(catalog)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportError(R.string.page_translation_check_failed, error)
                } finally {
                    mutableState.update { it.copy(checkingModels = false) }
                }
            }
        }
    }

    private fun observeDownloads(catalog: PageTranslationCatalog) {
        if (observingDownloads) return
        observingDownloads = true
        viewModelScope.launch {
            downloadScheduler.observe(catalog).collect { downloads ->
                val snapshot = state.value
                val completed = downloads.filter { (model, work) ->
                    val previous = snapshot.downloads[model]
                    work.state == WorkInfo.State.SUCCEEDED && model !in snapshot.downloadedModels.orEmpty() &&
                        (previous?.state != work.state || previous.id != work.id)
                }.keys
                mutableState.update { it.copy(downloads = downloads) }
                if (completed.isNotEmpty()) refreshModels(completed)
            }
        }
    }

    fun downloadMissing(systemLocale: Locale) {
        val snapshot = state.value
        val plan = snapshot.catalog?.plan(snapshot.options, systemLocale) ?: return
        snapshot.missingModels(plan).forEach(::download)
    }

    fun download(model: PageTranslationModel) {
        if (!state.value.canDownload(model)) return
        mutableState.update { it.copy(enqueuingModels = it.enqueuingModels + model, error = null) }
        viewModelScope.launch {
            try {
                downloadScheduler.enqueue(model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(R.string.page_translation_download_failed, error)
            } finally {
                mutableState.update { it.copy(enqueuingModels = it.enqueuingModels - model) }
            }
        }
    }

    fun cancelDownload(model: PageTranslationModel) = downloadScheduler.cancel(model)

    fun delete(model: PageTranslationModel) {
        val snapshot = state.value
        if (!snapshot.supported || snapshot.deletingModel != null || model !in snapshot.downloadedModels.orEmpty() ||
            snapshot.isDownloadPending(model)
        ) return
        mutableState.update { it.copy(deletingModel = model, error = null) }
        viewModelScope.launch {
            try {
                storageLock.withLock {
                    FoxletPageTranslation.delete(getApplication(), model)
                    mutableState.update { it.copy(downloadedModels = it.downloadedModels?.minus(model)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(R.string.page_translation_delete_failed, error)
                refreshModels()
            } finally {
                mutableState.update { it.copy(deletingModel = null) }
            }
        }
    }

    private fun reportError(@StringRes message: Int, error: Exception) {
        Log.w("PageTranslationSettings", "Model operation failed", error)
        mutableState.update { it.copy(error = message) }
    }
}
