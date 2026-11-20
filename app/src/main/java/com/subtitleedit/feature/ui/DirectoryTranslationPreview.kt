package com.subtitleedit.feature.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.translation.DirectoryNamePreviewer
import com.subtitleedit.translation.DirectoryTranslationEntry
import com.subtitleedit.translation.DirectoryTranslationResult
import com.subtitleedit.translation.FoxletPageTranslation
import com.subtitleedit.translation.PageTranslationModel
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.settings.displayName
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.work.PageTranslationDownloadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class DirectoryTranslationPreviewState {
    private val names = mutableStateMapOf<String, String>()
    val translatedNames: Map<String, String> get() = names
    var request by mutableStateOf<Any?>(null)
        private set
    private var runningRequest by mutableStateOf<Any?>(null)
    val enabled: Boolean get() = request != null
    val running: Boolean get() = runningRequest != null
    var result by mutableStateOf<DirectoryTranslationResult?>(null)

    fun setPreviewEnabled(value: Boolean) {
        request = if (value) Any() else null
        runningRequest = null
        names.clear()
        result = null
    }

    fun beginRequest(): Any = Any().also { token ->
        names.clear()
        result = null
        runningRequest = token
    }

    fun isCurrent(token: Any): Boolean = runningRequest === token

    fun showName(token: Any, path: String, name: String) {
        if (isCurrent(token)) names[path] = name
    }

    fun finishRequest(token: Any) {
        if (isCurrent(token)) runningRequest = null
    }
}

internal fun MainActivityScreenState.canTranslateDirectory(): Boolean = currentDirectory != null &&
    !isDirectoryLoading && !isDirectoryNavigationPending && !isSearchExpanded && !isSearchInProgress

@Composable
internal fun rememberDirectoryTranslationPreview(
    state: MainActivityScreenState,
    pageVisible: Boolean,
): DirectoryTranslationPreviewState {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemLocale = remember(configuration) { FoxletPageTranslation.systemLocale() }
    val previewer = remember { DirectoryNamePreviewer() }
    val directoryPath = state.currentDirectory?.absolutePath?.takeIf {
        pageVisible && state.selectedTopLevelItem == R.id.nav_directory && !state.isSearchExpanded
    }
    val preview = remember(directoryPath) { DirectoryTranslationPreviewState() }
    val ready = directoryPath != null && state.canTranslateDirectory() && FoxletPageTranslation.isSupported
    val request = preview.request
    val entriesKey = if (ready && request != null) BrowserFileRefreshKey(state.files) else null

    LaunchedEffect(preview, request, entriesKey, systemLocale) {
        if (entriesKey == null || request !== preview.request) return@LaunchedEffect
        val token = preview.beginRequest()
        try {
            val options = SettingsManager.getInstance(context).getPageTranslationOptions()
            val entries = withContext(Dispatchers.IO) {
                state.files.map { file ->
                    val path = file.absolutePath
                    DirectoryTranslationEntry(path, file.name, state.fileMetadata[path]?.isDirectory ?: file.isDirectory)
                }
            }
            val result = FoxletPageTranslation.openPreview(context, options, systemLocale).use { session ->
                previewer.preview(entries, session.plan, session) { path, name ->
                    preview.showName(token, path, name)
                }
            }
            if (!preview.isCurrent(token)) return@LaunchedEffect
            val hasTranslations = preview.translatedNames.isNotEmpty()
            if (!hasTranslations) preview.setPreviewEnabled(false)
            preview.result = result
            val message = when {
                result.missingRequiredModels.isNotEmpty() || result.missingDetectedModels.isNotEmpty() -> null
                result.hasFailures -> R.string.page_translation_preview_failed
                !hasTranslations -> R.string.page_translation_no_changes
                else -> null
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (preview.isCurrent(token)) {
                preview.setPreviewEnabled(false)
                Toast.makeText(context, R.string.page_translation_preview_failed, Toast.LENGTH_SHORT).show()
            }
        } finally {
            preview.finishRequest(token)
        }
    }

    return preview
}

@Composable
internal fun DirectoryTranslationStatus(
    preview: DirectoryTranslationPreviewState,
    isActive: Boolean,
    onOpenSettings: () -> Unit,
) {
    val result = preview.result
    val required = !result?.missingRequiredModels.isNullOrEmpty()
    val models = if (required) result?.missingRequiredModels.orEmpty() else result?.missingDetectedModels.orEmpty()
    val hasTranslations by remember(preview) { derivedStateOf { preview.translatedNames.isNotEmpty() } }
    if (!hasTranslations && models.isEmpty()) return

    var dialogOpen by remember(preview, models, required, isActive) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.page_translation_attribution),
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        if (models.isNotEmpty()) {
            Text(
                text = stringResource(R.string.page_translation_missing_models_hint),
                modifier = Modifier.clickable(enabled = isActive, role = Role.Button) { dialogOpen = true }
                    .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    if (dialogOpen && isActive && models.isNotEmpty()) {
        MissingDirectoryTranslationModelsDialog(
            models = models,
            required = required,
            onDismiss = { dialogOpen = false },
            onOpenSettings = {
                dialogOpen = false
                preview.setPreviewEnabled(false)
                onOpenSettings()
            },
        )
    }
}

@Composable
private fun MissingDirectoryTranslationModelsDialog(
    models: Set<PageTranslationModel>,
    required: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enqueuing by remember(models) { mutableStateOf(false) }
    val directions = models.map { it.displayName() }.joinToString("、")
    val message = if (required) R.string.page_translation_missing_models_message
    else R.string.page_translation_detected_models_message
    AppDialog(
        onDismissRequest = { if (!enqueuing) onDismiss() },
        title = { Text(stringResource(R.string.page_translation_missing_models)) },
        text = {
            Text(
                stringResource(message, directions),
                modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            if (required) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.page_translation_open_settings)) }
            } else {
                TextButton(enabled = !enqueuing, onClick = {
                    enqueuing = true
                    scope.launch {
                        try {
                            val scheduler = PageTranslationDownloadScheduler(context.applicationContext)
                            for (model in models) scheduler.enqueue(model)
                            Toast.makeText(context, R.string.page_translation_detected_models_queued, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.page_translation_download_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            enqueuing = false
                        }
                    }
                }) {
                    Text(stringResource(R.string.page_translation_download_missing))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !enqueuing, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun DirectoryTranslationButton(preview: DirectoryTranslationPreviewState, enabled: Boolean) {
    val supported = FoxletPageTranslation.isSupported
    val description = stringResource(when {
        !supported -> R.string.page_translation_unsupported
        preview.enabled -> R.string.page_translation_show_original
        else -> R.string.page_translation_show_preview
    })
    IconToggleButton(
        checked = preview.enabled,
        onCheckedChange = preview::setPreviewEnabled,
        enabled = enabled && supported,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        if (preview.running) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_page_translation), contentDescription = null)
        }
    }
}
