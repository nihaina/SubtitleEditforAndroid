package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.subtitleedit.PageTranslationSettingsState
import com.subtitleedit.R
import com.subtitleedit.translation.PageTranslationLanguage
import com.subtitleedit.translation.PageTranslationModel
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.work.PageTranslationDownloadWorker
import java.util.Locale

@Composable
private fun PageTranslationLanguage.displayName(): String = when (this) {
    PageTranslationLanguage.CHINESE -> stringResource(R.string.page_translation_chinese)
    PageTranslationLanguage.TRADITIONAL_CHINESE -> stringResource(R.string.page_translation_traditional_chinese)
    PageTranslationLanguage.JAPANESE -> stringResource(R.string.page_translation_japanese)
    PageTranslationLanguage.ENGLISH -> stringResource(R.string.page_translation_english)
    else -> toLocale().getDisplayLanguage(LocalConfiguration.current.locales[0])
}

@Composable
internal fun PageTranslationModel.displayName(): String = stringResource(
    R.string.page_translation_model_direction, source.displayName(), target.displayName(),
)

@Composable
internal fun PageTranslationSettingsScreen(
    state: PageTranslationSettingsState,
    systemLocale: Locale,
    onSourceChanged: (PageTranslationLanguage?) -> Unit,
    onTargetChanged: (PageTranslationLanguage) -> Unit,
    onDownload: (PageTranslationModel) -> Unit,
    onDownloadMissing: () -> Unit,
    onCancelDownload: (PageTranslationModel) -> Unit,
    onDelete: (PageTranslationModel) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var deleteModelId by rememberSaveable { mutableStateOf<String?>(null) }
    val catalog = state.catalog
    val models = remember(catalog) { catalog?.models.orEmpty().toList() }
    val plan = remember(catalog, state.options, systemLocale) { catalog?.plan(state.options, systemLocale) }
    AppToolScaffold(
        title = stringResource(R.string.page_translation),
        onBack = onBack,
        scrollable = false,
        contentBackground = settingsPageBackground(),
        topBarContainerColor = settingsPageBackground(),
        topBarScrolledContainerColor = settingsPageBackground(),
    ) {
        if (!state.supported) {
            Text(stringResource(R.string.page_translation_unsupported), color = settingsSecondaryTextColor())
            return@AppToolScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                SettingsTileSection(
                    subtitle = stringResource(R.string.page_translation_description),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    if (catalog != null && plan != null) {
                        val sourceOptions = listOf(AppOption(null, stringResource(R.string.page_translation_auto))) +
                            catalog.sources.map { AppOption(it, it.displayName()) }.sortedBy { it.label }
                        val targetOptions = catalog.targetsFor(plan.options.source)
                            .map { AppOption(it, it.displayName()) }.sortedBy { it.label }
                        AppOptionSelector(
                            label = stringResource(R.string.page_translation_source),
                            value = sourceOptions.firstOrNull { it.id == plan.options.source },
                            options = sourceOptions,
                            onSelected = onSourceChanged,
                        )
                        AppOptionSelector(
                            label = stringResource(R.string.page_translation_target),
                            value = targetOptions.firstOrNull { it.id == plan.options.target },
                            options = targetOptions,
                            onSelected = onTargetChanged,
                        )
                        val missing = state.missingModels(plan)
                        if (missing.isNotEmpty()) {
                            Column {
                                val directions = missing.map { it.displayName() }.joinToString("、")
                                Text(
                                    stringResource(R.string.page_translation_missing_models_inline, directions),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = settingsSecondaryTextColor(),
                                )
                                TextButton(onClick = onDownloadMissing, enabled = missing.any(state::canDownload)) {
                                    Text(stringResource(R.string.page_translation_download_missing))
                                }
                                val status = when {
                                    missing.any(state::isDownloadPending) -> R.string.page_translation_required_models_pending
                                    missing.any { state.downloads[it]?.state == WorkInfo.State.FAILED } ->
                                        R.string.page_translation_download_failed
                                    else -> null
                                }
                                status?.let {
                                    Text(
                                        stringResource(it),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = settingsSecondaryTextColor(),
                                    )
                                }
                            }
                        }
                        if (plan.options.source == null && plan.systemLanguage == null) {
                            Text(
                                stringResource(
                                    R.string.page_translation_system_language_unavailable,
                                    systemLocale.getDisplayLanguage(LocalConfiguration.current.locales[0]),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = settingsSecondaryTextColor(),
                            )
                        }
                    }
                    if (state.checkingModels && state.downloadedModels == null) {
                        Text(stringResource(R.string.page_translation_checking_models), color = settingsSecondaryTextColor())
                    }
                    state.error?.let { error ->
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        if (error == R.string.page_translation_check_failed) {
                            TextButton(onClick = onRetry, enabled = !state.checkingModels) {
                                Text(stringResource(R.string.page_translation_retry))
                            }
                        }
                    }
                }
            }
            if (models.isNotEmpty()) {
                item {
                    SettingsTileSection(
                        title = stringResource(R.string.page_translation_models),
                        subtitle = stringResource(R.string.page_translation_models_description),
                    ) {}
                }
                items(models, key = PageTranslationModel::id) { model ->
                    SettingsTileCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        PageTranslationModelRow(
                            model = model,
                            state = state,
                            onDownload = { onDownload(model) },
                            onCancel = { onCancelDownload(model) },
                            onDelete = { deleteModelId = model.id },
                        )
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.page_translation_disclaimer),
                        modifier = Modifier.padding(top = 8.dp),
                        color = settingsSecondaryTextColor(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    catalog?.model(deleteModelId)?.let { model ->
        AppDialog(
            onDismissRequest = { deleteModelId = null },
            title = { Text(stringResource(R.string.page_translation_delete_model)) },
            text = { Text(stringResource(R.string.page_translation_delete_model_confirmation, model.displayName())) },
            confirmButton = {
                TextButton(onClick = {
                    deleteModelId = null
                    onDelete(model)
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteModelId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PageTranslationModelRow(
    model: PageTranslationModel,
    state: PageTranslationSettingsState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val downloaded = state.downloadedModels?.contains(model)
    val enqueuing = model in state.enqueuingModels
    val work = state.downloads[model]
    val active = work?.state?.isFinished == false
    val failed = work?.state == WorkInfo.State.FAILED && downloaded != true
    val status = when {
        state.deletingModel == model -> stringResource(R.string.page_translation_model_deleting)
        work?.state == WorkInfo.State.RUNNING -> modelDownloadStatus(work)
        enqueuing || active -> stringResource(R.string.page_translation_model_queued)
        downloaded == true -> stringResource(R.string.page_translation_model_downloaded)
        state.checkingModels -> stringResource(R.string.page_translation_checking_models)
        failed -> stringResource(R.string.page_translation_download_failed)
        downloaded == false -> stringResource(R.string.page_translation_model_not_downloaded)
        else -> stringResource(R.string.page_translation_model_unknown)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(model.displayName(), color = settingsPrimaryTextColor())
            Text(status, style = MaterialTheme.typography.bodySmall, color = settingsSecondaryTextColor())
        }
        if (active) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        } else if (downloaded != null) {
            val action = when {
                downloaded -> R.string.delete
                failed -> R.string.page_translation_retry
                else -> R.string.page_translation_download
            }
            TextButton(
                enabled = if (downloaded) !state.checkingModels && state.deletingModel == null else state.canDownload(model),
                onClick = if (downloaded) onDelete else onDownload,
            ) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun modelDownloadStatus(work: WorkInfo): String {
    val progress = work.progress
    if (progress.getBoolean(PageTranslationDownloadWorker.KEY_VERIFYING, false)) {
        return stringResource(R.string.page_translation_model_verifying)
    }
    val total = progress.getLong(PageTranslationDownloadWorker.KEY_TOTAL, 0)
    if (total <= 0) return stringResource(R.string.page_translation_model_downloading)
    val downloaded = progress.getLong(PageTranslationDownloadWorker.KEY_DOWNLOADED, 0).coerceIn(0, total)
    return stringResource(R.string.page_translation_model_progress, (downloaded * 100 / total).toInt())
}
