package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                modifier = Modifier.height(56.dp),
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
            .padding(16.dp)
    ) {
        Text(
            text = "集中选择和导入识别、分段及人声分离模型",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
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
    onExport: (ModelManagementItemUi) -> Unit,
    onDelete: (ModelManagementItemUi) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionContainer {
                Text(
                    text = modelsDirectoryLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            val groupedModels = models.groupBy { it.category }
            when {
                errorMessage != null -> EmptyState(errorMessage)
                models.isEmpty() && !isLoading -> EmptyState(emptyMessage)
                models.isEmpty() -> Unit
                else -> LazyColumn(
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
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                                fontWeight = FontWeight.Bold,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.canExport) {
                        TextButton(
                            onClick = onExport,
                            enabled = !isExporting && !isDeleting,
                            modifier = Modifier.semantics {
                                contentDescription = "导出 ${item.displayName}"
                            }
                        ) {
                            Text("导出")
                        }
                    }
                }
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.formattedSize,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (item.canDelete) {
                TextButton(onClick = onDelete, enabled = !isExporting && !isDeleting, modifier = Modifier.widthIn(min = 72.dp)) {
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
        Text(
            text = message,
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}
