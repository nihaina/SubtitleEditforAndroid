package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.ExperimentalFoundationApi
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppCard
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.StatusBadge
import kotlinx.coroutines.launch

data class ModelManagementItemUi(
    val key: String,
    val category: String,
    val displayName: String,
    val path: String,
    val formattedSize: String,
    val canExport: Boolean,
    val canDelete: Boolean
)

sealed interface ModelManagementDialogUi {
    data class ConfirmDelete(val item: ModelManagementItemUi) : ModelManagementDialogUi
    data class ConfirmOverwrite(val item: ModelManagementItemUi) : ModelManagementDialogUi
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelManagementScreen(
    asrImport: AsrModelImportUiState,
    llmImport: LlmModelImportUiState,
    demucsImport: DemucsModelImportUiState,
    selectedPage: Int,
    models: List<ModelManagementItemUi>,
    modelsDirectoryLabel: String,
    isLoading: Boolean,
    errorMessage: String?,
    emptyMessage: String,
    isExporting: Boolean,
    deletingModelKey: String?,
    asrDialog: ModelImportDialogUi?,
    llmDialog: ModelImportDialogUi?,
    demucsDialog: ModelImportDialogUi?,
    exportDialog: ModelImportDialogUi?,
    dialog: ModelManagementDialogUi?,
    onPageSelected: (Int) -> Unit,
    onAsrImportAction: (AsrModelImportAction) -> Unit,
    onBuiltInVadChanged: (Boolean) -> Unit,
    onLlmImportAction: (LlmModelImportAction) -> Unit,
    onDemucsImportAction: (DemucsModelImportAction) -> Unit,
    onNavigateBack: () -> Unit,
    onExport: (ModelManagementItemUi) -> Unit,
    onDelete: (ModelManagementItemUi) -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmDelete: (ModelManagementItemUi) -> Unit,
    onConfirmOverwrite: (ModelManagementItemUi) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = selectedPage.coerceIn(0, 1)) { 2 }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(selectedPage) {
        if (pagerState.currentPage != selectedPage) pagerState.animateScrollToPage(selectedPage)
    }
    LaunchedEffect(pagerState.currentPage) {
        onPageSelected(pagerState.currentPage)
    }

    AppToolScaffold(
        title = stringResource(
            if (pagerState.currentPage == 0) R.string.model_management_import
            else R.string.model_management_title
        ),
        onBack = onNavigateBack,
        scrollable = false
    ) {
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            listOf(
                R.string.model_management_import,
                R.string.model_management_title
            ).forEachIndexed { index, titleRes ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(titleRes)) }
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            if (page == 0) {
                ImportPage(
                    asrImport = asrImport,
                    llmImport = llmImport,
                    demucsImport = demucsImport,
                    onAsrAction = onAsrImportAction,
                    onBuiltInVadChanged = onBuiltInVadChanged,
                    onLlmAction = onLlmImportAction,
                    onDemucsAction = onDemucsImportAction
                )
            } else {
                ModelListPage(
                    models = models,
                    modelsDirectoryLabel = modelsDirectoryLabel,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    emptyMessage = emptyMessage,
                    isExporting = isExporting,
                    deletingModelKey = deletingModelKey,
                    onExport = onExport,
                    onDelete = onDelete
                )
            }
        }
    }

    ModelImportDialogs(
        asrDialog = asrDialog,
        llmDialog = llmDialog,
        demucsDialog = demucsDialog,
        exportDialog = exportDialog
    )

    when (val activeDialog = dialog) {
        is ModelManagementDialogUi.ConfirmDelete -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.model_management_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_management_delete_message,
                        activeDialog.item.displayName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmDelete(activeDialog.item) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialog) { Text(stringResource(R.string.cancel)) }
            },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
        )

        is ModelManagementDialogUi.ConfirmOverwrite -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.model_management_overwrite_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_management_overwrite_message,
                        activeDialog.item.displayName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmOverwrite(activeDialog.item) }) {
                    Text(stringResource(R.string.model_management_overwrite_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialog) { Text(stringResource(R.string.cancel)) }
            }
        )

        null -> Unit
    }
}

@Composable
private fun ImportPage(
    asrImport: AsrModelImportUiState,
    llmImport: LlmModelImportUiState,
    demucsImport: DemucsModelImportUiState,
    onAsrAction: (AsrModelImportAction) -> Unit,
    onBuiltInVadChanged: (Boolean) -> Unit,
    onLlmAction: (LlmModelImportAction) -> Unit,
    onDemucsAction: (DemucsModelImportAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.model_management_import_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ModelManagementImportContent(
            asr = asrImport,
            llm = llmImport,
            demucs = demucsImport,
            onAsrAction = onAsrAction,
            onBuiltInVadChanged = onBuiltInVadChanged,
            onLlmAction = onLlmAction,
            onDemucsAction = onDemucsAction
        )
    }
}

@Composable
private fun ModelListPage(
    models: List<ModelManagementItemUi>,
    modelsDirectoryLabel: String,
    isLoading: Boolean,
    errorMessage: String?,
    emptyMessage: String,
    isExporting: Boolean,
    deletingModelKey: String?,
    onExport: (ModelManagementItemUi) -> Unit,
    onDelete: (ModelManagementItemUi) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        val groupedModels = models.groupBy { it.category }
        when {
            errorMessage != null -> Column(Modifier.fillMaxSize()) {
                ModelsDirectoryText(modelsDirectoryLabel)
                EmptyState(errorMessage, Modifier.weight(1f))
            }
            models.isEmpty() && !isLoading -> Column(Modifier.fillMaxSize()) {
                ModelsDirectoryText(modelsDirectoryLabel)
                EmptyState(emptyMessage, Modifier.weight(1f))
            }
            models.isEmpty() -> Column(Modifier.fillMaxSize()) {
                ModelsDirectoryText(modelsDirectoryLabel)
                Box(Modifier.weight(1f))
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "models-directory") {
                    ModelsDirectoryText(modelsDirectoryLabel)
                }
                groupedModels.forEach { (category, categoryModels) ->
                    item(key = "category:$category") {
                        Text(
                            text = category,
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    items(categoryModels, key = { it.key }) { item ->
                        ModelRow(
                            item = item,
                            isExporting = isExporting,
                            isDeleting = deletingModelKey == item.key,
                            onExport = { onExport(item) },
                            onDelete = { onDelete(item) }
                        )
                    }
                }
            }
        }
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ModelsDirectoryText(value: String) {
    SelectionContainer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 0.dp)
    ) {
        Text(
            text = value,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ModelRow(
    item: ModelManagementItemUi,
    isExporting: Boolean,
    isDeleting: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val exportDescription = stringResource(R.string.export)
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(stringResource(R.string.model_status_installed))
                    if (item.canExport) {
                        TextButton(
                            onClick = onExport,
                            enabled = !isExporting && !isDeleting,
                            modifier = Modifier.semantics {
                                contentDescription = "${item.displayName} $exportDescription"
                            }
                        ) {
                            Text(stringResource(R.string.export))
                        }
                    }
                }
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.formattedSize,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (item.canDelete) {
                TextButton(onClick = onDelete, enabled = !isExporting && !isDeleting, modifier = Modifier.widthIn(min = 72.dp)) {
                    Text(
                        text = stringResource(
                            if (isDeleting) R.string.model_management_deleting
                            else R.string.model_management_delete_file
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}
