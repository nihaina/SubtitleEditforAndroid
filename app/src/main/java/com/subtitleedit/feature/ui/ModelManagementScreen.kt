package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.ExperimentalFoundationApi
import com.subtitleedit.R
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
    demucsDialog: ModelImportDialogUi?,
    exportDialog: ModelImportDialogUi?,
    dialog: ModelManagementDialogUi?,
    onPageSelected: (Int) -> Unit,
    onAsrImportAction: (AsrModelImportAction) -> Unit,
    onBuiltInVadChanged: (Boolean) -> Unit,
    onDemucsImportAction: (DemucsModelImportAction) -> Unit,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pagerState.currentPage == 0) "模型导入" else "模型管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                listOf("模型导入", "模型管理").forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                if (page == 0) {
                    ImportPage(
                        asrImport = asrImport,
                        demucsImport = demucsImport,
                        onAsrAction = onAsrImportAction,
                        onBuiltInVadChanged = onBuiltInVadChanged,
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
                        onRefresh = onRefresh,
                        onExport = onExport,
                        onDelete = onDelete
                    )
                }
            }
        }
    }

    ModelImportDialogs(
        asrDialog = asrDialog,
        demucsDialog = demucsDialog,
        exportDialog = exportDialog
    )

    when (val activeDialog = dialog) {
        is ModelManagementDialogUi.ConfirmDelete -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("删除模型文件") },
            text = {
                Text(
                    "确定永久删除“${activeDialog.item.displayName}”吗？\n\n" +
                        "对应模型文件及相关模型选择将被清除，此操作无法撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmDelete(activeDialog.item) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text("取消") } },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
        )

        is ModelManagementDialogUi.ConfirmOverwrite -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("覆盖已导出的模型？") },
            text = {
                Text("下载模型目录中已存在 ${activeDialog.item.displayName}。覆盖前会完整复制并校验新模型。")
            },
            confirmButton = {
                TextButton(onClick = { onConfirmOverwrite(activeDialog.item) }) { Text("覆盖导出") }
            },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text("取消") } }
        )

        null -> Unit
    }
}

@Composable
private fun ImportPage(
    asrImport: AsrModelImportUiState,
    demucsImport: DemucsModelImportUiState,
    onAsrAction: (AsrModelImportAction) -> Unit,
    onBuiltInVadChanged: (Boolean) -> Unit,
    onDemucsAction: (DemucsModelImportAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "集中选择和导入识别、分段及人声分离模型",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ModelManagementImportContent(
            asr = asrImport,
            demucs = demucsImport,
            onAsrAction = onAsrAction,
            onBuiltInVadChanged = onBuiltInVadChanged,
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
    onRefresh: () -> Unit,
    onExport: (ModelManagementItemUi) -> Unit,
    onDelete: (ModelManagementItemUi) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelsDirectoryLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onRefresh, enabled = !isLoading) { Text("刷新") }
        }
        if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())

        val groupedModels = models.groupBy { it.category }
        if (errorMessage != null) {
            EmptyState(errorMessage)
        } else if (models.isEmpty() && isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (models.isEmpty()) {
            EmptyState(emptyMessage)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    top = 4.dp,
                    end = 16.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedModels.forEach { (category, categoryModels) ->
                    item(key = "category:$category") {
                        Text(
                            text = category,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.formattedSize,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (item.canExport) {
                TextButton(onClick = onExport, enabled = !isExporting && !isDeleting) {
                    Text("导出")
                }
            }
            if (item.canDelete) {
                TextButton(onClick = onDelete, enabled = !isExporting && !isDeleting) {
                    Text(
                        text = if (isDeleting) "删除中" else "删除文件",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
