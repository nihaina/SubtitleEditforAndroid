package com.subtitleedit.feature.ui

import android.os.Environment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.util.FileBrowserNavigation
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DirectoryBreadcrumbBar(
    directory: File,
    enabled: Boolean,
    onNavigate: (File) -> Boolean,
    modifier: Modifier = Modifier
) {
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    val directories = remember(directory.absolutePath, storageRoot) {
        FileBrowserNavigation.breadcrumbDirectories(storageRoot, directory)
    }
    val scrollState = rememberScrollState()
    var editingPath by rememberSaveable(directory.absolutePath) { mutableStateOf<String?>(null) }
    val editPathLabel = stringResource(R.string.directory_edit_path)
    val editPath: (File) -> Unit = { target ->
        editingPath = target.absolutePath.let { if (it.endsWith('/')) it else "$it/" }
    }
    LaunchedEffect(directory.absolutePath, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onLongClickLabel = editPathLabel,
                    onLongClick = { editPath(storageRoot) },
                    onClick = { onNavigate(storageRoot) }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_phone_storage),
                contentDescription = stringResource(R.string.directory_storage_root, storageRoot.absolutePath),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
            )
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            directories.forEachIndexed { index, ancestor ->
                val isCurrentDirectory = index == directories.lastIndex
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .combinedClickable(
                            enabled = enabled,
                            role = Role.Button,
                            onLongClickLabel = editPathLabel,
                            onLongClick = { editPath(ancestor) },
                            onClick = { onNavigate(ancestor) }
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ancestor.name.ifEmpty { ancestor.path },
                        color = (if (isCurrentDirectory) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                            .copy(alpha = if (enabled) 1f else 0.38f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrentDirectory) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }

    editingPath?.let { initialPath ->
        DirectoryPathDialog(
            initialPath = initialPath,
            onDismiss = { editingPath = null },
            onNavigate = onNavigate
        )
    }
}

@Composable
private fun DirectoryPathDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onNavigate: (File) -> Boolean
) {
    var path by rememberSaveable(initialPath, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialPath, selection = TextRange(0, initialPath.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = {
        if (onNavigate(File(path.text.trim()))) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.directory_jump_title)) },
        text = {
            TextField(
                value = path,
                onValueChange = { path = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    scope.launch {
                        val clip = clipboard.getClipEntry()?.clipData
                        val pasted = clip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (pasted != null) {
                            val start = path.selection.min
                            val updated = path.text.replaceRange(start, path.selection.max, pasted)
                            path = TextFieldValue(updated, selection = TextRange(start + pasted.length))
                        }
                    }
                }) { Text(stringResource(android.R.string.paste)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = submit) { Text(stringResource(android.R.string.ok)) }
            }
        }
    )
}
