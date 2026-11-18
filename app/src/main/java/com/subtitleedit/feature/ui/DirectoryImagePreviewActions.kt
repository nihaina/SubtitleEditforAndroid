package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

@Composable
internal fun DirectoryImagePreviewActions(
    onShare: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onShowInfo: () -> Unit
) {
    Row(Modifier.fillMaxWidth().height(64.dp)) {
        val item = Modifier.weight(1f).fillMaxHeight()
        PreviewAction(item, R.drawable.ic_share, R.string.media_selection_share, onShare)
        PreviewAction(item, R.drawable.ic_image_rotate, R.string.directory_image_preview_rotate, onRotate)
        PreviewAction(item, R.drawable.ic_delete_normal, R.string.delete, onDelete)
        PreviewAction(item, R.drawable.ic_info, R.string.directory_image_preview_info, onShowInfo)
    }
}

@Composable
private fun PreviewAction(modifier: Modifier, icon: Int, label: Int, onClick: () -> Unit) {
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick),
        Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        Alignment.CenterHorizontally
    ) {
        val color = MaterialTheme.colorScheme.onSurface
        Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp), tint = color)
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
