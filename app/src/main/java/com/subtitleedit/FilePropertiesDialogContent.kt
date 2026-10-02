package com.subtitleedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.util.FilePropertiesInfo

internal sealed interface FilePropertiesDialogUiState {
    data class Single(
        val properties: FilePropertiesInfo,
        val loading: Boolean
    ) : FilePropertiesDialogUiState

    data class Multiple(
        val count: Int,
        val totalSize: String,
        val loading: Boolean
    ) : FilePropertiesDialogUiState
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun FilePropertiesDialog(
    state: FilePropertiesDialogUiState,
    onDismiss: () -> Unit,
    onCopyPath: (String) -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 4.dp)
                    .heightIn(max = 640.dp)
            ) {
                Text("详情", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Column(
                    modifier = Modifier.weight(1f, fill = false)
                        .padding(top = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (state) {
                        is FilePropertiesDialogUiState.Single -> SingleProperties(
                            state = state,
                            onCopyPath = onCopyPath
                        )
                        is FilePropertiesDialogUiState.Multiple -> MultipleProperties(state)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun SingleProperties(
    state: FilePropertiesDialogUiState.Single,
    onCopyPath: (String) -> Unit
) {
    val info = state.properties
    PropertyRow("名称", info.name)
    PropertyRow(
        label = "目录",
        value = info.path,
        onClick = { onCopyPath(info.path) },
        contentDescription = "点击复制路径：${info.path}"
    )
    PropertyRow("类型", info.type)
    PropertyRow("大小", info.size)
    PropertyRow("修改时间", info.modifiedTime, bottomSpacing = 0.dp)

    if (state.loading) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).wrapContentSize(Alignment.Center),
            strokeWidth = 2.dp
        )
    }
    info.mediaInfoTitle?.let { title ->
        Text(title, modifier = Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleMedium)
        info.mediaDetails.forEach { detail ->
            PropertyRow(
                detail.label,
                detail.value,
                topSpacing = 12.dp,
                bottomSpacing = 0.dp
            )
        }
    }
}

@Composable
private fun MultipleProperties(state: FilePropertiesDialogUiState.Multiple) {
    PropertyRow("已选择", "${state.count} 项")
    PropertyRow("总大小", state.totalSize, bottomSpacing = 0.dp)
    if (state.loading) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).wrapContentSize(Alignment.Center),
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun PropertyRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    topSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    bottomSpacing: androidx.compose.ui.unit.Dp = 12.dp
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(top = topSpacing, bottom = bottomSpacing)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier
            ),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.width(72.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
