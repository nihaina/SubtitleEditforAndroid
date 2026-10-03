package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.subtitleedit.demix.DemixOutputWriter
import com.subtitleedit.demix.VocalSeparationEngine
import com.subtitleedit.feature.ui.VocalSeparationDialog
import com.subtitleedit.feature.ui.VocalSeparationUiState
import com.subtitleedit.nativebridge.NativeMediaOperation
import com.subtitleedit.nativebridge.PcmFormat
import com.subtitleedit.task.LongTaskController
import com.subtitleedit.util.ByteSizeFormat
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.RuntimeLogManager
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.TaskLogBuffer
import com.subtitleedit.util.UriDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal class VocalSeparationViewModel(
    application: Application
) : AppViewModel<VocalSeparationUiState, Nothing>(application, VocalSeparationUiState()) {
    private companion object {
        const val OUTPUT_DIRECTORY_KEY = "vocal_separation"
        const val LOG_TAG = "VocalSeparation"
    }

    private data class SelectedMediaFile(val uri: Uri, val fileName: String)

    private val settings = SettingsManager.getInstance(application)
    private val taskController = LongTaskController(dependencies.taskStateStore, "vocal-separation")
    private val runtimeLog = TaskLogBuffer()
    private var mediaOperation: NativeMediaOperation? = null
    private var separationJob: Job? = null
    private var selectedFiles: List<SelectedMediaFile> = emptyList()
    private var accessWarningShown = false

    /** Current output directory, used as the picker's initial location. */
    var outputDirUri: Uri? = null
        private set

    private val isCancelled: Boolean get() = taskController.isCancellationRequested
    val isRunning: Boolean get() = currentState.isRunning

    /** Re-reads model configuration; called on every resume while idle. */
    fun refresh() {
        if (isRunning) return
        discardInaccessibleModelUris()
        val hasGeneral = isModelConfigured("general")
        val useFtModels = settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT
        val enabled = VocalSeparationEngine.Stem.entries.filterTo(linkedSetOf()) { stem ->
            hasGeneral || useFtModels && isModelConfigured(stem)
        }
        setState {
            var selected = selectedStems.intersect(enabled)
            if (!hasGeneral && selected.size > 1) selected = setOf(selected.first())
            copy(enabledStems = enabled, selectedStems = selected)
        }
        if (outputDirUri == null) setupDefaultOutputDir()
    }

    fun selectFiles(uris: List<Uri>) {
        if (isRunning) return
        selectedFiles = uris.map { SelectedMediaFile(it, fileName(it)) }
        val text = buildString {
            append(string(R.string.files_selected_header, selectedFiles.size))
            selectedFiles.forEachIndexed { index, item ->
                append('\n').append(string(R.string.files_selected_item, index + 1, item.fileName))
            }
        }
        setState { copy(selectedFilesText = text, hasSelectedFiles = selectedFiles.isNotEmpty()) }
    }

    fun selectOutputDirectory(uri: Uri) {
        val permissionSaved = runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            toast(R.string.directory_permission_save_failed, it.message.orEmpty(), duration = Toast.LENGTH_LONG)
        }
        outputDirUri = uri
        setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, uri)) }
        if (permissionSaved.isSuccess) {
            settings.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        }
    }

    fun setStemSelected(stem: VocalSeparationEngine.Stem, checked: Boolean) {
        val updated = currentState.selectedStems.toMutableSet().apply {
            if (checked) add(stem) else remove(stem)
        }
        if (checked && updated.size > 1 && !isModelConfigured("general")) {
            toast(R.string.vocal_separation_multi_stem_requires_general, duration = Toast.LENGTH_LONG)
            return
        }
        setState { copy(selectedStems = updated) }
    }

    fun start() {
        val output = outputDirUri
        val stems = selectedStems()
        val hasGeneral = isModelConfigured("general")
        val useFtModels = settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT
        val modelsAvailable = when {
            stems.size > 1 -> hasGeneral
            stems.size == 1 -> hasGeneral || useFtModels && isModelConfigured(stems.first())
            else -> false
        }
        if (selectedFiles.isEmpty() || output == null || !modelsAvailable) {
            toast(R.string.vocal_separation_missing_inputs)
            return
        }
        val expected = selectedFiles.flatMap { file ->
            val base = file.fileName.substringBeforeLast(".")
            stems.map { "${base}_${it.fileSuffix}.wav" }
        }
        if (expected.any { DemixOutputWriter.exists(app, output, it) }) {
            setState { copy(dialog = VocalSeparationDialog.OUTPUT_CONFLICT) }
        } else {
            runSeparation(output, stems, overwrite = false)
        }
    }

    fun resolveOutputConflict(overwrite: Boolean) {
        dismissDialog()
        outputDirUri?.let { runSeparation(it, selectedStems(), overwrite) }
    }

    /** Cancel button: first press asks for confirmation, confirmation cancels. */
    fun onCancelClicked() {
        if (currentState.dialog == VocalSeparationDialog.CANCEL) {
            dismissDialog()
            cancelSeparation()
        } else if (isRunning) {
            setState { copy(dialog = VocalSeparationDialog.CANCEL) }
        }
    }

    /** Returns true when the page may close immediately. */
    fun requestBack(): Boolean {
        if (!isRunning) return true
        setState { copy(dialog = VocalSeparationDialog.BACK) }
        return false
    }

    fun confirmBack() {
        dismissDialog()
        cancelSeparation()
    }

    fun dismissDialog() = setState { copy(dialog = VocalSeparationDialog.NONE) }

    private fun runSeparation(
        output: Uri,
        stems: Set<VocalSeparationEngine.Stem>,
        overwrite: Boolean
    ) {
        if (isRunning || taskController.isRunning) return
        mediaOperation?.cancel()
        mediaOperation = dependencies.nativeMediaEngine.openOperation()
        val files = selectedFiles
        runtimeLog.clear()
        setState {
            copy(
                isRunning = true,
                progressVisible = true,
                progressStatus = null,
                progress = 0,
                log = "",
                dialog = VocalSeparationDialog.NONE
            )
        }

        separationJob = taskController.launch(viewModelScope) { task ->
            task.onCancel { mediaOperation?.cancel() }
            var success = 0
            try {
                logRouting(files, stems)
                val overwrittenBaseNames = mutableSetOf<String>()
                for ((index, selected) in files.withIndex()) {
                    if (isCancelled) break
                    val baseName = selected.fileName.substringBeforeLast(".").lowercase(Locale.ROOT)
                    val shouldOverwrite = overwrite && overwrittenBaseNames.add(baseName)
                    if (processOne(selected, index + 1, files.size, output, stems, shouldOverwrite)) success++
                }
                if (isCancelled) {
                    appendRuntimeLog(string(R.string.vocal_separation_log_cancelled))
                    toast(R.string.task_cancelled)
                } else {
                    appendRuntimeLog(string(R.string.vocal_separation_log_done, success, files.size))
                    showProgress(string(R.string.task_all_done), 100)
                    toast(R.string.vocal_separation_toast_done, success, files.size, duration = Toast.LENGTH_LONG)
                }
            } catch (e: CancellationException) {
                appendRuntimeLog(string(R.string.vocal_separation_log_coroutine_cancelled))
            } catch (e: Exception) {
                if (!isCancelled) {
                    appendRuntimeLog(string(R.string.task_failed_with_reason, e.message.orEmpty()))
                    showError(e.message ?: string(R.string.vocal_separation_failed_default))
                }
            } finally {
                mediaOperation?.cancel()
                mediaOperation = null
                setState { copy(isRunning = false, progressVisible = false) }
            }
        }
        if (separationJob == null) {
            setState { copy(isRunning = false, progressVisible = false) }
        }
    }

    private fun logRouting(files: List<SelectedMediaFile>, stems: Set<VocalSeparationEngine.Stem>) {
        appendRuntimeLog(string(R.string.vocal_separation_log_start))
        appendRuntimeLog(string(R.string.task_pending_files, files.size))
        appendRuntimeLog(string(R.string.vocal_separation_log_stems, stems.joinToString { it.displayName }))
        appendRuntimeLog(string(R.string.task_output_directory, currentState.outputDirectory))
        if (stems.size > 1) {
            appendRuntimeLog(string(R.string.vocal_separation_log_route_general_multi, stems.size))
            appendRuntimeLog(
                string(
                    R.string.vocal_separation_log_general_model,
                    fileName(Uri.parse(settings.getDemixModelUri("general")))
                )
            )
            return
        }
        val stem = stems.first()
        val ftSelected = settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT
        appendRuntimeLog(
            when {
                ftSelected && isModelConfigured(stem) ->
                    string(R.string.vocal_separation_log_route_ft, stem.displayName)
                ftSelected -> string(R.string.vocal_separation_log_route_ft_fallback, stem.displayName)
                else -> string(R.string.vocal_separation_log_route_general)
            }
        )
    }

    private suspend fun processOne(
        selected: SelectedMediaFile,
        index: Int,
        count: Int,
        output: Uri,
        stems: Set<VocalSeparationEngine.Stem>,
        overwrite: Boolean
    ): Boolean {
        val taskCache = File(app.cacheDir, "vocal_separation_${System.currentTimeMillis()}_${System.nanoTime()}")
            .apply { mkdirs() }
        val prefix = string(R.string.task_file_progress_prefix, index, count)
        return try {
            showProgress(string(R.string.vocal_separation_progress_preparing, prefix), 0)
            appendRuntimeLog(string(R.string.vocal_separation_log_file_start, prefix, selected.fileName))
            val input = withContext(Dispatchers.IO) { copyUriToCache(selected.uri, selected.fileName, taskCache) }
                ?: throw IllegalStateException(string(R.string.vocal_separation_copy_input_failed))
            appendRuntimeLog(
                string(R.string.vocal_separation_log_input_cache, prefix, input.name, ByteSizeFormat.format(input.length()))
            )
            if (isCancelled) return false

            showProgress(string(R.string.vocal_separation_progress_extract, prefix), 5)
            appendRuntimeLog(string(R.string.vocal_separation_log_extract, prefix))
            val pcm = withContext(Dispatchers.IO) { convertToPcm(input, taskCache) }
                ?: throw IllegalStateException(string(R.string.vocal_separation_ffmpeg_failed))
            appendRuntimeLog(
                string(R.string.vocal_separation_log_pcm_cache, prefix, pcm.name, ByteSizeFormat.format(pcm.length()))
            )
            if (isCancelled) return false

            val tempOutput = File(taskCache, "outputs").apply { mkdirs() }
            showProgress(string(R.string.vocal_separation_progress_onnx, prefix), 10)
            val useGeneral = stems.size > 1
            val singleStem = stems.firstOrNull()
            val useFtModels = settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT
            val modelKey = if (useGeneral) "general"
            else if (useFtModels && singleStem != null && isModelConfigured(singleStem)) singleStem.fileSuffix
            else "general"
            val modelUri = Uri.parse(settings.getDemixModelUri(modelKey))
            val modelLabel = when {
                useGeneral -> string(R.string.vocal_separation_model_general)
                modelKey != "general" -> string(R.string.vocal_separation_model_specialist, singleStem?.displayName.orEmpty())
                useFtModels -> string(R.string.vocal_separation_model_general_fallback)
                else -> string(R.string.vocal_separation_model_general)
            }
            val modelName = fileName(modelUri)
            appendRuntimeLog(string(R.string.vocal_separation_log_use_model, prefix, modelLabel, modelName))
            val result = withContext(Dispatchers.IO) {
                withDirectModelPath(modelUri) { directPath, size ->
                    val runner = dependencies.vocalSeparationRunner(
                        modelPath = directPath,
                        modelDisplayName = modelName,
                        modelSize = size,
                        graphOptimizationEnabled = settings.isDemixOrtGraphOptimizationEnabled(),
                        cpuArenaEnabled = settings.isDemixOrtCpuArenaEnabled(),
                        log = { message -> appendRuntimeLog("$prefix $modelLabel: $message") }
                    )
                    runner.separate(
                        pcm,
                        tempOutput,
                        selected.fileName.substringBeforeLast("."),
                        stems,
                        { isCancelled }
                    ) { done, total ->
                        val progress = 10 + (done.toDouble() / total * 85).toInt()
                        showProgress(string(R.string.vocal_separation_progress_chunks, prefix, modelLabel, done, total), progress)
                    }
                }
            }
            for (stem in stems) {
                val temp = result.outputFiles.getValue(stem)
                val name = withContext(Dispatchers.IO) {
                    DemixOutputWriter.copy(app, temp, output, temp.name, overwrite)
                }
                appendRuntimeLog(string(R.string.vocal_separation_log_saved_stem, prefix, stem.displayName, name))
            }
            appendRuntimeLog(
                string(
                    R.string.vocal_separation_log_file_done,
                    prefix,
                    result.chunkCount,
                    string(R.string.duration_seconds_decimal, result.elapsedMs / 1000.0)
                )
            )
            showProgress(string(R.string.vocal_separation_progress_file_done, prefix), 100)
            true
        } catch (e: InterruptedException) {
            appendRuntimeLog(string(R.string.vocal_separation_log_file_cancelled, prefix, e.message.orEmpty()))
            false
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { taskCache.deleteRecursively() }
        }
    }

    private suspend fun convertToPcm(input: File, cache: File): File? {
        val output = File(cache, "${input.nameWithoutExtension}_44k_stereo.f32le")
        if (output.exists()) output.delete()
        return if (mediaOperation?.convertToPcm(input, output, PcmFormat.DEMIX_FLOAT_44K_STEREO) == true) output else null
    }

    private fun copyUriToCache(uri: Uri, name: String, cache: File): File? = runCatching {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val output = File(cache, "input_$safeName")
        app.contentResolver.openInputStream(uri)?.use { input -> output.outputStream().use { input.copyTo(it, 1024 * 1024) } }
            ?: throw IllegalStateException(string(R.string.vocal_separation_read_input_failed))
        output
    }.getOrNull()

    private fun selectedStems(): LinkedHashSet<VocalSeparationEngine.Stem> =
        currentState.selectedStems.toCollection(linkedSetOf())

    private fun isModelConfigured(stem: VocalSeparationEngine.Stem): Boolean = isModelConfigured(stem.fileSuffix)

    private fun isModelConfigured(modelKey: String): Boolean {
        val uriString = settings.getDemixModelUri(modelKey)
        return uriString.isNotBlank() && isSavedUriReadable(uriString)
    }

    private fun discardInaccessibleModelUris() {
        var discarded = false
        val modelKeys = listOf("general") + VocalSeparationEngine.Stem.entries.map { it.fileSuffix }
        modelKeys.forEach { modelKey ->
            val uriString = settings.getDemixModelUri(modelKey)
            if (uriString.isNotBlank() && !isSavedUriReadable(uriString)) {
                settings.setDemixModelUri(modelKey, "")
                discarded = true
            }
        }
        if (discarded && !accessWarningShown) {
            accessWarningShown = true
            toast(R.string.vocal_separation_model_access_lost, duration = Toast.LENGTH_LONG)
        }
    }

    private fun isSavedUriReadable(uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.isFile == true
        } else {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    }.getOrDefault(false)

    private fun <T> withDirectModelPath(uri: Uri, block: (path: String, size: Long?) -> T): T {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            return block(file.absolutePath, file.length())
        }
        val descriptor = app.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException(string(R.string.vocal_separation_open_model_failed, fileName(uri)))
        descriptor.use {
            val size = it.statSize.takeIf { value -> value >= 0L }
            return block("/proc/self/fd/${it.fd}", size)
        }
    }

    private fun setupDefaultOutputDir() {
        val savedUri = settings.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)?.let(Uri::parse)
        if (savedUri != null) {
            outputDirUri = savedUri
            setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, savedUri)) }
            return
        }
        val path = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SubtitleEdit/Output"
        )
        path.mkdirs()
        outputDirUri = Uri.fromFile(path)
        setState { copy(outputDirectory = path.absolutePath) }
    }

    private fun cancelSeparation() {
        if (!isRunning) return
        taskController.cancel()
        mediaOperation?.cancel()
        separationJob?.cancel()
        appendRuntimeLog(string(R.string.task_cancel_requested))
    }

    private fun showProgress(status: String, progress: Int) =
        setState { copy(progressStatus = status, progress = progress.coerceIn(0, 100)) }

    private fun appendRuntimeLog(message: String) {
        RuntimeLogManager.i(LOG_TAG, message)
        val text = runtimeLog.append(message)
        setState { copy(log = text) }
    }

    private fun showError(message: String) =
        setState { copy(dialog = VocalSeparationDialog.ERROR, errorMessage = message) }

    private fun fileName(uri: Uri): String = UriDisplayName.of(app, uri)

    override fun onCleared() {
        // Page is finishing for real (not a configuration change): stop native work.
        taskController.cancel()
        mediaOperation?.cancel()
        separationJob?.cancel()
        super.onCleared()
    }
}
